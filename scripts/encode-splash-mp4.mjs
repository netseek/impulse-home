/**
 * Re-encode assets/app-splash.mp4 to the Haval MMI panel.
 *
 * The head unit is 1920×720. The WebView's hardware video overlay often
 * ignores CSS object-fit, so a 2560×1440 clip is shown 1:1 and the car sits
 * in the bottom-right of a cropped frame. Bake 16:9 content to height 720
 * (1280×720) and pillarbox to 1920×720.
 *
 * Codec: H.264 Baseline 4.0 / yuv420p — WebView 91 reports Baseline/Main/High
 * as "probably", and 1920×720 (5400 macroblocks) needs at least Level 4.0
 * (Level 3.1 tops out at 1280×720). The Qualcomm AVC decoder on the MMI
 * accepts far larger than this. Audio is AAC LC so muted autoplay can unmute
 * once `playing` fires (setMediaPlaybackRequiresUserGesture(false) on the
 * WebView).
 *
 * Requires ffmpeg on PATH (or FFMPEG env). Usage:
 *   npm run build:splash-video
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dest = path.join(root, 'assets', 'app-splash.mp4');
const backupDir = path.join(root, 'assets', '_backup');
const backup = path.join(backupDir, 'app-splash-2560x1440.mp4');

function findFfmpeg() {
  if (process.env.FFMPEG && fs.existsSync(process.env.FFMPEG)) return process.env.FFMPEG;
  const which = process.platform === 'win32' ? 'where' : 'which';
  const found = spawnSync(which, ['ffmpeg'], { encoding: 'utf8' });
  if (found.status === 0) {
    const first = (found.stdout || '').split(/\r?\n/).map((s) => s.trim()).find(Boolean);
    if (first) return first;
  }
  const home = os.homedir();
  const extras = [
    path.join(home, 'AppData', 'Local', 'Microsoft', 'WinGet', 'Links', 'ffmpeg.exe'),
    path.join('C:', 'ffmpeg', 'bin', 'ffmpeg.exe'),
    path.join('C:', 'Program Files', 'ffmpeg', 'bin', 'ffmpeg.exe'),
  ];
  const wingetPkgs = path.join(home, 'AppData', 'Local', 'Microsoft', 'WinGet', 'Packages');
  if (fs.existsSync(wingetPkgs)) {
    for (const dir of fs.readdirSync(wingetPkgs)) {
      if (!/ffmpeg/i.test(dir)) continue;
      const pkg = path.join(wingetPkgs, dir);
      const walk = (d, depth) => {
        if (depth > 4) return null;
        let names = [];
        try { names = fs.readdirSync(d); } catch { return null; }
        for (const n of names) {
          const p = path.join(d, n);
          if (n === 'ffmpeg.exe' || n === 'ffmpeg') return p;
        }
        for (const n of names) {
          const p = path.join(d, n);
          try {
            if (fs.statSync(p).isDirectory()) {
              const hit = walk(p, depth + 1);
              if (hit) return hit;
            }
          } catch { /* ignore */ }
        }
        return null;
      };
      const hit = walk(pkg, 0);
      if (hit) extras.push(hit);
    }
  }
  return extras.find((p) => fs.existsSync(p)) || null;
}

function probe(ffmpeg, file) {
  const r = spawnSync(ffmpeg, ['-hide_banner', '-i', file], { encoding: 'utf8' });
  return `${r.stderr || ''}\n${r.stdout || ''}`;
}

function parseSize(probeText) {
  const m = probeText.match(/Video:.*?\b(\d{2,5})x(\d{2,5})\b/);
  return m ? { w: Number(m[1]), h: Number(m[2]) } : null;
}

const ffmpeg = findFfmpeg();
if (!ffmpeg) {
  console.error('ffmpeg not found. Install it (winget install Gyan.FFmpeg) or set FFMPEG=');
  process.exit(1);
}

if (!fs.existsSync(dest)) {
  console.error(`missing ${path.relative(root, dest)}`);
  process.exit(1);
}

fs.mkdirSync(backupDir, { recursive: true });
const srcProbe = probe(ffmpeg, dest);
const srcSize = parseSize(srcProbe);
const alreadyTarget = srcSize && srcSize.w === 1920 && srcSize.h === 720;

if (!alreadyTarget && !fs.existsSync(backup)) {
  fs.copyFileSync(dest, backup);
  console.log(`backed up original → ${path.relative(root, backup)}`);
}

const input = fs.existsSync(backup) ? backup : dest;
const inProbe = probe(ffmpeg, input);
const inSize = parseSize(inProbe);
console.log(`ffmpeg: ${ffmpeg}`);
console.log(`input:  ${path.relative(root, input)} ${inSize ? `${inSize.w}x${inSize.h}` : '(size unknown)'}`);

if (alreadyTarget && input === dest && /Audio:/.test(srcProbe)) {
  console.log('already 1920x720 with audio — skipping re-encode');
  process.exit(0);
}

const tmp = path.join(root, 'assets', 'app-splash.encoded.mp4');
const args = [
  '-y', '-i', input,
  '-vf', 'scale=-2:720,pad=1920:720:(ow-iw)/2:(oh-ih)/2:black',
  '-c:v', 'libx264',
  '-profile:v', 'baseline',
  '-level', '4.0',
  '-pix_fmt', 'yuv420p',
  '-preset', 'medium',
  '-crf', '18',
  '-c:a', 'aac',
  '-profile:a', 'aac_low',
  '-b:a', '96k',
  '-ar', '44100',
  '-ac', '2',
  '-movflags', '+faststart',
  tmp,
];
console.log(`encode: ffmpeg ${args.join(' ')}`);
const enc = spawnSync(ffmpeg, args, { stdio: 'inherit' });
if (enc.status !== 0) {
  try { fs.unlinkSync(tmp); } catch { /* ignore */ }
  process.exit(enc.status || 1);
}
fs.renameSync(tmp, dest);
const outSize = parseSize(probe(ffmpeg, dest));
const bytes = fs.statSync(dest).size;
console.log(`wrote ${path.relative(root, dest)} ${outSize ? `${outSize.w}x${outSize.h}` : ''} (${(bytes / 1024).toFixed(0)} KB)`);

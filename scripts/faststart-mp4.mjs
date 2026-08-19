/**
 * Move an MP4's `moov` atom in front of `mdat` ("faststart"), in place.
 *
 * WHY: the boot splash (assets/app-splash.mp4) is played by a <video> inside
 * the WebView, and every byte of it arrives through MainActivity's
 * shouldInterceptRequest, which hands Chromium a plain APK asset InputStream —
 * no Content-Length, no Range support. A player that finds `moov` only at the
 * end of the file cannot start until it has the whole thing, and with a
 * non-seekable body some WebView builds simply give up and fire `error`. With
 * `moov` first, playback starts from the head of the stream.
 *
 * `moov` holds absolute file offsets into `mdat` (stco 32-bit / co64 64-bit
 * chunk offset tables), so moving it forward means adding its own size to every
 * entry. Nothing is re-encoded and no box changes size, so the shift is exactly
 * moov's length.
 *
 * Idempotent: a file whose `moov` already precedes `mdat` is left alone.
 *
 * Usage: npm run build:splash-video   (or: node scripts/faststart-mp4.mjs <file...>)
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** Iterate the boxes in `buf` between [start, end), yielding {type, start, size, headerSize}. */
function* boxes(buf, start, end) {
  let p = start;
  while (p + 8 <= end) {
    let size = buf.readUInt32BE(p);
    const type = buf.toString('latin1', p + 4, p + 8);
    let headerSize = 8;
    if (size === 1) {
      size = Number(buf.readBigUInt64BE(p + 8));
      headerSize = 16;
    } else if (size === 0) {
      size = end - p;
    }
    if (size < headerSize) return;
    yield { type, start: p, size, headerSize };
    p += size;
  }
}

/** Containers we need to descend through to reach stbl/stco. */
const CONTAINERS = new Set(['moov', 'trak', 'mdia', 'minf', 'stbl', 'edts', 'udta']);

/** Add `shift` bytes to every chunk offset in the stco/co64 tables under `moov`. */
function shiftChunkOffsets(buf, start, end, shift) {
  for (const box of boxes(buf, start, end)) {
    if (CONTAINERS.has(box.type)) {
      shiftChunkOffsets(buf, box.start + box.headerSize, box.start + box.size, shift);
      continue;
    }
    // Full box: 1 byte version + 3 bytes flags, then a uint32 entry count.
    if (box.type === 'stco') {
      const base = box.start + box.headerSize;
      const count = buf.readUInt32BE(base + 4);
      for (let i = 0; i < count; i++) {
        const at = base + 8 + i * 4;
        buf.writeUInt32BE(buf.readUInt32BE(at) + shift, at);
      }
    } else if (box.type === 'co64') {
      const base = box.start + box.headerSize;
      const count = buf.readUInt32BE(base + 4);
      for (let i = 0; i < count; i++) {
        const at = base + 8 + i * 8;
        buf.writeBigUInt64BE(buf.readBigUInt64BE(at) + BigInt(shift), at);
      }
    }
  }
}

function faststart(file) {
  const rel = path.relative(root, file);
  const buf = fs.readFileSync(file);
  const top = [...boxes(buf, 0, buf.length)];
  const moov = top.find((b) => b.type === 'moov');
  const mdat = top.find((b) => b.type === 'mdat');
  if (!moov || !mdat) {
    console.log(`  skip  ${rel} (no moov/mdat — not a plain MP4?)`);
    return false;
  }
  if (moov.start < mdat.start) {
    console.log(`  skip  ${rel} (already faststart)`);
    return false;
  }

  // Rewrite the chunk offsets IN the moov slice before moving it, so the tables
  // point at where mdat will end up rather than where it is now.
  const moovBuf = Buffer.from(buf.subarray(moov.start, moov.start + moov.size));
  shiftChunkOffsets(moovBuf, moovBuf.length ? 8 : 0, moovBuf.length, moov.size);

  // ftyp (+ any other leading boxes) → moov → everything else, moov removed.
  const head = [];
  const tail = [];
  for (const b of top) {
    if (b === moov) continue;
    (b.type === 'ftyp' || b.type === 'free' ? head : tail).push(buf.subarray(b.start, b.start + b.size));
  }
  const out = Buffer.concat([...head, moovBuf, ...tail]);
  if (out.length !== buf.length) {
    throw new Error(`size changed for ${rel}: ${buf.length} -> ${out.length}`);
  }
  fs.writeFileSync(file, out);
  console.log(`  ok    ${rel} (moov ${moov.size} bytes moved ahead of mdat)`);
  return true;
}

const targets = process.argv.slice(2);
const files = targets.length ? targets : [path.join(root, 'assets', 'app-splash.mp4')];
let moved = 0;
for (const f of files) {
  const abs = path.resolve(f);
  if (!fs.existsSync(abs)) { console.log(`  MISS  ${f}`); continue; }
  if (faststart(abs)) moved++;
}
console.log(`\n${moved} file(s) rewritten.`);

import { spawn } from 'child_process';
import fs from 'fs';
import path from 'path';
import sharp from 'sharp';
import os from 'os';

const chrome = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const root = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');

async function shot(fileUrl, outRel) {
  const out = path.join(root, outRel);
  const tmp = path.join(os.tmpdir(), `wt-${Date.now()}-${Math.random().toString(16).slice(2)}.png`);
  const url = `http://127.0.0.1:8765/scripts/wheel-thumb-preview.html?file=${fileUrl}&t=${Date.now()}`;
  console.log('render', fileUrl);
  await new Promise((resolve) => {
    const args = [
      '--headless=new',
      '--disable-gpu',
      '--hide-scrollbars',
      '--window-size=160,160',
      `--screenshot=${tmp}`,
      '--virtual-time-budget=15000',
      url,
    ];
    const p = spawn(chrome, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let err = '';
    p.stderr.on('data', (d) => { err += d; });
    p.on('exit', (c) => {
      console.log('chrome exit', c, 'exists', fs.existsSync(tmp), err.slice(-120));
      resolve();
    });
  });
  if (!fs.existsSync(tmp)) throw new Error('no shot for ' + fileUrl);
  const m = await sharp(tmp).metadata();
  await sharp(tmp)
    .extract({ left: 0, top: 0, width: Math.min(160, m.width || 160), height: Math.min(160, m.height || 160) })
    .png()
    .toFile(out);
  fs.unlinkSync(tmp);
  const { data } = await sharp(out).raw().toBuffer({ resolveWithObject: true });
  console.log('ok', outRel, fs.statSync(out).size, 'corner', data[0], data[1], data[2]);
}

await shot('/assets/wheels/HavalPHEV34-wheel.glb', 'assets/wheel-thumbnails/haval_phev.png');
await shot('/assets/wheels/HavalPHEV19-wheel.glb', 'assets/wheel-thumbnails/haval_phev19.png');
await shot('/assets/wheels/HavalHEV-wheel.glb', 'assets/wheel-thumbnails/haval_hev.png');

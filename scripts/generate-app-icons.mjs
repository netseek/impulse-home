import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const sharp = require('sharp');

const silhouette = path.resolve('app-icon-silhouette.png');
const resourceRoot = path.resolve('app/src/main/res');
const masterSize = 512;
const cornerRadiusFrac = 0.22; // modern squircle-ish corners

const densities = {
  'mipmap-mdpi': 48,
  'mipmap-hdpi': 72,
  'mipmap-xhdpi': 96,
  'mipmap-xxhdpi': 144,
  'mipmap-xxxhdpi': 192,
};

function roundedRectSvg(size, radius) {
  return Buffer.from(
    `<svg width="${size}" height="${size}" xmlns="http://www.w3.org/2000/svg">`
    + `<rect x="0" y="0" width="${size}" height="${size}" rx="${radius}" ry="${radius}" fill="white"/>`
    + '</svg>',
  );
}

async function buildMaster() {
  const meta = await sharp(silhouette).metadata();
  const pad = Math.round(masterSize * 0.08);
  const inner = masterSize - pad * 2;
  const scale = Math.min(inner / meta.width, inner / meta.height);
  const w = Math.round(meta.width * scale);
  const h = Math.round(meta.height * scale);
  const left = Math.round((masterSize - w) / 2);
  const top = Math.round((masterSize - h) / 2);

  const car = await sharp(silhouette)
    .resize(w, h, { fit: 'inside' })
    .png()
    .toBuffer();

  const square = await sharp({
    create: {
      width: masterSize,
      height: masterSize,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 1 },
    },
  })
    .composite([{ input: car, left, top }])
    .png()
    .toBuffer();

  const radius = Math.round(masterSize * cornerRadiusFrac);
  const mask = roundedRectSvg(masterSize, radius);

  const legacy = await sharp(square)
    .composite([{ input: mask, blend: 'dest-in' }])
    .png()
    .toBuffer();

  const fgPad = Math.round(masterSize * 0.12);
  const fgInner = masterSize - fgPad * 2;
  const fgScale = Math.min(fgInner / meta.width, fgInner / meta.height);
  const fgW = Math.round(meta.width * fgScale);
  const fgH = Math.round(meta.height * fgScale);
  const fgLeft = Math.round((masterSize - fgW) / 2);
  const fgTop = Math.round((masterSize - fgH) / 2);

  const foreground = await sharp({
    create: {
      width: masterSize,
      height: masterSize,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  })
    .composite([{
      input: await sharp(silhouette).resize(fgW, fgH, { fit: 'inside' }).png().toBuffer(),
      left: fgLeft,
      top: fgTop,
    }])
    .png()
    .toBuffer();

  return { legacy, foreground };
}

if (!fs.existsSync(silhouette)) {
  console.error('Missing app-icon-silhouette.png — add the splash car silhouette there.');
  process.exit(1);
}

const { legacy, foreground } = await buildMaster();

await Promise.all(Object.entries(densities).flatMap(([directory, size]) => {
  const destination = path.join(resourceRoot, directory);
  const fgSize = Math.round(size * 2.25);
  return [
    sharp(legacy).resize(size, size).png()
      .toFile(path.join(destination, 'ic_launcher.png')),
    sharp(legacy).resize(size, size).png()
      .toFile(path.join(destination, 'ic_launcher_round.png')),
    sharp(foreground).resize(fgSize, fgSize).png()
      .toFile(path.join(destination, 'ic_launcher_foreground.png')),
  ];
}));

console.log('Generated Android launcher icons from app-icon-silhouette.png');

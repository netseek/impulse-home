import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const html = await fs.readFile(path.join(root, 'index.html'), 'utf8');
const catalogBlock = html.match(/this\._wheelCatalog\s*=\s*\[([\s\S]*?)\n\s*\];/);
if (!catalogBlock) throw new Error('Could not find _wheelCatalog in index.html');

const keys = [...catalogBlock[1].matchAll(/\{\s*key:\s*'([^']+)'/g)].map((match) => match[1]);
if (!keys.length) throw new Error('Wheel catalog is empty');

for (const key of keys) {
  const file = path.join(root, 'assets', 'wheel-thumbnails', `${key}.png`);
  const image = sharp(file);
  const metadata = await image.metadata();
  if (metadata.width !== 160 || metadata.height !== 160 || metadata.format !== 'png') {
    throw new Error(`${key}: expected a 160x160 PNG, got ${metadata.width}x${metadata.height} ${metadata.format}`);
  }
  const stats = await image.stats();
  const alpha = stats.channels[3];
  if (!alpha || alpha.max === 0) throw new Error(`${key}: thumbnail is fully transparent`);
}

console.log(`Verified ${keys.length} pre-rendered wheel thumbnails (160x160 PNG).`);

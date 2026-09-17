// Makes transparent, model-specific chassis crops from the approved concept atlas.
// The original atlas is retained unchanged for provenance and design review.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
let chromium;
try { ({ chromium } = require('playwright')); } catch {
  ({ chromium } = require(path.join(process.env.CODEX_NODE_MODULES || '', 'playwright')));
}
const atlas = 'assets/power/graphics/approved-chassis-atlas.png';
const crops = {
  phev19: [20, 158, 350, 770],
  phev34: [398, 158, 350, 770],
  hev2: [774, 158, 350, 770],
};
const server = http.createServer((req, res) => {
  const file = path.resolve(root, '.' + decodeURIComponent(req.url.split('?')[0]));
  if (!file.startsWith(root + path.sep)) { res.writeHead(403).end(); return; }
  try { res.setHeader('Content-Type', file.endsWith('.png') ? 'image/png' : 'text/html'); res.end(fs.readFileSync(file)); }
  catch { res.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let browser;
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage();
  await page.goto(`http://127.0.0.1:${server.address().port}/${atlas}`);
  const source = await page.evaluate(() => {
    const image = document.querySelector('img');
    const canvas = document.createElement('canvas');
    canvas.width = image.naturalWidth; canvas.height = image.naturalHeight;
    canvas.getContext('2d').drawImage(image, 0, 0);
    return canvas.toDataURL();
  });
  for (const [key, crop] of Object.entries(crops)) {
    const png = await page.evaluate(async ({ source, crop }) => {
      const image = new Image(); image.src = source; await image.decode();
      const [left, top, width, height] = crop;
      const canvas = document.createElement('canvas'); canvas.width = width; canvas.height = height;
      const ctx = canvas.getContext('2d', { willReadFrequently: true });
      ctx.drawImage(image, left, top, width, height, 0, 0, width, height);
      const pixels = ctx.getImageData(0, 0, width, height), data = pixels.data, total = width * height;
      const seen = new Uint8Array(total), queue = new Int32Array(total);
      const isBackdrop = index => {
        const offset = index * 4, r = data[offset], g = data[offset + 1], b = data[offset + 2];
        // The approved artwork uses a dark teal ground; tyres and steel remain neutral/bright.
        return (r + g + b) / 3 < 70 && g - r >= 3 && b - r >= 5 && b >= g - 2;
      };
      let head = 0, tail = 0;
      const add = index => { if (!seen[index] && isBackdrop(index)) { seen[index] = 1; queue[tail++] = index; } };
      for (let x = 0; x < width; x++) { add(x); add((height - 1) * width + x); }
      for (let y = 1; y < height - 1; y++) { add(y * width); add(y * width + width - 1); }
      while (head < tail) {
        const index = queue[head++], x = index % width, y = (index / width) | 0;
        if (x) add(index - 1); if (x + 1 < width) add(index + 1);
        if (y) add(index - width); if (y + 1 < height) add(index + width);
      }
      let transparent = 0;
      for (let index = 0; index < total; index++) {
        if (!seen[index]) continue;
        data[index * 4 + 3] = 0; transparent++;
      }
      ctx.putImageData(pixels, 0, 0);
      return { image: canvas.toDataURL('image/png'), transparent, total };
    }, { source, crop });
    fs.writeFileSync(path.join(root, 'assets/power/graphics', `approved-${key}-chassis.png`), Buffer.from(png.image.split(',')[1], 'base64'));
    console.log(`${key}: ${Math.round(png.transparent / png.total * 100)}% transparent approved chassis crop`);
  }
} finally {
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
}

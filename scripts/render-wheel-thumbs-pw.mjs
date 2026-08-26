/**
 * Render catalog wheel thumbnails via Playwright (black bg, face-on).
 * Usage: node scripts/render-wheel-thumbs-pw.mjs [baseUrl]
 * Requires a local static/vite server serving the repo root.
 */
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const outDir = path.join(root, 'assets', 'wheel-thumbnails');
const base = (process.argv[2] || 'http://127.0.0.1:5173').replace(/\/$/, '');

const jobs = [
  { key: 'haval_phev', file: '/assets/wheels/HavalPHEV34-wheel.glb' },
  { key: 'haval_phev19', file: '/assets/wheels/HavalPHEV19-wheel.glb' },
  { key: 'haval_hev', file: '/assets/wheels/HavalHEV-wheel.glb' },
];

const browser = await chromium.launch({
  headless: true,
  args: [
    '--enable-webgl',
    '--use-gl=angle',
    '--use-angle=swiftshader',
    '--enable-unsafe-swiftshader',
    '--ignore-gpu-blocklist',
  ],
});
const page = await browser.newPage({ viewport: { width: 160, height: 160 } });
page.on('console', (m) => {
  if (m.type() === 'error') console.error('console', m.text());
});

for (const job of jobs) {
  const url =
    `${base}/scripts/wheel-thumb-preview.html?file=${encodeURIComponent(job.file)}&_=${Date.now()}`;
  console.log('loading', url);
  await page.goto('about:blank');
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForFunction(
    (expected) =>
      (window.__thumbReady === true &&
        window.__thumbFile === expected &&
        typeof window.__thumbDataUrl === 'string' &&
        window.__thumbDataUrl.length > 2000) ||
      !!window.__thumbError,
    job.file,
    { timeout: 60000 },
  );
  const err = await page.evaluate(() => window.__thumbError || null);
  if (err) throw new Error(`${job.key}: ${err}`);
  const { dataUrl, debug } = await page.evaluate(() => ({
    dataUrl: window.__thumbDataUrl,
    debug: window.__debug,
  }));
  console.log('debug', job.key, JSON.stringify(debug), 'dataUrlLen', dataUrl.length);
  const buf = Buffer.from(dataUrl.split(',')[1], 'base64');
  const out = path.join(outDir, `${job.key}.png`);
  fs.writeFileSync(out, buf);
  console.log('wrote', out, buf.length);
}

await browser.close();
console.log('done');

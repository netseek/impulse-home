// node scripts/render-h6-ghost.mjs — original GLB is read only.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
let chromium;
try { ({ chromium } = require('playwright')); } catch {
  if (!process.env.PLAYWRIGHT_NODE_MODULES) throw Error('Install Playwright or set PLAYWRIGHT_NODE_MODULES to the directory containing it.');
  ({ chromium } = require(path.join(process.env.PLAYWRIGHT_NODE_MODULES, 'playwright')));
}
// Strip texture references in an in-memory copy: ghost materials need no textures/KTX decoder.
const source = fs.readFileSync(path.join(root, 'assets/haval-h6-hev-lite.glb'));
const jsonLength = source.readUInt32LE(12);
const gltf = JSON.parse(source.subarray(20, 20 + jsonLength));
gltf.materials = gltf.materials.map(m => ({ name: m.name || 'Unnamed' }));
delete gltf.textures; delete gltf.images; delete gltf.samplers;
delete gltf.extensionsRequired; delete gltf.extensionsUsed;
const json = Buffer.from(JSON.stringify(gltf));
const padded = Buffer.alloc(Math.ceil(json.length / 4) * 4, 32); json.copy(padded);
const tail = source.subarray(20 + jsonLength);
const header = Buffer.alloc(20); header.writeUInt32LE(0x46546c67); header.writeUInt32LE(2, 4);
header.writeUInt32LE(20 + padded.length + tail.length, 8); header.writeUInt32LE(padded.length, 12); header.writeUInt32LE(0x4e4f534a, 16);
const model = Buffer.concat([header, padded, tail]);
const server = http.createServer((req, res) => {
  if (req.url === '/ghost.glb') { res.end(model); return; }
  const filename = path.resolve(root, '.' + decodeURIComponent(req.url.split('?')[0]));
  if (!filename.startsWith(root + path.sep)) { res.writeHead(403).end(); return; }
  try { res.setHeader('Content-Type', filename.endsWith('.html') ? 'text/html' : 'application/javascript'); res.end(fs.readFileSync(filename)); }
  catch { res.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let browser;
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage({ viewport: { width: 1600, height: 800 }, deviceScaleFactor: 1 });
  page.on('pageerror', e => console.error(e));
  await page.goto(`http://127.0.0.1:${server.address().port}/scripts/h6-ghost-render.html`);
  await page.waitForFunction(() => window.ready || window.failure, null, { timeout: 60000 });
  const failure = await page.evaluate(() => window.failure); if (failure) throw Error(failure);
  const output = path.join(root, 'assets/power');
  for (const theme of ['dark', 'light']) {
    const data = await page.evaluate(theme => window.renderGhost(theme), theme);
    fs.writeFileSync(path.join(output, `h6-ghost-${theme}.png`), Buffer.from(data.split(',')[1], 'base64'));
    await page.evaluate(theme => document.body.style.background = theme === 'dark' ? '#101923' : '#edf1f5', theme);
    await page.screenshot({ path: path.join(root, 'docs', `h6-ghost-${theme}-preview.png`) });
  }
  const manifest = await page.evaluate(() => window.manifest);
  fs.writeFileSync(path.join(output, 'h6-ghost-layout.json'), JSON.stringify(manifest, null, 2) + '\n');
  console.log(JSON.stringify(manifest, null, 2));
} finally { await browser?.close(); server.close(); }

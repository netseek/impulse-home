/**
 * Prepare viewer www assets for older Android System WebViews (emulator API 28
 * ships WebView ~69; the Haval MMI is ~91). Desktop keeps the original sources.
 *
 * - Downlevels support.js (optional chaining / newer syntax)
 * - Downlevels the inline data-dc-script in index.html (class fields, ??, ...)
 */
import esbuild from 'esbuild';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const outDir = path.join(root, 'app', 'build', 'generated', 'viewerSource');
const target = 'chrome69';

fs.mkdirSync(outDir, { recursive: true });

// WebView 69 (API 28 emulator) lacks a few ES2019+ builtins that support.js uses.
const polyfill = `if (!Object.fromEntries) {
  Object.fromEntries = function (entries) {
    var out = {};
    if (entries == null) return out;
    var list = Array.from ? Array.from(entries) : [].slice.call(entries);
    for (var i = 0; i < list.length; i++) {
      var pair = list[i];
      if (pair) out[pair[0]] = pair[1];
    }
    return out;
  };
}
`;

const supportSrc = fs.readFileSync(path.join(root, 'support.js'), 'utf8');
const supportOut = await esbuild.transform(supportSrc, {
  loader: 'js',
  target,
  sourcefile: 'support.js',
});
fs.writeFileSync(path.join(outDir, 'support.js'), polyfill + supportOut.code);

const htmlPath = path.join(root, 'index.html');
let html = fs.readFileSync(htmlPath, 'utf8');
const scriptOpen = '<script type="text/x-dc" data-dc-script>';
const scriptClose = '</script>';
const start = html.indexOf(scriptOpen);
const end = html.indexOf(scriptClose, start);
if (start < 0 || end < 0) {
  throw new Error('Could not find data-dc-script block in index.html');
}
const scriptStart = start + scriptOpen.length;
const originalScript = html.slice(scriptStart, end);
const transformed = await esbuild.transform(originalScript, {
  loader: 'js',
  target,
  sourcefile: 'index.dc.js',
});
html = html.slice(0, scriptStart) + '\n' + transformed.code + html.slice(end);
fs.writeFileSync(path.join(outDir, 'index.html'), html);

for (const name of ['telemetryClient.js', 'appLauncherClient.js']) {
  fs.copyFileSync(path.join(root, name), path.join(outDir, name));
}

// Downlevel Three.js classic builds for WebView 69 (optional chaining in
 // three.min.js / GLTFExporter breaks script loading entirely).
async function copyDownlevelJs(srcDir, destDir) {
  fs.mkdirSync(destDir, { recursive: true });
  for (const ent of fs.readdirSync(srcDir, { withFileTypes: true })) {
    const from = path.join(srcDir, ent.name);
    const to = path.join(destDir, ent.name);
    if (ent.isDirectory()) {
      await copyDownlevelJs(from, to);
      continue;
    }
    if (ent.name.endsWith('.js')) {
      const src = fs.readFileSync(from, 'utf8');
      const out = await esbuild.transform(src, {
        loader: 'js',
        target,
        sourcefile: ent.name,
      });
      fs.writeFileSync(to, out.code);
    } else {
      fs.copyFileSync(from, to);
    }
  }
}

await copyDownlevelJs(
  path.join(root, 'vendor', 'three'),
  path.join(outDir, 'vendor', 'three'),
);

// Non-Three vendor assets (fonts, draco, basis, react) copy as-is.
// basis/ is the Basis Universal transcoder backing KTX2Loader — emscripten
// output, same situation as draco/, so it is copied rather than downleveled.
for (const name of ['draco', 'fonts', 'basis', 'react']) {
  const from = path.join(root, 'vendor', name);
  const to = path.join(outDir, 'vendor', name);
  fs.cpSync(from, to, { recursive: true });
}

console.log('Prepared Android www sources in', outDir);

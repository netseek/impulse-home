#!/usr/bin/env node
// One-shot: replace Space Mono UI text with Space Grotesk via --hv-font.
import fs from 'node:fs';

const path = process.argv[2] || 'index.html';
let c = fs.readFileSync(path, 'utf8');
const monoBefore = (c.match(/Space Mono/g) || []).length;

c = c.replace(
  /\r?\n    @font-face \{\r?\n      font-family: 'Space Mono';[\s\S]*?unicode-range: [^;]+;\r?\n    \}/g,
  '',
);

c = c.replace(
  /\/\* Self-hosted fonts \(was Google Fonts\)\. Space Grotesk is a variable font,[\s\S]*?Vietnamese subset dropped \(unused\)\. \*\//,
  `/* Self-hosted UI font. Space Grotesk (variable, 400-600) is used for all UI text
       (Space Mono removed for readability). latin-ext gated by unicode-range;
       latin already covers Portuguese accents. Vietnamese subset dropped. */`,
);

if (!c.includes('--hv-font:')) {
  c = c.replace(
    'html, body { margin: 0; padding: 0; width: 100%; height: 100%; background: #000; overflow: hidden; -webkit-tap-highlight-color: transparent; }',
    [
      ":root { --hv-font: 'Space Grotesk', system-ui, sans-serif; }",
      'html, body { margin: 0; padding: 0; width: 100%; height: 100%; background: #000; overflow: hidden; -webkit-tap-highlight-color: transparent; font-family: var(--hv-font); }',
    ].join('\n    '),
  );
}

const pairs = [
  ["'Space Grotesk', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif", 'var(--hv-font)'],
  ["'Space Mono', monospace", 'var(--hv-font)'],
  ['"Space Mono", monospace', 'var(--hv-font)'],
  ["'Space Mono',monospace", 'var(--hv-font)'],
  ['"Space Mono",monospace', 'var(--hv-font)'],
  ["'Space Grotesk', system-ui, sans-serif", 'var(--hv-font)'],
  ['"Space Grotesk", system-ui, sans-serif', 'var(--hv-font)'],
  ["'Space Grotesk', sans-serif", 'var(--hv-font)'],
  ['"Space Grotesk", sans-serif', 'var(--hv-font)'],
  ["'Space Grotesk',sans-serif", 'var(--hv-font)'],
  ['"Space Grotesk",sans-serif', 'var(--hv-font)'],
  ["'Space Grotesk',system-ui,sans-serif", 'var(--hv-font)'],
  ['"Space Grotesk",system-ui,sans-serif', 'var(--hv-font)'],
];
for (const [a, b] of pairs) c = c.split(a).join(b);

c = c.replace(/socTextFamily:\s*"var\(--hv-font\)"/g, `socTextFamily: "'Space Grotesk', system-ui, sans-serif"`);
c = c.replace(/ctx\.font = '(\d+)px var\(--hv-font\)'/g, "ctx.font = '$1px Space Grotesk, system-ui, sans-serif'");
c = c.replace(/font-family="var\(--hv-font\)"/g, `font-family="'Space Grotesk',sans-serif"`);
c = c.replace(/--hv-font:\s*var\(--hv-font\)/, "--hv-font: 'Space Grotesk', system-ui, sans-serif");

fs.writeFileSync(path, c);
const monoAfter = (c.match(/Space Mono/g) || []).length;
const varCount = (c.match(/var\(--hv-font\)/g) || []).length;
const grotesk = (c.match(/Space Grotesk/g) || []).length;
console.log(JSON.stringify({ path, monoBefore, monoAfter, varCount, grotesk }, null, 2));
for (const [i, line] of c.split('\n').entries()) {
  if (line.includes('Space Mono')) console.log(`${i + 1}: ${line.trim().slice(0, 120)}`);
}

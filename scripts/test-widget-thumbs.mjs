/*
 * Widget picker thumbnails are screenshots of the real 2x1 widgets
 * (scripts/capture-widget-thumbs.mjs). This checks the contract that keeps
 * them honest: every catalogue type except the live clock has a dark AND a
 * light shot on disk, both picker copies render the pair, and the picker
 * switches them on the widget theme. Each rule is re-run against a broken copy.
 */
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const SOURCE = readFileSync(resolve(root, 'index.html'), 'utf8').replace(/\r\n/g, '\n');

function catalogTypes(html) {
  const at = html.indexOf('  _widgetCatalog() {');
  const body = html.slice(at, html.indexOf('\n  }\n', at));
  return [...body.matchAll(/^\s+(\w+): \{ label:/gm)].map((m) => m[1]);
}

function check(html, exists = existsSync) {
  const types = catalogTypes(html);
  assert.ok(types.length >= 10, 'catalogue parsed: ' + types.join(','));
  for (const t of types) {
    if (t === 'clock') continue;
    for (const theme of ['dark', 'light']) {
      const file = resolve(root, 'assets/ui/widget-thumbs', `${t}-${theme}.webp`);
      assert.ok(exists(file), `missing thumbnail ${t}-${theme}.webp`);
    }
  }
  const pairs = html.match(/<img class="hv-wpick-shot hv-wpick-shot-dark" src="\{\{ pi\.shotDark \}\}"[^>]*>\s*<img class="hv-wpick-shot hv-wpick-shot-light" src="\{\{ pi\.shotLight \}\}"/g) || [];
  assert.equal(pairs.length, 2, 'both picker copies (board picker, Layout manager) render the dark+light pair');
  assert.ok(html.includes("shotDark: key === 'clock' ? '' : 'assets/ui/widget-thumbs/' + key + '-dark.webp'"), 'dark path');
  assert.ok(html.includes("shotLight: key === 'clock' ? '' : 'assets/ui/widget-thumbs/' + key + '-light.webp'"), 'light path');
  assert.ok(html.includes('#hv-root.hv-widgets-light .hv-wpick-shot-light { display:block; }'), 'light theme shows the light shot');
  assert.ok(/\.hv-wpick-shot-light,#hv-root\.hv-widgets-light \.hv-wpick-shot-dark \{ display:none; \}/.test(html), 'only one shot visible per theme');
}

check(SOURCE);

const MUTANTS = {
  'a thumbnail file is missing': [SOURCE, (f) => !f.endsWith('media-light.webp') && existsSync(f)],
  'a new widget type with no shots': [SOURCE.replace("      clock: { label: 'CLOCK'", "      weather: { label: 'WEATHER', title: '', hint: '', sizes: [[2, 1]] },\n      clock: { label: 'CLOCK'")],
  'studio copy lost the light shot': [SOURCE.replace(/(<img class="hv-wpick-shot hv-wpick-shot-light" src="\{\{ pi\.shotLight \}\}"[^>]*>)([\s\S]*?)<img class="hv-wpick-shot hv-wpick-shot-light"[^>]*>/, '$1$2')],
  'light theme never swaps': [SOURCE.replace('#hv-root.hv-widgets-light .hv-wpick-shot-light { display:block; }', '')],
};
for (const [name, [html, exists]] of Object.entries(MUTANTS)) {
  assert.notEqual(html, exists ? html + 'x' : SOURCE, `mutant did not change anything: ${name}`);
  assert.throws(() => check(html, exists), undefined, `no rule catches the mutant: ${name}`);
}

console.log(`widget thumbs: ok (${catalogTypes(SOURCE).length} types, ${Object.keys(MUTANTS).length} mutants caught)`);

/*
 * Behavioural test for the autonomia burn series (index.html _rangeBurnSeries).
 *
 * The method is pure (no `this`). Samples are [t, soc, evKm, km, oem, hist].
 * Each scenario is re-run against a broken copy of the source: a mutant that
 * no scenario catches means the suite is decoration.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const SOURCE = readFileSync(resolve(root, 'index.html'), 'utf8').replace(/\r\n/g, '\n');

function methodText(html, name) {
  const start = html.indexOf(`  ${name}(`);
  if (start < 0) throw new Error(`missing ${name}()`);
  const brace = html.indexOf('{', start);
  let depth = 0;
  for (let i = brace; i < html.length; i++) {
    if (html[i] === '{') depth++;
    if (html[i] === '}' && --depth === 0) return html.slice(start, i + 1).trim();
  }
  throw new Error(`unterminated ${name}()`);
}

function load(html) {
  // eslint-disable-next-line no-new-func
  return new Function(`return function ${methodText(html, '_rangeBurnSeries')}`)();
}

const evOnly = [
  [0, 90, 0, 0, 150, 130],
  [1, 80, 20, 20, 120, 110],
  [2, 70, 40, 40, 100, 90],
];
const hybrid = [
  [0, 90, 0, 0, 150, 130],
  [1, 80, 20, 20, 120, 100],
  [2, 88, 20, 40, 140, 100],
  [3, 70, 40, 60, 90, 70],
];
const regen = [
  [0, 80, 10, 10, 100, 90],
  [1, 83, 12, 12, 104, 92],
];
const hybridDrop = [
  [0, 50, 10, 10, 80, 70],
  [1, 45, 10, 25, 70, 60],
];
const blip = [
  [0, 80, 10, 10, 100, 90],
  [1, 81, 10, 16, 108, 90],
];

const SCENARIOS = {
  'a pure EV burn spends its SOC and stays on EV km': (series) => {
    const r = series(evOnly);
    assert.equal(r.socDropped, 20);
    assert.equal(r.hybridKm, 0);
    assert.equal(r.rises.length, 0);
    assert.equal(r.pts[2].ev, 40);
    assert.deepEqual(r.bands.map((b) => b.kind), ['ev']);
  },
  'hybrid recharge is a step and is not EV consumption': (series) => {
    const r = series(hybrid);
    assert.equal(r.socDropped, 28);
    assert.equal(r.hybridKm, 20);
    assert.equal(r.rises.length, 1);
    assert.equal(r.rises[0].soc0, 80);
    assert.equal(r.rises[0].soc1, 88);
    assert.equal(r.rises[0].ev0, 20);
    assert.equal(r.rises[0].ev1, 20);
    assert.equal(r.adjust.oem, 140);
    assert.deepEqual(r.bands.map((b) => b.kind), ['ev', 'hyb', 'ev']);
    assert.equal(r.pts[2].ev, 20);
  },
  'EV regen is not a hybrid recharge': (series) => {
    const r = series(regen);
    assert.equal(r.rises.length, 0);
    assert.equal(r.hybridKm, 0);
    assert.equal(r.socDropped, 0);
  },
  'SOC spent while the engine covers the distance is not EV burn': (series) => {
    const r = series(hybridDrop);
    assert.equal(r.socDropped, 0);
    assert.equal(r.hybridKm, 15);
    assert.equal(r.rises.length, 0);
  },
  'a one-point blip is not a recharge step': (series) => {
    const r = series(blip);
    assert.equal(r.rises.length, 0);
    assert.equal(r.hybridKm, 6);
  },
};

function run(html) {
  const series = load(html);
  const failed = [];
  for (const [name, scenario] of Object.entries(SCENARIOS)) {
    try { scenario(series); } catch (e) { failed.push(name); }
  }
  return failed;
}

const clean = run(SOURCE);
assert.deepEqual(clean, [], `burn series scenarios failed: ${clean.join(', ')}`);

const MUTANTS = {
  'x axis follows total km': ['pts.push({ ev: p[2],', 'pts.push({ ev: p[3],'],
  'hybrid SOC drop counted as EV burn': ['if (dSoc < 0 && !(hyb >= 0.5 && hyb >= dEv))', 'if (dSoc < 0 && !(false))'],
  'regen treated as a recharge': ['const charging = hyb >= 0.5 && dSoc > 0.2;', 'const charging = dSoc > 0.2;'],
  'one-point blip kept as a step': ['if (open.soc1 - open.soc0 >= 2)', 'if (open.soc1 - open.soc0 >= 0)'],
};

for (const [name, [from, to]] of Object.entries(MUTANTS)) {
  assert.equal(SOURCE.split(from).length, 2, `mutant anchor must match once: ${name}`);
  const failed = run(SOURCE.replace(from, to));
  assert.ok(failed.length > 0, `no scenario catches the mutant: ${name}`);
}

console.log(`range burn series: ok (${Object.keys(SCENARIOS).length} scenarios, ${Object.keys(MUTANTS).length} mutants caught)`);

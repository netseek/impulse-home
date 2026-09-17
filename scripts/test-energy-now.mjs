/*
 * Behavioural test for the ENERGIA AGORA screen's pure logic (index.html):
 * _energyTenMinView (the last-10-minutes chart and its averages) and
 * _energyLevelView (a level bar, trip start against now). Lift the methods out,
 * run fixtures, then prove every mutant is caught.
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
  const fn = (name) => new Function(`return function ${methodText(html, name)}`)();
  return { tenMin: fn('_energyTenMinView'), level: fn('_energyLevelView') };
}

const NOW = 1_800_000_000_000;
// n samples, one a second, ending at NOW.
const series = (n, f) => Array.from({ length: n }, (_, i) => Object.assign({ t: NOW - (n - 1 - i) * 1000 }, f(i)));
const moves = (d) => (d.match(/M/g) || []).length;

const SCENARIOS = {
  'averages over the window: km/L, net kWh/100 km': ({ tenMin }) => {
    // 36 km/h is 0.01 km a second; 5 L/100 km; 10 kW out.
    const v = tenMin(series(120, () => ({ kmh: 36, fuel: 5, kw: 10, ice: true })), NOW);
    assert.equal(+v.km.toFixed(6), 1.19);
    assert.equal(+v.kmPerL.toFixed(6), 20);
    assert.equal(+v.kwhPer100.toFixed(2), 27.78);
  },
  'the electric share counts only distance with the engine state known': ({ tenMin }) => {
    const v = tenMin(series(121, (i) => ({ kmh: 36, kw: 5, ice: i <= 40 ? null : (i <= 80 ? false : true) })), NOW);
    assert.equal(+v.evShare.toFixed(6), 0.5);
  },
  'a gap in the samples breaks the traces': ({ tenMin }) => {
    const a = series(30, () => ({ kmh: 30, kw: 8, fuel: 6, ice: true })).map((s) => Object.assign({}, s, { t: s.t - 60000 }));
    const b = series(30, () => ({ kmh: 30, kw: 8, fuel: 6, ice: true }));
    const v = tenMin(a.concat(b), NOW);
    assert.equal(moves(v.drive), 2);
    assert.equal(moves(v.fuel), 2);
  },
  'the kW scale grows in 20 kW steps and never below -20..40': ({ tenMin }) => {
    const small = tenMin(series(10, () => ({ kw: 5 })), NOW);
    assert.deepEqual([small.top, small.bottom], ['40', '-20']);
    const big = tenMin(series(10, (i) => ({ kw: i % 2 ? 57 : -31 })), NOW);
    assert.deepEqual([big.top, big.bottom], ['60', '-40']);
  },
  'samples older than ten minutes are not drawn': ({ tenMin }) => {
    const old = [{ t: NOW - 700000, kw: 90, kmh: 50 }];
    const v = tenMin(old.concat(series(5, () => ({ kw: 10, kmh: 50 }))), NOW);
    assert.equal(v.top, '40');
  },
  'a level that fell shows the used part from the start': ({ level }) => {
    const v = level(72, 58, 'ev');
    assert.equal(v.baseW, '58%');
    assert.equal(v.deltaL, '58%');
    assert.equal(v.deltaW, '14%');
    assert.equal(v.deltaClass, 'used');
    assert.equal(v.markL, '72%');
    assert.equal(v.delta, 'início 72% · −14 p.p.');
  },
  'a refuel above the start is a gain': ({ level }) => {
    const v = level(20, 80, 'fuel');
    assert.equal(v.baseW, '20%');
    assert.equal(v.deltaW, '60%');
    assert.equal(v.deltaClass, 'gain');
    assert.equal(v.delta, 'início 20% · +60 p.p. abastecimento');
  },
  'no trip start shows the level alone': ({ level }) => {
    const v = level(undefined, 44, 'ev');
    assert.equal(v.baseW, '44%');
    assert.equal(v.deltaW, '0%');
    assert.equal(v.markClass, 'off');
    assert.equal(v.delta, '');
  },
};

function run(html) {
  const fns = load(html);
  const failed = [];
  for (const [name, scenario] of Object.entries(SCENARIOS)) {
    try { scenario(fns); } catch (e) { failed.push(name); }
  }
  return failed;
}

const clean = run(SOURCE);
assert.deepEqual(clean, [], `AGORA scenarios failed: ${clean.join(', ')}`);

const MUTANTS = {
  'gaps never break a trace': ['if (!cur || !prev || s.t - prev.t > GAP) {', 'if (!cur || !prev) {'],
  'electric share over all distance': ['evShare: knownKm >= 0.05 ? evKm / knownKm : NaN,', 'evShare: km >= 0.05 ? evKm / km : NaN,'],
  'kW scale floor removed': ['const hi = Math.max(40, Math.ceil(maxKw / 20) * 20);', 'const hi = Math.max(0, Math.ceil(maxKw / 20) * 20);'],
  'old samples kept': ['const list = (samples || []).filter((s) => s && s.t > now - SPAN && s.t <= now);', 'const list = (samples || []).filter((s) => s);'],
  'gain and used swapped': ["deltaClass: n > s ? 'gain' : 'used',", "deltaClass: n > s ? 'used' : 'gain',"],
  'fuel integrated per second instead of per km': ['if (isFinite(b.fuel)) fuelL += b.fuel * dkm / 100;', 'if (isFinite(b.fuel)) fuelL += b.fuel * dt / 100000;'],
};

for (const [name, [from, to]] of Object.entries(MUTANTS)) {
  assert.equal(SOURCE.split(from).length, 2, `mutant anchor must match once: ${name}`);
  assert.ok(run(SOURCE.replace(from, to)).length > 0, `no scenario catches the mutant: ${name}`);
}

console.log(`energy AGORA: ok (${Object.keys(SCENARIOS).length} scenarios, ${Object.keys(MUTANTS).length} mutants caught)`);

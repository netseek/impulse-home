/*
 * Behavioural test for the ENERGIA AGORA screen's pure logic (index.html):
 * _energyChartView (the chart over minutes or km, its density and averages) and
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
  const chart = fn('_energyChartView');
  return {
    tenMin: (samples, now) => chart(samples, now, { kind: 'time', span: 600000, points: 0 }),
    chart: chart,
    level: fn('_energyLevelView'),
  };
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
  // Owner, 2026-09-20: the scale fits the window in round steps, so the traces
  // fill the box; with no regen the floor is zero.
  'the kW scale fits the data, in round steps': ({ tenMin }) => {
    const small = tenMin(series(10, () => ({ kw: 5 })), NOW);
    assert.equal(small.bottom, '0', 'no regen: floor at zero');
    assert.ok(+small.top >= 5 && +small.top <= 10, 'small top ' + small.top);
    const big = tenMin(series(10, (i) => ({ kw: i % 2 ? 57 : -31 })), NOW);
    assert.ok(+big.top >= 57 && +big.top <= 75, 'big top ' + big.top);
    assert.ok(+big.bottom <= -31 && +big.bottom >= -50, 'big bottom ' + big.bottom);
  },
  'the fuel scale fits the window too': ({ tenMin }) => {
    const thirsty = tenMin(series(10, () => ({ kw: 5, fuel: 18, ice: true })), NOW);
    assert.ok(+thirsty.fuelTop >= 18, 'top ' + thirsty.fuelTop);
    const thrifty = tenMin(series(10, () => ({ kw: 5, fuel: 4, ice: true })), NOW);
    assert.ok(+thrifty.fuelTop < +thirsty.fuelTop, 'thrifty ' + thrifty.fuelTop);
  },
  'samples older than ten minutes are not drawn': ({ tenMin }) => {
    const old = [{ t: NOW - 700000, kw: 90, kmh: 50 }];
    const v = tenMin(old.concat(series(5, () => ({ kw: 10, kmh: 50 }))), NOW);
    // The 90 kW sample is outside the window, so it cannot stretch the scale.
    assert.ok(+v.top <= 20, 'top ' + v.top);
  },
  'a km span keeps only the last N km': ({ chart }) => {
    // 30 min at 60 km/h = 30 km; a 10 km window holds the last ~600 samples.
    const s = series(1800, (i) => ({ kw: i < 1000 ? 90 : 10, kmh: 60, km: i / 60 }));
    const v = chart(s, NOW, { kind: 'km', span: 10, points: 0 });
    // Only the last 10 km are in view, and they are the 10 kW stretch.
    assert.ok(+v.top <= 20, 'top ' + v.top);
    assert.ok(Math.abs(v.km - 10) < 0.2, 'window distance ' + v.km);
  },
  'density averages into buckets': ({ chart }) => {
    const s = series(600, (i) => ({ kw: i % 2 ? 30 : 10, kmh: 50 }));
    const raw = chart(s, NOW, { kind: 'time', span: 600000, points: 0 });
    const coarse = chart(s, NOW, { kind: 'time', span: 600000, points: 20 });
    const count = (d) => (d.match(/L/g) || []).length;
    assert.ok(count(coarse.drive) < 30 && count(raw.drive) > 500, count(coarse.drive) + ' / ' + count(raw.drive));
  },
  'no fuel trace while the engine is off': ({ chart }) => {
    const s = series(60, (i) => ({ kw: 10, kmh: 50, fuel: 7, ice: i < 30 }));
    const v = chart(s, NOW, { kind: 'time', span: 600000, points: 0 });
    // 30 engine-on points, then one drop back to 0 where the engine stops.
    assert.equal((v.fuel.match(/L/g) || []).length, 30);
    const zeroY = Number(v.zero.split(' ')[1]);
    const lastY = Number(v.fuel.trim().split(' ').pop());
    assert.equal(lastY, zeroY, v.fuel.slice(-30));
  },
  'fuel 0 sits on the kW zero line': ({ chart }) => {
    // Some regen, so the zero line sits above the floor and the two scales can differ.
    const s = series(10, (i) => ({ kw: i === 0 ? -20 : 10, kmh: 50, fuel: 0, ice: true }));
    const v = chart(s, NOW, { kind: 'time', span: 600000, points: 0 });
    assert.ok(Number(v.bottom) < 0, 'expected a regen floor, got ' + v.bottom);
    const zeroY = Number(v.zero.split(' ')[1]);
    const ys = (v.fuel.match(/[ML][\d.]+ ([\d.]+)/g) || []).map((m) => Number(m.split(' ')[1]));
    assert.ok(ys.length > 0 && ys.every((y) => y === zeroY), v.fuel.slice(0, 40) + ' vs ' + zeroY);
  },
  'smooth densities draw curves, the finest does not': ({ chart }) => {
    const s = series(600, (i) => ({ kw: 20 * Math.sin(i / 20), kmh: 50 }));
    const soft = chart(s, NOW, { kind: 'time', span: 600000, points: 40, smooth: true });
    const fine = chart(s, NOW, { kind: 'time', span: 600000, points: 300, smooth: false });
    assert.ok(soft.drive.includes(' C') && !fine.drive.includes(' C'));
  },
  'averaged points do not shift as the window slides': ({ chart }) => {
    const s = series(600, (i) => ({ kw: 10 + 10 * Math.sin(i / 7), kmh: 50 }));
    const spec = { kind: 'time', span: 600000, points: 60 };
    const a = chart(s, NOW, spec);
    const b = chart(s.concat([{ t: NOW + 1000, kw: 12, kmh: 50 }]), NOW + 1000, spec);
    const xs = (d) => (d.match(/[ML]-?[\d.]+/g) || []).map((m) => +m.slice(1));
    const shift = xs(a.drive)[3] - xs(b.drive)[3];
    // One second of a 10-minute window is one pixel of 600, not a re-cut bucket,
    // and every averaged point sits where its own timestamp falls in the window.
    assert.ok(shift > 0.5 && shift < 1.5, 'shift ' + shift);
    assert.ok(xs(a.drive).every((x) => x >= -20 && x <= 620), 'off-window x: ' + xs(a.drive).slice(0, 3));
  },
  'a level that fell shows the used part from the start': ({ level }) => {
    const v = level(72, 58, 'ev', 34);
    assert.equal(v.baseW, '58%');
    assert.equal(v.deltaL, '58%');
    assert.equal(v.deltaW, '14%');
    assert.equal(v.deltaClass, 'used');
    assert.equal(v.markL, '72%');
    assert.equal(v.delta, '−4.8 kWh · −14%');
  },
  'a refuel above the start is a gain': ({ level }) => {
    const v = level(20, 80, 'fuel', 55);
    assert.equal(v.baseW, '20%');
    assert.equal(v.deltaW, '60%');
    assert.equal(v.deltaClass, 'gain');
    assert.equal(v.delta, '+33 L · +60%');
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
  'gaps never break a trace': ['const joined = (a, b) => (n ? b.b - a.b === 1 : b.t - a.t <= GAP);', 'const joined = () => true;'],
  'density ignored': ['const n = spec.points > 0 ? spec.points : 0;', 'const n = 0;'],
  'km span ignored': ['const inside = byKm ? (s) => isFinite(s.km) && s.km > kmNow - SPAN', 'const inside = byKm ? (s) => true'],
  'electric share over all distance': ['evShare: knownKm >= 0.05 ? evKm / knownKm : NaN,', 'evShare: km >= 0.05 ? evKm / km : NaN,'],
  'kW scale ignores the data': ['    const hi = topFor(kwStep);', '    const hi = 40;'],
  'fuel scale ignores the data': ['const fuelTop = Math.max(5, nice(Math.max(1, maxFuel / 2)) * 2);', 'const fuelTop = 20;'],
  'buckets cut from the window edge again': ['const keyOf = byKm ? (s) => Math.floor(s.km / wide) : (s) => Math.floor(s.t / wide);',
    'const keyOf = byKm ? (s) => Math.floor((s.km - (kmNow - SPAN)) / wide) : (s) => Math.floor((s.t - (now - SPAN)) / wide);'],
  'fuel drawn with the engine off': ['fuel: s.ice === false ? NaN : s.fuel,', 'fuel: s.fuel,'],
  'fuel scale off the zero line': ['const yf = (f) => (zeroY - Math.max(0, Math.min(fuelTop, f)) / fuelTop * zeroY).toFixed(1);', 'const yf = (f) => ((fuelTop - Math.max(0, Math.min(fuelTop, f))) / fuelTop * H).toFixed(1);'],
  'smoothing never applied': ['if (!spec.smooth || xy.length < 3)', 'if (true)'],
  'fuel run left floating': ['if (after) xy.push([Number(x(after)), z]);', ''],
  'old samples kept': [': (s) => s.t > now - SPAN;', ': (s) => true;'],
  'gain and used swapped': ["deltaClass: n > s ? 'gain' : 'used',", "deltaClass: n > s ? 'used' : 'gain',"],
  'fuel integrated per second instead of per km': ['if (isFinite(b.fuel)) fuelL += b.fuel * dkm / 100;', 'if (isFinite(b.fuel)) fuelL += b.fuel * dt / 100000;'],
};

for (const [name, [from, to]] of Object.entries(MUTANTS)) {
  assert.equal(SOURCE.split(from).length, 2, `mutant anchor must match once: ${name}`);
  assert.ok(run(SOURCE.replace(from, to)).length > 0, `no scenario catches the mutant: ${name}`);
}

console.log(`energy AGORA: ok (${Object.keys(SCENARIOS).length} scenarios, ${Object.keys(MUTANTS).length} mutants caught)`);

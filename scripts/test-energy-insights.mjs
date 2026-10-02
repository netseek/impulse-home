/*
 * Behavioural test for the ENERGY insights (index.html _energyTripInsights,
 * _energyMonthCompare, _energyBestWorst).
 *
 * Those methods are pure (no `this`), so they are lifted out of index.html and
 * run against fixtures. Every scenario is then re-run against a deliberately
 * broken copy of the source: a mutant that no scenario catches means the suite
 * is decoration (docs/engineering, "negative-control every assertion you add").
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
  return {
    insights: fn('_energyTripInsights'),
    monthCompare: fn('_energyMonthCompare'),
    bestWorst: fn('_energyBestWorst'),
  };
}

const DAY = 86400000;
const NOW = new Date(2026, 8, 13, 18, 0).getTime();   // 13 Sep 2026, local time
const trip = (o) => Object.assign({ startMs: NOW - DAY, km: 10, fuelL: 0.7, kwhOut: 1.5, kwhIn: 0.3, evKm: 5,
  driveMs: 1200000, idleMs: 60000, harshAccel: 0, harshBrake: 1, climbM: 10 }, o);
const peers = (n, o) => Array.from({ length: n }, (_, i) => trip(Object.assign({ startMs: NOW - (i + 2) * DAY }, o)));
const kinds = (list) => list.map((i) => i.kind);
const find = (list, kind) => list.find((i) => i.kind === kind);

const SCENARIOS = {
  'better economy is reported with its size': ({ insights }) => {
    const r = insights(trip({ fuelL: 0.55 }), peers(4));          // 18.2 vs 14.3 km/L
    const e = find(r, 'economy');
    assert.ok(e, 'economy insight expected');
    assert.equal(e.tone, 'good');
    assert.match(e.text, /^27% melhor consumo de combustível/);
  },
  'a small economy difference is not an insight': ({ insights }) => {
    const r = insights(trip({ fuelL: 0.67 }), peers(4));          // ~4.5% better
    assert.ok(!find(r, 'economy'), JSON.stringify(r));
  },
  'no comparison without enough recent trips': ({ insights }) => {
    const r = insights(trip({ fuelL: 0.4 }), peers(2));
    assert.ok(!find(r, 'economy'), JSON.stringify(r));
  },
  'an electric trip compares electricity per km instead': ({ insights }) => {
    const r = insights(trip({ fuelL: 0.01, kwhOut: 0.8, kwhIn: 0.3, evKm: 10 }), peers(4));  // 5 vs 12 Wh/km net
    const e = find(r, 'economy');
    assert.ok(e, JSON.stringify(r));
    assert.match(e.text, /menos eletricidade por km/);
  },
  'more hard events than usual are flagged with both rates': ({ insights }) => {
    const r = insights(trip({ harshBrake: 4, harshAccel: 2 }), peers(4));   // 6.0 vs 1.0 per 10 km
    const h = find(r, 'harsh');
    assert.ok(h && h.tone === 'bad', JSON.stringify(r));
    assert.match(h.text, /^4 frenagens bruscas e 2 acelerações bruscas, acima do normal \(6\.0 vs 1\.0 a cada 10 km\)$/);
  },
  'a smooth trip says so when the driver is usually not': ({ insights }) => {
    const r = insights(trip({ harshBrake: 0, km: 8 }), peers(4));
    const h = find(r, 'harsh');
    assert.ok(h && h.tone === 'good', JSON.stringify(r));
  },
  'long idling is reported, short idling is not': ({ insights }) => {
    assert.ok(find(insights(trip({ driveMs: 600000, idleMs: 300000 }), peers(4)), 'idle'));
    assert.ok(!find(insights(trip({ driveMs: 600000, idleMs: 120000 }), peers(4)), 'idle'));
  },
  'at most three, strongest first': ({ insights }) => {
    const r = insights(trip({ fuelL: 0.4, harshBrake: 5, idleMs: 900000, climbM: 200, kwhIn: 0.9, evKm: 9.5 }), peers(4));
    assert.equal(r.length, 3);
    for (let i = 1; i < r.length; i++) assert.ok(r[i - 1].score >= r[i].score);
  },
  'the trip itself is not its own peer': ({ insights }) => {
    // The history list the page passes in includes the trip being shown. Three
    // copies of it plus one real peer is ONE usable peer: too few to compare.
    // Counted as peers, the copies would pull the average toward the trip and
    // still produce an economy claim (+19% here).
    const t = trip({ fuelL: 0.4, startMs: NOW - 5 * DAY });
    const r = insights(t, [t, t, t, trip({ startMs: NOW - 6 * DAY })]);
    assert.ok(!find(r, 'economy'), JSON.stringify(r));
  },
  'a guided trip compares its time with the Android Auto estimate': ({ insights }) => {
    const r = insights(trip({ guided: 1, arrived: 1, plannedS: 1080, guidedS: 1500 }), peers(4));
    const g = find(r, 'route');
    assert.ok(g && g.tone === 'bad', JSON.stringify(r));
    assert.match(g.text, /^Levou 25 min, contra a estimativa de 18 min do Android Auto$/);
    assert.ok(!find(insights(trip({ guided: 1, arrived: 1, plannedS: 1080, guidedS: 1150 }), peers(4)), 'route'),
      'a 6% difference is not an insight');
    assert.ok(!find(insights(trip({ guided: 1, arrived: 0, plannedS: 1080, guidedS: 2000 }), peers(4)), 'route'),
      'an abandoned route has no arrival time to compare');
  },
  'month compare uses the same days of last month': ({ monthCompare }) => {
    const days = [
      { period: '2026-09-02', km: 30, fuelL: 2 }, { period: '2026-09-13', km: 20, fuelL: 1 },
      { period: '2026-08-05', km: 40, fuelL: 3 }, { period: '2026-08-20', km: 500, fuelL: 30 },
    ];
    const c = monthCompare(days, NOW);
    assert.equal(c.cur.km, 50);
    assert.equal(c.prev.km, 40, 'Aug 20 is past day 13 and must not count');
    assert.equal(c.comparable, true);
  },
  'a short previous month is clamped': ({ monthCompare }) => {
    const c = monthCompare([{ period: '2026-02-28', km: 7 }], new Date(2026, 2, 30, 12).getTime());
    assert.equal(c.prevThroughDay, 28);
    assert.equal(c.prev.km, 7);
  },
  'January compares with December of the year before': ({ monthCompare }) => {
    const c = monthCompare([{ period: '2025-12-03', km: 12 }, { period: '2026-01-02', km: 9 }], new Date(2026, 0, 5, 12).getTime());
    assert.equal(c.prev.km, 12);
    assert.equal(c.cur.km, 9);
  },
  'best and worst ignore short and old trips': ({ bestWorst }) => {
    const list = [
      trip({ startMs: NOW - DAY, km: 12, fuelL: 0.6 }),        // 20 km/L
      trip({ startMs: NOW - 2 * DAY, km: 10, fuelL: 0.9 }),    // 11.1
      trip({ startMs: NOW - 3 * DAY, km: 2, fuelL: 0.05 }),    // 40, but under 3 km
      trip({ startMs: NOW - 40 * DAY, km: 10, fuelL: 2 }),     // 5, but older than 30 days
    ];
    const bw = bestWorst(list, NOW);
    assert.equal(bw.best.km, 12);
    assert.equal(bw.worst.km, 10);
    assert.equal(bestWorst([list[0], list[2]], NOW), null, 'one eligible trip is not a ranking');
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
assert.deepEqual(clean, [], `insight scenarios failed: ${clean.join(', ')}`);

const MUTANTS = {
  'economy threshold removed': ['if (Math.abs(diff) >= 0.08) {\n          add(\'economy\', diff > 0', 'if (Math.abs(diff) >= 0) {\n          add(\'economy\', diff > 0'],
  'peer minimum removed': ['if (km >= 1 && usable.length >= 3) {', 'if (km >= 1 && usable.length >= 0) {'],
  'own trip counted as a peer': ['p && p.startMs !== trip.startMs && Number(p.km) >= 1', 'p && Number(p.km) >= 1'],
  'harsh rate bar raised': ['rate >= Math.max(0.5, avgRate * 1.5)', 'rate >= Math.max(0.5, avgRate * 100)'],
  'January rollover broken': ['const prevY = m === 0 ? y - 1 : y;', 'const prevY = y;'],
  'last month not clamped to the same days': ['const prevDay = Math.min(day, prevLen);', 'const prevDay = prevLen;'],
  'short trips ranked': ['Number(t.km) >= 3 && Number(t.fuelL) >= 0.05', 'Number(t.km) >= 0 && Number(t.fuelL) >= 0.05'],
  'route threshold removed': ['if (Math.abs(r) >= 0.15) add(', 'if (Math.abs(r) >= 0) add('],
  'abandoned routes compared': ['if (trip.guided && trip.arrived && plannedS >= 300', 'if (trip.guided && plannedS >= 300'],
  'more than three returned': ['.sort((a, b) => b.score - a.score).slice(0, 3);', '.sort((a, b) => b.score - a.score);'],
};

for (const [name, [from, to]] of Object.entries(MUTANTS)) {
  assert.equal(SOURCE.split(from).length, 2, `mutant anchor must match once: ${name}`);
  const failed = run(SOURCE.replace(from, to));
  assert.ok(failed.length > 0, `no scenario catches the mutant: ${name}`);
}

console.log(`energy insights: ok (${Object.keys(SCENARIOS).length} scenarios, ${Object.keys(MUTANTS).length} mutants caught)`);

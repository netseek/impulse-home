/*
 * Behavioural test for the ENERGY map's pure logic (index.html):
 * _energyMercator, _energyFitZoom, _energyOverlayFrame, _energyRouteSegments,
 * _energyRangeEstimates. Lift the methods out, run fixtures, then prove every
 * mutant is caught.
 *
 * The JS projection must agree with TripMapMath (Java), which frames the saved
 * snapshot: a route drawn by one over tiles framed by the other would not line up.
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
  const self = {};
  for (const name of ['_energyMercator', '_energyFitZoom', '_energyOverlayFrame', '_energyRouteSegments', '_energyRangeEstimates', '_energyMapView', '_energyTrackAppend']) {
    self[name] = fn(name);
  }
  const bind = (name) => (...a) => self[name].apply(self, a);
  return {
    mercator: bind('_energyMercator'),
    fit: bind('_energyFitZoom'),
    mapView: bind('_energyMapView'),
    trackAppend: bind('_energyTrackAppend'),
    frame: bind('_energyOverlayFrame'),
    segments: bind('_energyRouteSegments'),
    range: bind('_energyRangeEstimates'),
  };
}

const KML = { fuel: 'kml', ev: 'kwh100' };
const L100 = { fuel: 'l100', ev: 'kmkwh' };
// [lat, lon, kmh, kw, fuelMode, fuelRate]
const pt = (i, kmh, kw, mode, rate) => [-22.9 - i * 0.001, -43.1, kmh, kw, mode, rate];

const SCENARIOS = {
  'a finished route is framed whole; a live one follows the car once it outgrows zoom 13': ({ mapView, fit, mercator }) => {
    const short = [[-22.9, -43.1], [-22.9003, -43.1003]];
    const done = mapView(short, 600, 400, false, 3, 18);
    assert.equal(done.z, fit(short, 600, 400, 32, 3, 18));
    assert.ok(done.z > 16, 'fixture must zoom past the live cap');
    const a = mercator(short[0][0], short[0][1], done.z);
    const b = mercator(short[1][0], short[1][1], done.z);
    assert.equal(Math.round(done.cx), Math.round((a[0] + b[0]) / 2));
    assert.equal(mapView(short, 600, 400, true, 3, 18).z, 16);
    const long = [[-22.9, -43.1], [-23.5, -46.6]];
    assert.ok(mapView(long, 600, 400, false, 3, 18).z < 13);
    const follow = mapView(long, 600, 400, true, 3, 18);
    const car = mercator(-23.5, -46.6, 13);
    assert.deepEqual([follow.z, Math.round(follow.cx), Math.round(follow.cy)], [13, Math.round(car[0]), Math.round(car[1])]);
  },
  'the live track appends only newer points and thins its older half': ({ trackAppend }) => {
    const pt = (t) => [t, -22.9, -43.1, 0, 30, 1, 1, 5, 0, t / 1000];
    let tr = trackAppend([], [pt(1000), pt(2000)], 100);
    tr = trackAppend(tr, [pt(2000), pt(3000)], 100);
    assert.deepEqual(tr.map((p) => p[0]), [1000, 2000, 3000]);
    const thin = trackAppend([], Array.from({ length: 300 }, (_, i) => pt((i + 1) * 1000)), 100);
    assert.ok(thin.length <= 100);
    assert.equal(thin[0][0], 1000);
    assert.deepEqual(thin.slice(-20).map((p) => p[0]), Array.from({ length: 20 }, (_, i) => (281 + i) * 1000), 'the newest points stay at full rate');
  },
  'projection follows the slippy-map convention': ({ mercator }) => {
    assert.deepEqual(mercator(0, 0, 0).map((v) => +v.toFixed(9)), [128, 128]);
    assert.equal(+mercator(0, 180, 0)[0].toFixed(9), 256);
    assert.ok(mercator(-23, -43.1, 10)[1] > mercator(-22.9, -43.1, 10)[1], 'north is up');
    assert.equal(+(mercator(10, 20, 12)[0] * 2).toFixed(6), +mercator(10, 20, 13)[0].toFixed(6), 'zoom doubles the world');
  },
  'latitude is clamped at the Mercator limit': ({ mercator }) => {
    // At the limit y is 0 up to rounding (-1.6e-9 measured); past it, unclamped,
    // it would run away toward -infinity.
    const y = mercator(90, 0, 0)[1];
    assert.ok(isFinite(y) && Math.abs(y) < 1e-3, 'y=' + y);
    assert.equal(y, mercator(85.05112878, 0, 0)[1], '90° must clamp to the limit');
  },
  'A known point lands on the tile the OSM wiki formula gives': ({ mercator }) => {
    // The slippy-map wiki's asinh form of the projection, written independently
    // of the implementation (which uses log(tan + sec)): the two must agree.
    const z = 15, n = 2 ** z, lat = -22.9 * Math.PI / 180;
    const wikiX = Math.floor((-43.1 + 180) / 360 * n);
    const wikiY = Math.floor((1 - Math.asinh(Math.tan(lat)) / Math.PI) / 2 * n);
    const [x, y] = mercator(-22.9, -43.1, z);
    assert.equal(Math.floor(x / 256), wikiX);
    assert.equal(Math.floor(y / 256), wikiY);
    assert.deepEqual([wikiX, wikiY], [12460, 18526]);
  },
  'a one-kilometre route fits the popup map at street zoom': ({ fit }) => {
    const z = fit([[-22.909, -43.101], [-22.900, -43.100]], 900, 380, 32, 3, 18);
    assert.ok(z >= 14 && z <= 16, 'z' + z);
  },
  'a single point uses the closest zoom': ({ fit }) => {
    assert.equal(fit([[-22.9, -43.1], [-22.9, -43.1]], 900, 380, 32, 3, 18), 18);
  },
  'the overlay uses the stored snapshot frame when there is one': ({ frame }) => {
    assert.deepEqual(frame([[-22.9, -43.1], [-22.91, -43.1]], '15,100.5,200.25'), { z: 15, ox: 100.5, oy: 200.25 });
  },
  'without a snapshot the overlay frames the route the way the renderer does': ({ frame, mercator }) => {
    const fixes = [[-22.909, -43.101], [-22.900, -43.100]];
    const f = frame(fixes, '');
    assert.ok(f.z >= 14 && f.z <= 16, 'z' + f.z);
    const a = mercator(-22.909, -43.101, f.z), b = mercator(-22.900, -43.100, f.z);
    assert.ok(Math.abs((a[0] + b[0]) / 2 - f.ox - 320) < 1e-6, 'centred horizontally in 640');
    assert.ok(Math.abs((a[1] + b[1]) / 2 - f.oy - 200) < 1e-6, 'centred vertically in 400');
  },
  'speed bands merge consecutive points into one segment': ({ segments }) => {
    const r = segments([pt(0, 10), pt(1, 20), pt(2, 70), pt(3, 80), pt(4, 120)], 'speed', KML);
    assert.deepEqual(r.segments.map((s) => s.cls), ['b0', 'b2', 'b4']);
    assert.deepEqual(r.segments.map((s) => s.pts.length), [2, 3, 2]);
    assert.equal(r.legend.length, 5);
  },
  'electric bands: regeneration, and nothing below 10 km/h': ({ segments }) => {
    const r = segments([pt(0, 50, 5), pt(1, 50, -5), pt(2, 5, 3), pt(3, 50, 5)], 'ev', KML);
    assert.deepEqual(r.segments.map((s) => s.cls), ['b0', 'none', 'b2']);
  },
  'fuel bands, and legends follow the chosen unit': ({ segments }) => {
    const r = segments([pt(0, 50, 0, 1, 0), pt(1, 50, 0, 1, 0), pt(2, 50, 0, 1, 6)], 'fuel', KML);
    assert.deepEqual(r.segments.map((s) => s.cls), ['b0', 'b2']);
    assert.equal(r.legend[1].label, '> 20 km/L');
    assert.equal(segments([], 'fuel', L100).legend[1].label, '< 5 L/100');
    assert.equal(segments([], 'ev', L100).legend[1].label, '> 10 km/kWh');
  },
  "range per tank uses the version's tank; low levels give no estimate": ({ range }) => {
    const e = range({ km: 1000, fuelL: 50 }, { fuelRange: '400', fuelLevel: '50', evRange: '3', soc: '5' }, { tankL: 55 });
    assert.equal(e.perTankYours, 1100);
    assert.equal(range({ km: 1000, fuelL: 50 }, {}, { tankL: 60 }).perTankYours, 1200, 'the HEV tank is 60 L');
    assert.equal(e.perTankCar, 800);
    assert.ok(Number.isNaN(e.perChargeCar), 'SOC 5% is too coarse to scale up');
    assert.ok(Number.isNaN(range({ km: 100, fuelL: 0.5 }, {}, { tankL: 55 }).perTankYours), 'half a litre is not an economy');
  },
  'range per charge: measured capacity first, else nominal; share of the declared range': ({ range }) => {
    const spec = { tankL: 55, batteryKwh: 34, evRangeKm: 170 };
    const e = range({ km: 500, fuelL: 30, evKm: 200, kwhOut: 30, kwhIn: 10, capacityKwh: 1.5 }, { evRange: '2', soc: '40' }, spec);
    assert.equal(e.perChargeCar, 5);
    assert.equal(e.perChargeYours, 15);   // measured 1.5 kWh / (20 kWh / 200 km)
    assert.equal(e.capacityMeasured, true);
    const n = range({ km: 500, fuelL: 30, evKm: 200, kwhOut: 30, kwhIn: 13 }, {}, spec);
    assert.equal(n.capacityKwh, 34);
    assert.equal(Math.round(n.perChargeYours), 400);   // nominal 34 kWh / (17 kWh / 200 km)
    assert.equal(Math.round(n.declaredShare * 100), 235);
    const p19 = range({ km: 500, fuelL: 30, evKm: 200, kwhOut: 30, kwhIn: 13 }, {}, { tankL: 55, batteryKwh: 19, evRangeKm: 115 });
    assert.equal(Math.round(p19.declaredShare * 100), 194, "PHEV19 is measured against its own 115 km");
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
assert.deepEqual(clean, [], `map scenarios failed: ${clean.join(', ')}`);

const MUTANTS = {
  'live view never follows the car': ['if (live && z < FOLLOW_Z) {', 'if (live && z < 0) {'],
  'live view not capped at 16': ['live ? Math.min(maxZ, 16) : maxZ', 'maxZ'],
  'old points appended again': ['.filter((p) => p && p[0] > last)', '.filter((p) => p)'],
  'thinning drops the newest points too': ['out = out.slice(0, half).filter((p, i) => i % 2 === 0).concat(out.slice(half));', 'out = out.filter((p, i) => i % 2 === 0);'],
  'latitude clamp removed': ['const r = Math.max(-85.05112878, Math.min(85.05112878, lat)) * Math.PI / 180;', 'const r = lat * Math.PI / 180;'],
  'longitude scale wrong': ['return [(lon + 180) / 360 * scale,', 'return [(lon + 180) / 180 * scale,'],
  'fit never zooms in': ['for (let z = maxZ; z > minZ; z--) {\n      const nw = this._energyMercator(maxLat, minLon, z);', 'for (let z = minZ; z > minZ; z--) {\n      const nw = this._energyMercator(maxLat, minLon, z);'],
  'stored snapshot frame ignored': ['if (parts.length === 3 && parts.every((v) => isFinite(v))) return', 'if (false) return'],
  'overlay not centred like the renderer': ['return { z: z, ox: (minX + maxX) / 2 - 320, oy: (minY + maxY) / 2 - 200 };', 'return { z: z, ox: minX, oy: minY };'],
  'speed band edge moved': ["return kmh < 30 ? 'b0' : (kmh < 60 ? 'b1'", "return kmh < 30 ? 'b0' : (kmh < 75 ? 'b1'"],
  'segments never merge': ['if (cur && cur.cls === cls) {', 'if (false) {'],
  'regeneration not shown': ["if (kw < 0) return 'b0';", "if (kw < 0) return 'b1';"],
  'one tank size for every version': ['const tankL = spec && spec.tankL > 0 ? spec.tankL : 55;', 'const tankL = 55;'],
  'measured capacity ignored': ['const capacity = measured > 0 ? measured :', 'const capacity = false ? measured :'],
  'declared share against the wrong range': ['declaredShare: declared > 0 ? perChargeYours / declared : NaN,', 'declaredShare: declared > 0 ? perChargeYours / 170 : NaN,'],
  'low battery level scaled up': ['const perChargeCar = soc >= 10 && evRange >= 0', 'const perChargeCar = soc >= 0 && evRange >= 0'],
};

for (const [name, [from, to]] of Object.entries(MUTANTS)) {
  assert.equal(SOURCE.split(from).length, 2, `mutant anchor must match once: ${name}`);
  assert.ok(run(SOURCE.replace(from, to)).length > 0, `no scenario catches the mutant: ${name}`);
}

console.log(`energy map math: ok (${Object.keys(SCENARIOS).length} scenarios, ${Object.keys(MUTANTS).length} mutants caught)`);

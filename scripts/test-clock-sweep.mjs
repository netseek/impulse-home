/*
 * Contract for the panorama seconds sweep (assets/clock-faces.js).
 *
 * The native rail card rasterizes the face ONCE A MINUTE with omitSweep and
 * strokes the seconds sweep itself from H6ClockFaces.sweep() -- re-encoding the
 * whole face every second cost the WebView main thread 25-160 ms/s on the car.
 * That only holds while sweep() describes exactly the rect render() would have
 * drawn, so this pins the two to each other for every size and appearance, and
 * checks omitSweep really drops it. Every assertion is re-run against broken
 * copies of the source (negative controls).
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const SOURCE = readFileSync(resolve(root, 'assets/clock-faces.js'), 'utf8').replace(/\r\n/g, '\n');

function load(src) {
  const sandbox = { globalThis: {} };
  sandbox.window = sandbox;
  vm.runInNewContext(src, sandbox);
  return sandbox.H6ClockFaces;
}

const snap = {
  time: '03:07', hour: '03', minute: '07', second: '30', dayPeriod: 'AM',
  weekdayShort: 'Mon', day: '14', monthShort: 'Sep', dateKey: '2026-09-14', year: '2026',
  hourAngle: 93.5, minuteAngle: 42, accessible: '03:07',
};

// The one rect carrying a stroke-dasharray in the OUTER position is the sweep.
function sweepRect(svg) {
  const rects = [...svg.matchAll(/<rect ([^>]*stroke-dasharray[^>]*)\/>/g)].map((m) => m[1]);
  const attrs = rects.map((a) => Object.fromEntries([...a.matchAll(/([\w-]+)="([^"]*)"/g)].map((m) => [m[1], m[2]])));
  return attrs.find((a) => a['stroke-width'] !== '1.5') || null;
}

function check(F) {
  for (const size of ['rail', '1x1', '1x2', '2x1', '2x2']) {
    for (const opts of [{}, { light: true }, { accent: '#ff3366' }]) {
      const o = Object.assign({ size }, opts);
      const s = F.sweep('panorama', o);
      assert.ok(s, `sweep() for ${size}`);
      const r = sweepRect(F.render('panorama', snap, o));
      assert.ok(r, `render() draws a sweep for ${size}`);
      assert.equal(+r.x, s.x, `x ${size}`);
      assert.equal(+r.y, s.y, `y ${size}`);
      assert.equal(+r.width, s.w, `w ${size}`);
      assert.equal(+r.height, s.h, `h ${size}`);
      assert.equal(+r.rx, s.rx, `rx ${size}`);
      assert.equal(+r['stroke-width'], s.stroke, `stroke ${size}`);
      assert.equal(r.stroke, s.color, `color ${size} ${JSON.stringify(opts)}`);
      const vb = F.render('panorama', snap, o).match(/viewBox="0 0 ([\d.]+) ([\d.]+)"/);
      assert.deepEqual([+vb[1], +vb[2]], [s.viewW, s.viewH], `viewBox ${size}`);
      assert.equal(sweepRect(F.render('panorama', snap, Object.assign({ omitSweep: true }, o))), null,
        `omitSweep drops the sweep for ${size}`);
    }
  }
  for (const face of ['meridian', 'split', 'date-spine']) {
    assert.equal(F.sweep(face, {}), null, `${face} has no sweep`);
  }
}

check(load(SOURCE));

const mutants = [
  ['pad drifts', "var pad = small ? 5 : 4;", "var pad = small ? 5 : 3;"],
  ['radius drifts', "rx: small ? 8 : 16", "rx: small ? 8 : 15"],
  ['omitSweep ignored', "if (!options.omitSweep) out.push", "if (true) out.push"],
  ['colour drifts', "color(options.accent, light ? '#087c70' : '#8cebc9')\n    };", "color(options.accent, light ? '#087c71' : '#8cebc9')\n    };"],
  ['sweep for every face', "!== 'panorama') return null;", "=== 'nope') return null;"],
];
let caught = 0;
for (const [name, from, to] of mutants) {
  assert.ok(SOURCE.includes(from), `mutant anchor present: ${name}`);
  try { check(load(SOURCE.replace(from, to))); } catch (e) { caught++; continue; }
  throw new Error(`mutant survived: ${name}`);
}
console.log(`clock sweep: ok (${caught} mutants caught)`);

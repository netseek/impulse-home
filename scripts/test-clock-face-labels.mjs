/*
 * Behavioural test for the rail clock faces (assets/clock-faces.js): the date
 * labels keep their natural glyph proportions.
 *
 * textLength + lengthAdjust="spacingAndGlyphs" stretches a short label to a
 * fixed width, and "MON 14 SEP" read as widened type on Orbit and Dashboard
 * (owner, 2026-09-14). A label that is genuinely too long shrinks instead.
 * Every scenario is re-run against broken copies of the source.
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

const snap = (o) => Object.assign({
  time: '03:07', hour: '03', minute: '07', second: '12', dayPeriod: 'AM',
  weekdayShort: 'Mon', day: '14', monthShort: 'Sep', dateKey: '2026-09-14',
  hourAngle: 93.5, minuteAngle: 42, accessible: '03:07, Monday 14 September',
}, o);

// The <text> element whose content is exactly `value`.
const textFor = (svg, value) => (svg.match(new RegExp('<text[^>]*>' + value + '</text>', 'g')) || [])[0] || '';
const fontSize = (el) => Number((el.match(/font-size="([\d.]+)"/) || [])[1]);

const SCENARIOS = {
  'Orbit date label is not stretched': ({ render }) => {
    const el = textFor(render('panorama', snap()), 'MON 14 SEP');
    assert.ok(el, 'date label rendered');
    assert.ok(!/textLength|lengthAdjust/.test(el), el);
    assert.equal(fontSize(el), 10);
  },
  'Dashboard weekday and month are not stretched': ({ render }) => {
    const svg = render('date-spine', snap());
    for (const v of ['MON', 'SEP']) {
      const el = textFor(svg, v);
      assert.ok(el, v + ' rendered');
      assert.ok(!/textLength|lengthAdjust/.test(el), el);
    }
  },
  'Chronograph date label is not stretched': ({ render }) => {
    const el = textFor(render('meridian', snap()), 'MON 14 SEP');
    assert.ok(el && !/textLength|lengthAdjust/.test(el), el);
  },
  'a label too long for its pill shrinks instead of overflowing': ({ render }) => {
    const el = textFor(render('panorama', snap({ weekdayShort: 'Wednesday', monthShort: 'September' })), 'WEDNESDAY 14 SEPTEMBER');
    assert.ok(el, 'long label rendered');
    assert.ok(fontSize(el) < 10, el);
    assert.ok(!/textLength/.test(el), el);
  },
};

function run(src) {
  const faces = load(src);
  const failed = [];
  for (const [name, scenario] of Object.entries(SCENARIOS)) {
    try { scenario(faces); } catch (e) { failed.push(name); }
  }
  return failed;
}

const clean = run(SOURCE);
assert.deepEqual(clean, [], `clock label scenarios failed: ${clean.join(', ')}`);

const MUTANTS = {
  'Orbit date stretched again': ["label(dateLabel, 112, 101, 10, muted, 600, 'middle', 94);", "text(dateLabel, 112, 101, 10, muted, 600, 'middle', 94);"],
  'Dashboard month stretched again': ["label(String(snap.monthShort).toUpperCase(), 186.5, 58, 11, ground, 700, 'middle', 31);", "text(String(snap.monthShort).toUpperCase(), 186.5, 58, 11, ground, 700, 'middle', 31);"],
  'long labels never shrink': ['var fitted = em > 0 && em * size > maxWidth ?', 'var fitted = false ?'],
};

for (const [name, [from, to]] of Object.entries(MUTANTS)) {
  assert.equal(SOURCE.split(from).length, 2, `mutant anchor must match once: ${name}`);
  assert.ok(run(SOURCE.replace(from, to)).length > 0, `no scenario catches the mutant: ${name}`);
}

console.log(`clock face labels: ok (${Object.keys(SCENARIOS).length} scenarios, ${Object.keys(MUTANTS).length} mutants caught)`);

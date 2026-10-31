/*
 * PSI tyre readouts must match the instrument cluster: convert full-precision
 * bar, then round to one decimal. Truncating after convert sits ~0.1 low;
 * rounding bar first (OEM phone app) collapses nearby corners onto one value.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const html = readFileSync(resolve(root, 'index.html'), 'utf8').replace(/\r\n/g, '\n');

const helpers = html.match(
  /function truncateTo\(value, decimals\) \{[\s\S]*?\n\}\n\nfunction roundTo\(value, decimals\) \{[\s\S]*?\n\}\n\n\/\*\*[\s\S]*?\*\/\nfunction formatTirePressure\(bar, unit\) \{[\s\S]*?\n\}/,
);
assert.ok(helpers, 'truncateTo, roundTo and formatTirePressure stay adjacent top-level helpers');

const load = (src) => vm.runInNewContext(`${src}\n({ truncateTo, roundTo, formatTirePressure })`);
const { formatTirePressure, truncateTo, roundTo } = load(helpers[0]);

assert.equal(formatTirePressure(2.316, 'psi'), '33.6', 'cluster front corners (~2.32 bar)');
assert.equal(formatTirePressure(2.282, 'psi'), '33.1', 'cluster rear that is slightly lower');
assert.equal(formatTirePressure(2.48922, 'psi'), '36.1', 'OEM capture sample stays one-decimal PSI');
assert.notEqual(formatTirePressure(2.316, 'psi'), '33.3', 'must not round bar to 0.1 before convert');
assert.notEqual(truncateTo(2.316 * 14.5037738, 1), '33.6', 'truncate after convert is the old low reading');
assert.equal(roundTo(2.316 * 14.5037738, 1), '33.6');

assert.equal(formatTirePressure(2.48922, 'bar'), '2.4', 'bar still truncates toward zero');
assert.equal(formatTirePressure(2.48922, 'kpa'), '248', 'kPa still truncates toward zero');
assert.equal(formatTirePressure(Number.NaN, 'psi'), '—');
assert.equal(formatTirePressure(undefined, 'psi'), '—');

assert.throws(() => {
  const broken = load(helpers[0].replace(
    'if (unit === \'psi\') return roundTo(Number(bar) * 14.5037738, 1);',
    'if (unit === \'psi\') return truncateTo(Number(bar) * 14.5037738, 1);',
  ));
  assert.equal(broken.formatTirePressure(2.316, 'psi'), '33.6');
}, /Expected values to be strictly equal|33\.6/, 'negative control: truncate must not pass the cluster sample');

console.log('tire-pressure-format: ok');

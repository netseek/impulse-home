/* Source-level contract for the integrated Range card. */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const html = readFileSync(resolve(root, 'index.html'), 'utf8');
const native = readFileSync(resolve(root, 'app/src/main/java/com/havalh6/viewer/MainActivity.java'), 'utf8');

function method(name) {
  const start = html.indexOf(`  ${name}(`);
  assert.ok(start >= 0, `missing ${name}()`);
  const brace = html.indexOf('{', start);
  let depth = 0;
  for (let i = brace; i < html.length; i++) {
    if (html[i] === '{') depth++;
    if (html[i] === '}' && --depth === 0) return html.slice(start, i + 1);
  }
  throw new Error(`unterminated ${name}()`);
}

for (const token of [
  "{ id: 'range', title: 'Range', action: 'openRange' }", "case 'openRange':",
  'focusedCardIsRange', 'class="hv-range-focus"', 'h6_range_unit',
  'CAR_SIGNALS.evRange', 'CAR_SIGNALS.fuelRange', 'DEMO · SIMULATED · NOT VEHICLE',
]) assert.ok(html.includes(token), `missing ${token}`);
for (const name of ['_rangeTelemetry', '_rangeDistanceUnit', '_openRangeCard']) {
  assert.ok(method(name).includes(name), `range method contract ${name}`);
}
assert.ok(native.includes('"openRange"'), 'Android native shell must accept Range action');
assert.ok(!method('_rangeTelemetry').includes('this._powerSource()'), 'range freshness cannot inherit generic power flow');
console.log('range-card contracts: ok');

/* Source-level contract for the integrated Range card. */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
// Line endings are normalised on read. index.html and MainActivity.java are
// stored LF and checked out CRLF on Windows, so a source contract that
// hardcodes either one passes or fails depending on which command last
// rewrote the file. Two of these tests had already broken that way.
const html = readFileSync(resolve(root, 'index.html'), 'utf8').replace(/\r\n/g, '\n');
const native = readFileSync(resolve(root, 'app/src/main/java/com/havalh6/viewer/MainActivity.java'), 'utf8').replace(/\r\n/g, '\n');

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
  'focusedCardIsRange', 'h6_range_unit',
  'CAR_SIGNALS.evRange', 'CAR_SIGNALS.fuelRange', 'DEMO · SIMULATED · NOT VEHICLE',
]) assert.ok(html.includes(token), `missing ${token}`);
for (const name of ['_rangeTelemetry', '_rangeDistanceUnit', '_openRangeCard']) {
  assert.ok(method(name).includes(name), `range method contract ${name}`);
}
assert.ok(native.includes('"openRange"'), 'Android native shell must accept Range action');
// The popup root keeps its class whatever else it gains (the maximised view adds one).
assert.ok(/class="hv-range-focus[\s"]/.test(html), 'missing the hv-range-focus popup root');
// Forecast check: the history estimate reaches native on a timer, and the expanded popup draws what native recorded.
assert.ok(/\.setRangeForecast\(/.test(method('_startRangeForecastReporter')), 'history estimate must be reported to TripBridge.setRangeForecast');
assert.ok(html.slice(html.indexOf('async componentDidMount()'), html.indexOf('async componentDidMount()') + 4000).includes('this._startRangeForecastReporter()'), 'the reporter must start at mount, not only when a range widget renders');
assert.ok(/\.getRangeCycles\(/.test(method('_rangeCycles')), 'the expanded popup reads TripBridge.getRangeCycles');
assert.ok(/this\._rangeBurnView\(/.test(method('_rangeWidgetView')), 'the range popup view carries the forecast check');
assert.ok(html.includes('{{ focusedRangeBurnHistPath }}') && html.includes('{{ focusedRangeWideToggle }}'), 'expanded range popup markup is missing');
assert.ok(/histEvKmNum: !demo && hasHistEv/.test(method('_rangeTelemetry')), 'only a real history estimate may be reported, never demo or the OEM fallback');
assert.ok(!method('_rangeTelemetry').includes('this._powerSource()'), 'range freshness cannot inherit generic power flow');
console.log('range-card contracts: ok');

#!/usr/bin/env node
// Source-level contract for the Power / Energy Flow card family. This keeps
// the native rail, widget, popup, and live-data truthfulness rules together
// without requiring a WebView or an Android device.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const html = readFileSync(resolve(root, 'index.html'), 'utf8');
const java = readFileSync(resolve(root, 'app/src/main/java/com/havalh6/viewer/MainActivity.java'), 'utf8');

function method(source, name) {
  const start = source.indexOf(`  ${name}(`);
  assert.ok(start >= 0, `missing ${name}()`);
  const brace = source.indexOf('{', start);
  let depth = 0;
  for (let i = brace; i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  throw new Error(`unterminated ${name}()`);
}

assert.ok(html.includes("{ id: 'power', title: 'Power flow', action: 'openPower' }"));
assert.ok(html.includes("power: { label: 'POWER', title: 'Energy flow'"));
assert.ok(html.includes("[3, 2]"), '3x2 Power widget size is required');
assert.equal((html.match(/value="\{\{ wg\.isPower \}\}"/g) || []).length, 2,
  'Power widget markup must exist on both boards');
assert.ok(html.includes('focusedCardIsPower'));
assert.ok(html.includes('data-power-size="popup"'));
assert.equal((html.match(/class="hv-power-overlay"/g) || []).length, 3, 'both boards and popup share the vehicle overlay');
assert.equal((html.match(/class="hv-power-chassis"/g) || []).length, 3, 'all Power surfaces use the top-down chassis layer');
assert.ok(!html.includes('src="assets/power/h6-ghost-dark.png"'), 'retired side-view ghost must not remain in Power markup');
for (const token of ['CAR_POWER_GRAPHICS', '_powerGraphicsVariant', '_powerGraphicMarkup',
  '_renderPowerGraphic', '_syncPowerGraphicMotion', 'IntersectionObserver',
  'hv-power-flow-ribbon', 'hv-power-cell-fill', 'prefers-reduced-motion']) {
  assert.ok(html.includes(token), `missing layered Power integration: ${token}`);
}
for (const key of ['phev19', 'phev34', 'hev2']) {
  assert.ok(html.includes(`approved-${key}-chassis.png`), `missing transparent ${key} chassis crop`);
}
assert.ok(html.includes('filterUnits="userSpaceOnUse"'),
  'flow glow must not use a zero-height/width object bounding box');
assert.ok(html.includes('markerUnits="userSpaceOnUse"'),
  'wheel-end chevrons must retain a stable, unclipped size');
assert.ok(html.includes('.hv-power-canvas { position:relative;') && html.includes('overflow:visible;'),
  'Power canvas must permit the wheel-end glow to paint past the route bounds');
assert.ok(html.includes("case 'openPower':\n        this._openFocusedCard('power');"));

for (const key of [
  "powerFlow: 'haval.power.flow'",
  "batterySoc: 'car.ev_info.cur_battery_power_percentage'",
  "batteryVoltage: 'car.ev_info.power_battery_voltage'",
  "batteryCurrent: 'car.ev_info.cur_charge_current'",
]) assert.ok(html.includes(key), `missing verified power key: ${key}`);

const powerStatus = method(html, '_powerStatus');
for (const label of [
  "'LIVE · REPORTED FLOW'",
  "'PARTIAL · VEHICLE POWER SIGNALS'",
  "'STALE · POWER SIGNAL'",
  "'UNAVAILABLE · NO POWER SIGNAL'",
  "'DEMO · SIMULATED · NOT VEHICLE'",
]) assert.ok(powerStatus.includes(label), `missing power state: ${label}`);
const powerModel = method(html, '_powerModel');
assert.ok(!powerModel.includes('evRange') && !powerModel.includes('fuelRange'),
  'Power must not duplicate Range forecasting');
assert.ok(method(html, '_powerWidgetView').includes('powerStats: model.stats'));

const refresh = method(html, '_queuePowerRefresh');
assert.ok(refresh.includes('setTimeout'));
assert.ok(refresh.includes('250'));
assert.ok(!refresh.includes('requestAnimationFrame'),
  'Power refresh must not create a recurring render loop');

assert.ok(java.includes('case "power": drawPower'));
assert.ok(java.includes('sanitizePowerState'));
assert.ok(java.includes('sanitizePowerVariant'));
assert.ok(java.includes('drawPowerBatteryCells'));
assert.ok(java.includes('drawPowerTopRoute'));
assert.ok(java.includes('www/assets/power/graphics/'));
assert.ok(java.includes('Opens power flow details.'));

console.log('power card contract: OK');

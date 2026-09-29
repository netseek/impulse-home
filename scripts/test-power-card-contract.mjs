#!/usr/bin/env node
// Source-level contract for the Power / Energy Flow card family. This keeps
// the native rail, widget, popup, and live-data truthfulness rules together
// without requiring a WebView or an Android device.
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
const java = readFileSync(resolve(root, 'app/src/main/java/com/havalh6/viewer/MainActivity.java'), 'utf8').replace(/\r\n/g, '\n');

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

assert.ok(html.includes("{ id: 'power', title: 'Fluxo de energia', action: 'openPower' }"));
assert.ok(html.includes("power: { label: 'ENERGIA', title: 'Fluxo de energia'"));
assert.ok(html.includes("[3, 2]"), '3x2 Power widget size is required');
assert.equal((html.match(/value="\{\{ wg\.isPower \}\}"/g) || []).length, 1,
  'Power widget markup must exist on the widget board');
assert.ok(html.includes('focusedCardIsPower'));
assert.ok(html.includes('data-power-size="popup"'));
assert.equal((html.match(/class="hv-power-overlay"/g) || []).length, 2, 'both boards and popup share the vehicle overlay');
assert.equal((html.match(/class="hv-power-chassis"/g) || []).length, 2, 'all Power surfaces use the top-down chassis layer');
assert.ok(!html.includes('src="assets/power/h6-ghost-dark.png"'), 'retired side-view ghost must not remain in Power markup');
for (const token of ['CAR_POWER_GRAPHICS', '_powerGraphicsVariant', '_powerGraphicMarkup',
  '_renderPowerGraphic', '_syncPowerGraphicMotion', 'IntersectionObserver',
  'hv-power-flow-ribbon', 'hv-power-cell-fill', 'prefers-reduced-motion']) {
  assert.ok(html.includes(token), `missing layered Power integration: ${token}`);
}
for (const key of ['phev19', 'phev34', 'hev2']) {
  assert.ok(html.includes(`approved-${key}-chassis.png`), `missing transparent ${key} chassis crop`);
}
// Every animated frame repaints the overlay SVG, and a filter is re-rasterised
// on each repaint -- glows are layered strokes instead. (Markers were the old
// wheel-end chevrons; the comet head replaced them.)
const overlayMarkup = method(html, '_powerGraphicMarkup');
assert.ok(!/<filter|filter=|<marker|marker-end/.test(overlayMarkup),
  'Power overlay must not use SVG filters or markers');
// Motion is paused by default and runs only on .is-running (flow active AND on
// screen), so a hidden or idle card never animates.
for (const cls of ['hv-power-flow-track', 'hv-power-flow-head', 'hv-power-hub', 'hv-power-wheel-halo', 'hv-power-cell-wave', 'hv-power-cell-pulse', 'hv-power-bolt', 'hv-power-charge-sweep']) {
  const rule = html.match(new RegExp(`\\n\\s*(?:\\.hv-power-route )?\\.${cls} \\{[^}]*\\}`));
  assert.ok(rule && rule[0].includes('animation-play-state:paused'), `${cls} must start paused`);
  assert.ok(new RegExp(`\\.hv-power-canvas\\.is-running [^{]*\\.${cls}`).test(html), `${cls} must run only under .is-running`);
}
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
  "'AO VIVO · FLUXO INFORMADO'",
  "'PARCIAL · SINAIS DE POTÊNCIA DO VEÍCULO'",
  "'DESATUALIZADO · SINAL DE POTÊNCIA'",
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
assert.ok(java.includes('drawPowerWheelTraction'));
assert.ok(java.includes('www/assets/power/graphics/'));
assert.ok(java.includes('Abre os detalhes do fluxo de energia.'));

console.log('power card contract: OK');

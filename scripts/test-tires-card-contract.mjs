#!/usr/bin/env node
/*
 * Fast, source-level contract for the CoffeeOS Tires pilot. It exercises the
 * pure TPMS array parser and guards the web/native bridge without starting a
 * browser, Gradle, or an Android device.
 * Run: node scripts/test-tires-card-contract.mjs
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const html = readFileSync(resolve(root, 'index.html'), 'utf8');
const native = readFileSync(
  resolve(root, 'app/src/main/java/com/havalh6/viewer/MainActivity.java'), 'utf8');

function blockFrom(source, token, label = token) {
  const start = source.indexOf(token);
  assert.ok(start >= 0, `missing ${label}`);
  const brace = source.indexOf('{', start);
  assert.ok(brace >= 0, `missing body for ${label}`);
  let depth = 0;
  for (let i = brace; i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  throw new Error(`unterminated ${label}`);
}

function methodBody(source, token, label = token) {
  const block = blockFrom(source, token, label);
  return block.slice(block.indexOf('{') + 1, block.lastIndexOf('}'));
}

function includesAll(source, values, label) {
  for (const value of values) {
    assert.ok(source.includes(value), `${label}: expected ${value}`);
  }
}

// Tires remains a reusable data/focused view, but its launcher card is merged
// into Vehicle Status so the rail never shows two competing vehicle cards.
const catalogStart = html.indexOf('const H6_BOTTOM_CARD_CATALOG');
assert.ok(catalogStart >= 0, 'missing bottom-card catalog');
const catalog = html.slice(catalogStart, html.indexOf('];', catalogStart) + 2);
assert.doesNotMatch(catalog,
  /\{\s*id:\s*['"]tires['"]\s*,\s*title:\s*['"]Tires['"]/,
  'Tires must be merged into the Vehicle Status rail card');
assert.doesNotMatch(catalog,
  /id:\s*['"]tires['"][^}]*action:\s*['"]openDesktopStudio['"]/,
  'Tires must not route to Desktop Studio');

const nativeActions = native.slice(
  native.indexOf('BOTTOM_CARD_ACTIONS'),
  native.indexOf('));', native.indexOf('BOTTOM_CARD_ACTIONS')) + 3);
assert.match(nativeActions, /['"]openTires['"]/, 'native action allowlist must include openTires');

const dockCommandToken = html.includes('  _onDockCommand(') ? '  _onDockCommand(' : '  dockCommand(';
const dockCommand = blockFrom(html, dockCommandToken, 'dock command handler');
assert.match(dockCommand,
  /case\s+['"]openTires['"]\s*:\s*this\._openFocusedCard\(\s*['"]tires['"]\s*\)/,
  'openTires must open the focused Tires workspace');

includesAll(html, [
  'focusedCardIsTires',
  './assets/ui/tires-top-view-v1.png',
], 'focused Tires popup');
assert.ok(
  /focusedTires(?:Positions|Items|Wheels)/.test(html)
    || ['focusedTireFrontLeft', 'focusedTireFrontRight', 'focusedTireRearLeft', 'focusedTireRearRight']
      .every((name) => html.includes(name)),
  'focused Tires popup must expose all four tyre positions');
const focusedTiresStart = html.indexOf('<sc-if value="{{ focusedCardIsTires }}"');
const focusedTiresMarkup = html.slice(focusedTiresStart, html.indexOf('</sc-if>', focusedTiresStart) + 8);
includesAll(focusedTiresMarkup, [
  './assets/ui/tires-top-view-v1.png',
  'list="{{ focusedTiresWheels }}"',
  'hint-placeholder-count="4"',
  '{{ tw.pressure }}',
  '{{ tw.temperature }}',
  'focusedTiresCyclePressureUnit',
  'focusedTiresToggleTemperatureUnit',
], 'four-position focused Tires markup');
includesAll(html, [
  '.hv-tire-point.normal strong { color:var(--hv-widget-fg); }',
  '.hv-tires-focus-wheel.normal > strong,',
  '.hv-tires-focus-wheel.normal .hv-tires-temperature strong { color:var(--hv-widget-fg); }',
], 'Tires primary text colors');

// Unit changes are presentation-only: bar / °C remain the canonical signal values.
const pressureFormatter = Function('bar', 'unit',
  methodBody(html, 'function formatTirePressure(', 'formatTirePressure()'));
const temperatureFormatter = Function('celsius', 'unit',
  methodBody(html, 'function formatTireTemperature(', 'formatTireTemperature()'));
assert.equal(pressureFormatter(2.4, 'bar'), '2.4');
assert.equal(pressureFormatter(2.4, 'kpa'), '240');
assert.equal(pressureFormatter(2.4, 'psi'), '34.8');
assert.equal(temperatureFormatter(29, 'c'), '29');
assert.equal(temperatureFormatter(29, 'f'), '84');
includesAll(html, [
  "const TIRE_DISPLAY_PREF_KEY = 'h6_tire_display_v1';",
  "const units = ['bar', 'kpa', 'psi'];",
  "localStorage.setItem(TIRE_DISPLAY_PREF_KEY",
], 'persisted Tires display preference');

// Known vehicle keys are deliberately explicit. Snapshot replay must include
// both four-value arrays and every observed warning source.
const signals = blockFrom(html, 'const CAR_SIGNALS', 'CAR_SIGNALS');
includesAll(signals, [
  "tirePressures: 'car.tpms.pressures'",
  "tireTemperatures: 'car.tpms.temperatures'",
  "'car.basic.tirepress_warning'",
  "'car.basic.tiretemp_warning'",
  "'car.basic.tpms_warning'",
  "'car.ipk_light.tpms_warning'",
], 'TPMS signal catalog');

const replayKeys = blockFrom(html, '  _carSignalKeys(', '_carSignalKeys()');
includesAll(replayKeys, [
  'CAR_SIGNALS.tirePressures',
  'CAR_SIGNALS.tireTemperatures',
  '.concat(CAR_SIGNALS.tireWarnings)',
], 'TPMS snapshot replay');

const applySignal = blockFrom(html, '  _applyCarSignal(', '_applyCarSignal()');
includesAll(applySignal, [
  'key === CAR_SIGNALS.tirePressures',
  'key === CAR_SIGNALS.tireTemperatures',
  'CAR_SIGNALS.tireWarnings.indexOf(key)',
], 'TPMS live update handling');

// This helper is intentionally pure, so the contract can verify representative
// real-bus payloads instead of merely matching implementation text.
const parserBody = methodBody(html, '  _parseTireSignalArray(', '_parseTireSignalArray()');
assert.match(parserBody, /parts\.length\s*!==\s*4/, 'TPMS arrays must contain exactly four positions');
assert.match(parserBody, /\/\s*100/, 'vehicle pressure must convert from kPa to bar');
const parseTireSignalArray = Function('value', 'kind', parserBody);
assert.deepEqual(parseTireSignalArray('240,250,230,220', 'pressure'), [2.4, 2.5, 2.3, 2.2],
  'valid kPa array converts to bar in FL, FR, RL, RR order');
assert.deepEqual(parseTireSignalArray('[31,32,33,34]', 'temperature'), [31, 32, 33, 34],
  'valid temperature array preserves all four positions');
assert.deepEqual(parseTireSignalArray('240,250,230', 'pressure'), [null, null, null, null],
  'short arrays are wholly unavailable, never partially live');
assert.deepEqual(parseTireSignalArray('240,250,230,220,210', 'pressure'), [null, null, null, null],
  'long arrays are wholly unavailable, never mis-positioned');
assert.deepEqual(parseTireSignalArray('240,bad,,220', 'pressure'), [2.4, null, null, 2.2],
  'invalid or missing individual values remain unavailable');

// Source, state, and age are part of the user-visible safety contract. Demo
// values are allowed only when explicitly labelled as simulated/non-vehicle.
includesAll(html, [
  'DEMO · SIMULATED · NOT VEHICLE',
  'VEHICLE · LIVE',
  'VEHICLE · STALE',
  'UNAVAILABLE · NO TPMS SIGNAL',
], 'Tires provenance labels');
const tiresView = blockFrom(html, '  _tiresWidgetView(', '_tiresWidgetView()');
includesAll(tiresView, [
  'const demo = !!this._demoPreview && !observed;',
  'this._tirePressuresBar',
  'this._tireTemperaturesC',
  'formatTirePressure(pressure, pressureFormat)',
  'formatTireTemperature(temperature, temperatureFormat)',
  'tiresCyclePressureUnit:',
  'tiresToggleTemperatureUnit:',
  'hasTemperature:',
  'const hasPressure = pressure !== null;',
  'const warning = hasPressure &&',
  'const overallWarning = warningSignal || anyPressureWarning;',
  'TPMS WARNING · POSITION NOT REPORTED',
  '120000',
  "'normal'",
  "'warning'",
  "'unavailable'",
  "'demo'",
  "'stale'",
  "'—'",
], 'Tires freshness and semantic states');
assert.match(tiresView, /demo\s*\?\s*\[[^\]]*2\.4/s,
  'sample pressures must be guarded by demo mode');

// The structured native payload carries machine-readable state for rendering
// and a human-readable source label. Wheel states are always FL, FR, RL, RR.
const syncDock = blockFrom(html, '  _syncDockIndicators(', '_syncDockIndicators()');
const tiresPayloadStart = syncDock.indexOf('tires: {');
assert.ok(tiresPayloadStart >= 0, 'missing structured Tires native payload');
const tiresPayload = blockFrom(syncDock, 'tires: {', 'structured Tires native payload');
includesAll(tiresPayload, [
  'secondary: tires.tiresSource',
  'state: tires.tiresOverallState',
  'wheelStates: tires.tiresWheelStates',
], 'structured Tires native payload');
includesAll(syncDock, [
  'const isDemoBottomCard =',
  'const bottomCardDemoSources =',
  'demo: isDemoBottomCard(bottomCardDemoSources[card.id])',
], 'bottom-card demo payload');

includesAll(native, [
  'final String state;',
  'final String[] wheelStates;',
  'final boolean demo;',
  'raw.optBoolean("demo", false)',
  'quickCardDemoBadges',
  'demoBadge.setText("DEMO")',
  'demoBadge.setTag("frostAccent")',
  'raw.optString("state",',
  'raw.optString("wheelStates",',
  'sanitizeTiresState(',
  'sanitizeWheelStates(',
  'drawTiresRaster(',
  'drawTiresFallback(',
  'getTiresTopViewBitmap()',
  'getAssets().open("www/assets/ui/tires-top-view-v1.png")',
  'descriptor.wheelStates',
  'isProbablyEmulator() ? "&demo=1" : "&demo=0"',
], 'native Tires renderer and payload');
const nativeStateSanitizer = blockFrom(native, '    private String sanitizeTiresState(', 'sanitizeTiresState()');
includesAll(nativeStateSanitizer, [
  'case "live":', 'case "demo":', 'case "stale":', 'case "warning":', 'case "unavailable":',
], 'native Tires states');
const nativeWheels = blockFrom(native, '    private String[] sanitizeWheelStates(', 'sanitizeWheelStates()');
includesAll(nativeWheels, ['"normal"', '"warning"', '"unavailable"'], 'native wheel states');
assert.ok(!native.includes('drawTireIndicators('), 'native Tires card must not draw tyre dots');
const nativeReadouts = blockFrom(native, '        private void drawTireReadouts(', 'drawTireReadouts()');
assert.match(nativeReadouts, /for\s*\(int\s+i\s*=\s*0;\s*i\s*<\s*4;\s*i\+\+\)/,
  'native renderer must draw exactly four state-driven readouts');
includesAll(nativeReadouts, ['tireReadings(descriptor)', 'descriptor.wheelStates',
  'float[] valueYs'], 'native pressure-only readouts');
assert.ok(!nativeReadouts.includes('"FL", "FR", "RL", "RR"'),
  'native compact Tires card must not label pressures with wheel abbreviations');
assert.ok(!nativeReadouts.includes('drawCircle'), 'native corner readouts must contain no dots');
const nativeTiresRaster = blockFrom(native, '        private void drawTiresRaster(', 'drawTiresRaster()');
assert.ok(!nativeTiresRaster.includes('drawRoundRect'),
  'native compact Tires card must not add a rounded background behind the vehicle');
const nativeTireColor = blockFrom(native, '        private int tireSignalColor(', 'tireSignalColor()');
assert.match(nativeTireColor, /if\s*\("demo"\.equals\(descriptor\.state\)\)\s*return\s+dockLabelColor\(\);/,
  'normal demo readings must use the native primary text color');

// This visual pilot must not rename stores that hold the user's existing
// desktops, layouts, shell boot choice, launcher overrides, or native shell.
includesAll(html, [
  "const H6_DESKTOPS_KEY = 'h6_desktops_v1';",
  "const APP_OVERRIDES_KEY = 'h6_appOverrides';",
  "const SHELL_BOOT_KEY = 'hv_shell_boot_v1';",
  "localStorage.getItem('h6_widgets')",
  "localStorage.setItem('h6_widgets', json)",
], 'web persistence keys');
includesAll(native, [
  'private static final String PREFS_SHELL = "h6_shell";',
  '.putString("widgets",',
  '.getString("widgets", "")',
], 'native persistence keys');

console.log('Tires card contracts: ok');

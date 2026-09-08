/*
 * Fast contract test for desktop quick-card navigation.  This deliberately
 * reads index.html rather than importing the entire WebGL shell: it protects
 * the small, persistent navigation contract without booting a browser.
 * Run: node scripts/test-card-navigation.mjs
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const html = readFileSync(resolve(root, 'index.html'), 'utf8');

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

function includesAll(text, values, label) {
  for (const value of values) assert.ok(text.includes(value), `${label}: expected ${value}`);
}

// Native card payloads must name the page commands; Consumption must never
// regress to the generic widget-picker route.
includesAll(html, [
  "{ id: 'navigation', title: 'Navigation', action: 'openNavigation' }",
  "{ id: 'climate', title: 'Climate', action: 'openClimate' }",
  "{ id: 'consumption', title: 'Consumption', action: 'openConsumption' }",
  "{ id: 'range', title: 'Range', action: 'openRange' }",
  "{ id: 'status', title: 'Vehicle status', action: 'openVehicleStatus' }",
  "{ id: 'tires', title: 'Tires', action: 'openTires' }",
  "{ id: 'clock', title: 'Clock', action: 'openDesktopStudio' }",
  "{ id: 'driveMode', title: 'Drive mode', action: 'cycleDriveMode' }",
  "{ id: 'powerMode', title: 'Power mode', action: 'cyclePowerMode' }",
  "{ id: 'regen', title: 'Energy recovery', action: 'cycleRegenMode' }",
  "case 'openNavigation':",
  "case 'openClimate':",
  "case 'openConsumption':",
  "case 'openRange':",
  "case 'openTires':",
  "case 'openVehicleStatus':",
  "case 'cycleDriveMode':",
  "case 'cyclePowerMode':",
  "case 'cycleRegenMode':",
  "case 'openRoofControls':",
], 'command wiring');
assert.ok(!html.includes("{ id: 'roof', title: 'Sunroof / shade', action: 'openRoofControls' }"), 'Roof is unified into Vehicle Status, not a competing card');
assert.ok(!html.includes("{ id: 'consumption', title: 'Consumption', action: 'addWidget' }"), 'Consumption must not open addWidget');
assert.ok(!html.includes("{ id: 'tires', title: 'Tires', action: 'openDesktopStudio' }"), 'Tires must not open Desktop Studio');
assert.ok(!html.includes("{ id: 'status', title: 'Vehicle status', action: 'openDesktopStudio' }"), 'Vehicle status must not open Desktop Studio');

// CoffeeOS-style glance widgets remain page-owned cards: they are selectable
// from the visual picker and render in both widget boards.
includesAll(html, [
  "profile: { label: 'PROFILE'",
  "clock: { label: 'CLOCK'",
  "navigation: { label: 'NAVIGATION'",
  "tires: { label: 'TIRES'",
  "status: { label: 'STATUS'",
  "range: { label: 'RANGE'",
  'value="{{ wg.isProfile }}"',
  'value="{{ wg.isClock }}"',
  'value="{{ wg.isNavigation }}"',
  'value="{{ wg.isTires }}"',
  'value="{{ wg.isStatus }}"',
  'value="{{ wg.isRange }}"',
  "previewProfile: key === 'profile'",
  "previewClock: key === 'clock'",
  "previewNavigation: key === 'navigation'",
  "previewTires: key === 'tires'",
  "previewStatus: key === 'status'",
  "previewRange: key === 'range'",
], 'visual widget catalog');
includesAll(method('_widgetRenderFields'), [
  "this._hasWidgetType(mode, 'power')",
  "this._hasWidgetType(mode, 'range')",
], 'range refresh loop');

// Native rail cards get structured values for their large readout, compact
// metrics and visual progress. The legacy value remains for older shells.
const dockIndicators = method('_syncDockIndicators');
includesAll(dockIndicators, [
  'const bottomVisuals = {',
  'primary: navigation.navigationDistance',
  'secondary: range.rangeSource',
  "metricA: 'EV ' + range.rangeEv + ' ' + range.rangeUnit",
  'progress: percent(range.rangeSoc, 0)',
  'Object.assign({',
  'bottomVisuals[card.id] || {}',
], 'graphic bottom-card payload');
includesAll(method('_modeCardVisual'), [
  "'DEMO · LOCAL PREVIEW'",
  "'VEHICLE · LIVE'",
  "'VEHICLE NOT READY'",
], 'live-first mode card state');
includesAll(method('_setRoofLevelPopup'), [
  "classList.toggle('on'",
  "classList.toggle('lit'",
], 'roof popup visibility');
includesAll(html, [
  'class="hv-hs-vehicle-stage"',
  'class="hv-hs-vehicle-art"',
  'src="./assets/ui/vehicle-status/base.png"',
  'class="hv-vehicle-roof"',
  'src="./assets/ui/vehicle-status/roof-glass-fixed-v3.png"',
  'src="./assets/ui/vehicle-status/roof-glass-front-v3.png"',
  'aria-label="Sunroof opening"',
  'aria-label="Sunshade opening"',
  "'--sun-level' : '--curtain-level'",
  "classList.toggle('light', this._effectiveWidgetTheme(s) === 'light')",
  "card.style.setProperty('--thumb-position', (8 + level * .84) + '%')",
], 'graphical roof controls');

// Targets live in the desktop snapshot and retain a stable desktop ID, so a
// desktop rename cannot break a selected destination.
includesAll(method('_desktopSnapshot'), ['cardActions:', 'cardFocus:'], 'desktop persistence');
includesAll(method('_normalizeDesktop'), ['base.cardActions', 'base.cardFocus'], 'desktop migration');
includesAll(method('_setCardAction'), ["this._cardActions[type] = action", 'this._persistActiveDesktop()'], 'action persistence');
includesAll(method('_openFocusedCard'), ["action.kind === 'desktop'", 'd.id === action.desktopId', "this._setCardAction(type, { kind: 'popup' })"], 'deleted target fallback');

// Studio separates placement from bottom-bar management. The latter is one
// ordered enabled-first list, and no longer lets a second display-limit value
// hide cards that the user just enabled.
includesAll(html, ['>Layout &amp; widgets</button>', '>Bottom bar</button>', 'aria-label="Help"', 'studioBottomCardItems', 'Create focused desktop'], 'studio flow');
const studioFields = method('_desktopRenderFields');
includesAll(studioFields, ['studioIsLayout:', 'studioWidgetSummary:', 'studioBottomCardItems: H6_BOTTOM_CARD_CATALOG.slice().sort', 'leftDisabled: !enabled'], 'studio and bottom-bar ordering');
assert.ok(!studioFields.includes('studioIsWidgets:'), 'Widgets should be merged into Layout & widgets');
assert.ok(!studioFields.includes('studioBottomLimitItems:'), 'enabled cards must not have a separate display limit');

// Creating a destination needs a usable large card in the layout it opens,
// not only in the triple-layout template.
includesAll(method('_createFocusedCardDesktop'), [
  "w: 2, h: 2",
  "layout.appCar.left = { use: 'widgets'",
  "d.shellMode = 'appCar'",
  "desktopId: d.id",
], 'focused desktop');

// The popup follows shared native workspace geometry, and ordinary desktop
// navigation cannot leave it orphaned over a different desktop.
includesAll(html, ['top:var(--hv-popup-top', 'bottom:var(--hv-popup-bottom-inset'], 'popup geometry');
includesAll(method('_switchDesktop'), ['focusedCardType: null'], 'desktop popup cleanup');
includesAll(method('_openDesktopStudio'), ['focusedCardType: null'], 'studio popup cleanup');

console.log('card-navigation contracts: ok');

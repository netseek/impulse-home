#!/usr/bin/env node
/*
 * Source-level contract for the unified DRIVING card (drive mode + power mode +
 * energy recovery, with steering and ESP progressively disclosed in the popup).
 *
 * Like the Tires contract this reads index.html and MainActivity.java rather
 * than booting a browser, Gradle or a device: what it guards is the small,
 * durable part of the card — which CAN keys it addresses, that a write can only
 * leave through the allow-listed bridge, and that the source vocabulary in
 * docs/widget-data-audit.md is never abbreviated into something that reads like
 * a vehicle value when it is not.
 *
 * Run: node scripts/test-driving-card-contract.mjs
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

function includesAll(source, values, label) {
  for (const value of values) {
    assert.ok(source.includes(value), `${label}: expected ${value}`);
  }
}

// ---------------------------------------------------------------------------
// 1. Three rail tiles, one destination.
//
//    The rail stays glanceable with a small readout per mode, but every tile's
//    BODY opens the same popup. Only the icon keeps a per-mode quick action, so
//    a tap on the card can never change a setting the driver did not aim at.
// ---------------------------------------------------------------------------
const catalogStart = html.indexOf('const H6_BOTTOM_CARD_CATALOG');
assert.ok(catalogStart >= 0, 'missing bottom-card catalog');
const catalog = html.slice(catalogStart, html.indexOf('];', catalogStart) + 2);
const railTiles = [
  ['driveMode', 'Drive mode', 'cycleDriveMode'],
  ['powerMode', 'Power mode', 'cyclePowerMode'],
  ['regen', 'Energy recovery', 'cycleRegenMode'],
];
for (const [id, title, iconAction] of railTiles) {
  assert.match(catalog,
    new RegExp(`\\{\\s*id:\\s*'${id}'\\s*,\\s*title:\\s*'${title}'\\s*,\\s*action:\\s*'openDriving'\\s*,\\s*iconAction:\\s*'${iconAction}'\\s*\\}`),
    `${id} must open the Driving popup and quick-cycle from its icon`);
}
assert.doesNotMatch(catalog, /id:\s*'driving'/,
  'the rail carries the three tiles, not a fourth combined card');
assert.doesNotMatch(catalog,
  /id:\s*'(driveMode|powerMode|regen)'[^}]*action:\s*'cycle/,
  'a tap on the card body must open the popup, never cycle a mode');

// The payload has to carry the icon's command, and it is allow-listed natively
// exactly like the card's.
const dockIndicatorsEarly = blockFrom(html, '  _syncDockIndicators(', 'dock indicator payload');
includesAll(dockIndicatorsEarly, ["iconAction: card.iconAction || ''"], 'iconAction reaches the rail');

// ---------------------------------------------------------------------------
// 2. Command wiring: web command, native allow-list, focused workspace.
// ---------------------------------------------------------------------------
const dockCommandToken = html.includes('  _onDockCommand(') ? '  _onDockCommand(' : '  dockCommand(';
const dockCommand = blockFrom(html, dockCommandToken, 'dock command handler');
assert.match(dockCommand,
  /case\s+['"]openDriving['"]\s*:\s*this\._openFocusedCard\(\s*['"]driving['"]\s*\)/,
  'openDriving must open the focused Driving workspace');

const nativeActions = native.slice(
  native.indexOf('BOTTOM_CARD_ACTIONS'),
  native.indexOf('));', native.indexOf('BOTTOM_CARD_ACTIONS')) + 3);
assert.match(nativeActions, /['"]openDriving['"]/, 'native action allow-list must include openDriving');
// Native shells installed before the merge still emit the cycle commands.
includesAll(nativeActions, ['"cycleDriveMode"', '"cyclePowerMode"', '"cycleRegenMode"'],
  'legacy cycle commands stay allow-listed');

// ---------------------------------------------------------------------------
// 3. The card addresses the documented CAN keys through the named group index,
//    never a bare array position that a reorder could silently repoint.
// ---------------------------------------------------------------------------
assert.match(html, /const CAR_MODE_GROUP_INDEX = \{ drive: 0, power: 1, steer: 2, regen: 3 \};/,
  'group index must be declared by name');
// The retired MODES widget is migrated, not dropped: a saved layout is the
// user's arrangement and an unknown type would silently blank the slot.
assert.match(html, /const H6_DRIVING_LEGACY_WIDGET_TYPES = \{ modes: 'driving' \};/,
  'retired widget types must be named');
includesAll(blockFrom(html, '  _migrateRetiredWidgetTypes(layout) {', 'widget migration'),
  ['H6_DRIVING_LEGACY_WIDGET_TYPES[item.type]', 'item.type = next'], 'retype in place');
includesAll(blockFrom(html, '  _parseWidgetLayout(raw) {', 'layout parser'),
  ['this._migrateRetiredWidgetTypes(parsed)'], 'migration runs on every load');
// MODES is gone: two widgets offering the same four groups, one of them unusable
// on the light board, is the worse outcome.
for (const token of ["modes: { label: 'MODES'", 'wg.isModes', 'hv-modes-chip',
  '_modeWidgetView', '_modeStackRows', "previewModes: key === 'modes'"]) {
  assert.ok(!html.includes(token), `retired MODES widget leftover: ${token}`);
}

const view = blockFrom(html, '  _drivingWidgetView(item) {', 'driving view builder');
includesAll(view, [
  'CAR_MODE_GROUP_INDEX[name]',
  "group('drive', 'DRIVE MODE'",
  "group('power', 'POWER MODE'",
  "group('regen', 'ENERGY RECOVERY'",
  "group('steer', 'STEERING ASSIST'",
], 'driving groups addressed by name');

// The write gate is computed before the groups are built, so the card and the
// popup cannot disagree about whether a control is live.
const gateAt = view.indexOf('const controlsDisabled =');
const firstGroupAt = view.indexOf("const drive = group('drive'");
assert.ok(gateAt >= 0 && firstGroupAt > gateAt,
  'controlsDisabled must be resolved before the groups are built');
includesAll(view, [
  'label, colsClass, controlsDisabled)',
  'this._drivingEspModel(controlsDisabled)',
], 'the gate reaches every option');
const groups = html.slice(html.indexOf('const CAR_MODE_GROUPS'), html.indexOf('const CAR_MODE_STATE_BY_KEY'));
includesAll(groups, [
  "key: 'car.drive_setting.drive_mode'",
  "key: 'car.ev_setting.power_model_config'",
  "key: 'car.ev_setting.energy_recovery_level'",
  "key: 'car.drive_setting.steering_wheel_assist_mode'",
], 'documented CAN keys');
assert.match(html, /key: 'car\.drive_setting\.esp_enable'/, 'ESP key');

// ---------------------------------------------------------------------------
// 4. Truthfulness. The source badge uses the audited vocabulary; a DEMO state
//    always carries the full badge, and an un-ready vehicle disables writes
//    instead of showing something that reads like a vehicle value.
// ---------------------------------------------------------------------------
includesAll(view, [
  "'DEMO · SIMULATED · NOT VEHICLE'",
  "'LOCAL PREVIEW · NOT A VEHICLE SETTING'",
  "'UNAVAILABLE · VEHICLE NOT READY'",
  "'UNAVAILABLE · NO VEHICLE STATE'",
  "'PENDING · AWAITING VEHICLE STATE'",
  "'PARTIAL · VEHICLE STATE'",
  "'STALE · VEHICLE STATE'",
  "'VEHICLE · LIVE'",
], 'driving source vocabulary');
assert.ok(!/'DEMO · LOCAL PREVIEW'/.test(view),
  'a DEMO state must carry the full DEMO · SIMULATED · NOT VEHICLE badge');
includesAll(view, [
  'this._carReady',
  'window.TelemetryBridge',
  "typeof window.TelemetryBridge.setCarData !== 'function'",
  'drivingControlsDisabled',
], 'driving controls gate');

// An unreported group must stay "—" on a real head unit; the demo fallback is
// only ever consulted while the app is explicitly a preview.
const groupModel = blockFrom(html, '  _drivingGroupModel(', 'driving group model');
includesAll(groupModel, [
  'const preview = !this._androidApp || !!this._demoPreview;',
  'const shown = index >= 0 ? index : (preview ? fallback : -1);',
  'CAR_MODE_FRESH_MS',
], 'no fabricated mode value on a vehicle');
assert.match(html, /const CAR_MODE_FRESH_MS = 120000;/, 'freshness window');

// Every option is a direct write of the raw CAN value — no cycling, which would
// step through a mode the driver did not ask for.
includesAll(groupModel, ['this._setCarMode(group.stateKey, group.key, opt.value)'],
  'options write their own value');
// A disabled control must LOOK disabled. _setCarMode already refuses the write,
// but a live-looking button that silently does nothing is the exact failure the
// widget audit exists to prevent.
includesAll(groupModel, ['disabled: !!controlsDisabled,'], 'popup options carry the gate');
includesAll(blockFrom(html, '  _drivingEspModel(', 'esp model'),
  ['disabled: !!controlsDisabled,'], 'ESP options carry the gate');
const setCarMode = blockFrom(html, '  _setCarMode(stateKey, canKey, value) {', 'mode writer');
includesAll(setCarMode, [
  'if (!this._androidApp || this._demoPreview)',
  'if (!this._carReady || !window.TelemetryBridge',
  'this._modePending[stateKey]',
  'window.TelemetryBridge.setCarData(canKey, next)',
], 'guarded vehicle write');

// ---------------------------------------------------------------------------
// 5. The glance card and the popup come out of one builder, so the popup cannot
//    drift from the card. `_focusedCardRenderFields` re-exports every key that
//    starts with the card type.
// ---------------------------------------------------------------------------
const focusFields = blockFrom(html, '  _focusedCardRenderFields(s) {', 'focused card fields');
includesAll(focusFields, [
  "focusedCardIsDriving: type === 'driving'",
  "'DRIVING CONTROLS'",
  "type === 'driving' ? this._drivingWidgetView(entry.item)",
], 'focused Driving workspace');
includesAll(view, [
  'drivingGroups: [drive, power, regen, steer, esp]',
  'drivingSummary:',
  'drivingNote:',
], 'popup fields come from the card builder');

// ---------------------------------------------------------------------------
// 6. Markup. The card renders on both widget boards, the popup renders the
//    group list, and the picker offers the type.
// ---------------------------------------------------------------------------
assert.equal(html.split('<sc-if value="{{ wg.isDriving }}"').length - 1, 2,
  'the Driving card must render on both widget boards');
assert.equal(html.split('class="hv-driving {{ wg.drivingSizeClass }}"').length - 1, 2,
  'both boards must size the Driving card from the item');
const glanceStart = html.indexOf('<sc-if value="{{ wg.isDriving }}"');
const glance = html.slice(glanceStart, html.indexOf('</sc-if>', glanceStart) + 8);
includesAll(glance, [
  '{{ wg.drivingSource }}',
  '{{ wg.drivingMode }}',
  '{{ wg.drivingPower }}',
  '{{ wg.drivingRegen }}',
  'list="{{ wg.drivingRegenSteps }}"',
  'd="{{ wg.drivingGlyph }}"',
  'disabled="{{ wg.drivingControlsDisabled }}"',
  'onClick="{{ wg.onDrivingOpen }}"',
], 'Driving glance card');

const popupStart = html.indexOf('<sc-if value="{{ focusedCardIsDriving }}"');
assert.ok(popupStart >= 0, 'missing focused Driving popup');
const popup = html.slice(popupStart, html.indexOf('</sc-if>', popupStart) + 8);
includesAll(popup, [
  '{{ focusedDrivingSource }}',
  '{{ focusedDrivingSummary }}',
  'list="{{ focusedDrivingGroups }}"',
  'list="{{ dg.options }}"',
  '{{ dopt.label }}',
  'disabled="{{ dopt.disabled }}"',
  '{{ focusedDrivingNote }}',
], 'focused Driving popup');

includesAll(html, [
  "driving: { label: 'DRIVING'",
  "previewDriving: key === 'driving'",
  'class="hv-wpick-driving"',
  "isDriving: item.type === 'driving'",
  "if (item.type === 'driving') Object.assign(view, this._drivingWidgetView(item));",
], 'Driving widget registration');

// Sizes offered by the catalogue must cover the variants the card styles.
const catalogueEntry = html.slice(html.indexOf("driving: { label: 'DRIVING'"),
  html.indexOf('\n', html.indexOf("driving: { label: 'DRIVING'")));
for (const size of ['[1, 1]', '[1, 2]', '[2, 1]', '[2, 2]', '[3, 1]', '[3, 2]']) {
  assert.ok(catalogueEntry.includes(size), `Driving catalogue must offer ${size}`);
}

// ---------------------------------------------------------------------------
// 7. Theming. The card must resolve its colours through theme tokens: the older
//    MODES chips hard-code white and are unreadable on the light board.
// ---------------------------------------------------------------------------
const cssStart = html.indexOf('    .hv-driving { height:100%;');
assert.ok(cssStart >= 0, 'missing .hv-driving styles');
const css = html.slice(cssStart, html.indexOf('    .hv-status-body {', cssStart));
assert.ok(!/rgba\(255,\s*255,\s*255/.test(css),
  'Driving card styles must use theme tokens, not hard-coded white');
includesAll(css, [
  'var(--hv-widget-fg',
  'var(--hv-accent)',
  'var(--hv-frost-edge)',
  'var(--hv-frost-inner)',
], 'Driving card theme tokens');
for (const size of ['1x1', '2x1', '3x1', '1x2']) {
  assert.ok(css.includes('.hv-driving-' + size), `missing ${size} card styling`);
}

// ---------------------------------------------------------------------------
// 8. Native quick-card payload.
// ---------------------------------------------------------------------------
const dockIndicators = blockFrom(html, '  _syncDockIndicators(', 'dock indicator payload');
includesAll(dockIndicators, [
  "const driving = this._drivingWidgetView({ type: 'driving', w: 2, h: 1 });",
  'driveMode: driveModeVisual,',
  'powerMode: powerModeVisual,',
  'regen: regenVisual,',
  'secondary: driving.drivingSource,',
], 'native Driving quick cards');
// All three tiles read from the one builder, so a mode cannot say one thing on
// the rail and another in the popup.
includesAll(dockIndicators, [
  'const drivingGroup = (index) => driving.drivingGroups[index];',
  'CAR_DRIVE_MODE_CARD_STATES[drivingGroup(0).value]',
  'CAR_POWER_MODE_CARD_STATES[drivingGroup(1).value]',
], 'rail tiles share the card builder');
assert.ok(!dockIndicators.includes('_modeCardVisual'),
  'the replaced per-mode quick-card builder must be gone');

// The native rail draws the selected mode's own glyph, so the state vocabulary
// on both sides has to stay in step.
for (const state of ['eco', 'normal', 'sport', 'snow', 'sand', 'mud', 'awd']) {
  assert.ok(html.includes(`'${state}'`), `web drive-mode state ${state}`);
  assert.ok(native.includes(`case "${state}":`), `native drive-mode state ${state}`);
}
for (const state of ['hev', 'evp', 'ev']) {
  assert.ok(native.includes(`case "${state}":`), `native power-mode state ${state}`);
}
includesAll(native, [
  'private String sanitizeDrivingState(String value)',
  'DRIVING_CARD_IDS',
  'private void drawStepDots(',
  'private void drawUnitPath(',
], 'native rail graphics');
// A tap on the graphic is a command, so it passes the same allow-list as a tap
// on the card; an unknown one degrades to no icon action.
includesAll(native, [
  'String iconAction = raw.optString("iconAction", "").trim();',
  'if (!BOTTOM_CARD_ACTIONS.contains(iconAction)) iconAction = "";',
  'graphic.setOnClickListener(v -> callViewerDock(iconCommand));',
], 'icon quick action is gated');

console.log('Driving card contracts: ok');

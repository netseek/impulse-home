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
    new RegExp(`id:\\s*'${id}'\\s*,\\s*title:\\s*'${title}'\\s*,\\s*action:\\s*'openDriving'\\s*,\\s*iconAction:\\s*'${iconAction}'`),
    `${id} must open the Driving popup and quick-cycle from its icon`);
}
// Recovery is the one tile with a third gesture: hold to reach one-pedal.
assert.match(catalog, /id:\s*'regen'[\s\S]{0,160}longAction:\s*'openDrivingOnePedal'/,
  'a long press on Energy recovery must reach the one-pedal control');
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
// The drive icon cycles the three ROAD modes only. Stepping a driver into
// Neve/Areia/Lama from a rail tap is a surprise, not a quick action.
const roadCycle = blockFrom(html, '  _cycleDriveRoadMode() {', 'road cycle');
includesAll(roadCycle, ["const road = ['2', '0', '1'];", "at < 0 ? '0'"], 'road-only drive cycle');
const regenCycle = blockFrom(html, '  _cycleRegenMode() {', 'regen cycle');
includesAll(regenCycle, ['CAR_MODE_ONE_PEDAL.stateKey', 'this._setOnePedal(false)'],
  'a tap while one-pedal is on turns it off');
// Enabling remembers the level; disabling writes it back. Nothing on the bus
// reports a "previous" level, so the memory is explicitly ours.
const setOnePedal = blockFrom(html, '  _setOnePedal(on) {', 'one pedal writer');
includesAll(setOnePedal, [
  'this._regenLevelBeforeOnePedal = level',
  'if (!on && this._regenLevelBeforeOnePedal)',
], 'one-pedal restores the previous recovery level');

// One-pedal is evidenced, not invented: Impulse exposes this exact key for read
// and write. Note the separators — car.ev.setting, not car.ev_setting.
assert.match(html, /key: 'car\.ev\.setting\.pedal_control_enable'/, 'one-pedal key');
assert.ok(native.includes('"car.ev.setting.pedal_control_enable"'),
  'one-pedal must be on the native writable allow-list');
includesAll(native, [
  'String longAction = raw.optString("longAction", "").trim();',
  'if (!BOTTOM_CARD_ACTIONS.contains(longAction)) longAction = "";',
  'card.setOnLongClickListener(',
], 'long press is gated like every other command');

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
  'drivingGroups: [drive, power, regen, steer]',
  'drivingToggles: [onePedal, esp]',
  'drivingSummary:',
  'drivingNote:',
], 'popup fields come from the card builder');
// The popup adopted the widget's boolean treatment: one tile that lights and
// says which way it is set, rather than two competing ON/OFF tiles.
const popupToggles = html.slice(html.indexOf('drivingToggles: [onePedal, esp]'),
  html.indexOf('onDrivingOpen:', html.indexOf('drivingToggles: [onePedal, esp]')));
includesAll(popupToggles, ["group.on ? 'ON' : 'OFF'", 'disabled: controlsDisabled'],
  'popup toggles carry state and the write gate');

// The widget shows every option, not a read-only hero: it was the one surface
// where a mode could be seen but not changed.
includesAll(view, ['drivingRows: rows', 'const chipRow =', 'const toggleChip ='],
  'the widget renders the option groups');
assert.ok(!view.includes('drivingShowQuick'), 'the hero chip strip is replaced by full rows');
// Row height follows the chip lines a group needs; equal shares squeezed the
// seven-option drive row into one line and the buttons overlapped.
includesAll(view, ["linesClass: 'lines-' + Math.min(3, Math.ceil(chips.length / cols))"],
  'rows claim height in proportion to their chip lines');
// Booleans are one chip that lights, which is a row cheaper than ON/OFF pairs.
includesAll(blockFrom(html, '  _drivingOnePedalModel(', 'one pedal model'),
  ['on: on && (known || preview)', 'onToggle:'], 'one-pedal exposes a toggle');
includesAll(blockFrom(html, '  _drivingEspModel(', 'esp model'),
  ['on: on && (known || preview)', 'onToggle:'], 'ESP exposes a toggle');

// ---------------------------------------------------------------------------
// 6. Markup. The card renders on both widget boards, the popup renders the
//    group list, and the picker offers the type.
// ---------------------------------------------------------------------------
assert.equal(html.split('<sc-if value="{{ wg.isDriving }}"').length - 1, 2,
  'the Driving card must render on both widget boards');
assert.equal(html.split('class="hv-driving {{ wg.drivingSizeClass }}"').length - 1, 2,
  'both boards must size the Driving card from the item');
// Three rows contain an option called "Normal" and two contain "Sport", so the
// drive row carries each mode's own glyph beside the word.
assert.equal(html.split('class="hv-driving-chip-icon"').length - 1, 2,
  'both boards must render the chip icon');
assert.match(html, /hasIcon: !!opt\.glyph/, 'chips take an optional leading icon');
includesAll(blockFrom(html, '  _drivingOptionGlyph(key, value, selected) {', 'option glyph'), [
  "key === 'car.drive_setting.drive_mode'",
  "key === 'car.ev_setting.energy_recovery_level'",
  "key === 'car.drive_setting.steering_wheel_assist_mode'",
  'return selected ? CAR_STEER_GLYPH',
], 'options take their mark from one place');
// Steering wears its wheel on the SELECTED option only. Three copies of the
// same wheel would tell the row apart from drive and recovery but say nothing
// about which is chosen; effort bars would have been identical to the recovery
// row sitting beside it.
assert.match(html, /const CAR_STEER_GLYPH = 'M /, 'steering glyph must be generated, not hand-written');
includesAll(html, ['optionGlyph(group.key, opt.value, i === shown)'],
  'the steering mark has to know which option is selected');
// Recovery levels lost their LEVEL 1/2/3 line when the second line went, so the
// ascending bars carry that meaning instead.
assert.match(html, /const CAR_REGEN_LEVEL_GLYPHS = \{/, 'recovery levels need their bars');
for (const value of ['2', '0', '1']) {
  assert.ok(new RegExp("'" + value + "': 'M ").test(
    html.slice(html.indexOf('const CAR_REGEN_LEVEL_GLYPHS'),
      html.indexOf('};', html.indexOf('const CAR_REGEN_LEVEL_GLYPHS')))),
    'recovery level bar for ' + value);
}
// AWD is a code, not a picture: no icon set has a mark that says "all four
// wheels are driven" and survives 52px, so it reuses the POWER card's boxed
// treatment. It must therefore NOT be in the glyph table.
assert.match(html, /const CAR_DRIVE_MODE_BADGES = \{ '11': '4×4' \};/, 'AWD code badge');
assert.ok(!/'11': 'M /.test(html.slice(html.indexOf('const CAR_DRIVE_MODE_GLYPHS = {'),
  html.indexOf('};', html.indexOf('const CAR_DRIVE_MODE_GLYPHS = {')))),
  'AWD must not also carry a glyph path');
includesAll(native, [
  'private void drawCodeBadge(',
  'if (!descriptor.glyphText.isEmpty())',
  'String glyphText = cleanBottomCardText(raw.optString("glyphText", ""), 6);',
], 'the rail draws the code badge');
// A lit border is a weak way to say "on" for a toggle.
assert.match(html, /state: group\.on \? 'ON' : 'OFF'/, 'toggles spell out their state');
// The chip markup nests <sc-if> for the optional icon and state, so the block
// has to be balanced rather than cut at the first close tag.
function scIfBlock(source, start) {
  let depth = 0;
  for (let i = start; i < source.length; i++) {
    if (source.startsWith('<sc-if', i)) depth++;
    else if (source.startsWith('</sc-if>', i)) {
      depth--;
      if (depth === 0) return source.slice(start, i + 8);
    }
  }
  throw new Error('unbalanced sc-if');
}
const glanceStart = html.indexOf('<sc-if value="{{ wg.isDriving }}"');
const glance = scIfBlock(html, glanceStart);
includesAll(glance, [
  '{{ wg.drivingSource }}',
  'list="{{ wg.drivingRows }}"',
  'list="{{ dr.chips }}"',
  '{{ dr.label }}',
  '<span>{{ dc.label }}</span>',
  'class="hv-driving-row {{ dr.linesClass }}"',
  'disabled="{{ wg.drivingControlsDisabled }}"',
], 'Driving widget option grid');
// The widget is controls only. A background tap that opened the popup made the
// whole card one big target sitting under a grid of small ones; the rail card
// is the deliberate way in, with the long-press menu's OPEN as the escape hatch.
assert.ok(!glance.includes('onClick="{{ wg.onDrivingOpen }}"'),
  'the widget background must not open the popup');
includesAll(view, ['onEdit: (ev) =>'], 'the widget menu still opens the popup');
// One source badge per surface: the widget said DEMO in the header and again
// in a foot line, and the foot also spent a row on an affordance the whole
// card already has.
// The rail card opens a quick menu of the modes, with the full page as its last
// row. A menu row carries a VALUE, so it cannot be one of the fixed action
// tokens -- which makes it a wider door than anything else the dock accepts,
// with setCarData on the other side. It is minted from the live tables and
// re-derived from them on arrival; the native side only ever replays a string
// it was given.
includesAll(html, ["const H6_DRIVING_SET_PREFIX = 'drivingSet:';",
  'H6_DRIVING_MENU_GROUPS = { driveMode:',
  'menu: this._drivingMenuRows(card.id),'], 'the rail ships its quick menu');
const menuApply = blockFrom(html, '  _applyDrivingMenuCommand(command) {', 'menu write');
includesAll(menuApply, [
  'const index = CAR_MODE_GROUP_INDEX[name];',
  "if (typeof index !== 'number') return;",
  'group.options.some((opt) => opt.value === value)',
], 'a menu write is re-validated, never trusted');
includesAll(blockFrom(html, '  dockCommand(cmd) {', 'dock command'),
  ['if (c.indexOf(H6_DRIVING_SET_PREFIX) === 0)'], 'and is routed before the fixed switch');
includesAll(native, [
  'private boolean isDrivingSetCommand(String command)',
  'if (!BOTTOM_CARD_ACTIONS.contains(command) && !isDrivingSetCommand(command)) continue;',
  'if (!cardDescriptor.menu.isEmpty()) showQuickMenu(v, cardDescriptor);',
], 'the native menu only replays allow-shaped commands');
// Each row is a pill with a centred label, the way Coffee OS draws them: it
// reads as a set of choices rather than a dropdown, and every row gets a real
// edge to aim at on a panel operated at arm's length.
includesAll(native, [
  'private android.graphics.drawable.Drawable makeQuickMenuItemBackground(',
  'item.setGravity(android.view.Gravity.CENTER);',
  'item.setBackground(makeQuickMenuItemBackground(row.selected, last, density));',
  'states.addState(new int[] { android.R.attr.state_pressed }, pressed);',
], 'menu rows are centred pills with a pressed state');
// Each driving card carries a soft wash in its mode's colour. Painted, not
// shipped: no assets, no cold-start cost, both themes from one recipe, and no
// redistribution question. The theme pass rebuilds every card background, so it
// has to carry the wash or the cards repaint flat on the next payload.
includesAll(native, [
  'private int drivingWashColor(String state)',
  'private int washForCardView(View card)',
  'makeFrostStateDrawable(card.isSelected(), density,',
  'RADIAL_GRADIENT',
], 'the rail cards wear a mode wash that survives a theme sync');
// Sport is the one mode allowed to overrule the configured accent, and only for
// display: state.accentColor is never written, so leaving Sport restores the
// user's colour without a write.
assert.match(html, /const CAR_SPORT_ACCENT = '#e0392c';/, 'sport accent');
const effAccent = blockFrom(html, '  _effectiveAccentColor() {', 'effective accent');
includesAll(effAccent, ['CAR_DRIVE_MODE_SPORT', 'CAR_SPORT_ACCENT'], 'sport borrows the accent');
assert.ok(effAccent.indexOf('accentColor:') < 0,
  'the sport accent must never be persisted over the user choice');
includesAll(html, ['accentColor: this._effectiveAccentColor(),', 'accent: this._effectiveAccentColor(),'],
  'both the page and the rail get the effective accent');
// Accent-tinted icons bake their colour in at build time, so the rail has to be
// rebuilt when it changes or they keep the old one.
includesAll(native, ['if (accentChanged) rebuildQuickCardsRow();'],
  'the rail rebuilds when the accent changes');
// The accent still owns selection and liveness everywhere on these cards; a
// card-wide fill in that colour would drown the signal.
assert.ok(!/case "eco": return dockAccentColor/.test(native),
  'the wash must not be the configured accent');

// A chip tap used to ask for a full 3D frame via componentDidUpdate, and the
// widget card is backdrop-blurred, so repainting the canvas under it read as
// the card flashing. Mode state changes nothing the scene draws.
includesAll(blockFrom(html, '  _uiOnlySetState(patch) {', 'ui-only setState'),
  ['this._uiOnlyStateWrite = true;', 'this.setState(patch);'], 'UI-only writes are marked');
includesAll(blockFrom(html, '  componentDidUpdate() {', 'did update'),
  ['const uiOnly = this._uiOnlyStateWrite;', 'this._uiOnlyStateWrite = false;',
   'if (!uiOnly && this.requestRender)'], 'and skip the scene frame');
assert.equal(html.split('this._uiOnlySetState({').length - 1, 3,
  'every mode write must go through the UI-only path');
// The popup centres on the band the dock leaves rather than hugging the top.
includesAll(html, ['.hv-card-focus.fit.on { transform:translate(-50%,-50%) scale(1); }'],
  'the popup centres vertically');
// The head printed the value the selected tile already shows. The toggles keep
// their ON/OFF: a two-state control has to say which way it is set, and a lit
// border alone does not.
assert.ok(!html.includes('{{ dr.state }}') && !html.includes('{{ dg.state }}'),
  'a group head must not repeat the selected value');
includesAll(html, ['{{ dtg.state }}'], 'toggles still name their state');
assert.ok(!glance.includes('hv-driving-foot'),
  'the widget carries one source badge, in its header');

const popupStart = html.indexOf('<sc-if value="{{ focusedCardIsDriving }}"');
assert.ok(popupStart >= 0, 'missing focused Driving popup');
const popup = scIfBlock(html, popupStart);
// The source badge sits beside the popup title now; the summary is gone
// because every group head already prints its own value in bold, and the note
// survives only where it explains why nothing responds.
includesAll(html, [
  'class="hv-card-focus-badge {{ focusedCardBadgeClass }}"',
  'base.focusedCardBadge = view.drivingSource;',
  'drivingShowNote: controlsDisabled,',
], 'source badge moved to the header');
assert.ok(!popup.includes('hv-driving-focus-meta'),
  'the meta row repeated the badge and the group heads');
assert.ok(!popup.includes('{{ dopt.hint }}'),
  'the option second line is gone; the hint stays in the model to bring back');
includesAll(popup, [
  'list="{{ focusedDrivingGroups }}"',
  'list="{{ dg.options }}"',
  '{{ dopt.label }}',
  'disabled="{{ dopt.disabled }}"',
], 'focused Driving popup');
// The note and the assist toggles sit outside the group loop.
includesAll(html, ['{{ focusedDrivingNote }}', 'list="{{ focusedDrivingToggles }}"',
  '{{ dtg.state }}'], 'popup note and assist toggles');

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
// 6b. Glyphs are generated from a real icon set and shared by both surfaces.
//
//     Hand-drawn shapes kept colliding (tyre tread read as a barcode, a hub
//     circle read as an eye) and the native copy drifted from the web one.
//     Now one table feeds both, flattened to a subset the rail can parse.
// ---------------------------------------------------------------------------
assert.match(html, /Tabler Icons — Copyright \(c\) 2020-2024 Paweł Kuna — MIT License/,
  'the vendored icon set must keep its licence notice');
assert.match(html, /scripts\/build-drive-mode-glyphs\.mjs/, 'glyph table names its generator');
const glyphTable = html.slice(html.indexOf('const CAR_DRIVE_MODE_GLYPHS = {'),
  html.indexOf('};', html.indexOf('const CAR_DRIVE_MODE_GLYPHS = {')));
for (const value of ['0', '1', '2', '3', '4', '5']) {
  assert.match(glyphTable, new RegExp("'" + value + "': 'M "), 'glyph for drive mode ' + value);
}
// Only the flattened subset, or the native parser cannot draw it.
const paths = glyphTable.match(/'M [^']+'/g) || [];
assert.ok(paths.length >= 6, 'every drive mode but AWD needs a glyph');
for (const path of paths) {
  assert.ok(!/[^MLCZ0-9eE.\-\s']/.test(path),
    'glyphs must be flattened to absolute M/L/C/Z: ' + path.slice(0, 40));
}
// The rail draws the same art, so the path travels with the payload and is
// validated on arrival like any other untrusted string.
assert.match(html, /driveModeVisual\.glyph = CAR_DRIVE_MODE_GLYPHS\[drivingGroup\(0\)\.value\]/,
  'the glyph must reach the rail payload');
includesAll(native, [
  'private String sanitizeGlyphPath(String value)',
  'private void drawGlyphPath(',
  'drawGlyphPath(c, descriptor.glyph,',
], 'native glyph parser');
for (const gone of ['ecoGlyph', 'boltGlyph', 'snowGlyph', 'duneGlyph', 'mudGlyph',
  'awdGlyph', 'wheelGlyph', 'roadGlyph', 'drawUnitPath']) {
  assert.ok(!native.includes(gone), 'hand-drawn glyph left behind: ' + gone);
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

// A press on a chip used to light the whole widget: :active applies to every
// ancestor of the pressed element, and the card's own :active scales and rings
// it. :has() would be tidier but the car runs WebView 91.
assert.match(html, /item\.type === 'driving' \? ' has-controls' : ''/,
  'a control-dense widget must mark its card');
assert.match(html, /\.hv-widget-card\.has-controls:active \{[^}]*transform: none;/,
  'the card must not take the press feedback for its own buttons');

// The widget and the popup are one visual language: the chip is the popup's
// option tile at a smaller size, muted through colour rather than opacity
// (opacity also dims the icon).
assert.ok(!/\.hv-driving-chip \{[^}]*opacity:\.6/.test(css),
  'an unselected chip is muted by colour, not opacity');
includesAll(css, [
  '.hv-driving-chip.on { color:var(--hv-accent); border-color:currentColor;',
  'box-shadow:inset 0 0 0 1px currentColor;',
  '.hv-driving-chip-icon',
], 'the widget chip follows the popup tile');

// Every options grid needs its column rule; cols-2 was missing and the two
// ASSIST toggles fell back to a four-column track, clipping "ONE-PEDAL" to "O…".
for (const cols of ['cols-2', 'cols-3']) {
  assert.ok(html.includes('.hv-driving-options.' + cols + ' { grid-template-columns:'),
    'missing options column rule: ' + cols);
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
  'driveMode: driveModeVisual.secondary,',
  'powerMode: powerModeVisual.secondary,',
  'regen: regenVisual.secondary,',
  'secondary: driving.drivingSource,',
], 'native Driving quick cards');
assert.ok(!dockIndicators.includes('driveMode: driveMode.secondary'),
  'demo sources must use driveModeVisual, not the undefined driveMode');
assert.ok(!dockIndicators.includes('regen: regenMode.secondary'),
  'demo sources must use regenVisual, not the undefined regenMode');
// All three tiles read from the one builder, so a mode cannot say one thing on
// the rail and another in the popup.
includesAll(dockIndicators, [
  'const drivingGroup = (index) => driving.drivingGroups[index];',
  'CAR_DRIVE_MODE_CARD_STATES[drivingGroup(0).value]',
  'CAR_POWER_MODE_CARD_STATES[drivingGroup(1).value]',
], 'rail tiles share the card builder');
// The graphic draws the code, so the value line spells the mode out.
assert.match(html, /const CAR_POWER_MODE_LONG_LABELS = \{ '0': 'Hybrid EV', '1': 'Prioritary EV', '3': 'Full Electric' \};/,
  'power modes need their spelled-out labels');
includesAll(dockIndicators, ['CAR_POWER_MODE_LONG_LABELS[drivingGroup(1).value]'],
  'the power tile shows the long label');
// One-pedal replaces the level rather than extending it, so the tile names it.
includesAll(dockIndicators, ["onePedalOn ? 'onepedal'", "regenVisual.primary = 'One pedal'"],
  'the recovery tile reports one-pedal');
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
  'private void drawGlyphPath(',
], 'native rail graphics');
// A tap on the graphic is a command, so it passes the same allow-list as a tap
// on the card; an unknown one degrades to no icon action.
includesAll(native, [
  'String iconAction = raw.optString("iconAction", "").trim();',
  'if (!BOTTOM_CARD_ACTIONS.contains(iconAction)) iconAction = "";',
  'graphic.setOnClickListener(v -> callViewerDock(iconCommand));',
], 'icon quick action is gated');

console.log('Driving card contracts: ok');

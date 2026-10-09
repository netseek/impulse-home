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
// Line endings are normalised on read. index.html and MainActivity.java are
// stored LF and checked out CRLF on Windows, so a source contract that
// hardcodes either one passes or fails depending on which command last
// rewrote the file. Two of these tests had already broken that way.
const html = readFileSync(resolve(root, 'index.html'), 'utf8').replace(/\r\n/g, '\n');

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
  "{ id: 'navigation', title: 'Navegação', action: 'openNavigation' }",
  "{ id: 'climate', title: 'Ar Condicionado', action: 'openClimate' }",
  // The card id stays `consumption` (saved layouts, native allow-list); the
  // workspace it opens was renamed ENERGY (docs/features/energy-workspace.md).
  "{ id: 'consumption', title: 'Consumo', action: 'openConsumption' }",
  "{ id: 'range', title: 'Autonomia', action: 'openRange' }",
  "{ id: 'status', title: 'Status do veículo', action: 'openVehicleStatus' }",
  "{ id: 'clock', title: 'Relógio', action: 'openClockSettings' }",
  // The three driving tiles share one destination: the card body opens the
  // DRIVING popup, the icon keeps the per-mode quick change.
  "{ id: 'driveMode', title: 'Modo de condução', action: 'openDriving', iconAction: 'cycleDriveMode' }",
  "{ id: 'powerMode', title: 'Modo de energia', action: 'openDriving', iconAction: 'cyclePowerMode' }",
  "{ id: 'regen', title: 'Recuperação de energia', action: 'openDriving', iconAction: 'cycleRegenMode',",
  "case 'openNavigation':",
  "case 'openClimate':",
  "case 'openConsumption':",
  "case 'openRange':",
  "case 'openTires':",
  "case 'openVehicleStatus':",
  "case 'openDriving':",
  "case 'cycleDriveMode':",
  "case 'cyclePowerMode':",
  "case 'cycleRegenMode':",
  "case 'openRoofControls':",
], 'command wiring');
assert.ok(!html.includes("{ id: 'roof', title: 'Sunroof / shade', action: 'openRoofControls' }"), 'Roof is unified into Vehicle Status, not a competing card');
assert.ok(!html.includes("{ id: 'tires', title: 'Tires', action: 'openTires' }"), 'Tires is unified into Vehicle Status, not a competing rail card');
includesAll(method('_normalizeBottomCards'), ["id === 'tires' ? 'status' : id", 'seen[key]'], 'legacy Tires card migration');
assert.ok(!html.includes("{ id: 'consumption', title: 'Consumption', action: 'addWidget' }"), 'Consumption must not open addWidget');
assert.ok(!html.includes("{ id: 'status', title: 'Vehicle status', action: 'openDesktopStudio' }"), 'Vehicle status must not open Desktop Studio');

// CoffeeOS-style glance widgets remain page-owned cards: they are selectable
// from the visual picker and render on the widget board.
includesAll(html, [
  "profile: { label: 'PROFILE'",
  "clock: { label: 'CLOCK'",
  "navigation: { label: 'NAVIGATION'",
  "tires: { label: 'TIRES'",
  "driving: { label: 'DRIVING'",
  "status: { label: 'STATUS'",
  "range: { label: 'RANGE'",
  'value="{{ wg.isProfile }}"',
  'value="{{ wg.isClock }}"',
  'value="{{ wg.isNavigation }}"',
  'value="{{ wg.isTires }}"',
  'value="{{ wg.isDriving }}"',
  'value="{{ wg.isStatus }}"',
  'value="{{ wg.isRange }}"',
  "previewClock: key === 'clock'",
  "hasShot: key !== 'clock'",
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
  'primary: navigation.navigationShowTurn',
  'secondary: range.rangeSource',
  "metricA: 'EV ' + range.rangeEv + ' ' + range.rangeUnit",
  'progress: percent(range.rangeSoc, 0)',
  'Object.assign({',
  'bottomVisuals[card.id] || {}',
], 'graphic bottom-card payload');
// The unified Driving card carries the source vocabulary the audit requires:
// a DEMO badge is never abbreviated, and an un-ready vehicle disables writes
// rather than showing a plausible default.
includesAll(method('_drivingWidgetView'), [
  "'DEMO · SIMULADO · NÃO É DO VEÍCULO'",
  "'PRÉVIA LOCAL · NÃO É AJUSTE DO VEÍCULO'",
  "'INDISPONÍVEL · VEÍCULO NÃO PRONTO'",
  "'PENDENTE · AGUARDANDO O VEÍCULO'",
  "'VEÍCULO · AO VIVO'",
  "'DESATUALIZADO · ESTADO DO VEÍCULO'",
], 'live-first driving card state');
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
includesAll(method('_rangeTelemetry'), [
  'CAR_SIGNALS.batterySoc, CAR_SIGNALS.evRange, CAR_SIGNALS.fuelRange',
  "'DEMO · SIMULATED · NOT VEHICLE'",
  "'PARTIAL · VEHICLE RANGE'",
  "'STALE · VEHICLE RANGE'",
  "'UNAVAILABLE · NO RANGE SIGNAL'",
  'completeRange = evKnown && fuelKnown',
  "this._rangeDistanceUnit()",
  "unitKey === 'mi' ? .621371 : 1",
  'formatDistance = (km)',
], 'range telemetry semantics');
includesAll(method('_focusedCardRenderFields'), ["type === 'range'", 'focusedCardIsRange', 'this._rangeWidgetView(entry.item)'], 'focused range popup');
includesAll(method('_openRangeCard'), ["this._openFocusedCard('range')", 'this.state.widgetPlaceMode'], 'range widget interaction');
includesAll(method('_setRangeDistanceUnit'), ["localStorage.setItem('h6_range_unit', unit)", 'widgetRev'], 'range unit preference');
includesAll(html, ['aria-label="Distance unit"', '>KM</button>', '>MI</button>'], 'range unit controls');

// The Layout manager: a Desktops strip, and one frame for Cards & widgets. Cards is the enabled list itself, in rail
// order, with no second display-limit value hiding cards the user just enabled.
includesAll(html, ['class="hv-studio-segtabs"', '>Cards</button>', '>Widgets</button>',
  'list="{{ desksItems }}"', 'list="{{ studioAvailCards }}"', 'list="{{ studioWidgetItems }}"'], 'layout manager screens');
const studioFields = method('_desktopRenderFields');
includesAll(studioFields, ['desksItems:', 'studioIsLayout:', 'studioIsCards:', 'studioIsWidgets:', 'studioIsAppearance:',
  'this._studioCardFields(s, bottomCards,'], 'layout manager fields');
assert.ok(!studioFields.includes('studioBottomLimitItems:'), 'enabled cards must not have a separate display limit');
includesAll(method('_studioCardFields'), ['bottomCards.indexOf(card.id) < 0', 'this._addBottomCardLast(card.id)'], 'cards tab offers only cards not on the rail');

// Creating a destination needs a usable large card on the board it opens.
// Any popup-backed card can choose its destination, and the focused desktop is
// named after the workspace it hosts rather than a two-way ternary that called
// everything past Climate 'Consumption'.
includesAll(html, ['const H6_CARD_POPUP_TYPES = {', 'H6_CARD_FOCUS_TYPES.indexOf(type) < 0'], 'card destinations');
assert.ok(!html.includes("['climate', 'consumption'].indexOf(card.id) >= 0"),
  'the destination chooser must not be hardcoded to two cards');
includesAll(method('_createFocusedCardDesktop'), [
  "w: 2, h: 2",
  "this._widgetCatalog()[type]",
  "layout.appCar.left = { use: 'widgets'",
  "this._desktopSnapshot(name, null, layout)",
  "desktopId: d.id",
], 'focused desktop');

// The popup follows shared native workspace geometry, and ordinary desktop
// navigation cannot leave it orphaned over a different desktop.
includesAll(html, ['top:var(--hv-popup-top', 'bottom:var(--hv-popup-bottom-inset'], 'popup geometry');
includesAll(method('_switchDesktop'), ['focusedCardType: null'], 'desktop popup cleanup');
includesAll(method('_openDesktopStudio'), ['focusedCardType: null'], 'studio popup cleanup');
// Desktop swipe on the card popup / its backdrop (same handlers as the board).
includesAll(html, [
  'class="hv-card-focus {{ focusedCardPopupClass }}"',
  'onTouchStart="{{ desktopSwipeStart }}"',
  'class="hv-card-focus-backdrop {{ focusedCardBackdropClass }}"',
], 'popup desktop swipe handlers');
includesAll(method('_pointInDesktopSwipeZone'), [
  "cls.contains('hv-card-focus')",
  "cls.contains('hv-card-focus-backdrop')",
], 'popup swipe zone');
includesAll(method('_onDesktopGestureStart'), ["cls.contains('hv-card-focus')", 'fromPopup:'], 'popup swipe gesture');
includesAll(method('_isDesktopSwipeBlockedTarget'), [
  "t.closest('.hv-clim-wheel')",
  "t.closest('.hv-card-focus button')",
], 'popup swipe keeps controls');
includesAll(html, [
  'body.hv-desk-sliding #hv-root > .hv-card-focus',
  'body.hv-desk-sliding #hv-root > .hv-card-focus-backdrop',
], 'popup fades during desk slide');

// Android Auto TBT on the Navigation card / widget. Impulse publishes
// app.androidauto.session and app.navigation.directions; this viewer must
// listen, not invent a second map.
const htmlRoot = html;
const java = readFileSync(resolve(root, 'app/src/main/java/com/havalh6/viewer/MainActivity.java'), 'utf8').replace(/\r\n/g, '\n');
includesAll(htmlRoot, [
  "aaSession: 'app.androidauto.session'",
  "navDirections: 'app.navigation.directions'",
  "place: 'app.location.place'",
  'CAR_SIGNALS.aaSession, CAR_SIGNALS.navDirections, CAR_SIGNALS.place',
], 'AA telemetry keys');
includesAll(method('_applyCarSignal'), [
  'CAR_SIGNALS.aaSession',
  'CAR_SIGNALS.navDirections',
  'CAR_SIGNALS.place',
  'this._applyAaSession(value)',
  'this._applyNavDirections(value)',
  'this._applyPlaceGlance(value)',
], 'AA signal routing');
const navView = method('_navigationWidgetView');
includesAll(navView, [
  "'ANDROID AUTO · LIVE'",
  "'ANDROID AUTO · NO GUIDANCE'",
  "'NAVIGATION · NO ROUTE DATA'",
  "'DEMO · ROUTE PREVIEW'",
  "'DEMO · NO GUIDANCE'",
  'navigationTurnGlyph',
  'navigationCardState',
  'navigationCardGlyph',
  'navigationHasAppIcon',
  'navigationAppPackage',
  'navigationStreet',
  'navigationRemaining',
  'navigationDuration',
  'navigationEta',
  'navigationHasEta',
  'DEMO_NAV_SEQUENCE',
  'this._openNavigationApp()',
], 'navigation live sources');
includesAll(navView, [
  'formatNavMeters',
  'formatNavDuration',
  'resolveNavEta',
  'live.remainingM',
  'live.remainingS',
  'live.eta',
  'metricSlots',
], 'Impulse remaining distance / time / ETA');
includesAll(htmlRoot, [
  "family: 'turn_right'",
  "family: 'turn_left'",
  "family: 'straight'",
  "family: 'roundabout'",
  "family: 'uturn'",
  "family: 'fork'",
  "family: 'merge'",
  "family: 'exit'",
  "family: 'destination'",
  '{ idle: true }',
], 'demo cycles every turn family then idle');
includesAll(method('_openNavigationApp'), [
  "this._aaSession === 'active'",
  "B.launchProjection('AA')",
  "this._launchDefaultOrPick('navigation')",
], 'AA session tap must raise projection; an idle card uses the saved default');
assert.ok(!method('_openNavigationApp').includes('CAR_NAV_PACKAGE'),
  'an idle navigation card must not launch the fake navigation package');
includesAll(htmlRoot, [
  "case 'pickDefaultApp:navigation':",
  "this._openDefaultAppPicker('navigation', false)",
  'pickDefaultApp:\' + cardId',
  'h6_default_apps',
], 'idle navigation can save a default app from the ⋯ menu');
assert.ok(java.includes('isDefaultAppCommand'),
  'native must relay pickDefaultApp or the ⋯ row is dropped');
includesAll(method('_ensureDemoNavTicker'), [
  'DEMO_NAV_INTERVAL_MS',
  "this._aaSession === 'active'",
], 'disconnected demo must cycle guidance vs idle');
includesAll(htmlRoot, [
  "place: 'app.location.place'",
  'class="hv-nav-app-icon"',
  'class="hv-navigation-tbt"',
  'class="hv-navigation-metrics"',
  'class="hv-navigation-metric is-eta"',
  'NAV_TURN_ICONS',
  './assets/ui/tbt/right.svg',
], 'AA icon + place glance wiring');
assert.equal((htmlRoot.match(/class="hv-navigation-tbt"/g) || []).length, 1,
  'TBT strip must exist on the widget board');
assert.equal((htmlRoot.match(/class="hv-navigation-metrics"/g) || []).length, 1,
  'GMaps metric row must exist on the widget board');
assert.equal((htmlRoot.match(/class="hv-nav-app-icon"/g) || []).length, 1,
  'AA icon must exist on the widget board');
includesAll(method('_parseNavDirections'), [
  'obj.active',
  'obj.street',
  'obj.distance',
  'obj.distance_m',
  'obj.turn',
  'obj.remaining_m',
  'obj.remaining_s',
  'obj.eta',
  'return null',
], 'directions JSON shape');
includesAll(htmlRoot, [
  'resolveNavEta(',
  'formatNavEta(',
], 'prefer Impulse ETA string over now+remaining_s');
includesAll(method('_applyNavDirections'), [
  'parsed.remainingM == null && prev.remainingM != null',
  'parsed.remainingS == null && prev.remainingS != null',
  '!parsed.eta && prev.eta',
], 'retain trip totals across partial Waze frames');
includesAll(method('_queueNavRefresh'), [
  'setTimeout',
  '250',
  'this._commitNavUi()',
], 'TBT refresh throttle');
includesAll(method('_commitNavUi'), [
  "_hasWidgetType(mode, 'navigation')",
  'this._uiOnlyStateWrite = true',
  'this._syncDockIndicators()',
], 'TBT must not dirty the 3D loop');
const dock = method('_syncDockIndicators');
includesAll(dock, [
  'state: navigation.navigationCardState',
  'glyph: navigation.navigationCardGlyph',
  'appPackage: navigation.navigationAppPackage',
  'navRemaining: navigation.navigationRemaining',
  'navDuration: navigation.navigationDuration',
  'navEta: navigation.navigationShowTurn',
  "{ type: 'navigation', w: 3, h: 1 }",
], 'native rail TBT payload');
assert.equal((htmlRoot.match(/d="\{\{ wg\.navigationTurnGlyph \}\}"/g) || []).length, 1,
  'turn glyph fallback must exist on the widget board');
assert.equal((htmlRoot.match(/class="hv-navigation-turn-icon"/g) || []).length, 1,
  'minimalist TBT mask icon must exist on the widget board');
assert.ok(java.includes('case "navigation": drawNavigation'));
assert.ok(java.includes('sanitizeNavigationState'));
assert.ok(java.includes('ic_tbt_turn_right'));
const drawNavAt = java.indexOf('private void drawNavigation(');
assert.ok(drawNavAt >= 0, 'missing drawNavigation');
const drawNav = java.slice(drawNavAt, java.indexOf('private void drawTires(', drawNavAt));
assert.ok(drawNav.includes('drawTbtIcon'),
  'live navigation graphic must paint the filled minimalist TBT icon');
assert.ok(drawNav.includes('drawGlyphPath'),
  'drawNavigation must keep the stroke glyph as fallback for exit/destination');
// The live nav app icon is the MEDIA card's source chip in the card header
// (2bb5f71), not a badge painted on the canvas. Assert where the icon comes
// from and that it follows the live payload, not which view draws it.
const navChipApplyAt = java.indexOf('private void applyNavigationSourceChip(');
assert.ok(navChipApplyAt >= 0, 'missing applyNavigationSourceChip');
const navChipApply = java.slice(navChipApplyAt, java.indexOf('\n    }\n', navChipApplyAt));
assert.ok(navChipApply.includes('card.appPackage') && /resolve\w*Icon\(pkg/.test(navChipApply),
  'the navigation card must show the live nav app icon from its appPackage');
assert.ok(/quickCardSourceChips\.put\(descriptor\.id, \w+\);\s*applyNavigationSourceChip\(descriptor\);/.test(java),
  'the navigation card must build its source chip and stamp it on creation');
assert.ok(/graphic\.setDescriptor\(card\);\s*applyNavigationSourceChip\(card\);/.test(java),
  'the in-place rail patch must re-stamp the nav app icon when the payload changes');
assert.ok(!drawNav.includes('iconBitmapForPackage'),
  'the nav app icon must not also be painted on the canvas -- one Android Auto mark per card');
assert.ok(drawNav.includes('descriptor.metricA'),
  'manoeuvre distance is drawn under the glyph');
assert.ok(drawNav.includes('drawNavMetricRow'),
  'remaining / time / ETA are stacked Google Maps columns');
assert.ok(java.includes('measureNavMetricWidths'),
  'native metric columns pack to content width instead of equal-splitting into ellipses');
assert.ok(drawNav.includes('descriptor.navEta'),
  'ETA is a dedicated column, not a leftover trip-strip string');
assert.ok(java.includes('|| "navigation".equals(descriptor.id)'),
  'navigation is a full-graphic rail tile so the TBT strip can own the body');
assert.ok(java.includes('Opens navigation.'));
assert.ok(java.includes('ProjectionPresence.isProjectionPackage(packageName)'),
  'launchAppForPackage must raise AA/CarPlay instead of MediaCenter MAIN');
assert.ok(java.includes('public void launchProjection(String kind)'),
  'JS must be able to raise projection without a launcher entry');
assert.ok(java.includes('public String getAppIcon(String packageName)'),
  'widget AA chip needs a package icon data URL');
assert.ok(java.includes('new PlaceGlance('),
  'idle place glance must be started from the viewer, not invented in JS');
assert.ok(java.includes('PlaceGlance.KEY'),
  'place JSON must reuse the existing onCarDataUpdate pipe');
assert.ok(!java.includes('case "navigation": drawRing'),
  'navigation must not fall through to the generic ring');

console.log('card-navigation contracts: ok');

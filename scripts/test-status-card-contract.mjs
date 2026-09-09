/* Source-level status-card contract. It guards provenance, physical mapping,
 * dedicated navigation and the Android quick-card renderer without a device. */
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
// Line endings are normalised on read. index.html and MainActivity.java are
// stored LF and checked out CRLF on Windows, so a source contract that
// hardcodes either one passes or fails depending on which command last
// rewrote the file. Two of these tests had already broken that way.
const html = readFileSync(resolve(root, 'index.html'), 'utf8').replace(/\r\n/g, '\n');
const native = readFileSync(resolve(root, 'app/src/main/java/com/havalh6/viewer/MainActivity.java'), 'utf8').replace(/\r\n/g, '\n');

function block(source, token) {
  const start = source.indexOf(token);
  assert.ok(start >= 0, `missing ${token}`);
  const brace = source.indexOf('{', start);
  let depth = 0;
  for (let i = brace; i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  throw new Error(`unterminated ${token}`);
}
function all(source, values, label) {
  for (const value of values) assert.ok(source.includes(value), `${label}: missing ${value}`);
}

all(html, [
  "{ id: 'status', title: 'Vehicle status', action: 'openVehicleStatus' }",
  "case 'openVehicleStatus':", 'this._openRoofLevelPopup()',
  'hv-hs-roof-stack', 'data-hs-vehicle-art', 'data-hs-vehicle-layer', 'data-hs-vehicle-cavity',
  'data-hs-vehicle-window', 'data-hs-vehicle-belt', '_syncRoofVehicleStatus',
  './assets/ui/vehicle-status/base.png',
], 'unified status and canonical roof surface');
assert.ok(!html.includes("{ id: 'status', title: 'Vehicle status', action: 'openDesktopStudio' }"),
  'status must never fall back to Desktop Studio');

const snapshot = block(html, '  _statusDoorSnapshot(');
all(snapshot, ['CAR_DOOR_SLOTS[definition.key]', "state === 'open'", "state === 'closed'", "state === 'unknown'", "'partial'", "'stale'", "'demo'", "'unavailable'"], 'physical opening and provenance mapping');
assert.match(snapshot, /const demo = !!this\._demoPreview && !observed/, 'demo must only exist without observed door telemetry');
assert.match(snapshot, /DEMO · SIMULATED · NOT VEHICLE/, 'demo provenance must be explicit');
assert.match(snapshot, /UNAVAILABLE · NO DOOR SIGNAL/, 'missing telemetry must not receive fallback values');

const demoSequenceStart = html.indexOf('const DEMO_DOOR_SEQUENCE = [');
assert.ok(demoSequenceStart >= 0, 'missing alternating demo door sequence');
const demoSequenceEnd = html.indexOf('];', demoSequenceStart);
assert.ok(demoSequenceEnd > demoSequenceStart, 'unterminated alternating demo door sequence');
const demoSequence = html.slice(demoSequenceStart, demoSequenceEnd + 2);
const demoPhases = [...demoSequence.matchAll(
  /\{\s*key:\s*'([^']+)'[\s\S]*?open:\s*\{([^}]*)\}\s*\}/g,
)].map((match) => ({ key: match[1], open: match[2].trim() }));
assert.deepEqual(demoPhases.map((phase) => phase.key),
  ['closed', 'fl', 'closed', 'fr', 'closed', 'rl', 'closed', 'rr', 'closed', 'trunk'],
  'demo must alternate a secured phase with every physical opening');
for (let i = 0; i < demoPhases.length; i++) {
  const phase = demoPhases[i];
  if (i % 2 === 0) assert.equal(phase.open, '', `secured demo phase ${i} must open nothing`);
  else assert.match(phase.open, new RegExp(`^${phase.key}\\s*:\\s*true\\s*,?$`),
    `demo phase ${phase.key} must exercise only its matching opening`);
}
assert.match(html, /const DEMO_DOOR_INTERVAL_MS\s*=\s*2500\s*;/,
  'demo door phase interval must remain 2500ms');

const demoTicker = block(html, '  _ensureDemoDoorTicker(');
assert.match(demoTicker,
  /if\s*\(\s*this\._demoDoorTicker\s*\|\|\s*!this\._demoPreview\s*\|\|\s*typeof setInterval\s*!==\s*'function'\s*\)\s*return/,
  'door ticker must start only for demo mode and never duplicate itself');
all(demoTicker, [
  'at[CAR_SIGNALS.doors]',
  'this._demoDoorSequenceIndex + 1',
  '% DEMO_DOOR_SEQUENCE.length',
  'widgetRev: (state.widgetRev || 0) + 1',
  'this._syncRoofVehicleStatus(this._statusEnvelopeSnapshot())',
  'this._syncDockIndicators()',
  'DEMO_DOOR_INTERVAL_MS',
], 'demo ticker live-signal guard, phase advance, and surface synchronization');
assert.match(demoTicker, /if\s*\(\s*at\[CAR_SIGNALS\.doors\]\s*\)\s*return/,
  'actual door-signal timestamp must freeze the demo sequence');
assert.ok(!demoTicker.includes('_carDoorSlots'),
  'presentation ticker must neither read nor mutate canonical parsed door slots');

const unmount = block(html, '  componentWillUnmount(');
assert.match(unmount, /clearInterval\s*\(\s*this\._demoDoorTicker\s*\)/,
  'unmount must clear the demo door timer');
assert.match(unmount, /this\._demoDoorTicker\s*=\s*0/,
  'unmount must reset the demo door timer handle');

all(snapshot, [
  'DEMO_DOOR_SEQUENCE[',
  'this._demoDoorSequenceIndex',
  'demoPhase.open',
  'demoOpen[definition.key]',
], 'door snapshot must derive its demo state from the current sequence phase');
assert.doesNotMatch(snapshot, /const\s+demoOpen\s*=\s*\{\s*fl\s*:\s*true\s*,\s*trunk\s*:\s*true\s*\}/,
  'door snapshot must not retain the old hardcoded FL plus tailgate demo');

all(html, ['this._carDoorSlots = slots.slice()', 'this._carDoorSlots = null;', 'CAR_DOOR_SLOTS.trunk'], 'canonical parsed door vector retention');
const statusView = block(html, '  _statusWidgetView(');
all(statusView, ['statusAriaLabel', 'onStatusOpen', 'statusMetrics', 'statusRoof', 'statusWindows', 'statusSeatBelts', 'statusTires', 'statusWindowControls', 'statusSunroofInput', 'statusCurtainInput', 'this._openRoofLevelPopup()'], 'widget accessibility, unified data, and opening');
assert.match(statusView, /statusOpenRoofControls: .*_openRoofLevelPopup\(\)/, 'roof area must reuse the canonical roof popup');
assert.match(statusView, /statusSunroofInput: roofInput\('sunroof'\)/, 'large status surface must reuse the canonical sunroof range handler');
assert.match(statusView, /statusCurtainInput: roofInput\('curtain'\)/, 'large status surface must reuse the canonical sunshade range handler');
assert.match(html, /case 'openVehicleStatus':\s*this\._openRoofLevelPopup\(\)/, 'status action must use the canonical roof popup directly');
const popupMarkup = html.slice(html.indexOf('roofPop.innerHTML ='), html.indexOf('// Keep this control surface'));
assert.equal((popupMarkup.match(/vehicle-status\/base\.png/g) || []).length, 1, 'popup must contain exactly one base car');
assert.ok(!popupMarkup.includes('roof-top-view-v1.png'), 'popup must not contain a second roof-only car');
assert.ok(!popupMarkup.includes('hv-hs-unified'), 'popup must not retain the old two-panel/two-car layout');
all(popupMarkup, ['hv-vehicle-window-panel', 'data-hs-window-key="fl"', 'data-hs-window-key="fr"', 'data-hs-window-key="rl"', 'data-hs-window-key="rr"', 'data-hs-window-all="close"', 'data-hs-window-all="open"'], 'left-side window console');
assert.equal((popupMarkup.match(/data-hs-vehicle-tire=/g) || []).length, 4, 'popup must render all four tire positions around the one car');
assert.equal((popupMarkup.match(/data-hs-vehicle-tire="[^"]+"><strong>—<\/strong><small>—<\/small>/g) || []).length, 4,
  'popup tire positions must show pressure over temperature without redundant corner initials');
const envelope = block(html, '  _statusEnvelopeSnapshot(');
all(envelope, ['CAR_SIGNALS.sunroof', 'CAR_SIGNALS.curtain', "'unfastened'", "'fastened'", "'unknown'", 'DEMO', 'this._tiresWidgetView', 'tires.wheels'], 'envelope telemetry, seatbelt, and tire semantics');
assert.match(envelope, /if \(!demo && !doors\.open\.length && beltAttention\.length\)/,
  'alternating demo headline must keep naming its door phase');
assert.match(envelope, /key: 'rc', label: 'Rear center belt'/, 'seat map must contain two front and three rear belts');
assert.equal((popupMarkup.match(/data-hs-vehicle-belt=/g) || []).length, 5, 'popup must render all five seat positions');
const layerBuilder = block(html, '  _statusVehicleImageLayers(');
all(layerBuilder, ['door-', 'cavity-v1.png', 'closed-v4.png', 'open-v3.png', 'tailgate-open-v2.png', 'window-', "state + '-v2.png'"], 'repository image layer mapping');
for (const asset of ['base.png', 'door-fl-cavity-v1.png', 'door-fr-cavity-v1.png', 'door-rl-cavity-v1.png', 'door-rr-cavity-v1.png', 'door-fl-closed-v4.png', 'door-fr-closed-v4.png', 'door-rl-closed-v4.png', 'door-rr-closed-v4.png', 'door-fl-open-v3.png', 'door-fr-open-v3.png', 'door-rl-open-v3.png', 'door-rr-open-v3.png', 'tailgate-open-v2.png', 'roof-glass-fixed-v3.png', 'roof-glass-front-v3.png', 'window-fl-open-v2.png', 'window-fl-partial-v2.png']) {
  assert.ok(existsSync(resolve(root, 'assets/ui/vehicle-status', asset)), `missing aligned vehicle layer ${asset}`);
}
assert.match(html, /\.hv-hs-vehicle-layer\.on \{ opacity:1; \}/, 'exact opening image layers must become visible');
assert.match(html, /\.hv-hs-vehicle-layer\.closed-door \{ z-index:2; opacity:0; \}/,
  'popup closed-door layers must stay hidden unless explicitly activated');
assert.match(html, /\.hv-hs-vehicle-layer\.closed-door\.on \{ opacity:\.98; \}/,
  'popup closed-door layers must become visible only in their closed state');
assert.match(html, /\.hv-hs-vehicle-layer\.cavity \{ z-index:1; \}/,
  'open doorway cavity must sit above the base and beneath door panels');
assert.match(html, /\.hv-hs-vehicle-layer\.cavity,\.hv-hs-vehicle-layer\.closed-door,\.hv-hs-vehicle-layer\.door \{ transition:none; \}/,
  'mutually exclusive door states must never crossfade into a duplicate');
all(html, ['hv-vehicle-roof-cavity', 'hv-vehicle-roof-fixed', 'hv-vehicle-roof-glass', 'hv-vehicle-roof-curtain', 'roof-glass-fixed-v3.png', 'roof-glass-front-v3.png'], 'single-car two-pane roof mechanism');
all(html, ['--roof-glass-tilt', '--roof-glass-slide', '--roof-lift-cue', '--curtain-retract', '--curtain-shift', '_animateRoofVehicleTransition'], 'roof and sunshade motion variables');
all(html, ["Object.assign({ offset:.28 }, vent)", 'duration:720', 'duration:560'], 'staged full-travel roof animation');
const openPanelRule = html.match(/\.hv-hs-vehicle-layer\.door,\.hv-hs-vehicle-layer\.tailgate \{[^}]+\}/)?.[0] || '';
assert.match(openPanelRule, /saturate\(1\.62\).*contrast\(1\.18\)/, 'open panels must retain a strong red treatment');
assert.ok(!openPanelRule.includes('drop-shadow'), 'open panels must not have a glow');
assert.match(html, /\.hv-vehicle-roof-curtain \{[^}]*z-index:2;/, 'curtain must stay beneath both glass panes');
assert.match(html, /#e51f35/, 'unfastened seatbelt warning must be red');
assert.match(html, /@keyframes hv-seatbelt-alert/, 'unfastened seatbelt must have an attention animation');

all(native, [
  '"openVehicleStatus"', 'case "status": drawVehicleStatus',
  'drawVehicleStatus(', 'sanitizeOpeningStates(', 'descriptor.openingStates',
  'Vehicle status. ', 'getAssets().open("www/assets/ui/tires-top-view-v1.png")',
  'getStatusVehicleBitmap("base.png")', '"door-rl-open-v3.png"',
  '"door-rr-open-v3.png"', '"tailgate-open-v2.png"', '"door-fl-closed-v4.png"',
  'vividRed.setSaturation(1.55f)',
  'demoBadge.setTag("frostAccent")', 'demoBadge.setText("DEMO")',
  'demoBadge.setTextSize(8f)', 'demoBadge.setLetterSpacing(0.10f)',
  'demoBadgeLp.rightMargin = Math.round(7 * density)',
], 'native renderer, accessibility, and shared demo badge');
assert.match(native, /demoBadge\.setVisibility\(card\.demo \? View\.VISIBLE : View\.GONE\)/,
  'native DEMO badge must be controlled solely by descriptor.demo');
assert.match(native, /String\[\] labels = \{"driver door", "front passenger door", "rear left door",\s*"rear right door", "tailgate"\}/,
  'native accessibility must name every physical opening');

const nativeStatus = block(native, '        private void drawVehicleStatus(');
all(nativeStatus, [
  '"door-fl-closed-v4.png"', '"door-fr-closed-v4.png"',
  '"door-rl-closed-v4.png"', '"door-rr-closed-v4.png"',
  '"door-fl-cavity-v1.png"', '"door-fr-cavity-v1.png"',
  '"door-rl-cavity-v1.png"', '"door-rr-cavity-v1.png"',
  '"door-fl-open-v3.png"', '"door-fr-open-v3.png"',
  '"door-rl-open-v3.png"', '"door-rr-open-v3.png"', '"tailgate-open-v2.png"',
], 'native renderer aligned closed/open layer mapping');
assert.match(nativeStatus,
  /String opening\s*=\s*i\s*<\s*openings\.length\s*\?\s*openings\[i\]\s*:\s*"unknown";\s*if\s*\(\s*"open"\.equals\(opening\)\s*\)\s*continue;/,
  'native renderer must skip the matching closed layer while that door is open');
all(nativeStatus, [
  '* 1.08f', 'float left = (w - imageW) * .5f',
  'float top = -h * .019f', 'drawStatusRoof(c, vehicleRect)',
  'drawStatusSeatBelts(c, vehicleRect)',
], 'native centered visual-only status-card geometry');
// Sized FROM the card rather than pinned to one multiplier: the vehicle scales
// with the view's height and is capped by its width, and the readouts scale
// with the smaller dimension. Pinning the literals meant every deliberate
// resize failed the contract instead of a regression doing so.
assert.match(nativeStatus, /float imageH = h \* [\d.]+f/,
  'status vehicle height must scale with the card');
assert.match(nativeStatus, /Math\.min\(imageW, w \* \.\d+f\)/,
  'status vehicle width must stay capped by the card');
assert.match(nativeStatus, /paint\.setTextSize\(Math\.max\([\d.]+f, Math\.min\(w, h\) \* \.\d+f\)\)/,
  'status tyre pressures must scale with the card');
assert.ok(!nativeStatus.includes('drawCircle'),
  'status corner readouts must contain no dots');
assert.ok(!nativeStatus.includes('float textLeft'),
  'native compact status card must not render a competing text-summary column');
// ANTI_ALIAS_FLAG smooths shapes, not bitmap sampling. base.png is 494x675
// drawn into roughly 70px; without FILTER_BITMAP_FLAG that downscale samples
// nearest-neighbour and the vehicle reads as jagged.
// Matched on the three-flag construction, which is unique to the quick-card
// canvas. A file-wide ANTI_ALIAS/FILTER_BITMAP search passed vacuously off an
// unrelated paint that already had both flags, so it could never have failed.
assert.match(native, /ANTI_ALIAS_FLAG\s*\|\s*android\.graphics\.Paint\.FILTER_BITMAP_FLAG\s*\|\s*android\.graphics\.Paint\.DITHER_FLAG/,
  'the quick-card canvas must filter the rasters it downscales');
all(native, [
  'seatBeltStates', 'sunroofLevel', 'curtainLevel',
  'sanitizeSeatBeltStates(', 'raw.optInt("sunroofLevel", 0)',
  'raw.optInt("curtainLevel", 0)',
], 'native status descriptor must receive restraint and roof position state');
assert.match(html, /seatBeltStates:\s*status\.statusSeatBelts\.map/,
  'web status bridge must forward individual restraint states');
assert.match(html, /sunroofLevel:\s*status\.statusSunroofPct/,
  'web status bridge must forward sunroof position');
assert.match(html, /\.hv-status-1x1 \.hv-status-copy[^}]*display:none/,
  'compact web status cards must remove the competing text-summary column');
assert.match(html, /\.hv-status-card-tire\.front-left \{ left:-62%/,
  'compact web tire pressures must sit outside the vehicle');
// Asserts the INTENT -- the readout hangs off the bodywork -- not one offset,
// so tuning the composition does not fail the contract. A positive left would
// put the number on top of the car, which is the thing worth catching.
assert.match(html, /\.hv-hs-vehicle-tire\.fl \{ left:-\d+%/,
  'popup tire pressures must sit outside the vehicle');
// The popup readout is the big, unboxed composition the Tires popup uses: a
// plate behind each number fought the vehicle render and forced the value
// small. Pressure scales with the panel; nothing draws a card behind it.
assert.match(html, /\.hv-hs-vehicle-tire strong \{[^}]*font:[^;}]*clamp\(/,
  'popup tire pressure must scale with the panel');
assert.ok(!/\.hv-hs-vehicle-tire \{[^}]*background:/.test(html),
  'popup tire readouts must not sit on a plate');
// Pins that the revision is BUMPED, not that it holds one literal: the WebView
// caches the packaged bundle across a same-version reinstall, so every change to
// index.html needs a new value here. Pinning the exact string made the assertion
// fail on the next legitimate bump instead of on a forgotten one.
assert.match(native, /VIEWER_ASSET_REVISION\s*=\s*"vehicle-console-v(\d+)[^"]*"/,
  'native WebView bundle revision must be a vehicle-console revision');
const assetRevision = Number(/VIEWER_ASSET_REVISION\s*=\s*"vehicle-console-v(\d+)/.exec(native)[1]);
assert.ok(assetRevision >= 18,
  `bundle revision must be at or past the unified vehicle console (got v${assetRevision})`);

console.log('status-card contracts: ok');

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
function check(source) {
  let now = 1;
  const sent = [], timers = new Map();
  let id = 0;
  const window = { TelemetryBridge: { invokeVehicleCommand: (cmd) => sent.push(cmd) } };
  const ctx = vm.createContext({ DCLogic: class {}, window, console: { warn() {} },
    performance: { now: () => now }, setTimeout: (fn) => { timers.set(++id, fn); return id; },
    clearTimeout: (key) => timers.delete(key) });
  vm.runInContext(source.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1] + ';globalThis.App = Component;', ctx);
  const app = Object.create(ctx.App.prototype);
  app.state = {}; app.setState = (patch) => Object.assign(app.state, patch);
  app._armHotspotHideTimer = () => {};
  app._refreshUnifiedStatus = () => {};
  app._syncRoofVehicleStatus = () => {}; app._statusEnvelopeSnapshot = () => ({});
  app._applyCarWindows('{1,1,1,1}');
  app._toggleHotspotWindow('fl'); app._toggleHotspotWindow('fl');
  assert.equal(sent.length, 1, 'rapid clicks must send one toggle');
  assert.equal(app._hsWindowCornerState('fl'), 'closed', 'label retains confirmed state');
  app._applyCarWindows('{0,1,1,1}');
  assert.equal(app._isWindowBusIgnored('fl'), true, 'moving is not an acknowledgement');
  assert.equal(app._carWindowStates.fl, 'closed');
  assert.equal(app._windowCornerTargets.fl, 1, 'animation continues toward open');
  app._sendCarCommand('close_windows', '');
  assert.equal(sent.length, 1, 'bulk command cannot overlap a corner');
  app._toggleHotspotWindow('fr');
  assert.equal(sent.length, 2, 'other corner remains usable');
  app._applyCarWindows('{2,2,1,1}');
  assert.equal(app._isWindowBusIgnored('fl'), false);
  app._toggleHotspotWindow('fl');
  assert.equal(sent.length, 3, 'close is available after real acknowledgement');
  app._applyCarWindows('{0,2,1,1}');
  now += 10000;
  for (const fn of [...timers.values()]) fn();
  assert.equal(app._hsWindowCornerState('fl'), 'open', 'timeout restores confirmed state');
  app._toggleHotspotWindow('fl');
  assert.equal(sent.length, 4, 'stale moving telemetry cannot permanently block retry');
  app._applyCarWindows('{1,2,1,1}');
  // That retry may still be pending because its target was open.
  now += 10000;
  for (const fn of [...timers.values()]) fn();
  delete window.TelemetryBridge;
  app._toggleHotspotWindow('fl');
  assert.equal(app._isWindowBusIgnored('fl'), false, 'missing bridge unlocks immediately');
  assert.equal(app._windowCornerTargets.fl, 0, 'missing bridge restores geometry');
  window.TelemetryBridge = { invokeVehicleCommand: (cmd) => sent.push(cmd) };
  app._cardControlEvent = () => {};
  app._clearVehicleOpeningLevel = () => {};
  const beforeBulk = sent.length;
  app._setAllStatusWindows('open');
  app._setAllStatusWindows('close');
  assert.equal(sent.length, beforeBulk + 1, 'status widget collective control blocks rapid reversal');
  assert.equal(app._carWindowStates.fl, 'closed', 'collective open does not invent confirmed state');
  app._applyCarWindows('{3,2,3,3}');
  assert.equal(app._windowCornerTargets.fl, 1, 'collective target is stable during partial echoes');
  app._setAllStatusWindows('close');
  assert.equal(sent.length, beforeBulk + 1, 'partial echo does not release collective reversal');
  app._applyCarWindows('{2,2,2,2}');
  app._setAllStatusWindows('close');
  assert.equal(sent.length, beforeBulk + 2, 'collective close works after confirmed open');
  app._setDoorOpen = () => {}; app._syncEventCamera = () => {};
  app._setTrunkOpen = (pct) => { app.state.trunkOpen = pct; };
  app._applyCarDoors('{0,0,0,0,0,0}');
  const beforeTrunk = sent.length;
  app._sendCarCommand('toggle_trunk', '', { skipLocal: true });
  app._sendCarCommand('toggle_trunk', '', { skipLocal: true });
  assert.equal(sent.length, beforeTrunk + 1, 'tailgate rapid toggles share pending guard');
  app._applyCarDoors('{0,0,0,0,0,1}');
  app._sendCarCommand('toggle_trunk', '', { skipLocal: true });
  assert.equal(sent.length, beforeTrunk + 1, 'early tailgate open echo does not unlock reversal');
  now += 20000; for (const fn of [...timers.values()]) fn();
  app._sendCarCommand('toggle_trunk', '', { skipLocal: true });
  assert.equal(sent.length, beforeTrunk + 2, 'tailgate control unlocks after bounded travel');
  assert.equal(app.state.trunkOpen, 0, 'next tailgate command closes from confirmed open');
  delete window.TelemetryBridge;
  now += 20000; for (const fn of [...timers.values()]) fn();
  app._sendCarCommand('toggle_trunk', '', { skipLocal: true });
  assert.equal(app._isTrunkBusIgnored(), false, 'missing bridge unlocks tailgate');
  assert.equal(app.state.trunkOpen, 100, 'missing bridge restores confirmed tailgate');


}
check(html);
assert.throws(() => check(html.replace('if (corners.some((key)', 'if (false && corners.some((key)')), /rapid clicks/);
assert.throws(() => check(html.replace("this._ensureHsWindowMenuStates()[key] = ((this._carWindowStates || {})[key] || 'closed') === 'closed' ? 'closed' : 'open';", '')), /label retains confirmed state/);
console.log('PASS window command race: delayed telemetry, overlap, timeout, movement, missing bridge + negative controls');

assert.throws(() => check(html.replace('if (corners.some((key) => this._isWindowBusIgnored(key)))', "if (corners.some((key) => this._isWindowBusIgnored(key) || (this._carWindowStates || {})[key] === 'open'))")), /close is available|stale moving/);

assert.match(html, /onClick="{{ wg.statusWindowsToggleAll }}" disabled="{{ wg.statusWindowsPending }}"/);
assert.throws(() => assert.match(html.replaceAll('disabled="{{ wg.statusWindowsPending }}"', ''), /onClick="{{ wg.statusWindowsToggleAll }}" disabled="{{ wg.statusWindowsPending }}"/));

assert.throws(() => check(html.replace('if (this._isTrunkBusIgnored()) return false;', 'if (false) return false;')), /tailgate rapid toggles/);

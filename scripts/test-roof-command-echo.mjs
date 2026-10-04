import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
function check(source) {
  let now = 1;
  const timers = new Map(); let id = 0;
  const ctx = vm.createContext({ DCLogic: class {}, window: {}, console,
    performance: { now: () => now }, setTimeout: (fn) => { timers.set(++id, fn); return id; },
    clearTimeout: (key) => timers.delete(key) });
  vm.runInContext(source.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]+';globalThis.App=Component;', ctx);
  const app = Object.create(ctx.App.prototype);
  app.state = { sunroofOpen: 0, curtainOpen: 0 };
  app._setSunroofOpen = (pct) => { app.state.sunroofOpen = pct; };
  app._setCurtainOpen = (pct) => { app.state.curtainOpen = pct; };
  app._syncEventCamera = () => {}; app._clearVehicleOpeningLevel = () => {};
  app._sendCarCommand = () => true;
  app._applyCarSunroof(0);
  app._runVehicleOpeningAction('sunroof', 'open');
  app._applyCarSunroof(20);
  assert.equal(app.state.sunroofOpen, 100, 'open command keeps slider at requested target during echoes');
  assert.equal(app._roofBusLevel.sunroof, 0, 'echo does not overwrite travel origin');
  assert.equal(app._roofBusIgnore.sunroof.pending, 40, 'mapped echo is buffered');
  app._applyCarSunroof(100);
  assert.equal(app.state.sunroofOpen, 100);
  now += 20000; for (const fn of [...timers.values()]) fn();
  assert.equal(app.state.sunroofOpen, 100, 'timeout adopts latest sample');
  app._runVehicleOpeningAction('sunroof', 'close');
  app._applyCarSunroof(50);
  assert.equal(app.state.sunroofOpen, 0, 'close remains stable during movement');
  now += 20000; for (const fn of [...timers.values()]) fn();
  assert.equal(app.state.sunroofOpen, 25 + 50 * .75, 'timeout reconciles unsuccessful close with last reading');
  app._applyCarSunroof(200);
  assert.equal(app.state.sunroofOpen, 25, 'tilt mapping is preserved outside ignore window');
  app._setVehicleOpeningLevel('sunroof', 70);
  app._applyCarSunroof(0);
  assert.equal(app.state.sunroofOpen, 70, 'slider gesture also ignores delayed echo');
}
check(html);
assert.throws(() => check(html.replace("this._applyCarOpening('_setSunroofOpen', pct, 100);", 'this._setSunroofOpen(pct);')), /keeps slider/);
assert.throws(() => check(html.replaceAll('entry.pending = pct;', 'entry.pending = null;')), /mapped echo/);
console.log('PASS sunroof open/close/slider echo buffer, timeout, tilt + negative controls');

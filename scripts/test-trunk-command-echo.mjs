import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
function harness(source, initial = 0) {
  let now = 1, id = 0;
  const timers = new Map(), sent = [], targets = [];
  const window = { TelemetryBridge: { invokeVehicleCommand: (...args) => sent.push(args) } };
  const ctx = vm.createContext({ DCLogic: class {}, window, console: { warn() {} },
    performance: { now: () => now },
    setTimeout: (fn, ms) => { timers.set(++id, { fn, at: now + ms }); return id; },
    clearTimeout: (key) => timers.delete(key) });
  vm.runInContext(source.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]
    + ';globalThis.App=Component;', ctx);
  const app = Object.create(ctx.App.prototype);
  app.state = { trunkOpen: initial };
  app._setTrunkOpen = (pct) => {
    targets.push({ pct, pending: app._trunkBusIgnore?.pending });
    app.state.trunkOpen = pct;
  };
  app._setDoorOpen = app._syncEventCamera = app._refreshUnifiedStatus = () => {};
  app._carSignalFreshness = () => 'live';
  return { app, window, sent, targets,
    toggle: () => app._sendCarCommand('toggle_trunk', '', { skipLocal: true }),
    status: () => app._statusDoorSnapshot(false).openings.find((v) => v.key === 'trunk').state,
    advance(ms) {
      now += ms;
      for (const [key, timer] of [...timers]) {
        if (timer.at <= now) { timers.delete(key); timer.fn(); }
      }
    } };
}

function check(source) {
  const invalid = ['{0,0,0,0,0,-1}', '{0,0,0,0,0}', '{0,0,0,0,0,invalid}', 'invalid', undefined];
  for (const packet of invalid) {
    const h = harness(source), { app } = h;
    app._applyCarDoors('{0,0,0,0,0,1}');
    app._applyCarDoors(packet);
    assert.equal(h.status(), 'unknown', 'invalid/missing current slot stays unknown in status');
    assert.equal(h.toggle(), true);
    assert.equal(app.state.trunkOpen, 0, '1 -> unknown -> toggle must close');
    assert.equal(app._trunkBusLevel, 100, 'unknown slot preserves last valid bus value');
    assert.deepEqual(h.targets.at(-1), { pct: 0, pending: 100 }, 'wait is armed before optimistic close');
    assert.equal(h.status(), 'unknown', 'command must not invent a known status');
    assert.equal(h.toggle(), false, 'pending command still blocks reversal');
    assert.deepEqual(h.sent, [['toggle_trunk', '']], 'physical command is preserved');
    h.advance(6399);
    assert.equal(app._isTrunkBusIgnored(), true);
    h.advance(1);
    assert.equal(app._isTrunkBusIgnored(), false);
    assert.equal(app.state.trunkOpen, 100, 'no confirmation rolls back to last valid open');
    assert.equal(h.status(), 'unknown', 'rollback does not replace raw status');
  }
  {
    const h = harness(source), { app } = h;
    app._applyCarDoors('{0,0,0,0,0,0}');
    h.toggle();
    app._applyCarDoors('{0,0,0,0,0,4}');
    assert.equal(app._trunkBusLevel, 100, 'valid moving slot updates bus cache during command');
    assert.equal(app._trunkBusIgnore.pending, 100, 'valid moving slot updates pending reconciliation');
    assert.equal(app._isTrunkBusIgnored(), true, 'early echo does not release wait');
    app._applyCarDoors('{0,0,0,0,0,-1}');
    assert.equal(app._trunkBusIgnore.pending, 100, 'unknown does not erase valid pending sample');
    h.advance(6400);
    assert.equal(app.state.trunkOpen, 100);
    h.toggle();
    app._applyCarDoors('{0,0,0,0,0,0}');
    assert.equal(app._trunkBusLevel, 0, 'valid closed sample replaces cached open');
    assert.equal(app._trunkBusIgnore.pending, 0);
    h.advance(6400);
    assert.equal(app.state.trunkOpen, 0, 'confirmed close reconciles to closed');
  }
  for (const failure of ['absent', 'throws']) {
    const h = harness(source), { app } = h;
    app._applyCarDoors('{0,0,0,0,0,1}');
    app._applyCarDoors('{0,0,0,0,0,-1}');
    h.window.TelemetryBridge = failure === 'absent' ? undefined
      : { invokeVehicleCommand() { throw new Error('bridge unavailable'); } };
    assert.equal(h.toggle(), false, 'unavailable bridge reports failure');
    assert.equal(app._isTrunkBusIgnored(), false, 'unavailable bridge releases immediately');
    assert.equal(app.state.trunkOpen, 100, 'unavailable bridge restores confirmed open');
    assert.equal(h.status(), 'unknown', 'failed bridge does not fill in unknown telemetry');
  }
  for (const initial of [0, 42, 100, -1, NaN, Infinity, null, undefined]) {
    for (const local of [false, true]) {
      const h = harness(source, initial), { app } = h;
      const confirmed = Number.isFinite(initial) && initial > 0 ? 100 : 0;
      assert.equal(app._trunkConfirmedPct(), confirmed, 'no telemetry normalizes prior valid state');
      if (local) app._applyCarCommandLocally('toggle_trunk', '');
      else h.toggle();
      assert.deepEqual(h.targets.at(-1), { pct: 100 - confirmed, pending: confirmed },
        'local and bridge paths arm before optimism without telemetry');
      assert.equal(h.status(), 'unknown', 'no telemetry remains unknown');
      h.advance(6400);
      assert.equal(app.state.trunkOpen, confirmed, 'no-telemetry timeout restores normalized previous state');
      assert.equal(app._trunkBusLevel, undefined, 'fallback state is not promoted to telemetry');
    }
    const h = harness(source, initial);
    delete h.window.TelemetryBridge;
    assert.equal(h.toggle(), false);
    assert.equal(h.app.state.trunkOpen, Number.isFinite(initial) && initial > 0 ? 100 : 0,
      'missing bridge without telemetry restores normalized previous state');
  }
  const noMesh = harness(source);
  noMesh.app._setTrunkOpen = undefined;
  noMesh.app._applyCarDoors('{0,0,0,0,0,5}');
  assert.equal(noMesh.app._trunkBusLevel, 100, 'valid telemetry is stored independently of mesh');
}

check(html);
for (const [from, to, message] of [
  ['this._trunkBusLevel = pct;', '', /unknown -> toggle|unknown slot/],
  ['entry.pending = this._trunkConfirmedPct();', 'entry.pending = 0;', /wait is armed/],
  ['if (this._isTrunkBusIgnored()) return false;', '', /pending command/],
  ['this._trunkBusIgnore.pending = pct;', '', /pending reconciliation/],
  ['this._armTrunkBusIgnore(next);\n        this._setTrunkOpen(next);',
    'this._setTrunkOpen(next);\n        this._armTrunkBusIgnore(next);', /arm before optimism/],
  ["if (cmd === 'toggle_trunk') this._flushTrunkBusIgnore();", '', /releases immediately/],
]) {
  assert.ok(html.includes(from), 'negative-control target exists: ' + from);
  assert.throws(() => check(html.replaceAll(from, to)), message);
}
console.log('PASS tailgate unknown telemetry, normalized fallback, pending updates, timeout, unavailable bridge + negative controls');

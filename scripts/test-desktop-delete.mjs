import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');

function loadApp(source) {
  const context = vm.createContext({
    DCLogic: class {},
    window: {
      React: { memo: (f) => f, createElement: () => ({}) },
      localStorage: { getItem: () => null, setItem() {} },
    },
    document: {
      body: { classList: { add() {}, remove() {}, toggle() {} } },
      querySelectorAll: () => [],
    },
    clearTimeout() {},
    setTimeout() { return 0; },
    localStorage: { getItem: () => null, setItem() {} },
  });
  vm.runInContext(
    source.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]
      + ';globalThis.App=Component;',
    context,
  );
  return Object.create(context.App.prototype);
}

function desk(id, fill) {
  return {
    id, name: id, centerFill: fill || 'car', shellMode: 'appCar',
    widgetLayout: {}, bottomCards: [], cardActions: {}, cardFocus: {}, cameras: {},
  };
}

function check(source) {
  assert.ok(!/this\._desktopIndex\s*=\s*-1/.test(source),
    'delete must not park the active index on -1');
  assert.match(source, /fromCar/, 'delete passes car presence after splice');
  assert.match(source, /_clampedDesktopIndex/, 'live index is clamped');

  const app = loadApp(source);
  app._desktopReady = true;
  app.state = { desktopRev: 0, desktopStudioOpen: false };
  app.setState = function (patch, cb) {
    if (typeof patch === 'function') patch = patch(this.state);
    Object.assign(this.state, patch || {});
    if (cb) cb();
  };
  app._captureActiveDesktop = () => {};
  app._saveDesktopStore = () => {};
  app._saveDesktopShots = () => {};
  app._syncDockIndicators = () => {};
  app._desktopShowsCar = (d) => !d || d.centerFill !== 'wallpaper';

  // Add then open then delete the new desktop (the reported path).
  app._desktops = [desk('a', 'car'), desk('b', 'wallpaper')];
  app._desktopIndex = 1;
  let switchCall = null;
  app._switchDesktop = function (index, skipCapture, force, opts) {
    // Mirror the crash site: boards / componentDidUpdate read `.id` here.
    const cur = this._desktops[this._clampedDesktopIndex()];
    assert.ok(cur && cur.id, 'active desktop must resolve while switching after delete');
    switchCall = {
      index, force, fromCar: opts && opts.fromCar,
      indexAtCall: this._desktopIndex,
    };
    this._desktopIndex = index;
    this.setState({ desktopIndex: index });
  };

  app._deleteDesktopAt(1);
  assert.equal(app._desktops.length, 1);
  assert.equal(app._desktops[0].id, 'a');
  assert.equal(app._desktopIndex, 0, 'index lands on the remaining neighbour');
  assert.equal(switchCall.indexAtCall, 0, 'index is already valid when switch runs');
  assert.equal(switchCall.fromCar, false, 'wallpaper desktop reported its own car flag');
  assert.equal(switchCall.force, true);
  assert.equal(app._clampedDesktopIndex(), 0);
  assert.equal(app._activeDesktopId(), 'a');

  // Deleting a non-active desktop keeps the live one by id.
  app._desktops = [desk('a'), desk('b'), desk('c')];
  app._desktopIndex = 1;
  let committed = null;
  app._commitDesktopListChange = function (activeId) {
    committed = activeId;
    const at = this._desktops.findIndex((d) => d.id === activeId);
    this._desktopIndex = at >= 0 ? at : 0;
  };
  app._deleteDesktopAt(2);
  assert.equal(committed, 'b');
  assert.equal(app._desktops.map((d) => d.id).join(','), 'a,b');
  assert.equal(app._desktopIndex, 1);

  // Defense: a stale -1 must still clamp for board/id reads.
  app._desktopIndex = -1;
  assert.equal(app._clampedDesktopIndex(), 0);
  assert.equal(app._activeDesktopId(), 'a');
}

check(html);
assert.throws(
  () => check(html.replace(
    /this\._desktopIndex = target;\s*this\._switchDesktop\(target, true, true, \{ fromCar: fromCar \}\);/,
    'this._desktopIndex = -1;\n    this._switchDesktop(target, true, true);',
  )),
  'negative control: -1 sentinel fails the contract',
);
assert.throws(
  () => check(html.replace(
    /_clampedDesktopIndex\(\)\s*\{[\s\S]*?\n  \}/,
    '_clampedDesktopIndex() { return this._desktopIndex; }',
  )),
  'negative control: unclamped index fails after stale -1',
);
console.log('PASS delete active desktop keeps live index + negative controls');

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
function harness(source, kind) {
  let now = 1, id = 0;
  const timers = new Map(), sent = [];
  const ctx = vm.createContext({ DCLogic: class {}, console,
    window: { TelemetryBridge: { invokeVehicleCommand: (...args) => sent.push(args) } },
    performance: { now: () => now },
    setTimeout: (fn, ms) => { timers.set(++id, { fn, at: now + ms }); return id; },
    clearTimeout: (key) => timers.delete(key) });
  vm.runInContext(source.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]
    + ';globalThis.App=Component;', ctx);
  const app = Object.create(ctx.App.prototype);
  app.state = { sunroofOpen: 0, curtainOpen: 0 };
  app._setSunroofOpen = (pct) => { app.state.sunroofOpen = pct; };
  app._setCurtainOpen = (pct) => { app.state.curtainOpen = pct; };
  app._syncEventCamera = () => {};
  const signal = (raw) => kind === 'sunroof' ? app._applyCarSunroof(raw)
    : app._applyCarOpening('_setCurtainOpen', raw, 100);
  const advance = (ms) => {
    const end = now + ms;
    for (;;) {
      const next = [...timers].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      const [key, timer] = next;
      now = timer.at; timers.delete(key); timer.fn();
    }
    now = end;
  };
  return { app, sent, signal, advance, level: () => app.state[kind + 'Open'],
    remaining: () => app._roofBusIgnore[kind].until - now };
}

function check(source) {
  for (const kind of ['sunroof', 'curtain']) {
    // Sunroof raw 20 maps to 40%; the curtain already reports percentages.
    const raw40 = kind === 'sunroof' ? 20 : 40;
    const raw70 = kind === 'sunroof' ? 60 : 70;
    {
      const h = harness(source, kind), { app, signal, advance } = h;
      signal(0);
      app._runVehicleOpeningAction(kind, 'open');
      signal(raw40);
      assert.equal(h.level(), 100, kind + ': open target survives travel echo');
      assert.equal(app._roofBusLevel[kind], 40, kind + ': latest telemetry advances during wait');
      assert.equal(app._roofGestureFrom[kind], 0, kind + ': command origin stays fixed');
      assert.equal(app._roofBusIgnore[kind].pending, 40, kind + ': mapped echo is buffered');
      app._runVehicleOpeningAction(kind, 'close');
      assert.equal(h.remaining(), 4800, kind + ': reversal covers 40 percent travel plus margin');
      assert.equal(app._roofBusIgnore[kind].pending, null, kind + ': new command clears old buffer');
      advance(401);
      assert.equal(h.level(), 0, kind + ': close does not jump back to old echo');
      assert.equal(app._isRoofBusIgnored(kind), true, kind + ': close wait exceeds latency alone');
      advance(4399);
      assert.equal(h.level(), 0, kind + ': timeout cannot replay old-command buffer');
      assert.equal(app._roofGestureFrom[kind], null, kind + ': timeout clears origin');
      assert.equal(app._roofSliderActive[kind], false, kind + ': timeout ends slider gesture');
      assert.deepEqual(h.sent, [['open_' + kind, ''], ['close_' + kind, '']], 'physical commands stay unchanged');
    }
    {
      const h = harness(source, kind), { app, signal, advance } = h;
      signal(0); app._runVehicleOpeningAction(kind, 'open'); signal(raw40);
      app._queueVehicleOpeningLevel(kind, 0);
      assert.equal(app._roofGestureFrom[kind], 40, kind + ': first drag replaces command origin');
      assert.equal(app._roofBusIgnore[kind].pending, null, kind + ': first drag clears command buffer');
      assert.equal(h.remaining(), 4800, kind + ': dragging closed during previous command uses latest bus');
      advance(500); // Debounce has sent, but the finger has not been released.
      assert.equal(h.level(), 0, kind + ': held drag stays closed beyond 400 ms');
      assert.equal(app._isRoofBusIgnored(kind), true, kind + ': held drag keeps travel wait');
      assert.equal(app._roofSliderActive[kind], true, kind + ': debounce does not end gesture');
      assert.deepEqual(h.sent.at(-1), ['set_' + kind + '_level', '0']);
      signal(raw70);
      assert.equal(h.level(), 0, kind + ': held drag ignores new echo');
      app._queueVehicleOpeningLevel(kind, 20);
      assert.equal(app._roofGestureFrom[kind], 40, kind + ': subsequent input keeps origin after debounce');
      assert.equal(h.remaining(), 2600, kind + ': subsequent input measures from fixed origin');
      assert.equal(app._roofBusIgnore[kind].pending, 70, kind + ': same drag retains its latest echo');
      advance(100);
      app._queueVehicleOpeningLevel(kind, 30);
      advance(139);
      assert.equal(h.sent.length, 2, kind + ': obsolete debounce is cancelled');
      advance(1);
      assert.deepEqual(h.sent.at(-1), ['set_' + kind + '_level', '30']);
      app._queueVehicleOpeningLevel(kind, 35);
      app._setVehicleOpeningLevel(kind, 20);
      assert.equal(app._roofSliderActive[kind], false, kind + ': release ends gesture');
      assert.equal(app._roofGestureFrom[kind], 70, kind + ': release uses latest telemetry');
      assert.equal(h.remaining(), 5900, kind + ': release recalculates travel');
      assert.equal(app._roofBusIgnore[kind].pending, null, kind + ': release clears earlier echo');
      const count = h.sent.length;
      advance(140);
      assert.equal(h.sent.length, count, kind + ': release cancels queued debounce');
      assert.deepEqual(h.sent.at(-1), ['set_' + kind + '_level', '20']);
      signal(raw40);
      app._queueVehicleOpeningLevel(kind, 80);
      assert.equal(app._roofGestureFrom[kind], 40, kind + ': second drag starts from latest bus');
      assert.equal(app._roofBusIgnore[kind].pending, null, kind + ': second drag clears earlier echo');
      advance(100);
      app._runVehicleOpeningAction(kind, 'close');
      assert.equal(app._roofSliderActive[kind], false, kind + ': button ends prior drag');
      const afterButton = h.sent.length;
      advance(140);
      assert.equal(h.sent.length, afterButton, kind + ': button cancels slider debounce');
      signal(raw70);
      app._queueVehicleOpeningLevel(kind, 100);
      assert.equal(app._roofGestureFrom[kind], 70, kind + ': drag after button takes a fresh origin');
      assert.equal(app._roofBusIgnore[kind].pending, null, kind + ': drag after button clears its buffer');
      signal(raw40);
      advance(3700);
      assert.equal(h.level(), 40, kind + ': timeout adopts current-gesture latest sample');
      assert.equal(app._roofSliderActive[kind], false, kind + ': timeout ends held gesture');
      signal(raw70);
      app._queueVehicleOpeningLevel(kind, 0);
      assert.equal(app._roofGestureFrom[kind], 70, kind + ': post-timeout input starts fresh');
      signal(-1); signal('invalid');
      assert.equal(app._roofBusLevel[kind], 70, kind + ': invalid telemetry does not replace last valid sample');
    }
  }
  const tilt = harness(source, 'sunroof');
  tilt.signal(200);
  assert.equal(tilt.level(), 25, 'tilt mapping remains intact');
  const both = harness(source, 'sunroof');
  both.signal(20);
  both.app._queueVehicleOpeningLevel('sunroof', 0);
  both.app._applyCarOpening('_setCurtainOpen', 70, 100);
  both.app._queueVehicleOpeningLevel('curtain', 0);
  both.app._runVehicleOpeningAction('curtain', 'open');
  both.app._queueVehicleOpeningLevel('sunroof', 20);
  assert.equal(both.app._roofGestureFrom.sunroof, 40, 'other roof kind does not end this drag');
  assert.equal(both.app._roofSliderActive.sunroof, true, 'gesture markers are per kind');
}

check(html);
const mutations = [
  ["this._applyCarOpening('_setSunroofOpen', pct, 100);", 'this._setSunroofOpen(pct);', /open target/],
  ['this._roofBusLevel[kind] = pct;', 'if (!this._isRoofBusIgnored(kind)) this._roofBusLevel[kind] = pct;', /latest telemetry/],
  ['entry.pending = pct;', 'entry.pending = null;', /mapped echo/],
  ['this._roofGestureFrom[kind] = fromPct;\n      entry.pending = null;', 'this._roofGestureFrom[kind] = fromPct;', /clears old buffer/],
  ['!dragging || !this._roofSliderActive[kind]', '!dragging', /first drag/],
  ['!dragging || !this._roofSliderActive[kind]', 'true', /subsequent input/],
  ['this._applyVehicleOpeningLevel(kind, raw, true)', 'this._applyVehicleOpeningLevel(kind, raw, false)', /debounce does not end/],
  ['this._applyVehicleOpeningLevel(kind, raw, false)', 'this._applyVehicleOpeningLevel(kind, raw, true)', /release ends/],
  ['this._roofSliderActive[kind] = false;', 'void 0;', /timeout ends|button ends/],
  ['this._clearVehicleOpeningLevel(kind);\n    // Settled', '// Settled', /release cancels/],
];
for (const [from, to, error] of mutations) {
  assert.ok(html.includes(from), 'negative-control target exists: ' + from);
  assert.throws(() => check(html.replaceAll(from, to)), error);
}
console.log('PASS roof/curtain telemetry, command reversals, held/repeated drags, debounce, timeout + negative controls');

import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const script = html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1];
const timers = [];
let clock = 1_000_000_000;
const context = vm.createContext({
  DCLogic: class {},
  window: {},
  performance,
  console,
  setTimeout,
  clearTimeout,
  Date,
});
vm.runInContext(script + ';globalThis.ClimateComponent=Component;globalThis.HVAC=CAR_HVAC;globalThis.RECIRC_GRACE=CLIMATE_PANEL_RECIRC_GRACE_MS;', context);

context.Date.now = () => clock;
context.setTimeout = (fn, ms) => {
  const timer = { fn, at: clock + (ms || 0) };
  timers.push(timer);
  return timer;
};
context.clearTimeout = (timer) => {
  const i = timers.indexOf(timer);
  if (i >= 0) timers.splice(i, 1);
};

function advance(ms) {
  clock += ms;
  for (let guard = 0; guard < 20; guard++) {
    const due = timers.filter((timer) => timer.at <= clock).sort((a, b) => a.at - b.at);
    if (!due.length) return;
    timers.splice(timers.indexOf(due[0]), 1);
    due[0].fn();
  }
  throw new Error('timer loop did not settle');
}

function resetTimers() {
  timers.length = 0;
}

const HVAC = context.HVAC;
const GRACE = context.RECIRC_GRACE;

function makeApp() {
  resetTimers();
  const app = Object.create(context.ClimateComponent.prototype);
  app._androidApp = true;
  app._demoPreview = false;
  app._climSchedulePaint = () => {};
  app._climateHandoffActive = true;
  app._isDeskSwitchingOrRecent = () => false;
  app.state = { focusedCardType: null };
  app.opened = 0;
  app.closed = 0;
  app.held = 0;
  app._openFocusedCard = () => {
    app.opened += 1;
    app.state.focusedCardType = 'climate';
  };
  app._closeFocusedCard = () => {
    app.closed += 1;
    app.state.focusedCardType = null;
    app._climatePanelOpened = false;
  };
  app._armClimatePanelHold = () => { app.held += 1; };
  app._climModel();
  // Boot snapshot. The first recirculation reading is recorded, then aged out
  // of the pulse window so it cannot masquerade as a later press.
  app._applyClimateSignal(HVAC.auto, '1');
  app._applyClimateSignal(HVAC.cycle, '1');
  app._applyClimateSignal(HVAC.fan, '3');
  app._applyClimateSignal(HVAC.inside, '24');
  clock += 10000;
  app.opened = 0;
  app.closed = 0;
  app.held = 0;
  return app;
}

// AUTO flips recirculation, and the panel pulse rides along. The popup stays down.
{
  const app = makeApp();
  app._applyClimateSignal(HVAC.cycle, '0');
  assert.equal(app._climModel().recirc, true, 'the recycle state itself still updates');
  clock += 40;
  app._onCarClimatePanelRequest('2');
  assert.equal(timers.length, 1, 'the pulse must wait on the test clock, not a real timer');
  assert.equal(app.opened, 0, 'a recycle pulse must not open before the grace');
  advance(GRACE);
  assert.equal(app.opened, 0, 'a recycle-only pulse must not open the climate popup');
  assert.equal(app.held, 0, 'a recycle-only pulse must not arm the auto-close hold');
}

// The echo can arrive after the notify. Still no popup.
{
  const app = makeApp();
  app._onCarClimatePanelRequest('2');
  clock += 30;
  app._applyClimateSignal(HVAC.cycle, '0');
  advance(GRACE);
  assert.equal(app.opened, 0, 'a recycle echo during the grace must cancel the open');
}

// The notify won the race and the popup came up; the late recycle echo puts it away.
{
  const app = makeApp();
  app._onCarClimatePanelRequest('2');
  advance(GRACE);
  assert.equal(app.opened, 1, 'a pulse with no control change still opens (the panel button)');
  app._applyClimateSignal(HVAC.cycle, '0');
  assert.equal(app.closed, 1, 'a recycle echo just after the open must retract it');
  assert.equal(app.state.focusedCardType, null);
}

// Negative control: fan, including together with recycle, still opens.
{
  const app = makeApp();
  app._applyClimateSignal(HVAC.fan, '5');
  app._applyClimateSignal(HVAC.cycle, '0');
  app._onCarClimatePanelRequest('2');
  assert.equal(app.opened, 1, 'a fan change on the same pulse must still open the popup');
}

{
  const app = makeApp();
  app._onCarClimatePanelRequest('2');
  assert.equal(app.opened, 0);
  app._applyClimateSignal(HVAC.fan, '6');
  assert.equal(app.opened, 1, 'a fan echo during the grace must open without waiting it out');
}

// Cabin temperature moves constantly and must not turn a recycle pulse into a press.
{
  const app = makeApp();
  app._applyClimateSignal(HVAC.inside, '25');
  app._applyClimateSignal(HVAC.cycle, '0');
  app._onCarClimatePanelRequest('2');
  advance(GRACE);
  assert.equal(app.opened, 0, 'inside temperature must not count as a reason to open');
}

// A popup the driver opened is not closed when recycle flips underneath it.
{
  const app = makeApp();
  app.state.focusedCardType = 'climate';
  app._climatePanelOpened = false;
  app._applyClimateSignal(HVAC.cycle, '0');
  app._onCarClimatePanelRequest('2');
  advance(GRACE);
  assert.equal(app.closed, 0, 'recycle must not dismiss a popup the user opened');
  assert.equal(app.state.focusedCardType, 'climate');
}

// The falling edge, and a pulse while the hand-off is off, stay quiet.
{
  const app = makeApp();
  app._onCarClimatePanelRequest('0');
  advance(GRACE);
  assert.equal(app.opened, 0, 'the trailing 0 must not open the popup');
  app._climateHandoffActive = false;
  app._onCarClimatePanelRequest('2');
  advance(GRACE);
  assert.equal(app.opened, 0, 'without the hand-off the car keeps its own popup');
}

console.log('climate panel recirculation gate: ok');

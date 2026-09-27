import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const script = html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1];
const context = vm.createContext({ DCLogic: class {}, window: {}, performance, console, setTimeout, clearTimeout });
vm.runInContext(script + ';globalThis.ClimateComponent=Component;globalThis.HVAC=CAR_HVAC;', context);

const HVAC = context.HVAC;

function makeApp() {
  const app = Object.create(context.ClimateComponent.prototype);
  // A car, not the browser stand-in: the model starts empty and is filled by
  // whatever the bus reports.
  app._androidApp = true;
  app._demoPreview = false;
  app._climSchedulePaint = () => {};
  app._climModel();
  return app;
}

// The car boots with both zones at 22 and SYNC on.
const app = makeApp();
app._applyClimateSignal(HVAC.sync, '1');
app._applyClimateSignal(HVAC.driver, '22.0');
app._applyClimateSignal(HVAC.pass, '22.0');
assert.equal(app._climModel().driver, 22);
assert.equal(app._climModel().pass, 22);

// Steering-wheel button / the car's own panel: the bus carries the driver
// setpoint and never re-reports the passenger one, even though both zones moved.
app._applyClimateSignal(HVAC.driver, '24.5');
assert.equal(app._climModel().driver, 24.5);
assert.equal(app._climModel().pass, 24.5,
  'with SYNC on, a driver setpoint from the car must carry the passenger zone with it');

// SYNC off: the zones are independent again, and the passenger keeps its own.
app._applyClimateSignal(HVAC.sync, '0');
app._applyClimateSignal(HVAC.pass, '19.0');
app._applyClimateSignal(HVAC.driver, '26.0');
assert.equal(app._climModel().pass, 19, 'with SYNC off the passenger must not follow the driver');
assert.equal(app._climModel().driver, 26);

// Turning SYNC on is itself the moment the zones converge.
app._applyClimateSignal(HVAC.sync, '1');
assert.equal(app._climModel().pass, 26, 'switching SYNC on pulls the passenger to the driver setpoint');

// The car still wins when it does report the passenger zone.
app._applyClimateSignal(HVAC.pass, '25.0');
assert.equal(app._climModel().pass, 25, 'an explicit passenger reading from the car is authoritative');

// A finger on the passenger wheel is never overwritten mid-gesture.
const held = makeApp();
held._applyClimateSignal(HVAC.sync, '1');
held._applyClimateSignal(HVAC.driver, '22.0');
held._climHold = { pass: true };
held._climPend.pass = { v: 30, at: Date.now(), staged: true };
held._applyClimateSignal(HVAC.driver, '23.0');
assert.equal(held._climView().pass, 30, 'a staged passenger value survives a driver signal');

console.log('climate sync mirror: ok');

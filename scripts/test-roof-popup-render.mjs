// An open roof popup must let the on-demand 3D scene go idle.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source = readFileSync(new URL('../index.html', import.meta.url), 'utf8');

function boot(html) {
  const ctx = vm.createContext({DCLogic:class{}, window:{React:{Component:class{}}}});
  const script = html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1];
  vm.runInContext(script + ';globalThis.App=Component;', ctx);
  const app = Object.create(ctx.App.prototype);
  const rect = {left:0, top:0, width:1920, height:720};
  const classList = {remove(){}, toggle(){}};
  app.camera = {getWorldDirection(){}};
  app.renderer = {domElement:{getBoundingClientRect:() => rect}};
  app.THREE = {Vector3:class{}};
  app._hsLayer = {classList, getBoundingClientRect:() => rect};
  app._hsEls = {roof:{classList}};
  app._hsRoofPopOpen = true;
  app._hotspotsVisible = false;
  app._renderFrames = 0;
  app._shouldShowCar = () => true;
  app._closeFocusedCard = () => {};
  app._syncRoofLevelPopup = () => {};
  const h = {app, requests:[], hides:0};
  app.requestRender = (n = 3) => {
    h.requests.push(n);
    app._renderFrames = Math.max(app._renderFrames || 0, n);
    app._postFxDirty = true;
  };
  app._hideHotspots = () => { h.hides++; };
  return h;
}

function check(html) {
  const {app, requests} = boot(html);
  for (const visible of [false, true]) {
    app._hotspotsVisible = visible;
    for (let i = 0; i < 120; i++) app._updateHotspotLayer();
    assert.equal(requests.length, 0, 'a still camera and open roof popup request no frames');
    assert.equal(app._renderFrames, 0, 'hotspot updates do not replenish the frame budget');
  }
  app._hsRoofPopOpen = false;
  app._openRoofLevelPopup();
  assert.ok(requests.length > 0 && app._renderFrames > 0, 'opening the roof popup still requests frames');

  const hidden = boot(html);
  hidden.app._shouldShowCar = () => false;
  hidden.app._updateHotspotLayer();
  assert.equal(hidden.hides, 1, 'the layer still hides when the car leaves the screen');
}

// The mutation must apply before checking that the broken contract fails.
const mutate = (from, to) => {
  assert.ok(source.includes(from), 'control anchor not found: ' + from.slice(0, 60));
  return source.replace(from, () => to);
};
check(source);
for (const [from, to, label] of [
  ['    // The roof popup is fixed DOM;',
    '    if (this._hsRoofPopOpen && this.requestRender) this.requestRender(2);\n    // The roof popup is fixed DOM;',
    'per-frame rendering returns'],
  ['    this._setRoofLevelPopup(true);\n    if (this.requestRender) this.requestRender(6);',
    '    this._setRoofLevelPopup(true);', 'opening no longer requests frames'],
  ['      if (this._hotspotsVisible || this._hsRoofPopOpen) this._hideHotspots();\n      return;',
    '      return;', 'hidden car no longer hides the layer'],
]) {
  const mutated = mutate(from, to);
  assert.throws(() => check(mutated), label);
}
console.log('PASS roof popup render demand: idle updates, opening frames, hidden-car cleanup + negative controls');

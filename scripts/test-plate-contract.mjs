import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

// Licence plate (Mercosul): text rules, persistence, placeholders and the UI
// contract. The 3D placement itself is verified in the browser (index.html
// ?debug=1 → __app._setPlateText(...) → camera presets front/rear).
const html = fs.readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const script = html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1];
const context = vm.createContext({ DCLogic: class {}, window: {}, performance, console, setTimeout, clearTimeout });
vm.runInContext(script + ';globalThis.C=Component;globalThis.sanitize=h6SanitizePlate;globalThis.format=h6PlateFormat;globalThis.PH=H6_PLATE_PLACEHOLDERS;', context);
const { sanitize, format, PH } = context;

// Text rules: what a plate can carry, nothing else.
assert.equal(sanitize(' raf-1e23 '), 'RAF1E23');
assert.equal(sanitize('abc·1234'), 'ABC1234');
assert.equal(sanitize('HAV4L064567'), 'HAV4L06', 'never more than seven characters');
assert.equal(sanitize(null), '');
assert.equal(format('RAF1E23'), 'mercosul');
assert.equal(format('RAF1223'), 'legacy', 'LLLNNNN is the pre-2018 layout, not Mercosul');
assert.equal(format('ABC1234'), 'legacy');
assert.equal(format('AB1'), 'partial');
assert.equal(format(''), 'empty');
assert.equal(format('1234567'), 'partial');

assert.equal(format('HAV4L06'), 'mercosul');

// Persistence round trip through the settings blob.
const app = Object.create(context.C.prototype);
app.state = { plateText: '' };
app.setState = (patch, cb) => { Object.assign(app.state, patch); if (cb) cb(); };
app._repaintLicensePlates = () => { app._repainted = (app._repainted || 0) + 1; };
const store = {};
context.window.localStorage = { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); } };
globalThis.localStorage = context.window.localStorage;
vm.runInContext('globalThis.localStorage = window.localStorage;', context);
store.h6_settings_v1 = JSON.stringify({ modelTrim: 'hev' });
app._setPlateText('raf1e23', true);
assert.equal(app.state.plateText, 'RAF1E23');
assert.equal(app._repainted, 1, 'a new text repaints the plates');
assert.equal(JSON.parse(store.h6_settings_v1).plateText, 'RAF1E23', 'persisted next to the other settings');
app._setPlateText('RAF1E23', true);
assert.equal(app._repainted, 1, 'same text does not repaint');
assert.equal(app._plateHint('RAF1E23'), 'PADRÃO MERCOSUL');
assert.equal(app._plateHint('ABC1234'), 'FORMATO ANTIGO');
assert.equal(app._plateHint('AB'), 'INCOMPLETA');
assert.equal(app._plateHint(''), 'OPCIONAL');

// The input handler cleans what the user typed, in place.
const el = { value: 'raf-1e23x' };
app._onPlateInput({ target: el });
assert.equal(el.value, 'RAF1E23');

// Placeholders: the names this hangs plates on must exist in the shipped GLBs.
// Checked by string presence in the binary (node names are stored verbatim).
for (const [body, file] of [['hev', 'haval-h6-hev-lite.glb'], ['gt', 'haval-h6-gt-lite.glb']]) {
  const bin = fs.readFileSync(new URL('../assets/' + file, import.meta.url), 'latin1');
  for (const side of ['front', 'rear']) {
    assert.ok(bin.includes('"' + PH[body][side] + '"'), `${file} carries node ${PH[body][side]} for the ${side} plate`);
  }
}

// UI contract: both entry points carry the field and the saved blob the key.
assert.match(html, /hv-first-run-label">PLACA</, 'first-run setup asks for the plate');
assert.match(html, /hv-config-section-label">PLACA/, 'Tuning panel exposes the plate');
assert.equal((html.match(/class="hv-plate-input"/g) || []).length, 2);
assert.match(html, /plateText: h6SanitizePlate\(s\.plateText \|\| ''\),/, 'saveSettings stores the plate');
assert.match(html, /this\._installLicensePlates\(obj, profile\);/, 'plates are installed when a body loads');
assert.match(html, /plateText: 'HAV4L06',/, 'the app default plate is HAV4L06');

assert.match(html, /class="hv-plate-off \{\{ plateOffClass \}\}"/, 'both plate rows offer a no-plate toggle');
assert.equal((html.match(/aria-label="Sem placa">SEM PLACA/g) || []).length, 2);
assert.match(html, /plateOn: s\.plateOn !== false/, 'saveSettings stores whether the plate is shown');
assert.match(html, /max-width:50%/, 'the plate field gives half its width to the toggle');

const plate = { visible: true, userData: { h6Plate: {} }, parent: { isMesh: true, visible: true } };
app.scene = { traverse: (fn) => fn(plate) };
app.requestRender = () => {};
app.state.plateOn = true;
app._setPlateOn(false);
assert.equal(app.state.plateOn, false);
assert.equal(plate.visible, false, 'the Mercosul overlay hides');
assert.equal(plate.parent.visible, true, 'the 1.0.1 black plastic slab stays');
assert.equal(JSON.parse(store.h6_settings_v1).plateOn, false);
app._setPlateOn(true);
assert.equal(plate.visible, true);
assert.equal(plate.parent.visible, true);
app._setPlateOn(true);
assert.equal(app.state.plateOn, true, 'turning it on twice does not toggle back off');

console.log('Plate contract: sanitising, formats, persistence, repaint, placeholders in both GLBs and UI entry points OK');

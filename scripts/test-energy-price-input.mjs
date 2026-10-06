import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runBrowserTest } from './browser-test.mjs';

const source = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const support = readFileSync(new URL('../support.js', import.meta.url), 'utf8');
const react = readFileSync(new URL('../vendor/react/react-18.3.1.production.min.js', import.meta.url), 'utf8');
const reactDom = readFileSync(new URL('../vendor/react/react-dom-18.3.1.production.min.js', import.meta.url), 'utf8');

async function browserCheck(source, support, react, reactDom) {
  const equal = (actual, expected, message) => {
    if (actual !== expected) throw new Error(message + ': expected ' + expected + ', got ' + actual);
  };
  (0, eval)(react);
  (0, eval)(reactDom);
  (0, eval)(support);
  await Promise.resolve();

  function check(html) {
    const logic = html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1];
    const App = new Function('DCLogic', logic + ';return Component;')(class {});
    const app = Object.create(App.prototype);
    app.state = {};
    app.__host = {};
    app._energyPricesCache = { fuel: null, kwh: null, paid: {}, skipped: {} };
    // The real view bindings and input markup, compiled with the real template runtime.
    const fields = html.match(/      consumptionCfgFuelPrice:([\s\S]*?)      consumptionCfgAskOnClass:/)[1];
    const view = new Function('return { consumptionCfgFuelPrice:' + fields + '};');
    const raw = [...html.matchAll(/<input\b[^>]*class="hv-energy-price"[^>]*>/g)].map(m => m[0]).join('');
    equal((raw.match(/<input/g) || []).length, 2, 'both price fields present');
    const template = document.createElement('template');
    template.id = 'price-input-probe';
    template.innerHTML = raw;
    document.body.appendChild(template);
    const host = document.createElement('div');
    document.body.appendChild(host);
    let root = ReactDOM.createRoot(host);
    const memo = app._memoTemplate(template.id, 'PriceInputs');
    function render() {
      const normal = view.call(app), focused = {};
      Object.keys(normal).forEach(k => { focused['focused' + k[0].toUpperCase() + k.slice(1)] = normal[k]; });
      root.render(React.createElement(memo.component, { view: focused }));
    }
    app._uiOnlySetState = patch => {
      Object.assign(app.state, typeof patch === 'function' ? patch(app.state) : patch);
      render();
    };
    ReactDOM.flushSync(render);
    const [fuel, kwh] = host.querySelectorAll('input');
    const stored = () => JSON.parse(localStorage.getItem('h6_energy_prices'));
    const setNativeValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    function edit(input, text) {
      input.focus();
      ReactDOM.flushSync(() => {
        setNativeValue.call(input, text);
        input.dispatchEvent(new Event('input', { bubbles: true }));
      });
      equal(input.value, text, 'typed text survives controlled rerender');
    }
    function blur(input) { ReactDOM.flushSync(() => input.blur()); }
    try {
      // Each value is what a real keyboard leaves after the next keystroke.
      for (const text of ['0', '0,', '0,9', '0,95']) edit(kwh, text);
      equal(stored().kwh, 0.95, 'sub-real comma price saved');
      equal(app._energyPrices().fuel, null, 'electricity edit leaves fuel alone');
      blur(kwh);
      equal(kwh.value, '0,95', 'electricity formats after blur');
      for (const text of ['6', '6,', '6,2', '6,29']) edit(fuel, text);
      equal(stored().fuel, 6.29, 'fuel cents saved');
      equal(stored().kwh, 0.95, 'fuel edit leaves electricity alone');
      edit(fuel, '6,2');
      ReactDOM.flushSync(() => app._uiOnlySetState({ unrelated: true }));
      equal(fuel.value, '6,2', 'unrelated UI update keeps unfinished text');
      blur(fuel);
      equal(fuel.value, '6,20', 'blur formats a single decimal');
      edit(fuel, '');
      equal(stored().fuel, null, 'clearing removes default');
      for (const text of ['0', '0.', '0.8', '0.85']) edit(fuel, text);
      blur(fuel);
      equal(fuel.value, '0,85', 'dot separator accepted and formatted');
      equal(stored().fuel, 0.85, 'dot separator saved');
      edit(fuel, '7.35'); // Selection/replacement or paste.
      blur(fuel);
      equal(fuel.value, '7,35', 'whole-field replacement accepted');
      edit(fuel, '7,3');
      edit(fuel, '7,');
      edit(fuel, '7');
      edit(fuel, '');
      blur(fuel);
      equal(fuel.value, '', 'backspace can clear the complete field');
      edit(fuel, '6,4');
      ReactDOM.flushSync(() => root.unmount()); // Popup closes without a blur event.
      equal(stored().fuel, 6.4, 'closing without blur keeps the latest price');
      root = ReactDOM.createRoot(host);
      ReactDOM.flushSync(render);
      const reopenedFuel = host.querySelector('input');
      equal(reopenedFuel.value, '6,4', 'reopening retains the unfinished edit');
      reopenedFuel.focus();
      blur(reopenedFuel);
      equal(reopenedFuel.value, '6,40', 'reopened field formats when focus leaves');
      app._energyPricesCache = null;
      app._energyPriceDrafts = null;
      equal(app._energyDefaultPriceText('fuel'), '6,40', 'saved value restores after a page reload');
    } finally {
      ReactDOM.flushSync(() => root.unmount());
      host.remove();
      template.remove();
    }
  }

  check(source);
  const mutants = [
    ["return drafts && Object.prototype.hasOwnProperty.call(drafts, kind)", "return false && Object.prototype.hasOwnProperty.call(drafts, kind)"],
    ["consumptionCfgFuelPrice: this._energyDefaultPriceText('fuel')", "consumptionCfgFuelPrice: this._energyDefaultPriceText('kwh')"],
    ["consumptionCfgKwhPriceChange: (e) => this._setEnergyDefaultPrice('kwh'", "consumptionCfgKwhPriceChange: (e) => this._setEnergyDefaultPrice('fuel'"],
    ['onBlur="{{ focusedConsumptionCfgFuelPriceBlur }}"', ''],
    ['this._energyPriceDrafts[kind] = String(text);', "this._energyPriceDrafts[kind] = String(text).slice(0, -1);"],
    ["localStorage.setItem('h6_energy_prices', JSON.stringify(this._energyPricesCache))", 'void 0'],
  ];
  for (const [from, to] of mutants) {
    equal(source.includes(from), true, 'negative control target exists');
    localStorage.clear();
    let error;
    try { check(source.replace(from, to)); } catch (e) { error = e; }
    equal(!!error, true, 'negative control must fail: ' + from);
  }
  return { mutants: mutants.length };
}

const result = runBrowserTest(browserCheck, source, support, react, reactDom);
assert.equal(result.mutants, 6);
console.log('PASS energy price inputs: real React/template typing, comma/dot cents, clear, replacement, blur, rerender and reopen; 6 negative controls');

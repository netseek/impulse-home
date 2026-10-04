import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runBrowserTest } from './browser-test.mjs';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const support = readFileSync(new URL('../support.js', import.meta.url), 'utf8');
const logic = html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1];
const templates = [...html.matchAll(/<template\b[^>]*>([\s\S]*?)<\/template>/g)];
assert.ok(templates.length, 'real memoized templates exist');
const bindings = templates.flatMap(([, source]) =>
  [...source.matchAll(/\s(on\w+)="(\{\{[^"]+\}\})"/g)].map(([, name, value]) => [name, value]));
assert.ok(bindings.length, 'real template event bindings exist');

// Use the browser's real HTML parser and the complete vendored compiler.
// Only React element creation is recorded, so props/functions can be inspected.
async function browserCheck(support, logic, bindings) {
  const equal = (actual, expected, message) => {
    if (actual !== expected) throw new Error(message);
  };
  const events = [
    'onClick', 'onChange', 'onInput', 'onSubmit', 'onKeyDown', 'onKeyUp', 'onKeyPress',
    'onMouseDown', 'onMouseUp', 'onMouseEnter', 'onMouseLeave', 'onMouseMove', 'onMouseOver', 'onMouseOut',
    'onFocus', 'onBlur', 'onDoubleClick', 'onContextMenu',
    'onTouchStart', 'onTouchMove', 'onTouchEnd', 'onTouchCancel',
    'onPointerDown', 'onPointerMove', 'onPointerUp', 'onPointerCancel',
    'onPointerEnter', 'onPointerLeave', 'onPointerOver', 'onPointerOut',
    'onGotPointerCapture', 'onLostPointerCapture',
  ];
  const rows = [...bindings, ...events.map((name) => [name, '{{ handlers.' + name + ' }}'])];
  const view = {}, handlers = new Map();
  let calls = 0;
  for (const [name, binding] of rows) {
    equal(events.includes(name), true, 'cover template event ' + name);
    if (handlers.has(binding)) continue;
    const path = binding.slice(2, -2).trim().split('.');
    const key = path.pop();
    const parent = path.reduce((obj, part) => obj[part] || (obj[part] = {}), view);
    const handler = (event) => { calls++; return event; };
    parent[key] = handler;
    handlers.set(binding, handler);
  }
  const raw = rows.map(([name, binding], i) =>
    '<button data-probe="' + i + '" ' + name + '="' + binding + '"></button>').join('');
  const template = document.createElement('template');
  template.id = 'event-probe';
  template.innerHTML = raw;
  document.body.appendChild(template);
  equal(template.innerHTML.includes('ontouchstart='), true, 'DOM really lowercases event attributes');

  window.React = {
    Component: class {},
    createContext: () => ({}),
    Fragment: 'fragment',
    memo: (fn) => fn,
    createElement: (type, props, ...children) => ({ type, props, children }),
  };
  window.ReactDOM = {};
  const App = new Function('DCLogic', logic + ';return Component;')(class {});
  async function check(source) {
    (0, eval)(source);
    await Promise.resolve(); // Complete the runtime's normal init().
    const app = Object.create(App.prototype);
    app.__host = {};
    window.__dcUpdate('RawEvents', 'html', raw, false);
    const memo = app._memoTemplate(template.id, 'MemoEvents');
    equal(app._memoTemplate(template.id, 'MemoEvents').component, memo.component, 'memo component identity');
    const outputs = [
      ['raw HTML', window.__dcRegistry.RawEvents.tpl(view, app.__host)],
      ['template.innerHTML', memo.component({ view })],
    ];
    for (const [path, output] of outputs) {
      const nodes = [];
      function visit(node) {
        if (Array.isArray(node)) return node.forEach(visit);
        if (!node) return;
        if (node.props && node.props['data-probe'] != null) nodes.push(node);
        if (node.children) visit(node.children);
      }
      visit(output);
      equal(nodes.length, rows.length, path + ': every event node compiles');
      nodes.forEach((node, i) => {
        const [name, binding] = rows[i];
        const keys = Object.keys(node.props).filter((key) => key.startsWith('on'));
        equal(keys.join(','), name, path + ': prop name ' + name);
        equal(node.props[name], handlers.get(binding), path + ': handler identity ' + name);
        const event = { type: name, probe: i };
        const before = calls;
        equal(node.props[name](event), event, path + ': handler receives event ' + name);
        equal(calls, before + 1, path + ': handler called once ' + name);
      });
    }
  }
  await check(support);
  // Every explicit event mapping is broken separately. The normalized path
  // must fail on the expected prop, while the raw camel-case path still works.
  for (const name of events) {
    const entry = name.toLowerCase() + ': "' + name + '"';
    equal(support.includes(entry), true, 'mapping exists: ' + name);
    const broken = support.replace(entry, name.toLowerCase() + ': "brokenEvent"');
    let error;
    try { await check(broken); } catch (e) { error = e; }
    equal(!!error && error.message === 'template.innerHTML: prop name ' + name,
      true, 'negative control detects normalized ' + name + ': ' + (error?.message || 'no failure'));
  }
  return { bindings: bindings.length, events: events.length, paths: 2 };
}

const result = runBrowserTest(browserCheck, support, logic, bindings);
console.log('PASS real template compiler: ' + result.bindings + ' template bindings, '
  + result.events + ' event mappings, raw HTML + memoized DOM, prop names/identity/calls + negative controls');

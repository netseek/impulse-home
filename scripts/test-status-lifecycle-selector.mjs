/* Guards the lifecycle selector in test-status-card-contract.mjs. That test must
 * inspect the root component's componentWillUnmount, not the nested DesktopStage
 * one that also contains the substring. Needs no asset bundle: it reads index.html
 * and the contract test, then runs the contract's real block helper, selector and
 * the two timer-cleanup assertions against the real and mutated sources. */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const read = (p) => readFileSync(resolve(root, p), 'utf8').replace(/\r\n/g, '\n');
const html = read('index.html');
const contract = read('scripts/test-status-card-contract.mjs');

const helper = contract.match(/^function block\(source, token\) \{\n[\s\S]*?\n\}\n/m);
assert.ok(helper, 'contract helper block() not found');
const selector = contract.match(/^const unmount = block\(html, (.+)\);$/m);
assert.ok(selector, 'contract unmount selector not found');
const checks = [...contract.matchAll(/^assert\.match\(unmount, [\s\S]*?\);$/gm)].map((m) => m[0]);
assert.equal(checks.length, 2, 'expected the two unmount cleanup assertions');

// Runs the contract's own helper, selector and assertions on `source`.
function run(source, selectorExpr = selector[1]) {
  new Function('assert', 'html', `${helper[0]}\nconst unmount = block(html, ${selectorExpr});\n${checks.join('\n')}`)(assert, source);
}

// The nested lifecycle must stay in the HTML, otherwise this guard proves nothing.
assert.ok(/^ {3,}componentWillUnmount\(\)/m.test(html), 'nested DesktopStage lifecycle missing from index.html');

const rootStart = html.indexOf('\n  componentWillUnmount(');
assert.ok(rootStart >= 0, 'root componentWillUnmount missing');
function mutateRoot(from, to) {
  const open = html.indexOf('{', rootStart);
  let depth = 0, end = open;
  for (; end < html.length; end++) {
    if (html[end] === '{') depth++;
    if (html[end] === '}' && --depth === 0) break;
  }
  const body = html.slice(rootStart, end + 1);
  assert.ok(body.includes(from), `root unmount lacks ${from}`);
  return html.slice(0, rootStart) + body.replace(from, to) + html.slice(end + 1);
}

run(html);                                                     // new selector passes
assert.throws(() => run(html, "'  componentWillUnmount('"),    // old selector fails
  /unmount must clear the demo door timer/, 'reverting the selector must fail');
assert.throws(() => run(mutateRoot('clearInterval(this._demoDoorTicker)', '')),
  /unmount must clear the demo door timer/, 'dropping root clearInterval must fail');
assert.throws(() => run(mutateRoot('this._demoDoorTicker = 0', '')),
  /unmount must reset the demo door timer handle/, 'dropping root handle reset must fail');
console.log('status lifecycle selector ok');

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('./snapshot-readability.mjs', import.meta.url), 'utf8');
const helpers = source.slice(source.indexOf('  const rgb ='), source.indexOf('  const nodes = []'));
const panel = (color, parent = null, image = 'none') => ({
  parentElement: parent, style: { backgroundColor: color, backgroundImage: image },
  getRootNode: () => ({}) });
function check(code) {
  const context = { getComputedStyle: (el) => el.style };
  vm.runInNewContext(code + ';globalThis.measure = localContrast;globalThis.parse = rgb;', context);
  const white = panel('rgb(255, 255, 255)');
  assert.equal(context.measure(white, [0,0,0]).ratio, 21, 'black on white reference');
  assert.equal(context.measure(panel('rgb(0, 0, 0)'), [255,255,255]).ratio, 21, 'white on black reference');
  const layered = context.measure(panel('rgba(0, 0, 0, 0.5)', white), [0,0,0]);
  assert.equal(layered.ratio, 5.28, 'half black over white is composed before contrast');
  assert.deepEqual(Array.from(layered.backing), [128,128,128]);
  assert.equal(context.measure(panel('rgba(0, 0, 0, 0.5)'), [255,255,255]), null, 'unknown backing is not credited');
  assert.equal(context.measure(panel('rgb(255, 255, 255)', null, 'linear-gradient(black,white)'), [0,0,0]), null, 'gradient needs image QA');
  assert.equal(context.parse('rgba(1, 2, 3, 0.45)')[3], .45, 'foreground alpha is preserved');
}
check(helpers);
const broken = helpers.replace('backing[i] * (1 - top[3])', '0');
assert.notEqual(broken, helpers, 'negative control removes underlying color');
assert.throws(() => check(broken), /composed before contrast/);
console.log('PASS local contrast reference pairs, alpha composition, unknown backing + negative control');

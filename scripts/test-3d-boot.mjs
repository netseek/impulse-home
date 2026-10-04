import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
async function check(source) {
  assert.doesNotMatch(source, /window\.HAVAL_3D_DISABLED\s*=\s*true/, 'normal builds enable 3D');
  const preload = source.split('/* Boot GLB preload.')[1].split('</script>')[0];
  const code = preload.slice(preload.indexOf('(function () {'));
  for (const model of ['hev', 'gt']) {
    const window = {}, requests = [], buffer = new ArrayBuffer(4);
    vm.runInNewContext(code, { window, URLSearchParams,
      location: { search: '?model=' + model, hash: '' },
      fetch: async (url) => {
        requests.push(url);
        return { ok: true, headers: { get: () => '4' }, arrayBuffer: async () => buffer };
      },
    });
    assert.equal(requests.length, 1, 'boot starts the model preload');
    assert.ok(requests[0].includes('haval-h6-' + model + '-lite.glb'));
    await window.__bootGlb.promise;
    assert.equal(window.__bootGlb.buffer, buffer, 'the loader can reuse the fetched model');
  }
  const ctx = vm.createContext({ DCLogic: class {}, window: {} });
  vm.runInContext(source.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]
    + ';globalThis.App=Component;', ctx);
  const app = Object.create(ctx.App.prototype);
  for (const fill of ['car', 'mixed', 'wallpaper']) {
    app.state = { shellMode: 'appCar', centerFill: fill };
    assert.equal(app._shouldShowCar(), fill !== 'wallpaper', 'desktop controls car visibility');
  }
  app.state = { shellMode: 'appsOnly', centerFill: 'car' };
  assert.equal(app._shouldShowCar(), false);
  const boot = ctx.App.prototype.componentDidMount.toString();
  assert.match(boot, /new THREE\.WebGLRenderer\(/, 'boot creates the renderer');
  assert.match(boot, /this\._bindDesktopGestures\(renderer\.domElement\)/, '3D keeps desktop gestures');
}
await check(html);
await assert.rejects(() => check(html.replace('state.promise = fetch(url)', 'return; state.promise = fetch(url)')));
await assert.rejects(() => check(html.replace('new THREE.WebGLRenderer(', 'new MissingRenderer(')));
await assert.rejects(() => check(html.replace("if (fill === 'wallpaper') return false;", 'return false;')));
console.log('PASS model preload/reuse, renderer boot and per-desktop visibility + negative controls');

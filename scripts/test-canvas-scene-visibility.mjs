import { readFileSync } from 'node:fs';
import { runBrowserTest } from './browser-test.mjs';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
async function browserCheck(source) {
  const equal = (actual, expected, message) => {
    if (actual !== expected) throw new Error(message);
  };
  async function check(source) {
    const logic = source.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1];
    const App = new Function('DCLogic', logic + ';return Component;')(class {});
    const app = Object.create(App.prototype);
    const host = document.createElement('div'), canvas = document.createElement('canvas');
    host.style.cssText = 'position:fixed;left:0;top:0;width:40px;height:40px;opacity:.8;visibility:visible';
    canvas.width = canvas.height = 40;
    host.appendChild(canvas);
    document.body.appendChild(host);
    const ctx = canvas.getContext('2d');
    let rendered = 0, queued = 0, gestures = 0;
    app.state = { loading: false, shellMode: 'appCar', centerFill: 'car' };
    app.containerRef = { current: host };
    app.model = { visible: true };
    app.camera = {};
    app.controls = { enabled: true };
    app._introPlayed = true;
    app._kickShadowRefresh = () => {};
    app.renderer = { domElement: canvas, shadowMap: {}, render() {
      rendered++;
      ctx.fillStyle = rendered % 2 ? 'red' : 'blue';
      ctx.fillRect(0, 0, 40, 40);
    } };
    canvas.addEventListener('pointerdown', () => gestures++);
    // Execute the real frame request and suspension code. Replace only the
    // expensive 3D drawing boundary with a canvas painter, not the visibility logic.
    const request = source.match(/this\.requestRender = \(n = 3, keepPostFx\) => \{[\s\S]*?\n      \};/)[0];
    new Function('app', '(function(){' + request + '}).call(app);')(app);
    const prefix = source.split('const loop = () => {')[1]
      .split('// Motion: advance reflection flow offset + roll wheels')[0];
    const frame = new Function('app', 'requestAnimationFrame', 'loop', '_lastT',
      '(function(){' + prefix
      + 'if(this._renderFrames > 0){this._renderFrames--;this.renderer.render();}}).call(app);');
    const tick = () => frame(app, () => ++queued, () => {}, performance.now());
    const pixels = () => Array.from(ctx.getImageData(0, 0, 1, 1).data).join(',');
    app._setCarSceneVisible(true);
    tick();
    equal(rendered, 1, 'visible car draws initial frame');

    for (const wallpaper of ['absent', 'failed']) {
      let img;
      if (wallpaper === 'failed') {
        img = document.createElement('img');
        img.src = 'data:image/png;base64,invalid';
        document.body.prepend(img);
        await img.decode().then(() => { throw new Error('invalid wallpaper unexpectedly decoded'); }, () => {});
        equal(img.naturalWidth, 0, 'wallpaper has actually failed');
      }
      app.state.centerFill = 'wallpaper';
      const before = rendered, lastPixels = pixels();
      app._setCarSceneVisible(true);
      equal(rendered, before, 'hide happens before another frame');
      equal(canvas.style.opacity, '0', wallpaper + ': stale frame is hidden synchronously');
      equal(pixels(), lastPixels, 'last pixels remain but cannot composite');
      equal(host.style.opacity, '0.8', 'host opacity is preserved');
      equal(host.style.visibility, 'visible', 'host visibility is preserved');
      equal(host.style.pointerEvents, 'auto', 'wallpaper host still accepts input');
      equal(getComputedStyle(canvas).display, 'inline', 'canvas is not removed from layout');
      equal(getComputedStyle(canvas).visibility, 'visible', 'canvas is not hidden from hit testing');
      equal(document.elementFromPoint(20, 20), canvas, 'transparent canvas receives wallpaper gestures');
      const beforeGesture = gestures;
      document.elementFromPoint(20, 20).dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
      equal(gestures, beforeGesture + 1, 'canvas gesture handler is still callable');
      const beforeQueue = queued;
      tick(); tick();
      equal(rendered, before, 'hidden scene does no drawing');
      equal(queued, beforeQueue + 2, 'hidden scene keeps lightweight rAF alive');
      for (const fill of ['car', 'mixed']) {
        app._renderFrames = 0;
        app.state.centerFill = fill;
        app._setCarSceneVisible(true);
        equal(canvas.style.opacity, '', fill + ': canvas visibility is restored');
        equal(app.model.visible, true, fill + ': model is visible');
        equal(app._renderFrames > 0, true, fill + ': return requests fresh frames');
        const beforeFrame = rendered;
        tick();
        equal(rendered, beforeFrame + 1, fill + ': new frame reaches drawing boundary');
        app.state.centerFill = 'wallpaper';
        app._setCarSceneVisible(true);
      }
      if (img) img.remove();
    }
    for (const mirrored of [false, true]) {
      app.state.shellMode = mirrored ? 'appCar' : 'appsOnly';
      app._shellMode = mirrored ? 'appsOnly' : null;
      app.state.centerFill = 'mixed';
      app._setCarSceneVisible(true);
      equal(canvas.style.opacity, '0', 'appsOnly hides cached pixels');
      equal(host.style.pointerEvents, 'none', 'appsOnly host does not intercept input');
      const before = rendered;
      tick();
      equal(rendered, before, 'appsOnly suspends scene work');
      app._shellMode = null;
      app.state.shellMode = 'appCar';
      app._setCarSceneVisible(true);
      equal(canvas.style.opacity, '', 'return from appsOnly restores canvas');
      equal(host.style.pointerEvents, 'auto', 'return restores background input');
      tick();
      equal(rendered, before + 1, 'return from appsOnly draws a new frame');
    }
    app.state.centerFill = 'wallpaper';
    app.containerRef = null;
    app._syncCanvasPointerEvents();
    equal(canvas.style.opacity, '0', 'renderer visibility is independent of missing host ref');
    app.renderer = null;
    app._syncCanvasPointerEvents(); // Safe before renderer/host attachment.
    host.remove();
  }
  await check(source);
  for (const [from, to, message] of [
    ["canvas.style.opacity = this._shouldShowCar() ? '' : '0';", 'void 0;', 'stale frame'],
    ["canvas.style.opacity = this._shouldShowCar() ? '' : '0';", "canvas.style.opacity = '0';", 'visibility is restored'],
    ["el.style.pointerEvents = mode === 'appsOnly' ? 'none' : 'auto';", "el.style.pointerEvents = 'none';", 'wallpaper host'],
    ['if (!this.state.loading && !this._shouldShowCar()) return;', '', 'does no drawing'],
  ]) {
    equal(source.includes(from), true, 'negative-control target exists');
    let error;
    try { await check(source.replace(from, to)); } catch (e) { error = e; }
    equal(!!error && error.message.includes(message), true, 'negative control: ' + message);
    document.body.replaceChildren();
  }
  return {};
}
runBrowserTest(browserCheck, html);
console.log('PASS real canvas visibility before rendering, missing/failed wallpaper, gestures, appsOnly, car/mixed resume + negative controls');

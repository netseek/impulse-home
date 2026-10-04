import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
function check(source) {
  const ctx = vm.createContext({ DCLogic: class {}, window: {} });
  vm.runInContext(source.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]
    + ';globalThis.App=Component;', ctx);
  const app = Object.create(ctx.App.prototype), callbacks = [], commits = [], indicators = [];
  app.state = { centerFill: 'car', wallpaperPopupOpen: false };
  app._introPlayed = true;
  app.setState = (patch, done) => {
    Object.assign(app.state, typeof patch === 'function' ? patch(app.state) : patch);
    commits.push({ ...app.state });
    if (done) callbacks.push(done);
  };
  for (const name of ['_ensureWallpapers', '_applyBackground', '_syncDockIndicators',
    '_kickShadowRefresh', 'requestRender', 'saveSettings']) app[name] = () => {};
  app._syncWallpaperLayer = () => app._syncCarSceneForShell();
  app._setCarSceneVisible = show => { app._carSceneVisible = show; };
  app._syncCenterFillIndicator = () => indicators.push(app.state.centerFill);
  const flush = () => { while (callbacks.length) callbacks.shift()(); };

  for (let i = 0; i < 6; i++) {
    const mode = ['wallpaper', 'mixed', 'car'][i % 3];
    const before = commits.length;
    app._wallpaperReturnTab = 'appearance';
    app.dockCommand('toggleCenterFill');
    assert.equal(commits.length, before + 1, 'one mode click is one UI commit');
    assert.equal(app.state.centerFill, mode, 'one click advances one mode');
    assert.equal(app.state.wallpaperPopupOpen, mode !== 'car', 'mode and picker change together');
    assert.equal(app._wallpaperReturnTab, null, 'rail picker has no stale editor return target');
    flush();
    assert.equal(app._carSceneVisible, mode !== 'wallpaper', 'scene follows the selected mode');
    assert.equal(indicators.at(-1), mode, 'native indicator receives the selected mode');
  }

  // Multiple commands before React commit callbacks must not reopen a stale picker.
  app.openWallpaperPopup();
  assert.equal(app.state.centerFill, 'wallpaper');
  assert.equal(app.state.wallpaperPopupOpen, true, 'opening from 3D is atomic too');
  app.dockCommand('toggleCenterFill');
  app.dockCommand('toggleCenterFill');
  flush();
  assert.equal(app.state.centerFill, 'car');
  assert.equal(app.state.wallpaperPopupOpen, false, 'late callbacks cannot reopen the picker in 3D');

  for (const mode of ['wallpaper', 'mixed']) {
    app._setCenterFill(mode);
    flush();
    assert.equal(app.state.wallpaperPopupOpen, false, 'Appearance fill buttons do not force a picker');
    app.openWallpaperPopup();
    flush();
    assert.equal(app.state.centerFill, mode, 'opening the picker preserves wallpaper or mixed');
    app.closeWallpaperPopup();
    flush();
    assert.equal(app.state.wallpaperPopupOpen, false);
    assert.equal(app.state.centerFill, mode, 'closing the picker preserves the mode');
  }
}
check(html);
assert.throws(() => check(html.replace('this._setCenterFill(next, null, true);', 'this._setCenterFill(next);')));
assert.throws(() => check(html.replace("next === 'car' || showPicker", "next === 'car'")));
assert.throws(() => check(html.replace("this._setCenterFill('wallpaper', null, true);",
  "this._setCenterFill('wallpaper', () => this.setState({ wallpaperPopupOpen: true }));")));
const cycle = html.slice(html.indexOf('  cycleCenterFill() {'), html.indexOf('  openWallpaperPopup() {'));
assert.throws(() => check(html.replace(cycle, cycle.replace('order[(order.indexOf(cur) + 1) % order.length]', 'cur'))));
assert.throws(() => check(html.replaceAll('this._syncCarSceneForShell();', '')));
assert.throws(() => check(html.replaceAll('this._syncCenterFillIndicator();', '')));
assert.throws(() => check(html.replaceAll('this._wallpaperReturnTab = null;', '')));
console.log('PASS one-tap wallpaper modes, picker/scene synchronization and rapid commands + negative controls');

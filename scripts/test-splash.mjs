import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const java = readFileSync(new URL('../app/src/main/java/com/havalh6/viewer/MainActivity.java', import.meta.url), 'utf8');
function check(source, native) {
  assert.match(source, /<body class="hv-boot hv-splash-up">/, 'chrome stays behind the splash');
  assert.match(source, /video\.src = 'assets\/app-splash\.mp4'/, 'boot clip is assigned when the intro is on');
  assert.ok(!/id="hv-splash-video"[^>]*\ssrc=/.test(source), 'the video tag must not fetch before the setting is read');
  assert.match(native, /setupSplashSkip\(rootLayout\);/, 'native skip is above the video overlay');
  assert.ok(native.includes('window.HavalSplash.skip();'), 'native skip reaches the splash');
  const script = source.slice(source.indexOf('window.HavalSplash ='), source.indexOf('</script>', source.indexOf('window.HavalSplash =')));
  for (const mode of ['ended', 'skip', 'error', 'nosplash', 'settingOff']) {
    const classes = new Set(['hv-boot', 'hv-splash-up']);
    const classList = { add: (...names) => names.forEach(n => classes.add(n)), remove: (...names) => names.forEach(n => classes.delete(n)) };
    const element = () => ({ style: {}, events: {}, classList,
      parentNode: { removeChild(node) { node.parentNode = null; } },
      addEventListener(name, cb) { (this.events[name] ||= []).push(cb); },
    });
    let plays = 0, done = 0, fades = 0, ended = 0;
    const overlay = element(), skip = element(), video = Object.assign(element(), {
      readyState: 2, currentTime: 0, pause() {}, load() {}, removeAttribute() {},
      play() { plays++; return Promise.resolve(); },
    });
    const nodes = { 'hv-splash': overlay, 'hv-splash-video': video, 'hv-splash-skip': skip };
    const store = mode === 'settingOff'
      ? { h6_settings_v1: JSON.stringify({ introVideo: false }) }
      : {};
    const window = { innerWidth: 1920, innerHeight: 720, addEventListener() {},
      AppLauncherBridge: { isMusicActive: () => true, beginSplashFade: () => fades++, endSplashOverlay: () => ended++ },
    };
    vm.runInNewContext(script, { window, document: { body: { classList }, getElementById: id => nodes[id] },
      location: { search: mode === 'nosplash' ? '?android&nosplash' : '?android', hash: '' },
      localStorage: { getItem: (k) => (k in store ? store[k] : null) },
      setTimeout: () => 1, setInterval: () => 2, clearTimeout() {}, clearInterval() {},
      console: { log() {}, warn() {} },
    });
    const splash = window.HavalSplash;
    splash.whenDone(() => done++);
    if (mode === 'nosplash' || mode === 'settingOff') {
      assert.equal(splash.active, false); assert.equal(plays, 0); assert.equal(done, 1);
      assert.equal(splash.userOff, mode === 'settingOff', 'only the setting reports userOff');
    } else {
      assert.equal(splash.active, true); assert.equal(plays, 1); assert.equal(done, 0);
      assert.equal(video.muted, true, 'existing music keeps the clip silent');
      if (mode === 'skip') { splash.skip(); splash.skip(); assert.equal(splash.wasSkipped(), true); }
      else video.events[mode].forEach(cb => cb());
      assert.equal(done, 1, 'clip completion is delivered once'); assert.equal(splash.isDone(), true);
      if (mode === 'ended') { splash.fadeOut(); splash.fadeOut(); assert.equal(fades, 1); }
      else { splash.dropNow(); splash.dropNow(); assert.equal(ended, 1); }
    }
    assert.equal(video.parentNode, null, 'completion releases the decoder element');
    assert.equal(classes.has('hv-splash-up'), false, 'the page is released');
  }
}
check(html, java);
assert.throws(() => check(html, java.replace('setupSplashSkip(rootLayout);', '')));
assert.throws(() => check(html.replace('skipped = true;', 'skipped = false;'), java));
assert.throws(() => check(html.replace("document.body.classList.remove('hv-splash-up')", 'void 0'), java));
assert.throws(() => check(html.replaceAll('destroyVideo();', ''), java));
assert.throws(() => check(html.replace('saved.introVideo === false', 'saved.introVideo === true'), java));
console.log('PASS splash playback, completion, skip, errors, decoder cleanup, nosplash and intro-off + negative controls');

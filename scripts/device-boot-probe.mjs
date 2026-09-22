// Boot + intro probe for the viewer WebView (car or emulator).
//
// Installs a recorder with Page.addScriptToEvaluateOnNewDocument (a plain
// evaluate is wiped when the page navigates), then navigates the WebView and
// reads back, for the whole boot and the intro orbit:
//   - milestones: preload done, model parsed, loading=false, intro hold/orbit/end
//   - every bare rAF gap, tagged with the intro phase it fell in
//   - long tasks (PerformanceObserver 'longtask')
//   - React commits (setState -> componentDidUpdate), with the state keys
//   - any app method taking >= 4 ms once window.__app exists (outermost only)
//
//   node scripts/device-boot-probe.mjs --cold --arms default,gt,hev --reps 2
//   node scripts/device-boot-probe.mjs --cold --arms default,default@h6_revealPrewarm=0 --reps 3
//   node scripts/device-boot-probe.mjs --cold --arms default --profile --json out.json
//   node scripts/device-boot-probe.mjs --serve --arms index.html,_base.html --reps 3
//
// --cold      force-stop + launch per arm (a page reload keeps the GPU program
//             cache hot and made a 1 fps intro read as 34 fps). Without it,
//             arms are Page.navigate reloads.
// arm syntax  <model>[@key=value][#nograin]: model = default|gt|hev|phev19,
//             @ stages one extra localStorage key, #nograin hides .bg-grain.
// --serve     arms are repo files served over adb reverse (A/B an edit
//             without an APK). Load times are then network-bound.
// --profile   V8 CPU profile; every long task after the reveal is attributed.
// --capture a,b  record the arguments of these app methods.
//
// Arms are interleaved (A,B,C,A,B,C...) because this unit drifts ~2x over
// minutes -- see CLAUDE.md. h6_settings_v1 is backed up and restored so a
// ?model= arm cannot leave the car on a different body.

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.havalh6.viewer';
const BASE_URL = 'https://appassets.androidplatform.net/assets/www/index.html?android';

const argv = process.argv.slice(2);
const flag = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const SERIAL = flag('--serial', null);
const ARMS = String(flag('--arms', 'default')).split(',').filter(Boolean);
const REPS = Number(flag('--reps', 1));
const WAIT_MS = Number(flag('--wait', 45000));
const JSON_OUT = flag('--json', null);
const COLD = argv.includes('--cold');
// --capture a,b: record the arguments of these app methods (JSON, truncated).
const CAPTURE = String(flag('--capture', '')).split(',').filter(Boolean);
// --profile: V8 CPU profile for the whole run; the summary attributes every
// long task after the reveal to the functions sampled inside it.
const PROFILE = argv.includes('--profile');
// --serve: arms are FILES in the repo root (e.g. index.html,_ab-base.html),
// served from this machine over adb reverse. Implies --cold: the app is
// cold-launched, then navigated to the served file before it gets far, so the
// GPU/driver state is as cold as a real boot. The GLB then crosses the
// network, so load TIMES are not comparable with the APK -- the intro is.
const SERVE = argv.includes('--serve');
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const ADB = (() => {
  const p = process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe');
  return p && fs.existsSync(p) ? p : 'adb';
})();
const adb = (args) => execFileSync(ADB, SERIAL ? ['-s', SERIAL, ...args] : args, { encoding: 'utf8' }).trim();

function viewerPid() {
  const line = adb(['shell', 'ps', '-A']).split('\n').find(l => l.trim().endsWith(` ${PACKAGE}`));
  return line ? Number(line.trim().split(/\s+/)[1]) : null;
}
const freePort = () => new Promise((res, rej) => {
  const s = net.createServer(); s.once('error', rej);
  s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); });
});
const getJson = (url) => new Promise((res, rej) => {
  http.get(url, r => { let b = ''; r.on('data', c => { b += c; }); r.on('end', () => { try { res(JSON.parse(b)); } catch (e) { rej(e); } }); }).on('error', rej);
});
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const focusedApp = () => { const m = /mCurrentFocus=Window\{\S+ \S+ ([^\s/}]+)/.exec(adb(['shell', 'dumpsys window | grep mCurrentFocus'])); return m ? m[1] : '?'; };

async function attach() {
  let pid = viewerPid();
  if (!pid) {
    adb(['shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity']);
    for (let i = 0; i < 40 && !pid; i++) { await sleep(500); pid = viewerPid(); }
    if (!pid) throw new Error('viewer not running');
  }
  const port = await freePort();
  adb(['forward', `tcp:${port}`, `localabstract:webview_devtools_remote_${pid}`]);
  let page = null;
  for (let i = 0; i < 30 && !page; i++) {
    try { page = (await getJson(`http://127.0.0.1:${port}/json/list`)).find(t => t.type === 'page'); } catch {}
    if (!page) await sleep(500);
  }
  if (!page) {
    // The process can be up with no activity (the notification-listener
    // service keeps it alive, e.g. right after pm install): start the activity.
    if (attach.retried) throw new Error('no CDP page');
    attach.retried = true;
    adb(['shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity']);
    await sleep(4000);
    try { return await attach(); } finally { attach.retried = false; }
  }
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('CDP refused')); });
  let id = 0; const pending = new Map();
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(m.error.message)) : p.res(m.result); }
  };
  const send = (method, params = {}) => new Promise((res, rej) => { const n = ++id; pending.set(n, { res, rej }); ws.send(JSON.stringify({ id: n, method, params })); });
  const evaluate = async (expression) => {
    const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || 'eval threw');
    return r.result.value;
  };
  return { send, evaluate, close: () => ws.close() };
}

// Runs in the page, before any of its own scripts.
const PROBE = `(() => {
  const P = window.__bootProbe = { marks: {}, raf: [], lt: [], commits: [], slow: [], errors: [], vis: [[+performance.now().toFixed(0), document.visibilityState]] };
  document.addEventListener('visibilitychange', () => P.vis.push([+performance.now().toFixed(0), document.visibilityState]));
  const now = () => performance.now();
  const mark = (k) => { if (P.marks[k] == null) P.marks[k] = +now().toFixed(1); };
  try {
    new PerformanceObserver((l) => { for (const e of l.getEntries()) P.lt.push([+e.startTime.toFixed(1), +e.duration.toFixed(1)]); })
      .observe({ entryTypes: ['longtask'] });
  } catch (e) { P.errors.push('longtask: ' + e.message); }
  window.addEventListener('error', (e) => P.errors.push(String(e.message).slice(0, 200)));

  let last = null, hooked = false, lastPhase = '';
  const tick = () => {
    const t = now();
    const a = window.__app;
    let phase = 'boot';
    if (a) {
      if (a._intro) phase = a._intro.phase;
      else if (a._introPlayed) phase = 'post';
      if (!a.state || !a.state.loading) mark('loadingFalse');
      if (a._viewerReady) mark('viewerReady');
      if (!hooked) { hooked = true; hook(a); }
    }
    if (window.__bootGlb && window.__bootGlb.buffer) mark('preloadDone');
    if (phase !== lastPhase) { mark(phase + 'Start'); lastPhase = phase; }
    // New GL programs, i.e. shader compiles, and what they were for.
    if (a && a.renderer && a.renderer.info && a.renderer.info.programs) {
      const progs = a.renderer.info.programs;
      if (P.progCount == null) P.progCount = progs.length;
      if (progs.length > P.progCount) {
        for (let i = P.progCount; i < progs.length; i++) {
          const pr = progs[i];
          (P.progs = P.progs || []).push([+t.toFixed(0), pr.name || '?', String(pr.cacheKey || '').slice(0, 160)]);
        }
      }
      P.progCount = progs.length;
    }
    const rendered = a && a._perf ? a._perf.renderedFrames || 0 : 0;
    if (last != null) P.raf.push([+t.toFixed(1), +(t - last).toFixed(1), phase, rendered, a && a._intro ? +a._intro.t.toFixed(3) : null, a ? !!a._introActive : null, performance.memory ? +(performance.memory.usedJSHeapSize / 1e6).toFixed(1) : null]);
    last = t;
    if (t < ${WAIT_MS + 5000}) requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);

  // <body> class changes (hv-splash-up, hv-boot, ...) -- style/layout of the
  // whole page follows each one.
  const watchBody = () => {
    if (!document.body) { setTimeout(watchBody, 50); return; }
    new MutationObserver(() => (P.bodyClass = P.bodyClass || []).push([+now().toFixed(0), document.body.className]))
      .observe(document.body, { attributes: true, attributeFilter: ['class'] });
  };
  watchBody();
  // Synchronous Java bridges block this thread until Java returns.
  const wrapBridge = (name) => {
    const b = window[name];
    if (!b || b.__probed) return !!b;
    let n = 0;
    for (const k in b) {
      if (typeof b[k] !== 'function') continue;
      const fn = b[k];
      try {
        b[k] = function (...x) { const t0 = now(); try { return fn.apply(b, x); } finally { const d = now() - t0; if (d >= 15) (P.bridge = P.bridge || []).push([+t0.toFixed(0), +d.toFixed(1), name + '.' + k]); } };
        n++;
      } catch (e) {}
    }
    try { b.__probed = true; } catch (e) {}
    (P.bridgeWrapped = P.bridgeWrapped || {})[name] = n;
    return true;
  };
  const wrapBridges = () => { const a = wrapBridge('AppLauncherBridge'), b = wrapBridge('TelemetryBridge'); if (!(a && b) && now() < 20000) setTimeout(wrapBridges, 200); };
  wrapBridges();

  function hook(app) {
    mark('appHooked');
    let depth = 0, pendingAt = null, pendingKeys = null;
    const origSet = app.setState, origCdu = app.componentDidUpdate;
    app.setState = function (partial, cb) {
      if (pendingAt == null) { pendingAt = now(); pendingKeys = {}; }
      const ks = typeof partial === 'function' ? ['(fn)'] : Object.keys(partial || {});
      for (const k of ks) pendingKeys[k] = 1;
      return origSet.call(this, partial, cb);
    };
    app.componentDidUpdate = function (...x) {
      const t1 = now();
      const r = origCdu.apply(this, x);
      if (pendingAt != null) {
        P.commits.push([+pendingAt.toFixed(1), +(now() - pendingAt).toFixed(1), Object.keys(pendingKeys).slice(0, 6).join(',')]);
        pendingAt = null;
      }
      return r;
    };
    const proto = Object.getPrototypeOf(app);
    const skip = new Set(['constructor', 'render', 'renderVals', 'setState', 'componentDidUpdate']);
    for (const name of Object.getOwnPropertyNames(proto)) {
      if (skip.has(name)) continue;
      const d = Object.getOwnPropertyDescriptor(proto, name);
      if (!d || typeof d.value !== 'function') continue;
      if (Object.prototype.hasOwnProperty.call(app, name)) continue; // bound / overridden on instance
      const fn = d.value;
      const cap = ${JSON.stringify(CAPTURE)}.includes(name);
      app[name] = function (...x) {
        if (cap) { try { (P.args = P.args || []).push([+now().toFixed(0), name, JSON.stringify(x).slice(0, 600), JSON.stringify({ l: this._safeLeftPx, r: this._safeRightPx, t: this._safeTopPx, b: this._safeBottomPx, lb: this._launcherBottomPx })]); } catch (e) {} }
        depth++;
        const t0 = now();
        try { return fn.apply(this, x); }
        finally {
          depth--;
          const dur = now() - t0;
          if (dur >= 4 && depth === 0) P.slow.push([+t0.toFixed(1), +dur.toFixed(1), name]);
          else if (dur >= 20 && depth > 0) (P.nested = P.nested || []).push([+t0.toFixed(1), +dur.toFixed(1), name, depth]);
        }
      };
    }
    // The renderer itself: a stall inside a draw (first-use compile, texture
    // upload) shows up here and nowhere else.
    if (app.renderer && app.renderer.render) {
      const r = app.renderer, rr = r.render.bind(r);
      r.render = function (scene, cam) {
        const t0 = now(); try { return rr(scene, cam); }
        finally { const dur = now() - t0; if (dur >= 60) (P.nested = P.nested || []).push([+t0.toFixed(1), +dur.toFixed(1), 'renderer.render' + (r.getRenderTarget() ? '(rt)' : ''), depth]); }
      };
    }
    // Instance closures the prototype pass cannot see.
    for (const name of ['renderOnce', '_onResize']) {
      const fn = app[name];
      if (typeof fn !== 'function') continue;
      app[name] = function (...x) {
        const t0 = now(); try { return fn.apply(this, x); }
        finally { const dur = now() - t0; if (dur >= 20) (P.nested = P.nested || []).push([+t0.toFixed(1), +dur.toFixed(1), name, depth]); }
      };
    }
    // renderVals is the React render body; time it separately.
    const rv = app.renderVals;
    if (rv) app.renderVals = function (...x) {
      const t0 = now(); try { return rv.apply(this, x); }
      finally { const dur = now() - t0; if (dur >= 4) P.slow.push([+t0.toFixed(1), +dur.toFixed(1), 'renderVals']); }
    };
  }
})();`;

function summarise(p) {
  const q = (a, f) => { if (!a.length) return null; const v = [...a].sort((x, y) => x - y); return +v[Math.min(v.length - 1, Math.floor(v.length * f))].toFixed(1); };
  const m = p.marks;
  const introStart = m.holdStart ?? m.orbitStart;
  const introEnd = m.postStart;
  const inIntro = p.raf.filter(r => r[2] === 'hold' || r[2] === 'orbit');
  const orbit = p.raf.filter(r => r[2] === 'orbit');
  const rendered = inIntro.length ? inIntro[inIntro.length - 1][3] - inIntro[0][3] : 0;
  const within = (t) => introStart != null && introEnd != null && t >= introStart - 50 && t <= introEnd;
  const introCommits = p.commits.filter(c => within(c[0]));
  const introSlow = p.slow.filter(s => within(s[0]));
  const introLt = p.lt.filter(l => within(l[0]));
  const agg = {};
  for (const [, d, n] of introSlow) { agg[n] = agg[n] || [0, 0]; agg[n][0]++; agg[n][1] += d; }
  const bootAgg = {};
  for (const [t, d, n] of p.slow) if (introStart == null || t < introStart) { bootAgg[n] = bootAgg[n] || [0, 0]; bootAgg[n][0]++; bootAgg[n][1] += d; }
  const top = (o, n) => Object.entries(o).sort((a, b) => b[1][1] - a[1][1]).slice(0, n).map(([k, [c, d]]) => `${k} ${d.toFixed(0)}ms/${c}`);
  const introSec = introStart != null && introEnd != null ? (introEnd - introStart) / 1000 : null;
  return {
    marks: m,
    introSec: introSec && +introSec.toFixed(2),
    introFrames: inIntro.length,
    introFps: introSec ? +(inIntro.length / introSec).toFixed(1) : null,
    introRendered: rendered,
    introGapP50: q(inIntro.map(r => r[1]), 0.5),
    introGapP90: q(inIntro.map(r => r[1]), 0.9),
    introGapMax: q(inIntro.map(r => r[1]), 1),
    orbitGapP50: q(orbit.map(r => r[1]), 0.5),
    orbitGapMax: q(orbit.map(r => r[1]), 1),
    introCommits: introCommits.length,
    introCommitMs: +introCommits.reduce((s, c) => s + c[1], 0).toFixed(0),
    introCommitList: introCommits.map(c => `${(c[0] - introStart).toFixed(0)}+${c[1].toFixed(0)}ms[${c[2]}]`),
    introLongTasks: introLt.map(l => `${(l[0] - introStart).toFixed(0)}+${l[1].toFixed(0)}`),
    introSlowTop: top(agg, 12),
    bootSlowTop: top(bootAgg, 12),
    bootCommits: p.commits.filter(c => introStart == null || c[0] < introStart).length,
    errors: p.errors.slice(0, 5),
  };
}

// Cold start: kill the process, launch it, and attach before index.html loads.
// The WebView's devtools socket exists only once the process is up, so this
// races the app's own loadUrl. Whether the probe caught the first document is
// checked afterwards (marks.bootStart present), never assumed.
async function coldLaunch(url) {
  adb(['shell', 'am', 'force-stop', PACKAGE]);
  await sleep(1500);
  const args = ['shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity'];
  if (url) args.push('-d', url);
  adb(args);
  const t0 = Date.now();
  for (;;) {
    if (Date.now() - t0 > 20000) throw new Error('viewer did not come up');
    try {
      const c = await attach();
      await c.send('Page.enable');
      await c.send('Page.addScriptToEvaluateOnNewDocument', { source: PROBE });
      // Usually too late for the new-document hook: the page is already up.
      // Inject into the live document too -- __app appears later still, and
      // the intro is ~15 s out, so nothing this probe is for is missed.
      const late = await c.evaluate(`window.__bootProbe ? 0 : (${PROBE.trim().replace(/;$/, "")}, (window.__bootProbe.late = +performance.now().toFixed(0)))`);
      return { cdp: c, attachMs: Date.now() - t0, lateMs: late, focus: focusedApp() };
    } catch { await sleep(50); }
  }
}

function serveRoot() {
  const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
    '.json': 'application/json', '.glb': 'model/gltf-binary', '.png': 'image/png', '.jpg': 'image/jpeg',
    '.webp': 'image/webp', '.mp4': 'video/mp4', '.woff2': 'font/woff2', '.svg': 'image/svg+xml', '.wasm': 'application/wasm' };
  const srv = http.createServer((req, res) => {
    const rel = decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html';
    const file = path.join(ROOT, rel);
    if (!file.startsWith(ROOT) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'Content-Type': types[path.extname(file).toLowerCase()] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise(r => srv.listen(0, '127.0.0.1', () => r(srv)));
}

async function main() {
  let servePort = null;
  if (SERVE) {
    const srv = await serveRoot();
    servePort = srv.address().port;
    adb(['reverse', `tcp:${servePort}`, `tcp:${servePort}`]);
    console.error(`serving ${ROOT} on :${servePort} (adb reverse)`);
  }
  let cdp = await attach();
  if (COLD) { await cdp.send('Page.enable'); }
  const backup = await cdp.evaluate(`localStorage.getItem('h6_settings_v1')`);
  const bakFile = path.join(process.env.TEMP || '.', `h6_settings_backup_${Date.now()}.json`);
  fs.writeFileSync(bakFile, backup || '');
  console.error(`settings backed up -> ${bakFile}`);
  await cdp.send('Page.enable');
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: PROBE });

  if (SERVE) {
    // localStorage is per origin: the served page would boot with no saved
    // settings at all (first-run setup, no intro). Copy the car's settings in.
    await cdp.send('Page.navigate', { url: `http://127.0.0.1:${servePort}/${ARMS[0]}?android` });
    await sleep(4000);
    await cdp.evaluate(`localStorage.setItem('h6_settings_v1', ${JSON.stringify(backup || '{}')}), 1`);
    console.error('seeded served origin with the car settings');
  }

  const results = [];
  try {
    for (let rep = 0; rep < REPS; rep++) {
      for (const arm of ARMS) {
        // Put the saved settings back before every arm so arms cannot leak.
        await cdp.evaluate(`localStorage.setItem('h6_settings_v1', ${JSON.stringify(backup || '{}')}), 1`);
        const url = SERVE ? `http://127.0.0.1:${servePort}/${arm}?android`
          : arm === 'default' ? BASE_URL : `${BASE_URL}&model=${arm}`;
        console.error(`[rep ${rep + 1}] ${arm}: ${COLD || SERVE ? 'cold start' : 'navigate'}`);
        if (COLD || SERVE) {
          // arm "name@key=value" stages one extra localStorage key for that arm
          // (value "" removes it), e.g. default@h6_revealPrewarm=0.
          const noGrain = arm.includes('#nograin');
          const armBase = arm.replace('#nograin', '');
          const at = armBase.indexOf('@');
          const extra = {};
          await cdp.evaluate(`localStorage.removeItem('h6_revealPrewarm'), 1`);
          if (at >= 0) {
            const [k, v] = armBase.slice(at + 1).split('=');
            await cdp.evaluate(v ? `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)}), 1` : `localStorage.removeItem(${JSON.stringify(k)}), 1`);
          }
          const model = at >= 0 ? armBase.slice(0, at) : armBase;
          if (!SERVE && model !== 'default') {
            // The activity takes no model extra; stage the preset in saved
            // settings instead, which the boot path and the <head> preload both read.
            const trim = model === 'gt' ? 'gt' : model;
            await cdp.evaluate(`(() => { const s = JSON.parse(localStorage.getItem('h6_settings_v1') || '{}'); s.modelTrim = ${JSON.stringify(trim)}; if (${JSON.stringify(trim)} === 'gt') s.modelVariant = 'gt'; else s.modelVariant = 'hev'; localStorage.setItem('h6_settings_v1', JSON.stringify(s)); return 1; })()`);
          }
          // WebView persists localStorage lazily; a force-stop straight after
          // the write loses it (every arm booted phev34 until this wait).
          await sleep(6000);
          cdp.close();
          const r = await coldLaunch();
          cdp = r.cdp;
          if (SERVE) await cdp.send('Page.navigate', { url });
          // A/B for the SVG-noise grain layer: hidden from well before the reveal.
          if (noGrain) await cdp.evaluate(`(() => { const st = document.createElement('style'); st.textContent = '.bg-grain::after{display:none!important}'; (document.head || document.documentElement).appendChild(st); return 1; })()`);
          console.error(`  attached after ${r.attachMs} ms${r.lateMs ? `, probe injected late at page t=${r.lateMs} ms` : ''}, focus=${r.focus}`);
        } else {
          await cdp.send('Page.navigate', { url });
        }
        let profT0 = null;
        if (PROFILE) {
          await cdp.send('Profiler.enable');
          await cdp.send('Profiler.setSamplingInterval', { interval: 2000 });
          await cdp.send('Profiler.start');
          profT0 = await cdp.evaluate('performance.now()');
        }
        await sleep(WAIT_MS);
        let prof = null;
        if (PROFILE) { try { prof = (await cdp.send('Profiler.stop')).profile; } catch (e) { console.error('profile failed', e.message); } }
        let probe = null;
        for (let i = 0; i < 5 && !probe; i++) {
          try { probe = await cdp.evaluate('window.__bootProbe'); } catch { await sleep(1000); }
        }
        if (!probe) { console.error('  no probe data'); continue; }
        const prewarmMs = await cdp.evaluate('window.__app ? (__app._revealPrewarmMs ?? null) : null').catch(() => null);
        const trim = await cdp.evaluate(`(window.__app && window.__app.state) ? [__app.state.modelTrim, __app.state.modelVariant, __app.state.centerFill].join('/') : '?'`).catch(() => '?');
        const s = summarise(probe);
        s.arm = arm; s.rep = rep + 1; s.trim = trim; s.vis = probe.vis; s.focusEnd = focusedApp(); s.prewarmMs = prewarmMs;
        if (prof) {
          // Sample times, in page performance.now() ms (profile starts ~at profT0).
          const byId = new Map(prof.nodes.map(n => [n.id, n]));
          const parent = new Map(); for (const n of prof.nodes) for (const c of (n.children || [])) parent.set(c, n.id);
          const label = (n) => (n.callFrame.functionName || '(anon)') + ' ' + (n.callFrame.url || '').split('/').pop() + ':' + (n.callFrame.lineNumber + 1);
          let t = 0; const times = prof.timeDeltas.map(d => (t += d) / 1000);
          const t0 = times.length ? profT0 - (times[0] - 0) : profT0;
          const holdAt = probe.marks.holdStart || 0;
          s.profile = probe.lt.filter(l => l[0] > holdAt - 500 && l[1] > 400).map(([ls, ld]) => {
            const self = {}, top = {};
            prof.samples.forEach((id, i) => {
              const at = t0 + times[i];
              if (at < ls || at > ls + ld) return;
              const n = byId.get(id); const k = label(n); self[k] = (self[k] || 0) + 1;
              let cur = id, chain = []; while (cur != null) { const nn = byId.get(cur); if (nn.callFrame.functionName !== '(root)') chain.push(label(nn)); cur = parent.get(cur); }
              const outer = chain.slice(-4).reverse().join(' > '); top[outer] = (top[outer] || 0) + 1;
            });
            const sort = (o) => Object.entries(o).sort((a, b) => b[1] - a[1]).slice(0, 10).map(([k, v]) => v + '  ' + k);
            return { at: +(ls - holdAt).toFixed(0), dur: ld, self: sort(self), outer: sort(top) };
          });
        }
        results.push({ ...s, raw: JSON_OUT ? probe : undefined });
        console.log(JSON.stringify({ arm, rep: rep + 1, trim, ...s }, null, 1));
      }
    }
  } finally {
    await cdp.evaluate(`localStorage.setItem('h6_settings_v1', ${JSON.stringify(backup || '{}')}), localStorage.removeItem('h6_revealPrewarm'), 1`).catch(() => {});
    await cdp.send('Page.navigate', { url: BASE_URL }).catch(() => {});
    console.error('settings restored, navigated back to default');
    cdp.close();
  }
  if (JSON_OUT) fs.writeFileSync(JSON_OUT, JSON.stringify(results, null, 1));
}

main().catch((e) => { console.error(e); process.exit(1); });

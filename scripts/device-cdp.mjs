// Drive the viewer's WebView on a real device (emulator OR the car) over the
// Chrome DevTools Protocol — the harness docs/engineering describes, packaged.
//
// The app enables setWebContentsDebuggingEnabled(true), so its WebView always
// publishes @webview_devtools_remote_<pid>. This forwards that socket, attaches
// to the page, and gives you eval / navigate / screenshot / a wheel-spin sweep.
//
// Because index.html is loaded through a WebViewAssetLoader from inside the
// APK, `nav` is the way to test an EDITED index.html without rebuilding: serve
// the repo on the host, `adb reverse` the port, and navigate the WebView at it.
//
//   node scripts/device-cdp.mjs verify                # the whole local gate, one pass
//   node scripts/device-cdp.mjs rims                  # head-on frames, wheel centring
//   node scripts/device-cdp.mjs spin 3 12,20,27 --fps 18
//   node scripts/device-cdp.mjs serve                 # host server + adb reverse + navigate
//   node scripts/device-cdp.mjs eval "__wheelSpin()"
//   node scripts/device-cdp.mjs cam wheel fl --elev 0   # frame a wheel, no mouse
//   node scripts/device-cdp.mjs shot out.png
//   node scripts/device-cdp.mjs reset                 # back to the APK's own copy
//
// --serial <s> picks the device when more than one is attached. Everything
// else is discovered per run: the head unit is on DHCP and moves constantly.
//
// Zero dependencies — Node 22's global WebSocket does the CDP transport.

import { execFileSync, spawn } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import net from 'node:net';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PACKAGE = 'com.havalh6.viewer';
const ACTIVITY = `${PACKAGE}/.MainActivity`;
const APK_URL = 'https://appassets.androidplatform.net/assets/www/index.html?android';

// ── adb ─────────────────────────────────────────────────────────────────────

function findAdb() {
  const local = process.env.LOCALAPPDATA
    && path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe');
  if (local && fs.existsSync(local)) return local;
  const home = process.env.HOME || process.env.USERPROFILE;
  for (const p of [
    home && path.join(home, 'Android', 'Sdk', 'platform-tools', 'adb'),
    '/usr/local/bin/adb', '/usr/bin/adb',
  ]) if (p && fs.existsSync(p)) return p;
  return 'adb';   // hope it is on PATH
}
const ADB = findAdb();

const argv = process.argv.slice(2);
let SERIAL = null;
{
  const i = argv.indexOf('--serial');
  if (i >= 0) { SERIAL = argv[i + 1]; argv.splice(i, 2); }
}
const CMD = argv[0] || 'status';

function adb(args, opts = {}) {
  const full = SERIAL ? ['-s', SERIAL, ...args] : args;
  return execFileSync(ADB, full, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], ...opts }).trim();
}

function pickSerial() {
  if (SERIAL) return SERIAL;
  const rows = execFileSync(ADB, ['devices'], { encoding: 'utf8' })
    .split('\n').slice(1)
    .map(l => l.trim().split(/\s+/))
    .filter(p => p.length >= 2 && p[1] === 'device')
    .map(p => p[0]);
  if (!rows.length) throw new Error('no adb device (emulator not started, or the car is on a new IP — try `adb connect <ip>:5555`)');
  if (rows.length > 1) throw new Error(`several devices attached (${rows.join(', ')}) — pass --serial`);
  return rows[0];
}

function viewerPid() {
  const out = adb(['shell', 'ps', '-A']);
  // pidof can return the notification-listener service instead of the activity.
  const line = out.split('\n').find(l => l.trim().endsWith(` ${PACKAGE}`));
  return line ? Number(line.trim().split(/\s+/)[1]) : null;
}

async function freePort() {
  return new Promise((res, rej) => {
    const srv = net.createServer();
    srv.once('error', rej);
    srv.listen(0, '127.0.0.1', () => { const p = srv.address().port; srv.close(() => res(p)); });
  });
}

// ── CDP ─────────────────────────────────────────────────────────────────────

function getJson(url) {
  return new Promise((res, rej) => {
    http.get(url, r => {
      let b = '';
      r.on('data', c => { b += c; });
      r.on('end', () => { try { res(JSON.parse(b)); } catch (e) { rej(new Error(`bad JSON from ${url}: ${b.slice(0, 120)}`)); } });
    }).on('error', rej);
  });
}

async function attach() {
  SERIAL = pickSerial();
  let pid = viewerPid();
  if (!pid) {
    process.stderr.write('viewer not running — starting it\n');
    adb(['shell', 'am', 'start', '-n', ACTIVITY]);
    for (let i = 0; i < 40 && !pid; i++) {
      await new Promise(r => setTimeout(r, 500));
      pid = viewerPid();
    }
    if (!pid) throw new Error('viewer would not start');
    await new Promise(r => setTimeout(r, 4000));
  }
  const port = await freePort();
  adb(['forward', `tcp:${port}`, `localabstract:webview_devtools_remote_${pid}`]);

  let page = null;
  for (let i = 0; i < 30 && !page; i++) {
    try {
      const list = await getJson(`http://127.0.0.1:${port}/json/list`);
      page = list.find(t => t.type === 'page');
    } catch { /* WebView not up yet */ }
    if (!page) await new Promise(r => setTimeout(r, 500));
  }
  if (!page) throw new Error('no CDP page target on the WebView');

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('CDP socket refused')); });

  let id = 0;
  const pending = new Map();
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const { res, rej } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? rej(new Error(msg.error.message)) : res(msg.result);
    }
  };
  const send = (method, params = {}) => new Promise((res, rej) => {
    const n = ++id;
    pending.set(n, { res, rej });
    ws.send(JSON.stringify({ id: n, method, params }));
  });

  // Every eval awaits its promise, so __spinProbe(4) can be called directly.
  const evaluate = async (expression) => {
    const r = await send('Runtime.evaluate', {
      expression, awaitPromise: true, returnByValue: true, allowUnsafeEvalBlocklistBypass: true,
    });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || 'eval threw');
    return r.result.value;
  };

  return { send, evaluate, page, port, pid, close: () => ws.close() };
}

// Grab the framebuffer. NOT Page.captureScreenshot: on this WebView that
// returns the DOM layers only and the WebGL canvas comes back blank, which
// makes every visual check silently useless. screencap reads what is actually
// on the panel.
function screencap(outPath) {
  const args = SERIAL ? ['-s', SERIAL, 'exec-out', 'screencap', '-p'] : ['exec-out', 'screencap', '-p'];
  const buf = execFileSync(ADB, args, { maxBuffer: 128 * 1024 * 1024 });
  fs.writeFileSync(outPath, buf);
  return buf.length;
}

// ── host server + adb reverse, so the WebView loads the EDITED index.html ────

function serveRepo(port) {
  const types = {
    '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
    '.css': 'text/css', '.json': 'application/json', '.glb': 'model/gltf-binary',
    '.png': 'image/png', '.jpg': 'image/jpeg', '.webp': 'image/webp',
    '.hdr': 'application/octet-stream', '.ktx2': 'application/octet-stream',
    '.mp4': 'video/mp4', '.woff2': 'font/woff2', '.svg': 'image/svg+xml',
  };
  const srv = http.createServer(async (req, res) => {
    const rel = decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html';
    if (rel === '_bing-wallpaper.json') {
      try {
        const pages = await Promise.all([0, 8].map(async (idx) => {
          const upstream = await fetch(
            `https://www.bing.com/HPImageArchive.aspx?format=js&idx=${idx}&n=8&mkt=pt-BR`,
          );
          if (!upstream.ok) throw new Error('bing ' + upstream.status);
          return upstream.json();
        }));
        const images = pages.flatMap((p) => (p && p.images) || []);
        const body = JSON.stringify({ images });
        res.writeHead(200, {
          'Content-Type': 'application/json',
          'Cache-Control': 'no-store',
        });
        res.end(body);
      } catch (e) {
        res.writeHead(502, { 'Content-Type': 'text/plain' });
        res.end('bing proxy failed');
      }
      return;
    }
    const file = path.join(ROOT, rel);
    if (!file.startsWith(ROOT) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      res.writeHead(404); return res.end('not found');
    }
    res.writeHead(200, {
      'Content-Type': types[path.extname(file).toLowerCase()] || 'application/octet-stream',
      'Cache-Control': 'no-store',   // always pick up the latest edit
    });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise(res => srv.listen(port, '127.0.0.1', () => res(srv)));
}

// ── checks ──────────────────────────────────────────────────────────────────

// Cap the page's frame rate so the MMI's sampling rate can be reproduced on
// hardware that is far too fast. Injected from here rather than living in
// index.html: it is a measuring instrument, not a product feature.
async function capFps(cdp, fps) {
  if (!fps) return async () => {};
  await cdp.evaluate(`(() => {
    if (!window.__origRAF) window.__origRAF = window.requestAnimationFrame.bind(window);
    const minDt = 1000 / ${fps};
    let last = -1e9;
    // Defer an early frame with a timer rather than asking for another one:
    // re-entering rAF from inside an rAF callback wedged the WebView on the
    // emulator — it stopped painting and never fired again. This way every
    // frame that arrives is honoured exactly once.
    window.requestAnimationFrame = (cb) => window.__origRAF((t) => {
      const wait = minDt - (performance.now() - last);
      if (wait > 1) return setTimeout(() => { last = performance.now(); cb(last); }, wait);
      last = performance.now(); cb(t);
    });
    return 'capped';
  })()`);
  console.log(`  frame rate capped to ~${fps} fps to stand in for the MMI`);
  return async () => {
    await cdp.evaluate('if (window.__origRAF) window.requestAnimationFrame = window.__origRAF;');
  };
}

// The aliasing check. At each speed __spinProbe reports the rotation the EYE
// resolves between frames, not the one the maths intended: near zero reads as
// frozen, negative reads as spinning backwards.
async function runSpin(cdp, { seconds = 3, speeds = [12, 20, 27, 35, 45], fpsCap = 0 } = {}) {
  const has = await cdp.evaluate('typeof window.__spinProbe');
  if (has !== 'function') {
    throw new Error('__spinProbe missing — this WebView is on an older index.html (run `serve`, or rebuild and install)');
  }
  const lift = await capFps(cdp, fpsCap);
  console.log('   kmh   fps  repeat  actual/frame  perceived/frame  frozen  reverse  blur');
  let worstReverse = 0, worstFrozen = 0;
  for (const kmh of speeds) {
    await cdp.evaluate(`window.__app._setMotionSpeed(${kmh} / 40)`);
    await new Promise(r => setTimeout(r, 700));   // let _motionDt settle
    const p = await cdp.evaluate(`__spinProbe(${seconds})`);
    worstReverse = Math.max(worstReverse, p.reverseFrac);
    worstFrozen = Math.max(worstFrozen, p.frozenFrac);
    console.log(
      String(p.speedKmh).padStart(6),
      String(p.fpsMedian).padStart(5),
      `${p.repeatDeg}deg`.padStart(8),
      `${p.actualStepDeg}deg`.padStart(13),
      `${p.perceivedStepDeg}deg`.padStart(16),
      String(p.frozenFrac).padStart(7),
      String(p.reverseFrac).padStart(8),
      String(p.blurOpacity).padStart(5),
    );
  }
  await cdp.evaluate('window.__app._setMotionSpeed(0)');
  await lift();
  return { worstReverse, worstFrozen };
}

// The wheel-centring check. Frames each rim head-on down its own axle and grabs
// the framebuffer, so the tyre band above the rim can be compared with the band
// below BY EYE. Deliberately not a world-space vertex fit: fitting circles to
// matrixWorld-transformed vertices reported the same wrong answer for a broken
// build and a fixed one, and only the head-on frames told them apart.
const headOnJs = (key, inch) => `
(() => {
  const a = window.__app, cam = window.__camera, ctl = window.__controls;
  const wait = (ms) => new Promise(r => setTimeout(r, ms));
  if (!a.__savedCam) a.__savedCam = { pos: cam.position.clone(), tgt: ctl.target.clone(), enabled: ctl.enabled };
  const cap = []; const orig = console.log;
  console.log = (...x) => { cap.push(x.join(' ')); orig.apply(console, x); };
  return (async () => {
    // Stop the car FIRST. motionSpeed is restored from localStorage, so a
    // session that was left rolling frames every rim through the blur sprite
    // and the centring comparison becomes meaningless.
    a._setMotionSpeed(0);
    await wait(400);
    await new Promise(res => a._loadCatalogWheelByKey(${JSON.stringify(key)}, () => setTimeout(res, 1200)));
    a.onWheelSizeInch(${inch});
    await wait(1600);
    a._setMotionSpeed(0);
    a._applyWheelTransforms();
    await wait(300);
    console.log = orig;
    const rim = (a._allWheels || []).filter(m => m._wheelSide === 'fl');
    if (!rim.length) return JSON.stringify({ key: ${JSON.stringify(key)}, error: 'no FL rim after load' });
    const P = rim[0]._pivot.clone(), ax = rim[0]._rollAxis.clone().normalize();
    ctl.enabled = false;
    cam.position.copy(P).addScaledVector(ax, -1.15);
    cam.up.set(0, 1, 0); cam.lookAt(P); ctl.target.copy(P);
    cam.updateProjectionMatrix(); cam.updateMatrixWorld(true);
    if (window.__renderOnce) window.__renderOnce();
    await wait(1200);
    const p = P.clone().project(cam);
    return JSON.stringify({
      key: ${JSON.stringify(key)},
      inch: a.state.wheelSizeInch,
      speedKmh: +(Math.abs(a._currentMotionSpeed()) * 40).toFixed(1),
      blurOpacity: (a._blurredDiscs && a._blurredDiscs[0] && a._blurredDiscs[0].material)
        ? +a._blurredDiscs[0].material.opacity.toFixed(2) : null,
      runout: (cap.find(s => s.includes('Runout')) || '(no runout line)').replace('[UploadWheel] ', ''),
      spin: (cap.find(s => s.includes('[WheelSpin]')) || '').replace('[WheelSpin] ', ''),
      pivotScreen: [Math.round((p.x * 0.5 + 0.5) * innerWidth), Math.round((-p.y * 0.5 + 0.5) * innerHeight)],
    });
  })();
})()`;

const DEFAULT_RIMS = [
  'haval_phev',        // stock — must come through untouched
  'bmw_19',            // bbox dragged up by a badge mesh floating above the hub
  'vorsteiner_vff109', // the biggest genuine axle tilt in the catalogue
  'vossen_vps310t',
  'forgiato_multato',
];

async function runRims(cdp, { rims = DEFAULT_RIMS, inch = 21, outDir = 'wheel-check' } = {}) {
  fs.mkdirSync(outDir, { recursive: true });
  const rows = [];
  for (const key of rims) {
    const info = JSON.parse(await cdp.evaluate(headOnJs(key, inch)));
    const file = path.join(outDir, `${key}.png`);
    screencap(file);
    info.file = file;
    rows.push(info);
    const moving = info.speedKmh > 0 || info.blurOpacity > 0;
    console.log(`  ${key.padEnd(20)} ${info.error || info.runout}${moving ? '   [!] BLURRED — car still moving, frame is not usable' : ''}`);
    if (info.spin) console.log(`  ${' '.repeat(20)} ${info.spin}`);
  }
  await cdp.evaluate(`(() => {
    const a = window.__app, c = window.__camera, t = window.__controls, s = a.__savedCam;
    if (!s) return 'none';
    c.position.copy(s.pos); t.target.copy(s.tgt); t.enabled = s.enabled;
    c.updateProjectionMatrix(); c.updateMatrixWorld(true);
    if (window.__renderOnce) window.__renderOnce();
    return 'restored';
  })()`);
  await contactSheet(rows, path.join(outDir, '_all-rims.png'));
  return rows;
}

// sharp is a devDependency, so use it when installed and fall back to the
// individual frames when it is not. Keeps the core zero-dependency.
async function contactSheet(rows, out) {
  let sharp;
  try { ({ default: sharp } = await import('sharp')); }
  catch { console.log('  (install sharp for a side-by-side sheet; individual frames are in the folder)'); return null; }
  const L = 1240, T = 90, W = 600, H = 540;   // the wheel, on a 1920x720 panel
  const usable = rows.filter(r => !r.error);
  if (!usable.length) return null;
  const tiles = [];
  for (let i = 0; i < usable.length; i++) {
    tiles.push({
      input: await sharp(usable[i].file).extract({ left: L, top: T, width: W, height: H }).toBuffer(),
      left: i * (W + 12), top: 0,
    });
  }
  await sharp({ create: { width: usable.length * (W + 12) - 12, height: H, channels: 3, background: { r: 20, g: 20, b: 22 } } })
    .composite(tiles).png().toFile(out);
  console.log(`  contact sheet: ${out}`);
  return out;
}

// ── commands ────────────────────────────────────────────────────────────────

async function main() {
  if (CMD === 'serve') {
    const port = Number(argv[1]) || 8099;
    const query = argv[2] || '?android';
    await serveRepo(port);
    SERIAL = pickSerial();
    adb(['reverse', `tcp:${port}`, `tcp:${port}`]);
    const cdp = await attach();
    await cdp.evaluate(`location.href = ${JSON.stringify(`http://localhost:${port}/index.html${query}`)}`)
      .catch(() => {});
    console.log(`serving ${ROOT}`);
    console.log(`  device      ${SERIAL} (viewer pid ${cdp.pid})`);
    console.log(`  webview at  http://localhost:${port}/index.html${query}`);
    console.log(`  CDP         http://127.0.0.1:${cdp.port}/json/list`);
    console.log('');
    console.log('Edit index.html, then reload the WebView with:');
    console.log(`  node scripts/device-cdp.mjs${SERIAL ? ` --serial ${SERIAL}` : ''} eval "location.reload()"`);
    console.log('Ctrl-C stops the server (the WebView keeps the last-loaded copy).');
    return new Promise(() => {});   // stay up
  }

  const cdp = await attach();
  try {
    if (CMD === 'status') {
      const v = await cdp.evaluate('JSON.stringify({url: location.href, wheelSpin: typeof window.__wheelSpin, perf: window.__perf && window.__perf()})');
      console.log(`device ${SERIAL}, viewer pid ${cdp.pid}`);
      console.log(v);

    } else if (CMD === 'eval') {
      const out = await cdp.evaluate(argv.slice(1).join(' '));
      console.log(typeof out === 'string' ? out : JSON.stringify(out, null, 1));

    } else if (CMD === 'nav') {
      await cdp.evaluate(`location.href = ${JSON.stringify(argv[1])}`).catch(() => {});
      console.log(`navigated to ${argv[1]}`);

    } else if (CMD === 'reset') {
      await cdp.evaluate(`location.href = ${JSON.stringify(APK_URL)}`).catch(() => {});
      console.log('WebView back on the APK-packaged index.html');

    } else if (CMD === 'wheel') {
      // Sends a real scroll through the browser's input pipeline (not a
      // synthetic DOM event) and reports whether the orbit camera moved — the
      // way to tell "the page ignores the wheel" apart from "the emulator
      // window never forwarded it".
      const dy = Number(argv[1]) || -240;
      const before = await cdp.evaluate('+window.__camera.position.distanceTo(window.__controls.target).toFixed(3)');
      // The canvas fills the window but the UI panels sit ON TOP of it, and on
      // a 1920x720 panel the centre is a modes chip — a wheel there lands on a
      // button and looks exactly like "zoom is broken". Scan for a point that
      // really does hit the canvas, or take one from argv[2],argv[3].
      const m = await cdp.evaluate(`(() => {
        const want = ${JSON.stringify(argv[2] ? [Number(argv[2]), Number(argv[3])] : null)};
        if (want) return JSON.stringify({ x: want[0], y: want[1], forced: true });
        const w = innerWidth, h = innerHeight;
        for (const [fx, fy] of [[0.80, 0.5], [0.90, 0.7], [0.70, 0.85], [0.5, 0.9], [0.5, 0.5]]) {
          const x = Math.round(w * fx), y = Math.round(h * fy);
          const el = document.elementFromPoint(x, y);
          if (el && el.tagName === 'CANVAS') return JSON.stringify({ x, y });
        }
        return JSON.stringify({ x: Math.round(w / 2), y: Math.round(h / 2), noCanvas: true });
      })()`);
      const { x, y, noCanvas } = JSON.parse(m);
      if (noCanvas) console.log('warning: found no point where the canvas is on top — the UI is covering all of it');
      console.log(`scrolling at (${x}, ${y})`);
      await cdp.send('Input.dispatchMouseEvent', { type: 'mouseWheel', x, y, deltaX: 0, deltaY: dy, pointerType: 'mouse' });
      await new Promise(r => setTimeout(r, 900));
      const after = await cdp.evaluate('+window.__camera.position.distanceTo(window.__controls.target).toFixed(3)');
      console.log(`camera distance ${before} -> ${after} (deltaY ${dy}) — ${before === after ? 'NO zoom, the page ignored it' : 'zoomed'}`);

    } else if (CMD === 'cam') {
      // Drive the camera from here, because the emulator window does not
      // reliably hand the mouse wheel to the guest and there is no way to
      // orbit/zoom by hand in it.
      //
      //   cam wheel fl --elev 0 --dist 7 --fov 12   frame a wheel down its axle
      //   cam orbit 25 10                           azimuth/elevation degrees
      //   cam zoom 0.6                              <1 closer, >1 further
      //   cam reset                                 back to the app's own view
      const sub = argv[1] || 'wheel';
      const num = (flag, dflt) => { const i = argv.indexOf(flag); return i >= 0 ? Number(argv[i + 1]) : dflt; };
      let expr;
      if (sub === 'wheel') {
        const side = (argv[2] && !argv[2].startsWith('--')) ? argv[2] : 'fl';
        const elev = num('--elev', 0), dist = num('--dist', 7), fov = num('--fov', 12);
        expr = `(() => {
          const a = window.__app, cam = window.__camera, ctl = window.__controls;
          const rim = (a._allWheels || []).filter(m => m._wheelSide === ${JSON.stringify(side)});
          if (!rim.length) return JSON.stringify({ error: 'no ${side} wheel' });
          if (!a.__camBeforeCdp) a.__camBeforeCdp = { pos: cam.position.clone(), tgt: ctl.target.clone(), fov: cam.fov, enabled: ctl.enabled };
          const P = rim[0]._pivot.clone(), ax = rim[0]._rollAxis.clone().normalize();
          ctl.enabled = false; cam.fov = ${fov};
          cam.position.copy(P).addScaledVector(ax, -${dist});
          cam.position.y += ${dist} * Math.tan(${elev} * Math.PI / 180);
          cam.up.set(0, 1, 0); cam.lookAt(P); ctl.target.copy(P);
          cam.updateProjectionMatrix(); cam.updateMatrixWorld(true);
          if (window.__renderOnce) window.__renderOnce();
          const p = P.clone().project(cam);
          return JSON.stringify({ side: ${JSON.stringify(side)}, elevDeg: ${elev}, distM: ${dist}, fov: ${fov},
            pivotScreen: [Math.round((p.x*0.5+0.5)*innerWidth), Math.round((-p.y*0.5+0.5)*innerHeight)] });
        })()`;
      } else if (sub === 'orbit') {
        const az = Number(argv[2]) || 0, el = Number(argv[3]) || 0;
        expr = `(() => {
          const cam = window.__camera, ctl = window.__controls, a = window.__app, T = a.THREE;
          if (!a.__camBeforeCdp) a.__camBeforeCdp = { pos: cam.position.clone(), tgt: ctl.target.clone(), fov: cam.fov, enabled: ctl.enabled };
          const off = cam.position.clone().sub(ctl.target);
          const sph = new T.Spherical().setFromVector3(off);
          sph.theta += ${az} * Math.PI / 180;
          sph.phi = Math.max(0.05, Math.min(Math.PI - 0.05, sph.phi - ${el} * Math.PI / 180));
          cam.position.copy(ctl.target).add(new T.Vector3().setFromSpherical(sph));
          cam.lookAt(ctl.target); cam.updateMatrixWorld(true);
          if (window.__renderOnce) window.__renderOnce();
          return JSON.stringify({ azimuthDeg: +(sph.theta*180/Math.PI).toFixed(1), elevationDeg: +(90 - sph.phi*180/Math.PI).toFixed(1), distM: +sph.radius.toFixed(2) });
        })()`;
      } else if (sub === 'zoom') {
        const f = Number(argv[2]) || 0.8;
        expr = `(() => {
          const cam = window.__camera, ctl = window.__controls, a = window.__app;
          if (!a.__camBeforeCdp) a.__camBeforeCdp = { pos: cam.position.clone(), tgt: ctl.target.clone(), fov: cam.fov, enabled: ctl.enabled };
          const off = cam.position.clone().sub(ctl.target).multiplyScalar(${f});
          cam.position.copy(ctl.target).add(off); cam.lookAt(ctl.target); cam.updateMatrixWorld(true);
          if (window.__renderOnce) window.__renderOnce();
          return JSON.stringify({ distM: +off.length().toFixed(2) });
        })()`;
      } else if (sub === 'reset') {
        expr = `(() => {
          const cam = window.__camera, ctl = window.__controls, a = window.__app, s = a.__camBeforeCdp;
          if (!s) { ctl.enabled = true; return JSON.stringify({ note: 'nothing saved; controls re-enabled' }); }
          cam.position.copy(s.pos); ctl.target.copy(s.tgt); cam.fov = s.fov; ctl.enabled = s.enabled;
          cam.updateProjectionMatrix(); cam.updateMatrixWorld(true);
          if (window.__renderOnce) window.__renderOnce();
          delete a.__camBeforeCdp;
          return JSON.stringify({ restored: true });
        })()`;
      } else {
        throw new Error(`cam: unknown "${sub}" — wheel | orbit | zoom | reset`);
      }
      console.log(await cdp.evaluate(expr));

    } else if (CMD === 'shot') {
      const out = argv[1] || 'device.png';
      const bytes = screencap(out);
      console.log(`wrote ${out} (${(bytes / 1024).toFixed(0)} kB)`);

    } else if (CMD === 'spin') {
      // node scripts/device-cdp.mjs spin 3 12,20,27,35,45 --fps 18
      const fi = argv.indexOf('--fps');
      await runSpin(cdp, {
        seconds: Number(argv[1]) || 3,
        speeds: (argv[2] || '10,20,30,40,60,90').split(',').map(Number),
        fpsCap: fi >= 0 ? Number(argv[fi + 1]) || 0 : 0,
      });

    } else if (CMD === 'rims') {
      // node scripts/device-cdp.mjs rims [outDir] [inch] [--rims a,b,c]
      const ri = argv.indexOf('--rims');
      await runRims(cdp, {
        outDir: argv[1] && !argv[1].startsWith('--') ? argv[1] : 'wheel-check',
        inch: Number(argv[2]) || 21,
        rims: ri >= 0 ? argv[ri + 1].split(',') : undefined,
      });

    } else if (CMD === 'verify') {
      // Everything that can be settled without the car, in one pass.
      const fi = argv.indexOf('--fps');
      const fpsCap = fi >= 0 ? Number(argv[fi + 1]) || 0 : 18;
      const outDir = argv[1] && !argv[1].startsWith('--') ? argv[1] : 'wheel-check';
      const url = await cdp.evaluate('location.href');
      console.log(`device ${SERIAL}, viewer pid ${cdp.pid}`);
      console.log(`page   ${url}`);
      console.log('');
      console.log('1. wheel centring — each rim framed head-on down its own axle.');
      console.log('   Compare the black tyre band ABOVE the rim with the band BELOW.');
      const rows = await runRims(cdp, { outDir, inch: 21 });
      console.log('');
      // Put a named rim back on first. Otherwise the sweep silently reports on
      // whichever rim the centring pass happened to leave loaded (the last one
      // in the list), and the numbers describe a rim nobody is looking at.
      const spinRim = (() => { const i = argv.indexOf('--spin-rim'); return i >= 0 ? argv[i + 1] : 'haval_phev'; })();
      const info = JSON.parse(await cdp.evaluate(`
        (() => {
          const a = window.__app;
          return new Promise(res => a._loadCatalogWheelByKey(${JSON.stringify(spinRim)}, () => setTimeout(() => {
            a._setMotionSpeed(0);
            res(JSON.stringify({ repeatDeg: +((a._rimRepeatRad || 0) * 180 / Math.PI).toFixed(1),
                                 info: a._rimRepeatInfo || null }));
          }, 1500)));
        })()`));
      console.log(`2. wheel spin on ${spinRim} at ~${fpsCap} fps (the MMI's sampling rate).`);
      console.log(`   repeat ${info.repeatDeg} deg${info.info ? ` (order ${info.info.order}, corr ${info.info.corr.toFixed(2)}, ${info.info.from || 'verts'}${info.info.weak ? ', weak' : ''})` : ''}`);
      console.log('   perceived should track actual; reverse should stay near 0.');
      const spin = await runSpin(cdp, { seconds: 3, speeds: [12, 20, 27, 35, 45], fpsCap });
      console.log('');
      const bad = rows.filter(r => r.error);
      console.log('summary');
      console.log(`  rims framed        ${rows.length - bad.length}/${rows.length}${bad.length ? ' — ' + bad.map(b => b.key).join(', ') + ' failed to load' : ''}`);
      console.log(`  worst reverse frac ${spin.worstReverse}  (was 0.92 before the anti-alias clamp)`);
      console.log(`  worst frozen frac  ${spin.worstFrozen}`);
      console.log('');
      console.log(`Open ${path.join(outDir, '_all-rims.png')} and check the tyre bands by eye.`);
      console.log('Anything that still looks wrong is worth fixing BEFORE the car —');
      console.log('the car only adds frame rate and thermals, not geometry.');

    } else {
      console.error(`unknown command "${CMD}" — verify | rims | spin | cam | serve | status | eval | nav | reset | shot | wheel`);
      process.exitCode = 1;
    }
  } finally {
    cdp.close();
  }
}

main().catch(e => { console.error(String(e.message || e)); process.exitCode = 1; });

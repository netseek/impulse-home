#!/usr/bin/env node
// Readability snapshots of the REAL launcher UI, headless, without the 3D bundle.
//
//   node scripts/snapshot-readability.mjs --out <dir>            # everything
//   node scripts/snapshot-readability.mjs --out <dir> --only power,popup-clima
//   node scripts/snapshot-readability.mjs --list
//
// It serves the working tree, opens index.html in headless Chrome as the Android
// shell (`?android&demo=1`: demo signals, labelled DEMO), hides the model canvas
// and the load-error overlay, and drives the live app object (`__app`) to put one
// surface on screen at a time: every widget at every size `_widgetCatalog()`
// allows, the card popups, the layout manager tabs, the wallpaper picker, the
// widget picker and the settings panel, in the dark and the light widget theme.
// Nothing here replaces CSS or templates, so a screenshot is the shipped markup.
//
// Output: <name>.png per surface plus metrics.json -- for each surface the
// smallest text, text drawn with opacity < 1, text that overflows its box and
// the visible strings, so a before/after run can be compared as numbers and
// the copy checked for banned phrases (see BANNED below).
//
// Fixtures: widgets render in a board whose cell is 342x194 (8 px gap, the cell
// the 1x1 budget is written against; the window is widened to fit six columns).
// Popups and screens render at the real 1920x720 with the bounds the code uses.
// The animations are frozen; the demo signals still move, so the pixels of
// graphs and numbers differ run to run -- compare layout and size, not bytes.
// Not covered: the 3D scene, the native rail, real signals, the car's GPU.

import { spawn, execFileSync } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const argv = process.argv.slice(2);
const opt = (name) => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : null; };
const OUT = path.resolve(opt('--out') || path.join(os.tmpdir(), 'impulse-readability'));
const ONLY = (opt('--only') || '').split(',').filter(Boolean);

const CELL = { w: 342, h: 194, gap: 8 };
const BOARD = { l: 48, t: 40 };
const THEMES = ['dark', 'light'];
const BANNED = ['DRAG TO ORBIT', 'SCROLL TO ZOOM', 'RIGHT-DRAG TO PAN', 'Microsoft Bing daily images',
  'same feed as Windows Spotlight', 'WIDGET UI', 'FULL SIZE', 'MEDIA UNAVAILABLE', 'REMAINING', 'ETA'];

// Widget types and sizes are read from the app (`_widgetCatalog`), not copied.
const CARD_POPUPS = ['power', 'driving', 'status', 'range', 'climate', 'consumption', 'media'];
const STUDIO_TABS = ['desktops', 'cards', 'widgets', 'appearance', 'clock'];

// ---------------------------------------------------------------- page side
// Installed once per page load; every helper returns plain JSON.
const PAGE = String.raw`(() => {
if (window.__snapH) return;
const H = window.__snapH = {};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
H.sleep = sleep;
H.boot = async function (theme, bounds, bg) {
  const a = window.__app;
  document.body.classList.remove('hv-boot', 'hv-splash-up');
  let st = document.getElementById('snap-style');
  if (!st) { st = document.createElement('style'); st.id = 'snap-style'; document.head.appendChild(st); }
  st.textContent = 'canvas,.hv-wallpaper,.hv-canvas-host{visibility:hidden!important}'
    + 'html,body,#hv-root{background:' + bg + '!important}'
    + '*,*::before,*::after{animation-duration:0s!important;animation-delay:0s!important;transition:none!important}';
  a.applyShellLayout({ mode: 'appCar', splitRatio: '50', right: 'idle', left: false,
    leftBounds: bounds.left, rightBounds: bounds.right,
    safeTop: 22, safeLeft: 48, safeRight: 48, safeBottom: 60, launcherBottom: 60 });
  H.theme(theme);
  await sleep(300);
  return Object.keys(a._widgetCatalog()).map((t) => ({ type: t, sizes: a._widgetSizes(t, 'appCar') }));
};
H.theme = function (theme) {
  const a = window.__app;
  const accent = theme === 'light' ? '#029eb6' : '#2aa7b7';
  a.setState({ error: null, loading: false, splashActive: false, widgetThemeMode: theme, accentKey: 'cyan',
    accentDark: '#2aa7b7', accentLight: '#029eb6', accentColor: accent });
};
H.reset = function () {
  const a = window.__app;
  if (a._hsRoofPopOpen) a._setRoofLevelPopup(false);
  a.setState({ graphEditId: null, focusedCardType: null, desktopStudioOpen: false, widgetPickerStep: null, widgetPlaceType: null,
    widgetPlaceW: 0, widgetPlaceH: 0, widgetMenuId: null, panelOpen: false, activeGroup: null,
    wallpaperPopupOpen: false, configExpanded: false, error: null, loading: false });
};
H.layout = function (items) {
  const a = window.__app;
  a._widgetLayout = { version: 2, appCar: { left: { use: 'widgets', items } } };
  a.setState((s) => ({ desktopRev: (s.desktopRev || 0) + 1 }));
};
H.run = async function (code) { return await (0, eval)('(async()=>{' + code + '})()'); };
// Visible text and its drawn size inside the element matching sel.
H.metrics = function (sel, banned) {
  const root = document.querySelector(sel);
  if (!root) return { missing: true };
  const texts = [];
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  let minFont = 1e9, low = [], clipped = [];
  const seen = new Set();
  while (walker.nextNode()) {
    const n = walker.currentNode;
    const s = n.nodeValue.replace(/\s+/g, ' ').trim();
    if (!s) continue;
    const el = n.parentElement;
    if (!el || el.closest('canvas,script,style,template')) continue;
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden') continue;
    const range = document.createRange();
    range.selectNodeContents(n);
    const rr = range.getBoundingClientRect();
    if (rr.width < 1 || rr.height < 1) continue;
    let op = 1;
    for (let e = el; e && e !== document.documentElement; e = e.parentElement) op *= parseFloat(getComputedStyle(e).opacity);
    if (op < 0.02) continue;
    const m = /rgba?\(([^)]+)\)/.exec(cs.color);
    const alpha = m && m[1].split(',').length > 3 ? parseFloat(m[1].split(',')[3]) : 1;
    const fs = parseFloat(cs.fontSize);
    const svg = !!el.closest('svg');
    const eff = svg ? fs * (el.getBoundingClientRect().height / Math.max(1, fs * 1.2)) : fs;
    const rec = { t: s.slice(0, 48), fs: Math.round(fs * 10) / 10, fw: cs.fontWeight, op: Math.round(op * 100) / 100,
      a: Math.round(alpha * 100) / 100, ls: cs.letterSpacing };
    const key = rec.t + '|' + rec.fs;
    if (!seen.has(key)) { seen.add(key); texts.push(rec); }
    if (!svg) minFont = Math.min(minFont, fs);
    if (op * alpha < 0.999) low.push(rec.t);
    if (el.scrollWidth > el.clientWidth + 1 && el.clientWidth > 0 && /hidden|clip|auto|scroll/.test(cs.overflowX + cs.overflow)
      && cs.display !== 'inline') clipped.push(rec.t);
    if (el.scrollHeight > el.clientHeight + 2 && el.clientHeight > 0 && /hidden|clip/.test(cs.overflowY) && cs.display !== 'inline') clipped.push(rec.t + ' (v)');
  }
  const joined = texts.map((x) => x.t).join(' | ');
  return { minFont: minFont === 1e9 ? null : minFont, below18: texts.filter((x) => x.fs < 18).length,
    count: texts.length, lowOpacity: Array.from(new Set(low)).slice(0, 12), clipped: Array.from(new Set(clipped)).slice(0, 12),
    banned: banned.filter((b) => joined.indexOf(b) >= 0), texts };
};
H.rect = function (sel) {
  const el = document.querySelector(sel);
  if (!el) return null;
  const r = el.getBoundingClientRect();
  return { x: r.x, y: r.y, width: r.width, height: r.height };
};
})()`;

// ------------------------------------------------------------------ server
function serve() {
  const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
    '.png': 'image/png', '.webp': 'image/webp', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.mjs': 'text/javascript' };
  const server = http.createServer((req, res) => {
    const rel = decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html';
    const file = path.resolve(ROOT, rel);
    if (!file.startsWith(ROOT + path.sep) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      res.writeHead(404); res.end('not found'); return;
    }
    res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port })));
}

// ------------------------------------------------------------------ chrome
function findChrome() {
  const candidates = process.env.CHROME_BIN ? [process.env.CHROME_BIN]
    : ['google-chrome', 'chromium', 'chromium-browser', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'];
  const found = candidates.find((c) => {
    try { execFileSync(c, ['--version'], { stdio: 'ignore', timeout: 5000 }); return true; } catch { return false; }
  });
  if (!found) throw new Error('Chrome/Chromium is required; set CHROME_BIN to its executable');
  return found;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function launch(url, width, height) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'impulse-snap-'));
  const proc = spawn(findChrome(), ['--headless=new', '--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
    '--hide-scrollbars', '--no-first-run', '--disable-background-networking', '--no-default-browser-check',
    '--remote-debugging-port=0', '--user-data-dir=' + dir, 'about:blank'], { stdio: 'ignore' });
  let port;
  for (let i = 0; i < 100 && !port; i++) {
    const f = path.join(dir, 'DevToolsActivePort');
    if (fs.existsSync(f)) port = fs.readFileSync(f, 'utf8').split('\n')[0];
    else await sleep(100);
  }
  if (!port) throw new Error('Chrome did not expose a DevTools port');
  const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
  const ws = new WebSocket(targets.find((t) => t.type === 'page').webSocketDebuggerUrl);
  await new Promise((r) => { ws.onopen = r; });
  let id = 0;
  const pending = new Map();
  ws.onmessage = (e) => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } };
  const send = (method, params = {}) => new Promise((resolve) => { const i = ++id; pending.set(i, resolve); ws.send(JSON.stringify({ id: i, method, params })); });
  await send('Runtime.enable');
  await send('Page.enable');
  await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url });
  const ev = async (js) => {
    const r = (await send('Runtime.evaluate', { expression: js, returnByValue: true, awaitPromise: true })).result;
    if (r.exceptionDetails) throw new Error('page: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
    return r.result?.value;
  };
  for (let i = 0; i < 200; i++) { if (await ev('typeof window.__app === "object" && !!window.__app.state')) break; await sleep(100); }
  await ev(PAGE);
  const shot = async (file, rect) => {
    const clip = rect ? { x: Math.max(0, rect.x), y: Math.max(0, rect.y), width: rect.width, height: rect.height, scale: 1 } : undefined;
    const s = await send('Page.captureScreenshot', { format: 'png', ...(clip ? { clip } : {}) });
    fs.writeFileSync(file, Buffer.from(s.result.data, 'base64'));
  };
  return { ev, shot, async close() {
    try { ws.close(); } catch {}
    const exited = new Promise((r) => proc.once('exit', r));
    proc.kill();
    await Promise.race([exited, sleep(3000)]);
    try { fs.rmSync(dir, { recursive: true, force: true, maxRetries: 8, retryDelay: 250 }); } catch {}
  } };
}

// --------------------------------------------------------------- scenarios
const widgetPx = (w, h) => ({ w: CELL.w * w + CELL.gap * (w - 1), h: CELL.h * h + CELL.gap * (h - 1) });
const matches = (name) => !ONLY.length || ONLY.some((o) => name.includes(o));
// With --only, skip the rounds (each is a page load) that cannot match.
const SCREEN = /^(board|popup|layout|picker|wallpaper|settings)/;
const wantsWidgets = !ONLY.length || ONLY.some((o) => !SCREEN.test(o));
const wantsScreens = !ONLY.length || ONLY.some((o) => !o.startsWith('widget'));
const results = {};

async function record(page, name, sel, rect) {
  if (!matches(name)) return;
  const metrics = JSON.parse(await page.ev(`JSON.stringify(__snapH.metrics(${JSON.stringify(sel)}, ${JSON.stringify(BANNED)}))`));
  results[name] = { minFont: metrics.minFont, below18: metrics.below18, texts: metrics.count, lowOpacity: metrics.lowOpacity,
    clipped: metrics.clipped, banned: metrics.banned, strings: metrics.texts.map((t) => `${t.t} [${t.fs}px/${t.fw}${t.op * t.a < 1 ? ' a' + Math.round(t.op * t.a * 100) : ''}]`) };
  await page.shot(path.join(OUT, name + '.png'), rect);
}

async function widgetRound(port, theme, listOnly) {
  // Six columns of 342 px plus the 48 px side bands the board code keeps.
  const width = 2 * BOARD.l + 6 * CELL.w + 5 * CELL.gap;
  const bounds = { left: { l: BOARD.l, t: BOARD.t, r: 740, b: BOARD.t + 2 * CELL.h + CELL.gap },
    right: { l: 1180, t: BOARD.t, r: 1872, b: BOARD.t + 2 * CELL.h + CELL.gap } };
  const bg = theme === 'light' ? 'linear-gradient(135deg,#f2f5f8,#aab4be)' : 'linear-gradient(135deg,#0b1016,#3a4552)';
  // One page per state: injected signals must not leak into the next state.
  const states = [{ id: '', setup: '' },
    { id: '-live', only: ['power', 'graphs', 'media'], theme: 'dark', setup: LIVE },
    { id: '-sem-sinal', only: ['power', 'media', 'range', 'consumption'], theme: 'dark', sizes: ['1x1', '2x1'], noDemo: true, setup: '' }];
  for (const state of states) {
    if (state.theme && state.theme !== theme) continue;
    const page = await launch(`http://127.0.0.1:${port}/index.html?android&demo=1`, width, 720);
    try {
      const catalog = JSON.parse(await page.ev(`__snapH.boot(${JSON.stringify(theme)}, ${JSON.stringify(bounds)}, ${JSON.stringify(bg)}).then(JSON.stringify)`));
      if (state.setup) await page.ev(state.setup + '; 1');
      for (const { type, sizes } of catalog) {
        if (state.only && !state.only.includes(type)) continue;
        for (const [w, h] of sizes) {
          if (state.sizes && !state.sizes.includes(w + 'x' + h)) continue;
          const name = `widget-${type}-${w}x${h}-${theme}${state.id}`;
          if (!matches(name)) continue;
          if (listOnly) { console.log(name); continue; }
          await page.ev(`(async()=>{__snapH.reset();${state.noDemo ? 'window.__app._demoPreview = false;' : ''}
            __snapH.layout([{id:'snap',type:${JSON.stringify(type)},x:0,y:0,w:${w},h:${h}}]);await __snapH.sleep(1100);})()`);
          const rect = JSON.parse(await page.ev(`JSON.stringify(__snapH.rect('.hv-widget-card'))`));
          if (!rect) { console.log('missing', name); continue; }
          const px = widgetPx(w, h);
          if (Math.abs(rect.width - px.w) > 1.5 || Math.abs(rect.height - px.h) > 1.5) console.log('size drift', name, rect.width, rect.height);
          await record(page, name, '.hv-widget-card', rect);
          console.log('wrote', name);
        }
      }
    } finally { await page.close(); }
  }
}

// Signals the thumbnail tool also injects: a charging EV and a playing track.
const LIVE = String.raw`(() => {
  const a = window.__app;
  a._applyCarSignal('car.ev_info.cur_battery_power_percentage', '64');
  a._applyCarSignal('car.ev_info.power_battery_voltage', '382');
  a._applyCarSignal('car.ev_info.cur_charge_current', '-31');
  a._applyCarSignal('haval.power.flow', 'v1|ev|0|1|1');
  a._graphSource = function () { return 'LIVE'; };
  const c = document.createElement('canvas'); c.width = c.height = 160;
  const g = c.getContext('2d');
  const grd = g.createLinearGradient(0, 0, 160, 160);
  grd.addColorStop(0, '#1d3b5a'); grd.addColorStop(0.55, '#7a3f8f'); grd.addColorStop(1, '#f08a5d');
  g.fillStyle = grd; g.fillRect(0, 0, 160, 160);
  a.applyMediaNowPlaying({ title: 'Midnight City', artist: 'M83', album: 'Album', playing: true,
    durationMs: 243000, positionMs: 96000, appLabel: 'Spotify', hasTrack: true, canLaunch: true, artDataUrl: c.toDataURL('image/png') });
})();`;

async function screenRound(port, theme, listOnly) {
  const bounds = { left: { l: BOARD.l, t: BOARD.t, r: 740, b: 500 }, right: { l: 1180, t: BOARD.t, r: 1872, b: 500 } };
  const page = await launch(`http://127.0.0.1:${port}/index.html?android&demo=1`, 1920, 720);
  try {
    const bg = theme === 'light' ? 'linear-gradient(135deg,#f2f5f8,#aab4be)' : 'linear-gradient(135deg,#0b1016,#3a4552)';
    await page.ev(`__snapH.boot(${JSON.stringify(theme)}, ${JSON.stringify(bounds)}, ${JSON.stringify(bg)}).then(JSON.stringify)`);
    const full = { x: 0, y: 0, width: 1920, height: 720 };
    const board = `__snapH.layout([{id:'a',type:'power',x:0,y:0,w:2,h:1},{id:'b',type:'driving',x:2,y:0,w:2,h:1},{id:'c',type:'range',x:4,y:0,w:2,h:1},
      {id:'d',type:'status',x:0,y:1,w:2,h:1},{id:'e',type:'consumption',x:2,y:1,w:2,h:1},{id:'f',type:'climate',x:4,y:1,w:2,h:1}]);`;
    const scenes = [
      { name: 'board-mix', setup: board, sel: '#hv-root', rect: full },
      ...CARD_POPUPS.map((t) => ({ name: 'popup-' + t, setup: board + `__app.setState({focusedCardType:'${t}'});`, sel: '.hv-card-focus' })),
      ...STUDIO_TABS.map((t) => ({ name: 'layout-' + t, setup: (t === 'widgets' ? '__snapH.layout([]);' : board) + `__app._openDesktopStudio('${t}', true);`, sel: '.hv-desktop-studio' })),
      { name: 'popup-roof', setup: board + `__app._openRoofLevelPopup();`, sel: '.hv-hs-roof-pop' },
      { name: 'popup-graphs-editor', setup: `__snapH.layout([{id:'g',type:'graphs',x:0,y:0,w:3,h:2}]);__app.setState({graphEditId:'g',graphEditSide:'left'});`, sel: '.hv-graph-editor' },
      { name: 'picker-slot', setup: `__snapH.layout([]);__app._openWidgetPicker('slot');`, sel: '#hv-root', rect: full },
      { name: 'picker-type', setup: `__snapH.layout([]);__app._openWidgetPicker('type');__app.setState({widgetPlaceSide:'left',widgetPlaceAnchorX:0,widgetPlaceAnchorY:0});`, sel: '.hv-widget-picker' },
      { name: 'picker-size', setup: `__snapH.layout([]);__app._openWidgetPicker('size');__app.setState({widgetPlaceSide:'left',widgetPlaceAnchorX:0,widgetPlaceAnchorY:0,widgetPlaceType:'power'});`, sel: '.hv-widget-picker' },
      { name: 'wallpaper', setup: `__snapH.layout([]);__app.setState({wallpaperPopupOpen:true});`, sel: '.hv-wallpaper-pop' },
      { name: 'settings-layout', setup: `__snapH.layout([]);__app.openLayoutPanel();`, sel: '#hv-root', rect: full },
      { name: 'settings-camera', setup: `__snapH.layout([]);__app.toggleCameraRail(true);`, sel: '#hv-root', rect: full },
      ...['paint', 'wheel', 'tune', 'advanced'].map((t) => ({ name: 'settings-' + t, setup: `__snapH.layout([]);__app._openConfigTab('${t}');`, sel: '#hv-root', rect: full })),
    ];
    for (const sc of scenes) {
      const name = `${sc.name}-${theme}`;
      if (listOnly) { console.log(name); continue; }
      if (!matches(name)) continue;
      await page.ev(`(async()=>{__snapH.reset();${sc.setup};await __snapH.sleep(1500);})()`);
      const rect = sc.rect || JSON.parse(await page.ev(`JSON.stringify(__snapH.rect(${JSON.stringify(sc.sel)}))`));
      if (!rect) { console.log('missing', name); continue; }
      await record(page, name, sc.sel, rect);
      console.log('wrote', name);
    }
  } finally { await page.close(); }
}

// -------------------------------------------------------------------- main
const listOnly = argv.includes('--list');
if (!listOnly) fs.mkdirSync(OUT, { recursive: true });
const { server, port } = await serve();
try {
  for (const theme of THEMES) {
    if (wantsWidgets) await widgetRound(port, theme, listOnly);
    if (wantsScreens) await screenRound(port, theme, listOnly);
  }
  if (!listOnly) {
    fs.writeFileSync(path.join(OUT, 'metrics.json'), JSON.stringify(results, null, 1));
    const bad = Object.entries(results).filter(([, r]) => r.banned.length);
    console.log(`${Object.keys(results).length} snapshots in ${OUT}; banned phrases in ${bad.length}`);
  }
} finally { server.close(); }
process.exit(0);

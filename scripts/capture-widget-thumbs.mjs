// Re-shoot the widget picker thumbnails from the REAL widgets, rendered by the
// app itself on a device (the Haval AVD, or the car), at 2x1, dark and light.
//
//   node scripts/capture-widget-thumbs.mjs            # all types
//   node scripts/capture-widget-thumbs.mjs media power
//   node scripts/capture-widget-thumbs.mjs --serial emulator-5554
//
// Writes assets/ui/widget-thumbs/<type>-<dark|light>.webp. Re-run it whenever a
// widget's 2x1 layout changes -- hand-drawn thumbnails went stale exactly that
// way (owner, 2026-09-21).
//
// What it does to the running app, and undoes:
//  - swaps the widget board, theme and accent in memory (never persisted by
//    this script; the desktop-shot and energy-price keys that the app itself
//    re-writes while widgets are up are snapshotted and put back);
//  - injects sample POWER signals and a now-playing track for the one round
//    that shows those two, then reverts every field those signals touch. A
//    leftover injection makes the bus read as live and turns Range, Consumo
//    and Graphs into live-but-empty cards -- this happened once, so restore
//    runs in `finally`.
//  - hides provenance tags ("DEMO · SIMULATED") and the 3D canvas while shooting.
//
// No reload, no navigation: the WebView keeps whatever it was showing.
// Clock is not shot: its thumbnail renders the live <h6-clock> in the user's
// chosen face, which a screenshot could not follow.

import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sharp = createRequire(path.join(ROOT, 'package.json'))('sharp');
const OUT = path.join(ROOT, 'assets', 'ui', 'widget-thumbs');
const THUMB_W = 440;          // 2x the ~220 px the picker shows it at
const CARD_RADIUS = 12;       // .hv-widget-card border-radius, CSS px

const argv = process.argv.slice(2);
const serialAt = argv.indexOf('--serial');
const serialArgs = serialAt >= 0 ? ['--serial', argv.splice(serialAt, 2)[1]] : [];
const only = argv;

function adbPath() {
  const p = process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe');
  return p && fs.existsSync(p) ? p : 'adb';
}
const ev = (js) => execFileSync('node', [path.join(ROOT, 'scripts', 'device-cdp.mjs'), ...serialArgs, 'eval', js],
  { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim().split('\n').pop();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function screencap() {
  const s = serialArgs.length ? ['-s', serialArgs[1]] : [];
  return execFileSync(adbPath(), [...s, 'exec-out', 'screencap', '-p'], { maxBuffer: 64 << 20 });
}

// Page side. Installed once per eval (each eval is a fresh CDP attach, but the
// page and its window globals persist).
const PAGE = String.raw`(() => {
window.__thumbCap = window.__thumbCap || {};
const T = window.__thumbCap;
T.save = function () {
  const a = __app;
  if (T.saved) return 'already';
  T.saved = {
    layout: JSON.parse(JSON.stringify(a._widgetLayout)),
    st: ['widgetThemeMode', 'accentKey', 'accentColor', 'accentDark', 'accentLight'].reduce((o, k) => (o[k] = a.state[k], o), {}),
    ls: ['h6_desktop_shots_v2', 'h6_energy_prices'].reduce((o, k) => (o[k] = localStorage.getItem(k), o), {}),
  };
  return 'saved';
};
T.prep = async function (theme, items, inject) {
  const a = __app;
  a._widgetLayout = { version: 2, appCar: { left: { use: 'widgets', items } } };
  a.setState({ widgetThemeMode: theme, accentKey: 'cyan', accentDark: '#2aa7b7', accentLight: '#029eb6',
    accentColor: theme === 'light' ? '#029eb6' : '#2aa7b7', panelOpen: false, widgetPickerStep: null, widgetMenuId: null });
  let st = document.getElementById('thumb-cap-style');
  if (!st) { st = document.createElement('style'); st.id = 'thumb-cap-style'; document.head.appendChild(st); }
  st.textContent = '.hv-wallpaper,.hv-canvas-host{visibility:hidden!important}html,body,#hv-root{background:'
    + (theme === 'light' ? '#dfe5ea' : '#0b1016') + '!important}';
  a._graphSource = function () { return 'LIVE'; };
  if (inject) T.inject();
  clearInterval(T.stripTimer);
  T.stripTimer = setInterval(T.strip, 30);
  await new Promise((r) => setTimeout(r, 1500));
  document.querySelectorAll('.hv-widget-card').forEach((el, k) => { if (items[k]) el.dataset.thumbCap = items[k].type; });
  return document.querySelectorAll('.hv-widget-card').length;
};
T.strip = function () {
  const re = /\b(DEMO|SIMULADO|SIMULATED|NÃO É DO VEÍCULO|NOT VEHICLE|ROUTE PREVIEW)\b/gi;
  document.querySelectorAll('.hv-widget-card').forEach((card) => {
    const w = document.createTreeWalker(card, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (w.nextNode()) nodes.push(w.currentNode);
    nodes.forEach((n) => {
      if (!re.test(n.nodeValue)) return;
      re.lastIndex = 0;
      n.nodeValue = n.nodeValue.replace(re, '').replace(/(\s*·\s*)+/g, ' · ').replace(/^\s*·\s*|\s*·\s*$/g, '').trim();
    });
  });
};
T.inject = function () {
  const a = __app;
  if (!T.undo) {
    T.undo = { at: Object.assign({}, a._carSignalAt || {}), busAt: a._carBusAt, live: Object.assign({}, a._powerLive || {}),
      V: a._graphV, I: a._graphI, any: a._graphAnyLive, keys: Object.assign({}, a._graphLiveKeys || {}),
      gat: Object.assign({}, a._graphLiveAt || {}), vals: Object.assign({}, a._graphValues || {}), busCount: a._busCount };
  }
  // CAR_SIGNALS is module-scoped, not a page global: literal keys.
  a._applyCarSignal('car.ev_info.cur_battery_power_percentage', '64');
  a._applyCarSignal('car.ev_info.power_battery_voltage', '382');
  a._applyCarSignal('car.ev_info.cur_charge_current', '-31');
  a._applyCarSignal('haval.power.flow', 'v1|ev|0|1|1');
  const c = document.createElement('canvas'); c.width = c.height = 160;
  const g = c.getContext('2d');
  const grd = g.createLinearGradient(0, 0, 160, 160);
  grd.addColorStop(0, '#1d3b5a'); grd.addColorStop(0.55, '#7a3f8f'); grd.addColorStop(1, '#f08a5d');
  g.fillStyle = grd; g.fillRect(0, 0, 160, 160);
  g.globalAlpha = 0.35; g.fillStyle = '#fff'; g.beginPath(); g.arc(112, 52, 34, 0, Math.PI * 2); g.fill();
  a.applyMediaNowPlaying({ title: 'Midnight City', artist: 'M83', album: 'Hurry Up, We\'re Dreaming', playing: true,
    durationMs: 243000, positionMs: 96000, appLabel: 'Spotify', hasTrack: true, canLaunch: true, artDataUrl: c.toDataURL('image/png') });
};
T.uninject = function () {
  const a = __app, u = T.undo;
  if (!u) return;
  a._carSignalAt = u.at; a._carBusAt = u.busAt; a._powerLive = u.live;
  a._graphV = u.V; a._graphI = u.I; a._graphAnyLive = u.any; a._graphLiveKeys = u.keys; a._graphLiveAt = u.gat;
  a._graphValues = u.vals; a._busCount = u.busCount;
  if (a._queuePowerRefresh) a._queuePowerRefresh();
  a.applyMediaNowPlaying({ hasTrack: false, playing: false });
  T.undo = null;
};
T.restore = function () {
  const a = __app;
  T.uninject();
  clearInterval(T.stripTimer);
  delete a._graphSource;
  const st = document.getElementById('thumb-cap-style'); if (st) st.remove();
  const s = T.saved;
  if (s) {
    a._widgetLayout = s.layout;
    a.setState(Object.assign({}, s.st, { _thumbCap: Date.now() }));
    for (const k in s.ls) { if (s.ls[k] == null) localStorage.removeItem(k); else localStorage.setItem(k, s.ls[k]); }
    try { a._desktopShots = JSON.parse(s.ls.h6_desktop_shots_v2 || '{}'); } catch (e) {}
    T.saved = null;
  }
  return 'restored';
};
})()`;

const GROUPS = [
  { types: ['profile', 'navigation', 'driving', 'status', 'range'],
    // The demo route cycles through an idle leg; wait for active guidance.
    ready: `/Restante/.test((document.querySelector('[data-thumb-cap=navigation]')||{}).innerText||'')` },
  { types: ['graphs', 'climate', 'consumption'],
    ready: `!/desligado/i.test((document.querySelector('[data-thumb-cap=consumption]')||{}).innerText||'')` },
  { types: ['power', 'media'], inject: true },
].map((g) => ({ ...g, types: g.types.filter((t) => !only.length || only.includes(t)) }))
  .filter((g) => g.types.length);

async function roundedWebp(png, r, file) {
  const scale = THUMB_W / r.w;
  const w = Math.round(r.w), h = Math.round(r.h), rad = CARD_RADIUS;
  const mask = Buffer.from(`<svg width="${w}" height="${h}"><rect width="${w}" height="${h}" rx="${rad}" ry="${rad}"/></svg>`);
  const cut = await sharp(png).extract({ left: Math.round(r.x), top: Math.round(r.y), width: w, height: h })
    .composite([{ input: mask, blend: 'dest-in' }]).png().toBuffer();
  await sharp(cut).resize({ width: THUMB_W, height: Math.round(h * scale) }).webp({ quality: 84, alphaQuality: 90 }).toFile(file);
}

fs.mkdirSync(OUT, { recursive: true });
console.log(ev(PAGE + ';window.__thumbCap.save()'));
try {
  for (const theme of ['dark', 'light']) {
    for (const g of GROUPS) {
      const items = g.types.map((t, k) => ({ id: 'thumb-' + t, type: t, x: (k % 3) * 2, y: Math.floor(k / 3), w: 2, h: 1 }));
      ev(PAGE + `;window.__thumbCap.prep(${JSON.stringify(theme)}, ${JSON.stringify(items)}, ${!!g.inject})`);
      await sleep(2500);
      // The demo feed keeps moving, so the ready state can lapse between the
      // check and the screencap: re-check afterwards and re-shoot if it did.
      let rects, png;
      for (let attempt = 0; attempt < 6; attempt++) {
        for (let k = 0; g.ready && k < 80; k++) {
          if (ev(`String(${g.ready})`) === 'true') break;
          await sleep(400);
        }
        rects = JSON.parse(ev(`window.__thumbCap.strip(); JSON.stringify(${JSON.stringify(g.types)}.map((t) => {
          const el = document.querySelector('[data-thumb-cap="' + t + '"]'); const r = el && el.getBoundingClientRect();
          return r ? { t, x: r.x * devicePixelRatio, y: r.y * devicePixelRatio, w: r.width * devicePixelRatio, h: r.height * devicePixelRatio } : { t, missing: true }; }))`));
        png = screencap();
        if (!g.ready || ev(`String(${g.ready})`) === 'true') break;
        console.log('ready state lapsed during the shot, retrying');
      }
      if (g.inject) ev(PAGE + ';window.__thumbCap.uninject(); 1');
      for (const r of rects) {
        if (r.missing) { console.log('missing', r.t); continue; }
        const file = path.join(OUT, `${r.t}-${theme}.webp`);
        await roundedWebp(png, r, file);
        console.log('wrote', path.relative(ROOT, file), fs.statSync(file).size, 'B');
      }
    }
  }
} finally {
  console.log(ev(PAGE + ';window.__thumbCap.restore()'));
}

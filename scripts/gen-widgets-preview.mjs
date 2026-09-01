#!/usr/bin/env node
/**
 * Build widgets-preview.html from live index.html widget CSS + markup shells.
 * Cell sizes match the MMI board (~342×194 per cell, 8px gap).
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const indexHtml = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');

function extractWidgetCss(html) {
  let css = '';
  let i = 0;
  while (true) {
    const s = html.indexOf('<style>', i);
    if (s < 0) break;
    const e = html.indexOf('</style>', s);
    const block = html.slice(s + 7, e);
    if (block.includes('.hv-power') && block.includes('.hv-graphs')) css = block;
    i = e + 8;
  }
  if (!css) throw new Error('no widget CSS <style> block in index.html');
  const lines = css.split('\n');
  const out = [];
  let buf = [];
  let depth = 0;
  let keep = false;
  const flush = () => {
    if (keep && buf.length) out.push(buf.join('\n'));
    buf = [];
    keep = false;
  };
  for (const line of lines) {
    const opens = (line.match(/{/g) || []).length;
    const closes = (line.match(/}/g) || []).length;
    if (depth === 0) keep = /hv-widget-card|hv-power|hv-graphs|@keyframes hv-power/.test(line);
    buf.push(line);
    depth += opens - closes;
    if (depth <= 0) {
      depth = 0;
      flush();
    }
  }
  return out;
}

const keep = extractWidgetCss(indexHtml);
if (keep.length < 20) throw new Error('too few CSS blocks extracted: ' + keep.length);

const CELL_W = 342;
const CELL_H = 194;
const GAP = 8;
const sizePx = (w, h) => ({
  w: CELL_W * w + GAP * (w - 1),
  h: CELL_H * h + GAP * (h - 1),
});

const POWER_SIZES = [[1, 1], [1, 2], [2, 1], [3, 1], [2, 2]];
// Widget footprint + representative pane split (app caps panes at 2×2).
const GRAPH_SIZES = [
  { w: 1, h: 1, cols: 1, rows: 1 },
  { w: 2, h: 1, cols: 2, rows: 1 },
  { w: 3, h: 1, cols: 2, rows: 1 },
  { w: 2, h: 2, cols: 2, rows: 2 },
  { w: 3, h: 2, cols: 2, rows: 2 },
];

function powerInner(sizeClass, showRanges) {
  const ranges = showRanges ? `
    <div class="hv-power-ranges">
      <div class="hv-power-stat"><div class="hv-power-stat-kicker">EV</div><div class="hv-power-stat-value">42<span>km</span></div></div>
      <div class="hv-power-stat"><div class="hv-power-stat-kicker">GAS</div><div class="hv-power-stat-value">510<span>km</span></div></div>
    </div>` : '';
  return `<div class="hv-power ${sizeClass} ev" data-card>
    <div class="hv-power-stage">
      <div class="hv-power-car">
        <div class="hv-power-car-frame">
        <div class="hv-power-floor" style="--soc: 64;">
          <img class="hv-power-floor-ring" src="assets/power/floor_ring.png?v=4" alt="">
          <svg viewBox="0 0 240 56" preserveAspectRatio="none" aria-hidden="true">
            <path class="hv-power-floor-track" pathLength="100" d="M 5.4 18.1 A 122 23 0 1 0 234.6 18.1"></path>
            <path class="hv-power-floor-fill" pathLength="100" d="M 5.4 18.1 A 122 23 0 1 0 234.6 18.1"></path>
          </svg>
        </div>
        <img class="hv-power-car-img" src="assets/power/car.webp?v=4" alt="">
        <div class="hv-power-flow-layer" aria-hidden="true">
          <div class="hv-power-pipe front"></div>
          <div class="hv-power-pipe rear"></div>
          <div class="hv-power-lane front drive"></div>
          <div class="hv-power-lane rear"></div>
        </div>
        <img class="hv-power-ice" src="assets/power/engine.webp?v=4" alt="">
        <img class="hv-power-motor front on" src="assets/power/motor_on_blue.webp?v=6" alt="">
        <img class="hv-power-motor rear" src="assets/power/motor.webp?v=6" alt="">
        <div class="hv-power-batt on ev" style="--soc: 64%;">
          <img class="hv-power-batt-base" src="assets/power/battery.webp?v=6" alt="">
          <div class="hv-power-batt-fill" style="clip-path: polygon(20.4% 0%, 55.5% 0%, 58.4% 100%, 17.9% 100%);">
            <svg viewBox="0 0 140 60" preserveAspectRatio="none" aria-hidden="true">
              <path class="hv-power-batt-para" d="M 28.5 12.2 L 105.5 11.3 Q 107.5 11.1 108 13 L 111 30.5 Q 111.5 32.3 109.5 32.5 L 25 32.5 Q 23 32.4 23.3 30.5 L 26.5 14 Q 26.6 12.4 28.5 12.2 Z"></path>
            </svg>
          </div>
        </div>
        <div class="hv-power-charge-cable" aria-hidden="true">
          <svg viewBox="0 0 100 48" preserveAspectRatio="none">
            <path class="cable-glow" d="M 100 16 C 82 16, 68 42, 50 42 S 28 30, 22.5 20"></path>
            <path class="cable-core" d="M 100 16 C 82 16, 68 42, 50 42 S 28 30, 22.5 20"></path>
            <path class="cable-flow" d="M 100 16 C 82 16, 68 42, 50 42 S 28 30, 22.5 20"></path>
            <circle class="cable-plug" cx="18.5" cy="18.5" r="4.2"></circle>
          </svg>
        </div>
        <div class="hv-power-floor-label">
          <span class="hv-power-ring-num">64</span>
          <span class="hv-power-ring-unit">%</span>
        </div>
        </div>
      </div>
    </div>${ranges}
  </div>`;
}

function graphPane(title, value, unit, i) {
  return `<div class="hv-graphs-pane">
    <div class="hv-graphs-head">
      <div class="hv-graphs-kicker">${title}</div>
      <div class="hv-graphs-readout">
        <span class="hv-graphs-mode">LIVE</span>
        <span class="hv-graphs-value">${value}</span>
        <span class="hv-graphs-unit">${unit}</span>
      </div>
    </div>
    <div class="hv-graphs-plot">
      <canvas class="hv-graphs-canvas" data-seed="${i}"></canvas>
    </div>
  </div>`;
}

const PANE_TITLES = [
  ['EV POWER', '12.4', 'kW'],
  ['SOC', '64', '%'],
  ['SPEED', '48', 'km/h'],
  ['EV INST', '12.4', 'kWh/100'],
];

function graphInner(w, h, cols, rows) {
  const n = cols * rows;
  const split = n > 1 ? ' hv-graphs-split' : '';
  const panes = [];
  for (let i = 0; i < n; i++) {
    const t = PANE_TITLES[i % PANE_TITLES.length];
    panes.push(graphPane(t[0], t[1], t[2], i));
  }
  return `<div class="hv-graphs hv-graphs-${w}x${h}${split}" style="grid-template-columns:repeat(${cols},minmax(0,1fr));grid-template-rows:repeat(${rows},minmax(0,1fr))">
    ${panes.join('\n    ')}
  </div>`;
}

let powerSections = '';
for (const [w, h] of POWER_SIZES) {
  const { w: pw, h: ph } = sizePx(w, h);
  const showRanges = w >= 2 || h >= 2;
  powerSections += `
<h2>POWER ${w} × ${h} — ${pw} × ${ph}</h2>
<div class="hv-widget-card" style="width:${pw}px;height:${ph}px;">
  ${powerInner(`hv-power-${w}x${h}`, showRanges)}
</div>
`;
}

let graphSections = '';
for (const g of GRAPH_SIZES) {
  const { w: pw, h: ph } = sizePx(g.w, g.h);
  const cols = Math.min(g.cols, 2, g.w);
  const rows = Math.min(g.rows, 2, g.h);
  const note = cols * rows > 1 ? ` · panes ${cols}×${rows}` : '';
  graphSections += `
<h2>GRAPHS ${g.w} × ${g.h}${note} — ${pw} × ${ph}</h2>
<div class="hv-widget-card" style="width:${pw}px;height:${ph}px;">
  ${graphInner(g.w, g.h, cols, rows)}
</div>
`;
}

const out = `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>Widget sizes — power + graphs</title>
<link href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@600;700&family=Space+Mono:wght@400;700&display=swap" rel="stylesheet">
<style>
  html, body { margin: 0; background: #0b1016; color: #eaf2f8; font-family: 'Space Grotesk', sans-serif; }
  body { padding: 24px 28px 64px; }
  h1 { font-family: 'Space Mono', monospace; font-size: 14px; letter-spacing: .16em; margin: 0 0 6px; }
  .note { font-family: 'Space Mono', monospace; font-size: 11px; opacity: .45; margin: 0 0 22px; max-width: 820px; line-height: 1.5; }
  h2 { font-family: 'Space Mono', monospace; font-size: 12px; letter-spacing: .14em; opacity: .6; margin: 28px 0 10px; }
  h2.section { opacity: .9; letter-spacing: .18em; margin-top: 40px; border-top: 1px solid rgba(255,255,255,.1); padding-top: 24px; }
  .row { display: flex; flex-wrap: wrap; gap: 16px; align-items: flex-start; }
  .row > .hv-widget-card { flex: 0 0 auto; }
  /* extracted from index.html */
${keep.join('\n')}
</style>
</head>
<body>
<h1>WIDGET SIZES — POWER + GRAPHS</h1>
<p class="note">Cell grid ≈ 342×194 + 8px gap (MMI board). CSS pulled from live index.html. Power EV demo state @ 64% SOC. Graphs panes use a mock sparkline — layout only.</p>

<h2 class="section">POWER</h2>
${powerSections}

<h2 class="section">GRAPHS</h2>
${graphSections}

<script>
(function () {
  function spark(canvas, seed) {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const w = canvas.clientWidth || 120;
    const h = canvas.clientHeight || 80;
    canvas.width = Math.max(1, Math.floor(w * dpr));
    canvas.height = Math.max(1, Math.floor(h * dpr));
    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    const n = 48;
    const pts = [];
    for (let i = 0; i < n; i++) {
      const t = i / (n - 1);
      const y = 0.55 + 0.28 * Math.sin(t * 6.2 + seed * 1.7) + 0.12 * Math.sin(t * 14 + seed);
      pts.push({ x: 8 + t * (w - 16), y: 10 + (1 - y) * (h - 22) });
    }
    ctx.beginPath();
    ctx.moveTo(pts[0].x, h);
    for (let i = 0; i < pts.length; i++) ctx.lineTo(pts[i].x, pts[i].y);
    ctx.lineTo(pts[pts.length - 1].x, h);
    ctx.closePath();
    ctx.fillStyle = 'rgba(79,214,232,0.12)';
    ctx.fill();
    ctx.beginPath();
    ctx.moveTo(pts[0].x, pts[0].y);
    for (let i = 1; i < pts.length; i++) ctx.lineTo(pts[i].x, pts[i].y);
    ctx.strokeStyle = '#4fd6e8';
    ctx.lineWidth = 1.5;
    ctx.stroke();
  }
  function paintAll() {
    document.querySelectorAll('.hv-graphs-canvas').forEach((c) => {
      spark(c, parseInt(c.getAttribute('data-seed'), 10) || 0);
    });
  }
  paintAll();
  window.addEventListener('resize', paintAll);
})();
</script>
</body>
</html>
`;

const outPath = path.join(ROOT, 'widgets-preview.html');
fs.writeFileSync(outPath, out);
console.log('wrote', outPath, '(' + keep.length + ' css blocks)');

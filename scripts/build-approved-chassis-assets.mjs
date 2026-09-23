// Build the Power card/widget/popup chassis art from the approved renders in
// assets/_source/power/{hev2,phev19,phev34}.png (1536x1024, front on the LEFT,
// near-white studio backdrop, some with baked English labels).
//
//   node scripts/build-approved-chassis-assets.mjs          # write PNGs + print layout
//   node scripts/build-approved-chassis-assets.mjs --debug  # also write red-backed previews
//
// Output: assets/power/graphics/approved-<key>-chassis.png, 350x770, front at
// the TOP, transparent ground. The overlay geometry (battery, motors, routes)
// is authored below in SOURCE pixels and printed in the 350x770 space that
// CAR_POWER_GRAPHICS (index.html) and QuickCardGraphicView.drawPower
// (MainActivity.java) use -- paste the printed block into both.
//
// Why the matte is not a colour key: the frame openings show shadowed backdrop
// at ~220 grey and the silver engine / HEV battery sit at 170-205, so any
// brightness threshold either leaves the openings filled or eats the silver
// parts (the first version of this script did the latter). The backdrop is
// instead flood-filled across SMOOTH neutral pixels only -- every part edge is
// a gradient barrier -- from the border plus named interior openings. What the
// flood reaches becomes black with alpha = how much darker it is than the
// studio backdrop, so the contact shadows survive as real soft shadows on a
// dark card and on a light one, instead of as grey blotches.
import { createRequire } from 'node:module';
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const sharp = require('sharp');
const DEBUG = process.argv.includes('--debug');
const srcDir = path.join(root, 'assets/_source/power');
const outDir = path.join(root, 'assets/power/graphics');
const OUT_W = 350, OUT_H = 770, MARGIN = 6;

// Everything in source pixels (x along the car, front = small x).
const VARIANTS = {
  phev34: {
    labels: [ // [x0,y0,x1,y1, 'bright'] -- text lighter than the panel it sits on
      [212, 398, 266, 426], [196, 536, 294, 596], [742, 480, 850, 506], [1270, 450, 1360, 518],
    ],
    openings: [],
    battery: [500, 305, 1100, 683],        // pack lid
    engine: [158, 335, 318, 488],
    frontMotor: [160, 505, 345, 630],
    rearMotor: [1235, 405, 1395, 565],
    // Energy routes, drawn battery -> motor -> wheel for positive (drive) flow.
    front: [[500, 560], [420, 560], [345, 567]],
    iceL: [[268, 335], [268, 272]], iceR: [[268, 488], [268, 700]],
    iceBatt: [[318, 420], [420, 420], [500, 420]],
    fl: [[245, 505], [247, 275]], fr: [[245, 630], [247, 700]],
    rear: [[1100, 485], [1170, 485], [1235, 485]],
    rl: [[1287, 405], [1285, 272]], rr: [[1287, 565], [1285, 700]],
    hubs: [[247, 210], [247, 765], [1285, 210], [1285, 765]],
  },
  phev19: {
    labels: [[210, 390, 250, 414], [195, 518, 290, 590], [700, 474, 810, 528]],
    openings: [[450, 480], [1050, 480], [1300, 490]],
    battery: [551, 325, 969, 655],
    engine: [150, 330, 340, 470],
    frontMotor: [155, 480, 315, 625],
    front: [[551, 545], [440, 545], [315, 550]],
    iceL: [[265, 330], [265, 280]], iceR: [[265, 470], [265, 695]],
    iceBatt: [[340, 400], [450, 400], [551, 400]],
    fl: [[240, 480], [242, 280]], fr: [[240, 625], [242, 695]],
    hubs: [[245, 215], [245, 770], [1285, 215], [1285, 770]],
  },
  hev2: {
    labels: [],
    openings: [[770, 480], [1290, 480]],
    battery: [952, 395, 1124, 556],
    engine: [135, 320, 320, 465],
    frontMotor: [150, 475, 300, 600],
    // HV cable: battery -> along the lower rail -> past the tank -> motor.
    front: [[952, 530], [915, 606], [620, 608], [385, 560], [300, 545]],
    // Generator path along the UPPER rail, so it never shares the HV cable's.
    iceL: [[255, 320], [255, 280]], iceR: [[255, 465], [255, 690]],
    iceBatt: [[320, 420], [390, 352], [915, 352], [952, 440]],
    fl: [[228, 475], [232, 280]], fr: [[228, 600], [232, 690]],
    hubs: [[235, 205], [237, 770], [1290, 205], [1290, 775]],
  },
};

const lum = (r, g, b) => 0.299 * r + 0.587 * g + 0.114 * b;

/** Remove baked text: mask text pixels (dilated), then diffuse the panel in. */
function inpaintLabels(data, W, H, boxes) {
  for (const [x0, y0, x1, y1] of boxes) {
    const w = x1 - x0 + 1, h = y1 - y0 + 1;
    // Panel reference = median luminance of the box border ring.
    const ring = [];
    for (let x = x0; x <= x1; x++) for (const y of [y0, y1]) { const o = (y * W + x) * 3; ring.push(lum(data[o], data[o + 1], data[o + 2])); }
    for (let y = y0; y <= y1; y++) for (const x of [x0, x1]) { const o = (y * W + x) * 3; ring.push(lum(data[o], data[o + 1], data[o + 2])); }
    ring.sort((a, b) => a - b);
    const panel = ring[ring.length >> 1];
    let mask = new Uint8Array(w * h);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const o = ((y + y0) * W + x + x0) * 3;
      if (lum(data[o], data[o + 1], data[o + 2]) > panel + 12) mask[y * w + x] = 1;
    }
    for (let pass = 0; pass < 2; pass++) { // dilate: cover anti-aliased glyph edges
      const next = mask.slice();
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        if (mask[y * w + x]) continue;
        if ((x && mask[y * w + x - 1]) || (x < w - 1 && mask[y * w + x + 1]) || (y && mask[(y - 1) * w + x]) || (y < h - 1 && mask[(y + 1) * w + x])) next[y * w + x] = 1;
      }
      mask = next;
    }
    // Diffusion fill: each masked pixel converges to the mean of its neighbours.
    for (let it = 0; it < 400; it++) {
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        if (!mask[y * w + x]) continue;
        const X = x + x0, Y = y + y0, o = (Y * W + X) * 3;
        for (let c = 0; c < 3; c++) {
          data[o + c] = (data[o - 3 + c] + data[o + 3 + c] + data[o - W * 3 + c] + data[o + W * 3 + c]) / 4;
        }
      }
    }
  }
}

async function build(key, cfg) {
  const src = path.join(srcDir, key + '.png');
  const { data: raw, info } = await sharp(src).removeAlpha().raw().toBuffer({ resolveWithObject: true });
  const W = info.width, H = info.height, N = W * H;
  const data = Float32Array.from(raw);
  inpaintLabels(data, W, H, cfg.labels);

  const L = new Float32Array(N), C = new Float32Array(N);
  for (let i = 0; i < N; i++) {
    const r = data[i * 3], g = data[i * 3 + 1], b = data[i * 3 + 2];
    L[i] = lum(r, g, b); C[i] = Math.max(r, g, b) - Math.min(r, g, b);
  }
  // Gradient magnitude on a 3x3 neighbourhood (max abs difference).
  const G = new Float32Array(N);
  for (let y = 1; y < H - 1; y++) for (let x = 1; x < W - 1; x++) {
    const i = y * W + x; let m = 0;
    for (let j = -1; j <= 1; j++) for (let k = -1; k <= 1; k++) m = Math.max(m, Math.abs(L[i + j * W + k] - L[i]));
    G[i] = m;
  }
  // Studio backdrop brightness: a coarse field of bright-neutral medians, so the
  // vignette/gradient of the render is not read as shadow.
  const CELL = 64, gw = Math.ceil(W / CELL), gh = Math.ceil(H / CELL), field = new Float32Array(gw * gh).fill(NaN);
  for (let gy = 0; gy < gh; gy++) for (let gx = 0; gx < gw; gx++) {
    const vals = [];
    for (let y = gy * CELL; y < Math.min(H, (gy + 1) * CELL); y += 2) for (let x = gx * CELL; x < Math.min(W, (gx + 1) * CELL); x += 2) {
      const i = y * W + x; if (L[i] > 228 && C[i] < 10) vals.push(L[i]);
    }
    if (vals.length > 200) { vals.sort((a, b) => a - b); field[gy * gw + gx] = vals[vals.length >> 1]; }
  }
  const known = Array.from(field).filter((v) => !isNaN(v));
  const fallback = known.reduce((a, b) => a + b, 0) / known.length;
  for (let pass = 0; pass < 64 && field.some((v) => isNaN(v)); pass++) {
    const next = field.slice();
    for (let gy = 0; gy < gh; gy++) for (let gx = 0; gx < gw; gx++) {
      if (!isNaN(field[gy * gw + gx])) continue;
      let s = 0, n = 0;
      for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
        const X = gx + dx, Y = gy + dy; if (X < 0 || Y < 0 || X >= gw || Y >= gh) continue;
        const v = field[Y * gw + X]; if (!isNaN(v)) { s += v; n++; }
      }
      if (n) next[gy * gw + gx] = s / n;
    }
    field.set(next);
  }
  const backdrop = (x, y) => {
    const fx = Math.min(gw - 1.001, Math.max(0, x / CELL - 0.5)), fy = Math.min(gh - 1.001, Math.max(0, y / CELL - 0.5));
    const x0 = fx | 0, y0 = fy | 0, tx = fx - x0, ty = fy - y0;
    const v = (X, Y) => { const f = field[Y * gw + X]; return isNaN(f) ? fallback : f; };
    return (v(x0, y0) * (1 - tx) + v(x0 + 1, y0) * tx) * (1 - ty) + (v(x0, y0 + 1) * (1 - tx) + v(x0 + 1, y0 + 1) * tx) * ty;
  };

  // Flood the ground: neutral, not too dark, and SMOOTH. Parts have edges.
  const ground = new Uint8Array(N), queue = new Int32Array(N);
  let head = 0, tail = 0;
  const passable = (i) => C[i] < 14 && L[i] > 120 && G[i] < 13;
  const push = (i) => { if (!ground[i] && passable(i)) { ground[i] = 1; queue[tail++] = i; } };
  for (let x = 0; x < W; x++) { push(x); push((H - 1) * W + x); }
  for (let y = 0; y < H; y++) { push(y * W); push(y * W + W - 1); }
  for (const [x, y] of cfg.openings) {
    const i = y * W + x;
    if (!passable(i)) console.warn(`  ${key}: opening seed ${x},${y} is not passable (L=${L[i].toFixed(0)} C=${C[i].toFixed(0)} G=${G[i].toFixed(0)})`);
    push(i);
  }
  while (head < tail) {
    const i = queue[head++], x = i % W;
    if (x > 0) push(i - 1); if (x < W - 1) push(i + 1);
    if (i >= W) push(i - W); if (i < N - W) push(i + W);
  }
  // Enclosed pockets of ground (between a tyre and its suspension arms) are
  // cut off from the border by edges on every side. Accept a pocket when it is
  // bright like shadowed backdrop (215-235) AND clear of every named part: the
  // silver castings have smooth highlights up to 255, so brightness alone ate
  // slivers of the engine.
  const parts = [cfg.engine, cfg.frontMotor, cfg.rearMotor, cfg.battery].filter(Boolean);
  const inPart = (i) => { const x = i % W, y = (i / W) | 0; return parts.some(([a, b, c, d]) => x >= a - 6 && x <= c + 6 && y >= b - 6 && y <= d + 6); };
  const seen = new Uint8Array(N), comp = new Int32Array(N);
  let pockets = 0;
  for (let s = 0; s < N; s++) {
    if (ground[s] || seen[s] || !passable(s)) continue;
    let h = 0, t = 0, sum = 0, touchesPart = false; comp[t++] = s; seen[s] = 1;
    while (h < t) {
      const i = comp[h++], x = i % W; sum += L[i]; if (!touchesPart && inPart(i)) touchesPart = true;
      for (const j of [x > 0 ? i - 1 : -1, x < W - 1 ? i + 1 : -1, i - W, i + W]) {
        if (j < 0 || j >= N || seen[j] || ground[j] || !passable(j)) continue;
        seen[j] = 1; comp[t++] = j;
      }
    }
    if (t >= 80 && !touchesPart && sum / t >= 212) { for (let k = 0; k < t; k++) ground[comp[k]] = 1; pockets++; }
  }
  if (pockets) console.log(`  ${key}: ${pockets} enclosed ground pockets`);
  // Close pinholes: an isolated non-ground pixel surrounded by ground is noise.
  for (let y = 1; y < H - 1; y++) for (let x = 1; x < W - 1; x++) {
    const i = y * W + x; if (ground[i]) continue;
    if (ground[i - 1] + ground[i + 1] + ground[i - W] + ground[i + W] >= 4) ground[i] = 1;
  }

  // Compose straight-alpha RGBA.
  const rgba = Buffer.alloc(N * 4);
  const R = 3; // edge band radius
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const i = y * W + x, o = i * 4, s = i * 3;
    if (ground[i]) {
      // Ground: shadow only. Black, alpha = relative darkening vs the backdrop.
      const d = 1 - L[i] / backdrop(x, y);
      const a = Math.max(0, Math.min(0.62, (d - 0.025) * 1.35));
      rgba[o + 3] = Math.round(a * 255);
      continue;
    }
    let r = data[s], g = data[s + 1], b = data[s + 2], a = 1;
    // Edge band: unmix the backdrop out of pixels that straddle the silhouette.
    let nearGround = false;
    for (let j = -1; j <= 1 && !nearGround; j++) for (let k = -1; k <= 1; k++) {
      const X = x + k, Y = y + j; if (X >= 0 && Y >= 0 && X < W && Y < H && ground[Y * W + X]) { nearGround = true; break; }
    }
    if (nearGround) {
      let br = 0, bg = 0, bb = 0, bn = 0, fr = 0, fg = 0, fb = 0, fn = 0;
      for (let j = -R; j <= R; j++) for (let k = -R; k <= R; k++) {
        const X = x + k, Y = y + j; if (X < 0 || Y < 0 || X >= W || Y >= H) continue;
        const q = Y * W + X, qs = q * 3;
        if (ground[q]) { br += data[qs]; bg += data[qs + 1]; bb += data[qs + 2]; bn++; }
        else if (Math.abs(k) + Math.abs(j) >= 2) { fr += data[qs]; fg += data[qs + 1]; fb += data[qs + 2]; fn++; }
      }
      if (bn && fn) {
        br /= bn; bg /= bn; bb /= bn; fr /= fn; fg /= fn; fb /= fn;
        const dx = fr - br, dy = fg - bg, dz = fb - bb, den = dx * dx + dy * dy + dz * dz;
        if (den > 900) { // only where object and ground differ enough to unmix
          a = Math.max(0, Math.min(1, ((r - br) * dx + (g - bg) * dy + (b - bb) * dz) / den));
          if (a > 0.02) { r = br + (r - br) / a; g = bg + (g - bg) / a; b = bb + (b - bb) / a; }
        }
      }
    }
    rgba[o] = Math.max(0, Math.min(255, Math.round(r)));
    rgba[o + 1] = Math.max(0, Math.min(255, Math.round(g)));
    rgba[o + 2] = Math.max(0, Math.min(255, Math.round(b)));
    rgba[o + 3] = Math.round(a * 255);
  }

  // Crop to the car (ignore faint shadow), rotate 90 CW (front -> top), fit.
  let minX = W, minY = H, maxX = 0, maxY = 0;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    if (rgba[(y * W + x) * 4 + 3] > 160 && !ground[y * W + x]) { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
  }
  const pad = 14; // keep some shadow beyond the silhouette
  minX = Math.max(0, minX - pad); minY = Math.max(0, minY - pad); maxX = Math.min(W - 1, maxX + pad); maxY = Math.min(H - 1, maxY + pad);
  const cw = maxX - minX + 1, ch = maxY - minY + 1;          // source crop
  const rw = ch, rh = cw;                                      // rotated
  const scale = Math.min((OUT_W - 2 * MARGIN) / rw, (OUT_H - 2 * MARGIN) / rh);
  const dw = Math.round(rw * scale), dh = Math.round(rh * scale);
  const offX = Math.round((OUT_W - dw) / 2), offY = Math.round((OUT_H - dh) / 2);
  // 90 CW: rotated (u,v) = (maxY - y, x - minX).
  const map = ([x, y]) => [+((maxY - y) * (dw / rw) + offX).toFixed(1), +((x - minX) * (dh / rh) + offY).toFixed(1)];
  const mapBox = ([x0, y0, x1, y1]) => {
    const a = map([x0, y1]), b = map([x1, y0]);
    return [a[0], a[1], +(b[0] - a[0]).toFixed(1), +(b[1] - a[1]).toFixed(1)];
  };

  // Resize in premultiplied space so the unmixed edge colours do not bleed.
  // Two pipelines: sharp reorders extract/rotate/resize within one.
  const crop = await sharp(rgba, { raw: { width: W, height: H, channels: 4 } })
    .extract({ left: minX, top: minY, width: cw, height: ch }).raw().toBuffer();
  const cropped = await sharp(crop, { raw: { width: cw, height: ch, channels: 4 } }).rotate(90)
    .resize(dw, dh, { kernel: 'lanczos3', fit: 'fill' }).raw().toBuffer();
  const png = await sharp({ create: { width: OUT_W, height: OUT_H, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } } })
    .composite([{ input: cropped, raw: { width: dw, height: dh, channels: 4 }, left: offX, top: offY }])
    .png({ compressionLevel: 9, palette: false }).toBuffer();
  fs.writeFileSync(path.join(outDir, `approved-${key}-chassis.png`), png);

  if (DEBUG) {
    const dbg = path.join(root, 'build', 'power-chassis-debug');
    fs.mkdirSync(dbg, { recursive: true });
    for (const [name, bgc] of [['red', '#d02828'], ['dark', '#1c2831'], ['light', '#f8fafb']]) {
      await sharp(png).flatten({ background: bgc }).png().toFile(path.join(dbg, `${key}-${name}.png`));
    }
  }

  const out = {
    battery: mapBox(cfg.battery), engine: mapBox(cfg.engine), frontMotor: mapBox(cfg.frontMotor),
    front: cfg.front.map(map), fl: cfg.fl.map(map), fr: cfg.fr.map(map), hubs: cfg.hubs.map(map),
    iceL: cfg.iceL.map(map), iceR: cfg.iceR.map(map), iceBatt: cfg.iceBatt.map(map),
  };
  if (cfg.rearMotor) Object.assign(out, { rearMotor: mapBox(cfg.rearMotor), rear: cfg.rear.map(map), rl: cfg.rl.map(map), rr: cfg.rr.map(map) });
  console.log(`${key}: ${png.length} bytes, car ${dw}x${dh} at ${offX},${offY}`);
  return out;
}

const layout = {};
for (const [key, cfg] of Object.entries(VARIANTS)) layout[key] = await build(key, cfg);
console.log('\n// 350x770 overlay geometry -- paste into CAR_POWER_GRAPHICS and drawPower:');
for (const [key, g] of Object.entries(layout)) {
  console.log(key + ': ' + Object.entries(g).map(([k, v]) => k + ':' + JSON.stringify(v)).join(', '));
}

// Build the CLIMA popup's cabin images from assets/_source/clima/*.jpg.
//
//   node scripts/build-clima-cabin.mjs [--height=480]
//
// One image per airflow mode (plus `off`), in two themes.
//
// The sources are light-background renders. Two things have to happen or the
// cabin reads as a pasted-on rectangle inside the popup:
//
//   * the render's own background is remapped to the popup's surface colour —
//     for night that means flipping the neutral tones, NOT inverting the
//     image, because an invert turns the blue airflow arrows orange;
//   * the outer edge fades to transparent, so whatever is left of the
//     background blends instead of ending in a straight line.
//
// Coloured pixels (the arrows) keep their hue in both themes.
//
// Output: assets/ui/clima/<mode>-{day,night}.webp
import sharp from 'sharp';
import fs from 'node:fs';
import path from 'node:path';

const SRC = 'assets/_source/clima';
const OUT = 'assets/ui/clima';
// Dark renders, one per mode. Each replaces the remapped light image for night.
// The backdrop is removed by a flood fill from the border: the cabin floor is
// almost as dark as that backdrop, so a brightness key punches the floor out.
const NIGHT_SRC = 'assets/_source/clima/night';
const MODES = ['off', 'face', 'facefeet', 'feet', 'feetglass'];
const height = Number((process.argv.find((a) => a.startsWith('--height=')) || '--height=480').split('=')[1]);
// The popup surfaces these sit on (--hv-frost-fill-strong, flattened).
const NIGHT_BG = [14, 20, 27];
const SOURCE_BG = 236;
const FEATHER = 26;
// The render's backdrop is a soft vignette (lum 228-240), not a flat SOURCE_BG.
// Night ink below this floor is backdrop and keys out to transparent.
const NIGHT_INK_FLOOR = 10;

fs.mkdirSync(OUT, { recursive: true });

function remap(data, info, theme) {
  const out = Buffer.alloc(info.width * info.height * 4);
  for (let y = 0; y < info.height; y++) {
    const edge = Math.min(y, info.height - 1 - y);
    for (let x = 0; x < info.width; x++) {
      const i = (y * info.width + x) * info.channels;
      const o = (y * info.width + x) * 4;
      const r = data[i], g = data[i + 1], b = data[i + 2];
      const lum = r * 0.299 + g * 0.587 + b * 0.114;
      // 0 for a grey pixel, 1 for a saturated one. The arrows sit at 0.2-0.5.
      const chroma = Math.min(1, (Math.max(r, g, b) - Math.min(r, g, b)) / 60);
      for (let c = 0; c < 3; c++) {
        let neutral;
        if (theme === 'day') {
          // Lift the render's background to the popup's near-white surface.
          neutral = Math.min(255, Math.round(data[i + c] * 1.045));
        } else {
          // Ink: how far this pixel is below the render's background.
          const ink = Math.max(0, SOURCE_BG - NIGHT_INK_FLOOR - lum);
          neutral = Math.max(0, Math.min(235, Math.round(NIGHT_BG[c] + ink * 0.8)));
        }
        const tinted = theme === 'day'
          ? data[i + c]
          : Math.min(255, Math.round(data[i + c] * 0.55 + 40));
        out[o + c] = Math.round(neutral * (1 - chroma) + tinted * chroma);
      }
      const d = Math.min(edge, x, info.width - 1 - x);
      let alpha = d >= FEATHER ? 1 : d / FEATHER;
      if (theme === 'night') {
        // The dark popup is a translucent gradient, not a flat NIGHT_BG, so a
        // baked background shows as a box. Colour-to-alpha against NIGHT_BG:
        // every night pixel sits at or above it, so this inverts exactly and
        // over NIGHT_BG it composites back to the same picture.
        let a = 0;
        for (let c = 0; c < 3; c++) a = Math.max(a, (out[o + c] - NIGHT_BG[c]) / (255 - NIGHT_BG[c]));
        for (let c = 0; c < 3; c++) {
          out[o + c] = a > 1 / 255 ? Math.min(255, Math.round(NIGHT_BG[c] + (out[o + c] - NIGHT_BG[c]) / a)) : 0;
        }
        // The backdrop is faintly blue, so chroma tints it; matte it out by luminance.
        const matte = Math.min(1, Math.max(0, (SOURCE_BG - NIGHT_INK_FLOOR - lum) / 12));
        alpha *= a > 1 / 255 ? a * matte : 0;
      }
      out[o + 3] = Math.round(alpha * 255);
    }
  }
  return out;
}

// One crop box for every mode, measured on `off`. Trimming each image on its
// own shifts and rescales the car by a few pixels, so it visibly jumps when the
// airflow mode changes.
const probe = await sharp(path.join(SRC, 'off.jpg')).trim({ threshold: 8 }).toBuffer({ resolveWithObject: true });
const box = {
  left: -probe.info.trimOffsetLeft, top: -probe.info.trimOffsetTop,
  width: probe.info.width, height: probe.info.height,
};
console.log('crop', JSON.stringify(box));

for (const mode of MODES) {
  const src = path.join(SRC, `${mode}.jpg`);
  if (!fs.existsSync(src)) { console.warn('missing', src); continue; }
  const { data, info } = await sharp(src).extract(box)
    .resize({ height, fit: 'inside' }).removeAlpha().raw().toBuffer({ resolveWithObject: true });
  for (const theme of ['day', 'night']) {
    const rgba = remap(data, info, theme);
    await sharp(rgba, { raw: { width: info.width, height: info.height, channels: 4 } })
      .webp({ quality: 82, alphaQuality: 90 }).toFile(path.join(OUT, `${mode}-${theme}.webp`));
  }
  const nightSrc = path.join(NIGHT_SRC, `${mode}.webp`);
  if (fs.existsSync(nightSrc)) await buildNightRender(nightSrc, path.join(OUT, `${mode}-night.webp`));
  const kb = (f) => Math.round(fs.statSync(path.join(OUT, f)).size / 1024);
  console.log(`${mode}: ${info.width}x${info.height}  day ${kb(`${mode}-day.webp`)} kB  night ${kb(`${mode}-night.webp`)} kB`);
}

// A dark render sits on its own opaque backdrop, which reads as a box on the
// translucent popup. Flood-fill that backdrop in from the border and drop it,
// leaving the cabin — including the dark floor — opaque.
async function buildNightRender(src, out) {
  const { data, info } = await sharp(src).resize({ height, fit: 'inside' })
    .removeAlpha().raw().toBuffer({ resolveWithObject: true });
  const { width: w, height: h } = info;
  const border = [[], [], []];
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if (x > 2 && y > 2 && x < w - 3 && y < h - 3) continue;
    for (let c = 0; c < 3; c++) border[c].push(data[(y * w + x) * 3 + c]);
  }
  const bg = border.map((v) => v.sort((a, b) => a - b)[v.length >> 1]);
  const nearBg = (i) => {
    for (let c = 0; c < 3; c++) if (Math.abs(data[i + c] - bg[c]) > 18) return false;
    return true;
  };
  const drop = new Uint8Array(w * h);
  const queue = [];
  const push = (x, y) => {
    const p = y * w + x;
    if (drop[p] || !nearBg(p * 3)) return;
    drop[p] = 1;
    queue.push(p);
  };
  for (let x = 0; x < w; x++) { push(x, 0); push(x, h - 1); }
  for (let y = 0; y < h; y++) { push(0, y); push(w - 1, y); }
  for (let q = 0; q < queue.length; q++) {
    const p = queue[q], x = p % w, y = (p - x) / w;
    if (x > 0) push(x - 1, y);
    if (x + 1 < w) push(x + 1, y);
    if (y > 0) push(x, y - 1);
    if (y + 1 < h) push(x, y + 1);
  }
  const rgba = Buffer.alloc(w * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const p = y * w + x, i = p * 3, o = p * 4;
    const d = Math.min(y, h - 1 - y, x, w - 1 - x);
    const edge = d >= FEATHER ? 1 : d / FEATHER;
    const a = drop[p] ? 0 : edge;
    rgba[o] = Math.round(data[i] * a);
    rgba[o + 1] = Math.round(data[i + 1] * a);
    rgba[o + 2] = Math.round(data[i + 2] * a);
    rgba[o + 3] = Math.round(a * 255);
  }
  const blurred = await sharp(rgba, { raw: { width: w, height: h, channels: 4 } })
    .blur(0.6).ensureAlpha().raw().toBuffer();
  const straight = Buffer.alloc(w * h * 4);
  for (let p = 0; p < w * h; p++) {
    const o = p * 4;
    const a = blurred[o + 3] / 255;
    straight[o + 3] = blurred[o + 3];
    if (a > 1 / 255) {
      straight[o] = Math.max(0, Math.min(255, Math.round(blurred[o] / a)));
      straight[o + 1] = Math.max(0, Math.min(255, Math.round(blurred[o + 1] / a)));
      straight[o + 2] = Math.max(0, Math.min(255, Math.round(blurred[o + 2] / a)));
    }
  }
  await sharp(straight, { raw: { width: w, height: h, channels: 4 } })
    .webp({ quality: 82, alphaQuality: 90 }).toFile(out);
  console.log(`  night render ${path.basename(src)} bg ${bg.join(',')}`);
}

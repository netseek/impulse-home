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
const MODES = ['off', 'face', 'facefeet', 'feet', 'feetglass'];
const height = Number((process.argv.find((a) => a.startsWith('--height=')) || '--height=480').split('=')[1]);
// The popup surfaces these sit on (--hv-frost-fill-strong, flattened).
const NIGHT_BG = [14, 20, 27];
const SOURCE_BG = 236;
const FEATHER = 26;

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
          const ink = Math.max(0, SOURCE_BG - lum);
          neutral = Math.max(0, Math.min(235, Math.round(NIGHT_BG[c] + ink * 0.8)));
        }
        const tinted = theme === 'day'
          ? data[i + c]
          : Math.min(255, Math.round(data[i + c] * 0.55 + 40));
        out[o + c] = Math.round(neutral * (1 - chroma) + tinted * chroma);
      }
      const d = Math.min(edge, x, info.width - 1 - x);
      out[o + 3] = d >= FEATHER ? 255 : Math.round((d / FEATHER) * 255);
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
  const kb = (f) => Math.round(fs.statSync(path.join(OUT, f)).size / 1024);
  console.log(`${mode}: ${info.width}x${info.height}  day ${kb(`${mode}-day.webp`)} kB  night ${kb(`${mode}-night.webp`)} kB`);
}

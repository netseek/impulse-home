/**
 * Builds the surface-detail maps the powertrain model uses.
 *
 * Base colour stays a per-material factor in the GLB, so nothing here carries
 * colour — each set only contributes surface character:
 *   <set>_mr.jpg   metallicRoughness: G = roughness, B = 255 (metalness comes
 *                  from the material's own metallicFactor)
 *   <set>_n.jpg    normal map, OpenGL convention (what glTF expects)
 *
 * Metals are synthesised rather than photographed. ambientCG's metal scans were
 * measured first and rejected: their variance comes from rust, paint loss and
 * staining (Metal062C roughness sigma 40, Metal053C sigma 45), which paints a
 * clean chassis as corroded. The ones that were smooth enough to pass as
 * machined were flat instead — Metal032/Metal048A normal sigma ~0.5 of 255.
 * What a machined casting actually needs is fine, high-frequency, low-amplitude
 * detail, which is cheap to generate and exactly controllable.
 *
 * Rubber is the exception and stays CC0 from ambientCG — a real tyre scan beats
 * anything synthetic here. It is fetched on demand into node_modules/.cache and
 * never committed; only the downsampled packs under assets/power/tex/ are.
 *
 *   node scripts/build-powertrain-textures.mjs
 */
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import sharp from 'sharp';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CACHE = path.join(ROOT, 'node_modules', '.cache', 'ambientcg');
const OUT = path.join(ROOT, 'assets', 'power', 'tex');
const SIZES = [512, 256];

/* ------------------------------------------------------------------- noise */

const hash = (x, y, seed) => {
  let h = x * 374761393 + y * 668265263 + seed * 2246822519;
  h = (h ^ (h >>> 13)) * 1274126177;
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
};
const smooth = (t) => t * t * (3 - 2 * t);

/** Tiling value noise. `cx`/`cy` differ to stretch the grain (brushed metal). */
function valueNoise(size, cx, cy, seed) {
  const out = new Float32Array(size * size);
  for (let y = 0; y < size; y++) {
    const fy = (y / size) * cy, y0 = Math.floor(fy), ty = smooth(fy - y0);
    for (let x = 0; x < size; x++) {
      const fx = (x / size) * cx, x0 = Math.floor(fx), tx = smooth(fx - x0);
      const a = hash(x0 % cx, y0 % cy, seed), b = hash((x0 + 1) % cx, y0 % cy, seed);
      const c = hash(x0 % cx, (y0 + 1) % cy, seed), d = hash((x0 + 1) % cx, (y0 + 1) % cy, seed);
      out[y * size + x] = (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty;
    }
  }
  return out;
}

/** Fractal sum of tiling octaves, normalised to 0..1. */
function fbm(size, baseX, baseY, octaves, seed) {
  const out = new Float32Array(size * size);
  let amp = 1, sum = 0;
  for (let o = 0; o < octaves; o++) {
    const n = valueNoise(size, baseX << o, baseY << o, seed + o * 71);
    for (let i = 0; i < out.length; i++) out[i] += n[i] * amp;
    sum += amp;
    amp *= 0.5;
  }
  for (let i = 0; i < out.length; i++) out[i] /= sum;
  return out;
}

/** Height field -> tangent-space normal map, OpenGL (+Y up) convention. */
function heightToNormal(height, size, strength) {
  const rgb = Buffer.alloc(size * size * 3);
  const at = (x, y) => height[((y + size) % size) * size + ((x + size) % size)];
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const dx = (at(x + 1, y) - at(x - 1, y)) * strength;
      const dy = (at(x, y + 1) - at(x, y - 1)) * strength;
      const len = Math.hypot(dx, dy, 1);
      const i = (y * size + x) * 3;
      rgb[i] = Math.round((-dx / len * 0.5 + 0.5) * 255);
      rgb[i + 1] = Math.round((dy / len * 0.5 + 0.5) * 255);
      rgb[i + 2] = Math.round((1 / len * 0.5 + 0.5) * 255);
    }
  }
  return rgb;
}

/* --------------------------------------------------------------- procedural */

/**
 * grainX/grainY set the anisotropy: equal is cast/blasted, a wide ratio is
 * brushed. lo/hi bound the roughness band — a narrow band is what keeps this
 * reading as one machined surface rather than as dirt.
 */
const PROCEDURAL = {
  machined: { grainX: 96, grainY: 96, octaves: 4, lo: 0.30, hi: 0.52, relief: 1.6, seed: 11 },
  brushed:  { grainX: 320, grainY: 10, octaves: 3, lo: 0.14, hi: 0.34, relief: 1.1, seed: 23 },
  trim:     { grainX: 64, grainY: 64, octaves: 4, lo: 0.55, hi: 0.82, relief: 2.2, seed: 37 },
  cell:     { grainX: 160, grainY: 24, octaves: 3, lo: 0.38, hi: 0.60, relief: 1.4, seed: 53 },
};

async function buildProcedural(name, spec, size) {
  const h = fbm(size, spec.grainX / 8 | 0 || 1, spec.grainY / 8 | 0 || 1, spec.octaves, spec.seed);

  const mr = Buffer.alloc(size * size * 3);
  for (let i = 0; i < size * size; i++) {
    mr[i * 3] = 0;
    mr[i * 3 + 1] = Math.round((spec.lo + (spec.hi - spec.lo) * h[i]) * 255);
    mr[i * 3 + 2] = 255;
  }
  const normal = heightToNormal(h, size, spec.relief);
  return { mr, normal, size };
}

/* --------------------------------------------------------------- ambientCG */

async function ensureSource(id) {
  const dir = path.join(CACHE, id);
  if (fs.existsSync(dir) && fs.readdirSync(dir).length) return dir;
  await fsp.mkdir(dir, { recursive: true });

  const api = `https://ambientcg.com/api/v2/full_json?type=Material&id=${id}&include=downloadData`;
  const meta = await (await fetch(api, { signal: AbortSignal.timeout(20000) })).json();
  const asset = (meta.foundAssets || [])[0];
  if (!asset) throw new Error(`ambientCG has no asset "${id}"`);
  const zips = asset.downloadFolders.default.downloadFiletypeCategories.zip.downloads;
  const pick = zips.find((d) => d.attribute === '1K-JPG') || zips[0];

  const zip = path.join(CACHE, `${id}.zip`);
  const res = await fetch(pick.downloadLink, { signal: AbortSignal.timeout(180000) });
  if (!res.ok) throw new Error(`${id}: HTTP ${res.status}`);
  await fsp.writeFile(zip, Buffer.from(await res.arrayBuffer()));
  // No zip reader in core; PowerShell ships with one on this platform.
  execFileSync('powershell', ['-NoProfile', '-Command',
    `Expand-Archive -Path '${zip}' -DestinationPath '${dir}' -Force`], { stdio: 'ignore' });
  await fsp.unlink(zip);
  return dir;
}

const findMap = (dir, suffix) => {
  const hit = fs.readdirSync(dir).find((f) => f.endsWith(`_${suffix}.jpg`));
  if (!hit) throw new Error(`no ${suffix} map in ${dir}`);
  return path.join(dir, hit);
};

async function buildScanned(id, size) {
  const dir = await ensureSource(id);
  const rough = await sharp(findMap(dir, 'Roughness')).resize(size, size).greyscale().raw().toBuffer();
  const mr = Buffer.alloc(size * size * 3);
  for (let i = 0; i < size * size; i++) {
    mr[i * 3] = 0;
    mr[i * 3 + 1] = rough[i];
    mr[i * 3 + 2] = 255;
  }
  const normal = await sharp(findMap(dir, 'NormalGL')).resize(size, size).removeAlpha().raw().toBuffer();
  return { mr, normal, size };
}

/* ------------------------------------------------------------------- write */

await fsp.mkdir(OUT, { recursive: true });
const report = [];

async function emit(name, source, size) {
  const tag = size === SIZES[0] ? '' : `@${size}`;
  const { mr, normal } = source;
  const raw = { width: size, height: size, channels: 3 };

  const mrPath = path.join(OUT, `${name}_mr${tag}.jpg`);
  await sharp(mr, { raw }).jpeg({ quality: 88, chromaSubsampling: '4:4:4' }).toFile(mrPath);
  const nPath = path.join(OUT, `${name}_n${tag}.jpg`);
  await sharp(normal, { raw }).jpeg({ quality: 92, chromaSubsampling: '4:4:4' }).toFile(nPath);

  report.push([`${name}${tag}`,
    (fs.statSync(mrPath).size / 1024).toFixed(0) + ' KB',
    (fs.statSync(nPath).size / 1024).toFixed(0) + ' KB']);
}

for (const size of SIZES) {
  for (const [name, spec] of Object.entries(PROCEDURAL)) {
    await emit(name, await buildProcedural(name, spec, size), size);
  }
  await emit('rubber', await buildScanned('Rubber004', size), size);
}

const total = fs.readdirSync(OUT).reduce((n, f) => n + fs.statSync(path.join(OUT, f)).size, 0);
for (const [set, mr, n] of report) {
  console.log(`  ${set.padEnd(14)} mr ${mr.padStart(7)}   normal ${n.padStart(7)}`);
}
console.log(`[textures] ${report.length} packs -> assets/power/tex  (${(total / 1024).toFixed(0)} KB total)`);

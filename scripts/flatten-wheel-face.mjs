// Pulls a rim's face back behind its own outer border.
//
// The stock PHEV rim (assets/_source/wheels/HavalPHEV-wheel.glb) came out of Tripo as
// a dome: its spoke face bulges ~0.035 model units (~21 mm at the scale the
// app mounts it) OUTBOARD of the wheel's outer border ring, which no real
// wheel does — the rim flange is always the outermost thing. At grazing
// camera angles the spokes visibly poke out past the rim edge and read as
// broken shards floating in front of the tyre.
//
// This finds the border plane (the outboard face of the outer ring) and pulls
// everything in front of it back:
//
//   --mode=clamp  (default)  x = min(x, border)   — a literal flatten: every
//                            protruding vertex lands exactly on the border
//                            plane, so the whole outer face becomes a plateau.
//   --mode=soft              a tanh soft-clamp that starts at a knee plane
//                            behind the border and asymptotes to it. C1 at the
//                            knee (no crease), keeps most of the spoke relief,
//                            and still ends up just inside the border.
//
// Normals are recomputed for every vertex the move touched (and their
// neighbours), otherwise the flattened area keeps the dome's shading and
// still looks curved. Geometry is re-encoded with Draco on the way out
// because reading the file decodes it back to plain accessors.
//
// Usage:
//   node scripts/flatten-wheel-face.mjs [file] [--mode=clamp|soft] [--dry-run]
//   node scripts/flatten-wheel-face.mjs --out=/tmp/preview.glb --mode=soft
//
// In-place by default, with a one-time backup in assets/_backup/.
import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import { draco } from '@gltf-transform/functions';
import draco3d from 'draco3dgltf';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const args = process.argv.slice(2);
const flag = (name, dflt) => {
  const hit = args.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : dflt;
};
const positional = args.filter((a) => !a.startsWith('--'));

const inPath = path.resolve(projectDir, positional[0] || 'assets/_source/wheels/HavalPHEV-wheel.glb');
const outPath = path.resolve(projectDir, flag('out', inPath));
const mode = flag('mode', 'clamp');
const dryRun = args.includes('--dry-run');
// Radius band, as a fraction of the rim's outer radius, that the border plane
// is measured over. The default sits on the flange's outboard face, inside the
// round-over at the very edge (which curves away and would read too shallow).
const bandLo = parseFloat(flag('band-lo', '0.95'));
const bandHi = parseFloat(flag('band-hi', '0.99'));
const SECTORS = 72;

if (mode !== 'clamp' && mode !== 'soft') {
  console.error(`Unknown --mode=${mode} (expected "clamp" or "soft")`);
  process.exit(1);
}

const io = new NodeIO()
  .registerExtensions(ALL_EXTENSIONS)
  .registerDependencies({
    'draco3d.decoder': await draco3d.createDecoderModule(),
    'draco3d.encoder': await draco3d.createEncoderModule(),
  });

const doc = await io.read(inPath);
const root = doc.getRoot();

const prims = root.listMeshes().flatMap((m) => m.listPrimitives());
if (!prims.length) {
  console.error('No primitives found in', inPath);
  process.exit(1);
}

// --- Frame: which axis is the axle, and which way is outboard? --------------
// The axle is whichever axis the wheel is thinnest along; the disc lives in
// the other two. Centre comes from the bounding box, which is reliable here
// because the rim is a full disc.
const bbMin = [Infinity, Infinity, Infinity];
const bbMax = [-Infinity, -Infinity, -Infinity];
const scratch = [0, 0, 0];
for (const prim of prims) {
  const pos = prim.getAttribute('POSITION');
  for (let i = 0; i < pos.getCount(); i++) {
    pos.getElement(i, scratch);
    for (let a = 0; a < 3; a++) {
      if (scratch[a] < bbMin[a]) bbMin[a] = scratch[a];
      if (scratch[a] > bbMax[a]) bbMax[a] = scratch[a];
    }
  }
}
const size = bbMax.map((v, i) => v - bbMin[i]);
const axis = size.indexOf(Math.min(...size));
const radial = [0, 1, 2].filter((a) => a !== axis);
const centre = bbMax.map((v, i) => (v + bbMin[i]) / 2);
const outerR = Math.max(...radial.map((a) => size[a])) / 2;

// Walk every vertex once, bucketing by angular sector, and record how far each
// side of the outer ring reaches. Whichever side pokes out beyond its own ring
// face is the outboard (visible) side.
const ringPlus = new Array(SECTORS).fill(-Infinity);
const ringMinus = new Array(SECTORS).fill(Infinity);
let axMin = Infinity;
let axMax = -Infinity;
for (const prim of prims) {
  const pos = prim.getAttribute('POSITION');
  for (let i = 0; i < pos.getCount(); i++) {
    pos.getElement(i, scratch);
    const u = scratch[radial[0]] - centre[radial[0]];
    const v = scratch[radial[1]] - centre[radial[1]];
    const x = scratch[axis];
    if (x < axMin) axMin = x;
    if (x > axMax) axMax = x;
    const r = Math.hypot(u, v);
    if (r < outerR * bandLo || r > outerR * bandHi) continue;
    let a = Math.atan2(v, u);
    if (a < 0) a += Math.PI * 2;
    const s = Math.min(SECTORS - 1, Math.floor((a / (Math.PI * 2)) * SECTORS));
    if (x > ringPlus[s]) ringPlus[s] = x;
    if (x < ringMinus[s]) ringMinus[s] = x;
  }
}
const median = (xs) => {
  const v = xs.filter(Number.isFinite).sort((a, b) => a - b);
  if (!v.length) throw new Error('Border ring band is empty — try a wider --band-lo/--band-hi');
  return v[Math.floor(v.length / 2)];
};
const facePlus = median(ringPlus);
const faceMinus = median(ringMinus);
// Overhang = how far the model as a whole reaches past the ring's own face.
const overhangPlus = axMax - facePlus;
const overhangMinus = faceMinus - axMin;
const sign = overhangPlus >= overhangMinus ? 1 : -1;
const border = sign > 0 ? facePlus : faceMinus;
const peak = sign > 0 ? axMax : axMin;
const overhang = (peak - border) * sign;

const AXES = ['x', 'y', 'z'];
console.log(`file        ${path.relative(projectDir, inPath)}`);
console.log(`axle axis   ${AXES[axis]}  (outer radius ${outerR.toFixed(4)}, centre [${centre.map((c) => c.toFixed(4)).join(', ')}])`);
console.log(`outboard    ${sign > 0 ? '+' : '-'}${AXES[axis]}  (ring face +${facePlus.toFixed(4)} / -${faceMinus.toFixed(4)}; overhang +${overhangPlus.toFixed(4)} / -${overhangMinus.toFixed(4)})`);
console.log(`border      ${AXES[axis]} = ${border.toFixed(5)}   peak = ${peak.toFixed(5)}   overhang = ${overhang.toFixed(5)}`);

// Scale-relative, not absolute: on an already-flattened rim the outboard side
// has zero overhang, so the detection above legitimately picks the INBOARD
// side and reports whatever sub-millimetre wobble the rim's back face has.
// Re-running must be a no-op, so anything under 0.5% of the rim radius counts
// as "already flat".
const NOOP_FRACTION = 0.005;
if (overhang <= outerR * NOOP_FRACTION) {
  console.log(`\nNothing meaningful sits outboard of the border plane (overhang ${overhang.toFixed(5)} <= ${(outerR * NOOP_FRACTION).toFixed(5)}) — file left untouched.`);
  process.exit(0);
}

// --- The remap --------------------------------------------------------------
// clamp: hard stop at the border.
// soft:  identity up to a knee one overhang behind the border, then a tanh
//        that is C1 at the knee and asymptotes to the border. tanh(2) ≈ 0.964,
//        so the old peak lands just shy of the border rather than exactly on
//        it — no flat plateau, no crease.
const knee = border - sign * overhang;
const span = overhang; // border - knee, in outboard units
const remap = (x) => {
  const d = (x - knee) * sign; // outboard distance past the knee
  if (mode === 'clamp') return (x - border) * sign > 0 ? border : x;
  if (d <= 0) return x;
  return knee + sign * span * Math.tanh(d / span);
};

let moved = 0;
let total = 0;
let maxShift = 0;
for (const prim of prims) {
  const pos = prim.getAttribute('POSITION');
  const nrm = prim.getAttribute('NORMAL');
  const idx = prim.getIndices();
  const count = pos.getCount();
  total += count;

  const touched = new Uint8Array(count);
  for (let i = 0; i < count; i++) {
    pos.getElement(i, scratch);
    const before = scratch[axis];
    const after = remap(before);
    if (after === before) continue;
    scratch[axis] = after;
    pos.setElement(i, scratch);
    touched[i] = 1;
    moved++;
    maxShift = Math.max(maxShift, Math.abs(after - before));
  }

  if (!nrm || !idx) continue;

  // Recompute normals wherever the surface actually changed. Accumulation runs
  // over every triangle (a moved vertex's neighbours need the new face normals
  // too) but only vertices in a triangle that moved get written back, so the
  // untouched parts of the rim keep their authored shading byte-for-byte.
  const dirty = new Uint8Array(count);
  const acc = new Float64Array(count * 3);
  const a = [0, 0, 0];
  const b = [0, 0, 0];
  const c = [0, 0, 0];
  for (let t = 0; t < idx.getCount(); t += 3) {
    const i0 = idx.getScalar(t);
    const i1 = idx.getScalar(t + 1);
    const i2 = idx.getScalar(t + 2);
    if (touched[i0] || touched[i1] || touched[i2]) { dirty[i0] = 1; dirty[i1] = 1; dirty[i2] = 1; }
    pos.getElement(i0, a); pos.getElement(i1, b); pos.getElement(i2, c);
    const e1 = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
    const e2 = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
    // Un-normalised cross product == area-weighted face normal.
    const n = [
      e1[1] * e2[2] - e1[2] * e2[1],
      e1[2] * e2[0] - e1[0] * e2[2],
      e1[0] * e2[1] - e1[1] * e2[0],
    ];
    for (const vi of [i0, i1, i2]) {
      acc[vi * 3] += n[0];
      acc[vi * 3 + 1] += n[1];
      acc[vi * 3 + 2] += n[2];
    }
  }
  let rewritten = 0;
  const out = [0, 0, 0];
  for (let i = 0; i < count; i++) {
    if (!dirty[i]) continue;
    const len = Math.hypot(acc[i * 3], acc[i * 3 + 1], acc[i * 3 + 2]);
    if (!len) continue;
    out[0] = acc[i * 3] / len;
    out[1] = acc[i * 3 + 1] / len;
    out[2] = acc[i * 3 + 2] / len;
    nrm.setElement(i, out);
    rewritten++;
  }
  console.log(`normals     recomputed for ${rewritten} vertices`);
}

console.log(`mode        ${mode}${mode === 'soft' ? ` (knee ${AXES[axis]} = ${knee.toFixed(5)})` : ''}`);
console.log(`moved       ${moved} / ${total} vertices (${((moved / total) * 100).toFixed(2)}%), max shift ${maxShift.toFixed(5)}`);

if (dryRun) {
  console.log('\n--dry-run: nothing written.');
  process.exit(0);
}

if (outPath === inPath) {
  const backupDir = path.join(projectDir, 'assets', '_backup');
  fs.mkdirSync(backupDir, { recursive: true });
  const backup = path.join(backupDir, path.basename(inPath).replace(/\.glb$/, '.pre-flatten.glb'));
  if (fs.existsSync(backup)) {
    console.log(`backup      ${path.relative(projectDir, backup)} (already there, kept)`);
  } else {
    fs.copyFileSync(inPath, backup);
    console.log(`backup      ${path.relative(projectDir, backup)}`);
  }
}

await doc.transform(
  draco({ method: 'edgebreaker', quantizePosition: 14, quantizeNormal: 10, quantizeTexcoord: 12, quantizeColor: 8 }),
);
fs.mkdirSync(path.dirname(outPath), { recursive: true });
await io.write(outPath, doc);
console.log(`wrote       ${path.relative(projectDir, outPath)}`);

/**
 * Estimate per-catalog fitScale so Haval stock rims match visually at 19".
 * Replays the max-radius normalization from _handleUploadedWheelModel, then
 * measures the visible outer-band radius (12th-percentile bore proxy used in
 * onWheelStyle) relative to a reference wheel (PHEV34).
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { NodeIO } from '@gltf-transform/core';
import { KHRDracoMeshCompression } from '@gltf-transform/extensions';
import draco3d from 'draco3dgltf';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const io = new NodeIO()
  .registerExtensions([KHRDracoMeshCompression])
  .registerDependencies({
    'draco3d.decoder': await draco3d.createDecoderModule(),
    'draco3d.encoder': await draco3d.createEncoderModule(),
  });

/** @returns {Promise<{x:number,y:number,z:number}[]>} */
async function readVerts(file) {
  const doc = await io.read(file);
  const out = [];
  for (const mesh of doc.getRoot().listMeshes()) {
    for (const prim of mesh.listPrimitives()) {
      const pos = prim.getAttribute('POSITION');
      if (!pos) continue;
      const a = pos.getArray();
      for (let i = 0; i < a.length; i += 3) out.push({ x: a[i], y: a[i + 1], z: a[i + 2] });
    }
  }
  return out;
}

/** @param {{x:number,y:number,z:number}[]} verts */
function normalizeLikeViewer(verts) {
  if (!verts.length) throw new Error('empty mesh');

  let minX = Infinity, maxX = -Infinity;
  let minY = Infinity, maxY = -Infinity;
  let minZ = Infinity, maxZ = -Infinity;
  for (const v of verts) {
    minX = Math.min(minX, v.x); maxX = Math.max(maxX, v.x);
    minY = Math.min(minY, v.y); maxY = Math.max(maxY, v.y);
    minZ = Math.min(minZ, v.z); maxZ = Math.max(maxZ, v.z);
  }
  const cx = (minX + maxX) / 2;
  const cy = (minY + maxY) / 2;
  const cz = (minZ + maxZ) / 2;
  const sx = maxX - minX;
  const sy = maxY - minY;
  const sz = maxZ - minZ;

  let axle = 'z';
  const diffX = Math.abs(sy - sz);
  const diffY = Math.abs(sx - sz);
  if (diffX < diffY && diffX < Math.abs(sx - sy)) axle = 'x';
  else if (diffY < diffX && diffY < Math.abs(sx - sy)) axle = 'y';

  /** @param {{x:number,y:number,z:number}} v */
  const radial = (v) => {
    if (axle === 'x') return Math.hypot(v.y, v.z);
    if (axle === 'y') return Math.hypot(v.x, v.z);
    return Math.hypot(v.x, v.y);
  };
  /** @param {{x:number,y:number,z:number}} v */
  const axial = (v) => (axle === 'x' ? v.x : axle === 'y' ? v.y : v.z);

  const centered = verts.map((v) => ({
    x: v.x - cx,
    y: v.y - cy,
    z: v.z - cz,
  }));

  let maxR = 0;
  let sumAx = 0;
  for (const v of centered) {
    const r = radial(v);
    if (r > maxR) maxR = r;
    sumAx += axial(v);
  }
  const avgAx = sumAx / centered.length;
  const flip = avgAx < 0;

  const scaled = centered.map((v) => {
    const s = 1 / maxR;
    return { x: v.x * s, y: v.y * s, z: v.z * s };
  });

  // Rotate axle -> +Z, optional 180° flip so face is +Z
  /** @param {{x:number,y:number,z:number}} v */
  const mapAx = (v) => {
    let x = v.x, y = v.y, z = v.z;
    if (axle === 'x') [x, y, z] = [z, y, x];
    else if (axle === 'y') [x, y, z] = [x, z, y];
    if (flip) { x = -x; z = -z; }
    return { x, y, z };
  };
  const norm = scaled.map(mapAx);

  let zMin = Infinity, zMax = -Infinity;
  for (const v of norm) {
    if (v.z < zMin) zMin = v.z;
    if (v.z > zMax) zMax = v.z;
  }
  const faceBand = 0.02 * maxR; // same idea as runtime, in normalized units
  const faceRadii = [];
  for (const v of norm) {
    if (v.z > zMax - faceBand) faceRadii.push(Math.hypot(v.x, v.y));
  }
  faceRadii.sort((a, b) => a - b);
  const boreR = faceRadii.length
    ? faceRadii[Math.min(faceRadii.length - 1, Math.floor(faceRadii.length * 0.12))]
    : 0;

  let maxNormR = 0;
  for (const v of norm) maxNormR = Math.max(maxNormR, Math.hypot(v.x, v.y));

  return { boreR, maxNormR, maxR, axle, flip };
}

const wheels = [
  { key: 'haval_gt', file: 'assets/wheels/HavalGT-wheel.glb' },
  { key: 'haval_phev', file: 'assets/wheels/HavalPHEV34-wheel.glb' },
  { key: 'haval_phev19', file: 'assets/wheels/HavalPHEV19-wheel.glb' },
  { key: 'haval_hev', file: 'assets/wheels/HavalHEV-wheel.glb' },
];

const results = [];
for (const w of wheels) {
  const m = normalizeLikeViewer(await readVerts(path.join(root, w.file)));
  results.push({ key: w.key, ...m });
}

const ref = results.find((r) => r.key === 'haval_phev');
if (!ref || ref.boreR <= 0) throw new Error('bad reference');

console.log('Reference:', ref.key, 'boreR', ref.boreR.toFixed(4));
for (const r of results) {
  const fitScale = ref.boreR / r.boreR;
  console.log(
    `${r.key}: boreR=${r.boreR.toFixed(4)} maxNormR=${r.maxNormR.toFixed(4)} fitScale=${fitScale.toFixed(3)}`,
  );
}

// Build the x-ray GHOST body: one mesh, one material, no textures.
//
// WHY, measured on the car (2026-09-04):
//   x-ray off  178 draw calls / 344,716 tris
//   x-ray on   383 draw calls / 497,362 tris   submit +8.6 ms
//   GPU backlog 0.1 ms in BOTH  <- the GPU is idle; CPU-side draw submission
//                                  is the cost, not triangle throughput.
//
// _setBodyGhost currently keeps the whole body and clones every material into a
// transparent one, so x-ray pays the full ~136 draw calls AND loses early-Z AND
// gains a per-frame sort over ~44 transparent meshes. Collapsing that to a
// single merged mesh with one material is therefore worth far more than
// decimating polygons: a 5k-triangle ghost in 40 draw calls would be WORSE than
// a 100k-triangle ghost in one.
//
// Doors, lights and glass do not animate in x-ray, so nothing has to stay
// separable -- the whole shell can be one primitive.
//
//   node scripts/build-xray-ghost.mjs --dry     # report only, writes nothing
//   node scripts/build-xray-ghost.mjs
//
// NOT boot-critical (x-ray fetches on demand), so unlike the -lite bodies this
// MAY use Draco. See CLAUDE.md "Editing GLB assets".

import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS, KHRDracoMeshCompression } from '@gltf-transform/extensions';
import { dedup, prune, weld, join, simplify, flatten } from '@gltf-transform/functions';
import { MeshoptSimplifier } from 'meshoptimizer';
import draco3d from 'draco3dgltf';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DRY = process.argv.includes('--dry');
const RATIO = Number((process.argv.find((a) => a.startsWith('--ratio=')) || '').split('=')[1] || 0.35);

// Parts the viewer keeps SOLID in x-ray (see _setBodyGhost's keepSolid set):
// wheels, tyres, rims, brakes and the spin-blur discs are drawn from the real
// model, so they must not be merged into the ghost.
//
// Word-bounded on purpose. A bare /disc/ matched "10_car_body_metal_
// DISColoration" and "29_LF_door_metal_discoloration" and threw three body
// panels out of the ghost -- caught by --dry before anything was written.
// Underscores count as word characters, so the class is spelled explicitly.
// Parts the viewer keeps SOLID in x-ray (see _setBodyGhost's keepSolid set):
// wheels, tyres, rims, brakes and the spin-blur discs are drawn from the real
// model, so they must not be merged into the ghost.
//
// Three traps, all found with --dry before anything was written:
//   - a bare /disc/ matched "..._metal_DISColoration" and threw three body
//     panels out of the ghost. Hence "disk" only.
//   - /rim/ matches "trim", and /hub/ would match mid-word, so those two are
//     left-boundary anchored while the rest are plain substrings.
//   - the HEV spells its brake discs "Break_Disks", not "Brake".
const SOLID = /(wheel|tire|tyre|brake|break|caliper|rotor|disk|spoke|blur)|((^|[^a-z])(rim|hub))/i;
const NOT_BODY = /(^|[^a-z])(floor|ground|shadow|plane|backdrop)([^a-z]|$)/i;

/**
 * The two bodies name their parts completely differently, so neither source
 * alone classifies both:
 *   HEV  meshes are UNNAMED; the node carries it ("hub_lb", "Break_Disks").
 *   GT   wheels are identifiable ONLY by material name ("Wheel").
 *
 * So: structural names (mesh + node) decide first. Materials are consulted only
 * as a fallback, and then EVERY material on the mesh must read as a wheel part
 * -- because a GT rear light cluster carries "Chrome | BrakeLight |
 * PositionLight_Rear", and one brake-ish material must not condemn a lamp.
 */
function isSolid(doc, mesh) {
  const bits = [mesh.getName() || ''];
  for (const node of doc.getRoot().listNodes()) {
    if (node.getMesh() === mesh) bits.push(node.getName() || '');
  }
  const structural = bits.filter(Boolean).join(' | ');
  if (SOLID.test(structural) || NOT_BODY.test(structural)) return { solid: true, why: structural };

  // ...and a lamp is never a wheel part. The HEV's rear lenses carry exactly
  // one material, named "BrakeLight", so the every()-guard above cannot save
  // them on its own -- without this they were dropped and the ghost came out
  // with holes where the tail lights are.
  const IS_LIGHT = /(light|lamp|lens)/i;
  const mats = mesh.listPrimitives()
    .map((p) => (p.getMaterial() && p.getMaterial().getName()) || '').filter(Boolean);
  if (mats.length && mats.every((n) => SOLID.test(n) && !IS_LIGHT.test(n))) {
    return { solid: true, why: structural + ' [mat: ' + mats.join(',') + ']' };
  }
  return { solid: false, why: structural || '(unnamed)' };
}

const TARGETS = [
  { in: 'assets/haval-h6-hev-lite.glb', out: 'assets/haval-h6-hev-ghost.glb' },
  { in: 'assets/haval-h6-gt-lite.glb', out: 'assets/haval-h6-gt-ghost.glb' },
];

const io = new NodeIO()
  .registerExtensions(ALL_EXTENSIONS)
  .registerDependencies({
    'draco3d.decoder': await draco3d.createDecoderModule(),
    'draco3d.encoder': await draco3d.createEncoderModule(),
  });

await MeshoptSimplifier.ready;

for (const t of TARGETS) {
  const src = path.join(ROOT, t.in);
  if (!fs.existsSync(src)) { console.log(`\n${t.in}: MISSING — skipped`); continue; }
  const doc = await io.read(src);
  const root = doc.getRoot();

  let kept = 0, dropped = 0, keptTris = 0;
  const keptNames = [], droppedNames = [];

  // Drop everything that is not ghostable shell.
  for (const mesh of root.listMeshes()) {
    const verdict = isSolid(doc, mesh);
    const name = verdict.why;
    const solid = verdict.solid;
    for (const prim of mesh.listPrimitives()) {
      if (solid) {
        dropped++;
        if (droppedNames.length < 40) droppedNames.push(name);
        mesh.removePrimitive(prim);
        continue;
      }
      kept++;
      if (keptNames.length < 40) keptNames.push(name);
      const idx = prim.getIndices(); const pos = prim.getAttribute('POSITION');
      keptTris += idx ? idx.getCount() / 3 : (pos ? pos.getCount() / 3 : 0);
    }
  }

  console.log(`\n=== ${t.in} ===`);
  console.log(`  ghostable primitives ${kept}   (dropped as solid/non-body: ${dropped})`);
  console.log(`  ghostable triangles  ${Math.round(keptTris).toLocaleString('en-US')}`);
  console.log(`  kept sample:    ${[...new Set(keptNames)].slice(0, 10).join(', ')}`);
  console.log(`  dropped sample: ${[...new Set(droppedNames)].slice(0, 10).join(', ') || '(none)'}`);
  if (DRY) continue;

  // ONE material for the whole shell. The ghost look is a fresnel applied at
  // runtime by _applyGhostFresnel, so the asset needs no textures at all --
  // dropping them is most of the file size and all of the texture binds.
  const ghostMat = doc.createMaterial('xray-ghost')
    .setBaseColorFactor([1, 1, 1, 1])
    .setMetallicFactor(0)
    .setRoughnessFactor(1)
    .setDoubleSided(false);

  for (const mesh of root.listMeshes()) {
    for (const prim of mesh.listPrimitives()) prim.setMaterial(ghostMat);
  }

  await doc.transform(
    flatten(),
    dedup(),
    // weld before simplify or the simplifier cannot collapse across split verts
    weld({ tolerance: 0.0001 }),
    simplify({ simplifier: MeshoptSimplifier, ratio: RATIO, error: 0.01 }),
    // join is what actually collapses the draw calls: same material everywhere
    // now, so every primitive can merge into one.
    join({ keepNamed: false }),
    prune(),
  );

  // Strip every texture and any leftover material; the ghost is untextured.
  for (const tex of root.listTextures()) tex.dispose();
  for (const mat of root.listMaterials()) if (mat !== ghostMat) mat.dispose();

  doc.createExtension(KHRDracoMeshCompression).setRequired(true);

  let outPrims = 0, outTris = 0;
  for (const mesh of root.listMeshes()) {
    for (const prim of mesh.listPrimitives()) {
      outPrims++;
      const idx = prim.getIndices(); const pos = prim.getAttribute('POSITION');
      outTris += idx ? idx.getCount() / 3 : (pos ? pos.getCount() / 3 : 0);
    }
  }

  const dst = path.join(ROOT, t.out);
  await io.write(dst, doc);
  const inMb = (fs.statSync(src).size / 1048576).toFixed(1);
  const outMb = (fs.statSync(dst).size / 1048576).toFixed(2);
  console.log(`  -> ${t.out}`);
  console.log(`     draw calls  ${kept} -> ${outPrims}`);
  console.log(`     triangles   ${Math.round(keptTris).toLocaleString('en-US')} -> ${Math.round(outTris).toLocaleString('en-US')}`);
  console.log(`     size        ${inMb} MB -> ${outMb} MB   (textures: 0, materials: 1)`);
}

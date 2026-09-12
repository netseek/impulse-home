// What would an x-ray ghost body actually save?
//
// x-ray currently keeps the whole body and clones every material into a
// transparent ghost (see _setBodyGhost), so it pays the full draw-call count
// AND loses early-Z. Measured on the car: 178 calls / 344,716 tris with x-ray
// off, 383 / 497,362 with it on, at a GPU backlog of 0.1 ms -- i.e. CPU-side
// draw submission is the cost, not triangle throughput.
//
// This prints, per shipped body, the mesh/material/texture counts that a merged
// single-material ghost would collapse, so the ceiling of that idea is known
// before any asset is built.
//
//   node scripts/analyze-ghost-candidate.mjs
//   node scripts/analyze-ghost-candidate.mjs assets/_source/haval-h6-hev.glb

import { NodeIO } from '@gltf-transform/core';
import { KHRDracoMeshCompression, KHRTextureBasisu, ALL_EXTENSIONS } from '@gltf-transform/extensions';
import draco3d from 'draco3dgltf';
import fs from 'node:fs';
import path from 'node:path';

const files = process.argv.slice(2).length ? process.argv.slice(2) : [
  'assets/haval-h6-hev-lite.glb',
  'assets/haval-h6-gt-lite.glb',
];

const io = new NodeIO()
  .registerExtensions(ALL_EXTENSIONS)
  .registerDependencies({
    'draco3d.decoder': await draco3d.createDecoderModule(),
  });

// Rough classifier matching what the viewer keeps solid in x-ray: wheels and
// tyres stay real, so they are not ghost candidates.
const WHEEL = /wheel|tire|tyre|rim|brake|caliper|disc|spoke|hub/i;
const GLASS = /glass|window|windshield|windscreen|screen/i;
const DOOR = /door|hood|bonnet|trunk|tailgate|boot|hatch/i;

for (const f of files) {
  const p = path.resolve(f);
  if (!fs.existsSync(p)) { console.log(`\n${f}: MISSING`); continue; }
  const doc = await io.read(p);
  const root = doc.getRoot();

  let prims = 0, tris = 0, ghostPrims = 0, ghostTris = 0;
  let wheelPrims = 0, glassPrims = 0, doorPrims = 0;
  const ghostMats = new Set(), ghostTex = new Set();

  for (const mesh of root.listMeshes()) {
    const name = mesh.getName() || '';
    for (const prim of mesh.listPrimitives()) {
      prims++;
      const idx = prim.getIndices();
      const pos = prim.getAttribute('POSITION');
      const t = idx ? idx.getCount() / 3 : (pos ? pos.getCount() / 3 : 0);
      tris += t;

      if (WHEEL.test(name)) { wheelPrims++; continue; }   // stays solid
      ghostPrims++; ghostTris += t;
      if (GLASS.test(name)) glassPrims++;
      if (DOOR.test(name)) doorPrims++;
      const mat = prim.getMaterial();
      if (mat) {
        ghostMats.add(mat);
        for (const slot of ['BaseColor', 'Normal', 'Emissive', 'MetallicRoughness', 'Occlusion']) {
          const tx = mat[`get${slot}Texture`] && mat[`get${slot}Texture`]();
          if (tx) ghostTex.add(tx);
        }
      }
    }
  }

  const mb = (fs.statSync(p).size / 1048576).toFixed(1);
  console.log(`\n=== ${f}  (${mb} MB) ===`);
  console.log(`  primitives (= draw calls)   ${prims}`);
  console.log(`    of which wheels/brakes    ${wheelPrims}  (stay solid, not ghosted)`);
  console.log(`    GHOST CANDIDATES          ${ghostPrims}`);
  console.log(`      glass-named             ${glassPrims}`);
  console.log(`      door/hood/trunk-named   ${doorPrims}  <- must stay separate if they animate`);
  console.log(`  triangles total             ${tris.toLocaleString()}`);
  console.log(`    ghost candidates          ${ghostTris.toLocaleString()}`);
  console.log(`  distinct ghost materials    ${ghostMats.size}`);
  console.log(`  distinct ghost textures     ${ghostTex.size}`);
  console.log(`  -> merged single-material ghost: ${ghostPrims} draw calls -> 1`);
  console.log(`     (or ${1 + doorPrims + (glassPrims ? 1 : 0)} if doors/glass stay separable)`);
}

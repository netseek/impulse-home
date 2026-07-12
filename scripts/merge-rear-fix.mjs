/**
 * Merge the rear-light fix into haval-h6.glb.
 *
 * The rear-fix GLB has the same car but with corrected face→material splits on
 * 3 rear-light cluster nodes: Chrome faces that were wrongly assigned to
 * BrakeLight/PositionLight_Rear now have their own Chrome primitive.
 *
 * This script:
 * 1. Reads both GLBs (Draco-decompressed on read)
 * 2. For each of the 3 cluster nodes, replaces the mesh primitives in the main
 *    file with the corrected ones from the fix, rebinding materials by name
 * 3. Re-applies Draco compression
 * 4. Writes the patched file as haval-h6.glb (with backup)
 */
import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import { prune, draco } from '@gltf-transform/functions';
import draco3d from 'draco3dgltf';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BASE = path.resolve(__dirname, '..', 'assets');

const CLUSTERS = [
  'Rear Headlight Left (Internals #1)',
  'Rear Headlight Right (Internals #1)',
  'Rear Headlight Center (Internals #1)',
];

const norm = (s) => (s || '').toLowerCase().replace(/[^a-z0-9]+/g, '');

async function main() {
  const io = new NodeIO()
    .registerExtensions(ALL_EXTENSIONS)
    .registerDependencies({
      'draco3d.decoder': await draco3d.createDecoderModule(),
      'draco3d.encoder': await draco3d.createEncoderModule(),
    });

  console.log('Reading main model (haval-h6.glb)...');
  const mainDoc = await io.read(path.join(BASE, 'haval-h6.glb'));

  console.log('Reading rear-fix model (haval-h6-rear-fix.glb)...');
  const fixDoc = await io.read(path.join(BASE, 'haval-h6-rear-fix.glb'));

  // Build material lookup in main doc by name
  const mainMatByName = new Map();
  for (const mat of mainDoc.getRoot().listMaterials()) {
    if (mat.getName()) mainMatByName.set(mat.getName(), mat);
  }
  console.log(`Main doc has ${mainMatByName.size} named materials`);

  // Get (or create) a buffer in main doc for new accessors
  const mainBuffer = mainDoc.getRoot().listBuffers()[0] || mainDoc.createBuffer();

  let totalOldPrims = 0;
  let totalNewPrims = 0;

  for (const clusterName of CLUSTERS) {
    const key = norm(clusterName);

    const mainNode = mainDoc.getRoot().listNodes().find((n) => norm(n.getName()) === key);
    const fixNode = fixDoc.getRoot().listNodes().find((n) => norm(n.getName()) === key);

    if (!mainNode) { console.warn(`⚠ Main node not found: "${clusterName}"`); continue; }
    if (!fixNode)  { console.warn(`⚠ Fix node not found: "${clusterName}"`);  continue; }

    const mainMesh = mainNode.getMesh();
    const fixMesh  = fixNode.getMesh();

    if (!mainMesh) { console.warn(`⚠ Main mesh not found for "${clusterName}"`); continue; }
    if (!fixMesh)  { console.warn(`⚠ Fix mesh not found for "${clusterName}"`);  continue; }

    // --- Remove old primitives from main mesh ---
    const oldPrims = mainMesh.listPrimitives();
    console.log(`\n"${clusterName}"`);
    console.log(`  Removing ${oldPrims.length} old primitives`);
    totalOldPrims += oldPrims.length;
    for (const prim of [...oldPrims]) {
      mainMesh.removePrimitive(prim);
    }

    // --- Copy primitives from fix mesh into main mesh ---
    const fixPrims = fixMesh.listPrimitives();
    console.log(`  Copying ${fixPrims.length} corrected primitives from fix`);

    for (let i = 0; i < fixPrims.length; i++) {
      const fixPrim = fixPrims[i];
      const newPrim = mainDoc.createPrimitive();

      // Copy indices
      const fixIndices = fixPrim.getIndices();
      if (fixIndices) {
        const newIndices = mainDoc.createAccessor()
          .setBuffer(mainBuffer)
          .setType(fixIndices.getType())
          .setArray(fixIndices.getArray().slice());
        newPrim.setIndices(newIndices);
      }

      // Copy all vertex attributes (POSITION, NORMAL, TEXCOORD_0, etc.)
      for (const semantic of fixPrim.listSemantics()) {
        const fixAttr = fixPrim.getAttribute(semantic);
        if (fixAttr) {
          const newAttr = mainDoc.createAccessor()
            .setBuffer(mainBuffer)
            .setType(fixAttr.getType())
            .setArray(fixAttr.getArray().slice())
            .setNormalized(fixAttr.getNormalized());
          newPrim.setAttribute(semantic, newAttr);
        }
      }

      // Copy primitive mode (TRIANGLES=4, etc.)
      newPrim.setMode(fixPrim.getMode());

      // Rebind material by name — use main doc's existing material instance
      const fixMat = fixPrim.getMaterial();
      const fixMatName = fixMat ? fixMat.getName() : null;

      if (fixMatName && mainMatByName.has(fixMatName)) {
        newPrim.setMaterial(mainMatByName.get(fixMatName));
        console.log(`    [${i}] → material "${fixMatName}" (existing)`);
      } else if (fixMat) {
        // Material doesn't exist in main doc — create it with matching properties
        const newMat = mainDoc.createMaterial(fixMatName || 'Unnamed');
        newMat.setBaseColorFactor(fixMat.getBaseColorFactor());
        newMat.setMetallicFactor(fixMat.getMetallicFactor());
        newMat.setRoughnessFactor(fixMat.getRoughnessFactor());
        newMat.setEmissiveFactor(fixMat.getEmissiveFactor());
        newMat.setAlphaMode(fixMat.getAlphaMode());
        newMat.setAlphaCutoff(fixMat.getAlphaCutoff());
        newMat.setDoubleSided(fixMat.getDoubleSided());
        mainMatByName.set(fixMatName, newMat);
        newPrim.setMaterial(newMat);
        console.log(`    [${i}] → material "${fixMatName}" (NEW — created in main)`);
      }

      mainMesh.addPrimitive(newPrim);
      totalNewPrims++;
    }
  }

  console.log(`\n--- Summary ---`);
  console.log(`Replaced ${totalOldPrims} old primitives with ${totalNewPrims} corrected primitives`);

  // Clean up any orphaned resources (old accessors, unused materials, etc.)
  console.log('\nPruning unused resources...');
  await mainDoc.transform(prune());

  // Re-apply Draco compression for a compact output
  console.log('Applying Draco compression...');
  await mainDoc.transform(draco({ quantizePosition: 14, quantizeNormal: 10, quantizeTexcoord: 12 }));

  // Write output — save as new file first for safety
  const outPath = path.join(BASE, 'haval-h6-merged.glb');
  console.log(`\nWriting patched file to ${outPath}...`);
  await io.write(outPath, mainDoc);

  const stats = fs.statSync(outPath);
  console.log(`Output size: ${(stats.size / 1024 / 1024).toFixed(2)} MB`);
  console.log('\n✅ Done! Verify the file, then you can replace haval-h6.glb with haval-h6-merged.glb');
}

main().catch((err) => { console.error('❌ Error:', err); process.exit(1); });

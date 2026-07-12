/**
 * Quick verification that the merged GLB has the corrected rear-light primitives.
 */
import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import draco3d from 'draco3dgltf';
import path from 'path';
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

  console.log('=== MERGED MODEL (haval-h6-merged.glb) ===\n');
  const doc = await io.read(path.join(BASE, 'haval-h6-merged.glb'));

  for (const name of CLUSTERS) {
    const key = norm(name);
    const node = doc.getRoot().listNodes().find((n) => norm(n.getName()) === key);
    console.log(`"${name}" — ${node ? 'FOUND' : 'NOT FOUND'}`);
    if (node && node.getMesh()) {
      const mesh = node.getMesh();
      const prims = mesh.listPrimitives();
      console.log(`  Mesh "${mesh.getName()}" — ${prims.length} primitive(s)`);
      for (let i = 0; i < prims.length; i++) {
        const p = prims[i];
        const mat = p.getMaterial();
        const idx = p.getIndices();
        const pos = p.getAttribute('POSITION');
        const matName = mat ? mat.getName() : '(none)';
        const verts = pos ? pos.getCount() : '?';
        const faces = idx ? idx.getCount() / 3 : '?';
        const attrs = p.listSemantics().join(', ');
        console.log('    [' + i + '] material: "' + matName + '"  verts: ' + verts + '  faces: ' + faces + '  attrs: ' + attrs);
      }
    }
    console.log();
  }

  // Count total nodes/meshes/materials
  console.log(`Total nodes: ${doc.getRoot().listNodes().length}`);
  console.log(`Total meshes: ${doc.getRoot().listMeshes().length}`);
  console.log(`Total materials: ${doc.getRoot().listMaterials().length}`);
  console.log(`Total accessors: ${doc.getRoot().listAccessors().length}`);
}

main().catch(console.error);

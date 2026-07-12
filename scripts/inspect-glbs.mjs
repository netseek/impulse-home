/**
 * Inspect the rear-light cluster nodes in both the main and rear-fix GLBs.
 * Shows: node tree, mesh primitives, material names, and vertex/face counts.
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

function inspectMesh(mesh, indent = '  ') {
  if (!mesh) { console.log(indent + '(no mesh)'); return; }
  const prims = mesh.listPrimitives();
  console.log(indent + `Mesh "${mesh.getName()}" — ${prims.length} primitive(s)`);
  for (let i = 0; i < prims.length; i++) {
    const p = prims[i];
    const mat = p.getMaterial();
    const idx = p.getIndices();
    const pos = p.getAttribute('POSITION');
    const semantics = p.listSemantics();
    console.log(indent + `  [${i}] material: "${mat ? mat.getName() : '(none)'}"`);
    console.log(indent + `       vertices: ${pos ? pos.getCount() : '?'}, faces: ${idx ? idx.getCount() / 3 : '?'}`);
    console.log(indent + `       semantics: ${semantics.join(', ')}`);
    console.log(indent + `       mode: ${p.getMode()}`);
  }
}

function findNodesByCluster(doc) {
  const results = {};
  for (const name of CLUSTERS) {
    const key = norm(name);
    const node = doc.getRoot().listNodes().find((n) => norm(n.getName()) === key);
    results[name] = node || null;
  }
  return results;
}

async function main() {
  const io = new NodeIO()
    .registerExtensions(ALL_EXTENSIONS)
    .registerDependencies({
      'draco3d.decoder': await draco3d.createDecoderModule(),
      'draco3d.encoder': await draco3d.createEncoderModule(),
    });

  // --- Main model ---
  console.log('=== MAIN MODEL (haval-h6.glb) ===\n');
  const mainDoc = await io.read(path.join(BASE, 'haval-h6.glb'));
  
  // List all materials
  const mainMats = mainDoc.getRoot().listMaterials();
  console.log(`Materials (${mainMats.length}):`);
  for (const m of mainMats) {
    const bc = m.getBaseColorFactor();
    console.log(`  "${m.getName()}" — baseColor: [${bc.map(v => v.toFixed(3)).join(', ')}], metallic: ${m.getMetallicFactor().toFixed(2)}, roughness: ${m.getRoughnessFactor().toFixed(2)}`);
  }
  
  console.log('\nRear-light clusters:');
  const mainNodes = findNodesByCluster(mainDoc);
  for (const [name, node] of Object.entries(mainNodes)) {
    console.log(`\n  "${name}" — ${node ? 'FOUND' : 'NOT FOUND'}`);
    if (node) {
      inspectMesh(node.getMesh(), '    ');
      // Also check child nodes
      const children = node.listChildren();
      if (children.length > 0) {
        console.log(`    Children (${children.length}):`);
        for (const child of children) {
          console.log(`      "${child.getName()}"`);
          inspectMesh(child.getMesh(), '        ');
        }
      }
    }
  }

  // --- Fix model ---
  console.log('\n\n=== FIX MODEL (haval-h6-rear-fix.glb) ===\n');
  const fixDoc = await io.read(path.join(BASE, 'haval-h6-rear-fix.glb'));
  
  const fixMats = fixDoc.getRoot().listMaterials();
  console.log(`Materials (${fixMats.length}):`);
  for (const m of fixMats) {
    const bc = m.getBaseColorFactor();
    console.log(`  "${m.getName()}" — baseColor: [${bc.map(v => v.toFixed(3)).join(', ')}], metallic: ${m.getMetallicFactor().toFixed(2)}, roughness: ${m.getRoughnessFactor().toFixed(2)}`);
  }
  
  console.log('\nRear-light clusters:');
  const fixNodes = findNodesByCluster(fixDoc);
  for (const [name, node] of Object.entries(fixNodes)) {
    console.log(`\n  "${name}" — ${node ? 'FOUND' : 'NOT FOUND'}`);
    if (node) {
      inspectMesh(node.getMesh(), '    ');
      const children = node.listChildren();
      if (children.length > 0) {
        console.log(`    Children (${children.length}):`);
        for (const child of children) {
          console.log(`      "${child.getName()}"`);
          inspectMesh(child.getMesh(), '        ');
        }
      }
    }
  }

  // --- Diff ---
  console.log('\n\n=== MATERIAL DIFF ===\n');
  const mainMatNames = new Set(mainMats.map(m => m.getName()));
  const fixMatNames = new Set(fixMats.map(m => m.getName()));
  
  const onlyInFix = [...fixMatNames].filter(n => !mainMatNames.has(n));
  const onlyInMain = [...mainMatNames].filter(n => !fixMatNames.has(n));
  
  if (onlyInFix.length) console.log('Materials ONLY in fix (need to add to main):', onlyInFix);
  if (onlyInMain.length) console.log('Materials ONLY in main:', onlyInMain);
  if (!onlyInFix.length && !onlyInMain.length) console.log('Both files have the same material names.');
}

main().catch(console.error);

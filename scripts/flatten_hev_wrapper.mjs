// Fixes two regressions introduced when haval-h6-hev.glb was baked via a live
// browser export earlier this session:
//
// 1. The exported car root ("app.model") had already been centered/scaled by
//    the app's own framing logic by the time it was exported, so that
//    transform got baked into the export as a node's own TRS. On re-load,
//    GLTFExporter's own wrapper scene ("AuxScene") sits ABOVE that node,
//    adding an extra transform layer the app doesn't expect — every place in
//    the app that assumes "the loaded model root IS the content parent"
//    (e.g. the sunroof curtain's hand-measured fixed local coordinates) now
//    computes world positions in the wrong frame, since there's an unplanned
//    translate+scale between the model root and the real geometry.
//
// 2. Runtime-created objects (the procedural sunroof curtain + its frame)
//    were part of the live scene at export time and got baked into the file
//    as if they were static geometry. The app creates its OWN fresh copies
//    of these every time it loads a model, so the baked-in ones are inert
//    duplicates that visually clutter the scene.
//
// Fix: flatten the extra wrapper level (bake its transform into each child,
// then reparent children up one level and delete the empty wrapper), and
// delete the known-procedural duplicate nodes.
import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import { draco } from '@gltf-transform/functions';
import draco3d from 'draco3dgltf';

const io = new NodeIO()
  .registerExtensions(ALL_EXTENSIONS)
  .registerDependencies({
    'draco3d.decoder': await draco3d.createDecoderModule(),
    'draco3d.encoder': await draco3d.createEncoderModule(),
  });

const IN = 'assets/haval-h6-hev.glb';
const OUT = 'assets/haval-h6-hev.glb';

const doc = await io.read(IN);
const root = doc.getRoot();
const outerScene = root.listScenes()[0];

const children = outerScene.listChildren();
if (children.length !== 1) {
  console.error('Expected exactly 1 child of the outer scene, found', children.length);
  process.exit(1);
}
const wrapper = children[0];
if (wrapper.getName() !== 'Scene') {
  console.error('Expected wrapper node named "Scene", found', wrapper.getName());
  process.exit(1);
}

function multiply(a, b) {
  // 4x4 column-major matrix multiply: result = a * b
  const out = new Array(16).fill(0);
  for (let c = 0; c < 4; c++) {
    for (let r = 0; r < 4; r++) {
      let sum = 0;
      for (let k = 0; k < 4; k++) sum += a[k * 4 + r] * b[c * 4 + k];
      out[c * 4 + r] = sum;
    }
  }
  return out;
}

const wrapperMatrix = wrapper.getMatrix();
const grandchildren = wrapper.listChildren();
console.log('Flattening', grandchildren.length, 'children out of wrapper "Scene" (translation', wrapper.getTranslation(), 'scale', wrapper.getScale(), ')');

grandchildren.forEach((child) => {
  const combined = multiply(wrapperMatrix, child.getMatrix());
  wrapper.removeChild(child);
  outerScene.addChild(child);
  child.setMatrix(combined);
});

// wrapper node is now empty and unused — drop it from the scene entirely.
outerScene.removeChild(wrapper);
wrapper.dispose();

// Remove baked-in duplicates of procedurally-created runtime objects — the
// app always (re)creates these itself on load, so static copies are inert
// clutter that can end up controlled/positioned inconsistently with the
// live-created ones. "Sunroof_Front_Frame" here also has a degenerate
// (tens-of-thousands-of-units) bounding box baked in from whatever state the
// live scene was in at export time — another reason it must not survive.
// "Mesh_0376*" nodes are stray high-altitude (~y=5.2, well above the car)
// helper geometry with blank materials, also clearly not real car parts.
const PROCEDURAL_NODE_NAMES = new Set(['Sunroof_Curtain', 'Sunroof_Curtain_Frame', 'Sunroof_Front_Frame']);
let removed = 0;
root.listNodes().forEach((node) => {
  const name = node.getName();
  if (PROCEDURAL_NODE_NAMES.has(name) || name.startsWith('Mesh_0376')) {
    const mesh = node.getMesh();
    node.dispose();
    if (mesh && mesh.listParents().every(p => p.propertyType === 'Root')) mesh.dispose();
    removed++;
  }
});
console.log('Removed', removed, 'baked-in procedural duplicate node(s)');

// Re-encode geometry with Draco — reading the file above decoded it back to
// plain accessors, so writing without this would balloon the file size back
// toward its pre-compression footprint.
await doc.transform(
  draco({ method: 'edgebreaker', quantizePosition: 14, quantizeNormal: 10, quantizeTexcoord: 12, quantizeColor: 8 })
);

await io.write(OUT, doc);
console.log('Wrote', OUT);

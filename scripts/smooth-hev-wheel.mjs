/**
 * Smooth HavalHEV-wheel.glb rim geometry (bolts / rim / wheel_cap).
 *
 * Same duplicate-position / split-normal export artefact as the body GLB.
 * Re-averages normals (60° crease) then re-indexes. PBR lives in
 * patch-hev-wheel-material.mjs.
 */
import { NodeIO, VertexLayout } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import draco3d from 'draco3dgltf';
import * as THREE from 'three';
import { mergeVertices, toCreasedNormals } from 'three/examples/jsm/utils/BufferGeometryUtils.js';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const TARGET = path.join(root, 'assets', 'wheels', 'HavalHEV-wheel.glb');
const CREASE_RAD = Math.PI / 3;

const io = new NodeIO()
  .setVertexLayout(VertexLayout.SEPARATE)
  .registerExtensions(ALL_EXTENSIONS)
  .registerDependencies({
    'draco3d.decoder': await draco3d.createDecoderModule(),
    'draco3d.encoder': await draco3d.createEncoderModule(),
  });

function primitiveToGeometry(prim) {
  const geom = new THREE.BufferGeometry();
  const pos = prim.getAttribute('POSITION');
  geom.setAttribute('position', new THREE.BufferAttribute(new Float32Array(pos.getArray()), 3));
  const norm = prim.getAttribute('NORMAL');
  if (norm) geom.setAttribute('normal', new THREE.BufferAttribute(new Float32Array(norm.getArray()), 3));
  const uv = prim.getAttribute('TEXCOORD_0');
  if (uv) geom.setAttribute('uv', new THREE.BufferAttribute(new Float32Array(uv.getArray()), 2));
  const idx = prim.getIndices();
  if (idx) geom.setIndex(Array.from(idx.getArray()));
  return geom;
}

function disposeAttr(attr) {
  if (attr) attr.dispose();
}

function applyGeometry(doc, prim, geom) {
  disposeAttr(prim.getAttribute('POSITION'));
  disposeAttr(prim.getAttribute('NORMAL'));
  disposeAttr(prim.getIndices());
  prim.setExtension('KHR_draco_mesh_compression', null);

  prim.setAttribute(
    'POSITION',
    doc.createAccessor().setType('VEC3').setArray(new Float32Array(geom.attributes.position.array)),
  );
  prim.setAttribute(
    'NORMAL',
    doc.createAccessor().setType('VEC3').setArray(new Float32Array(geom.attributes.normal.array)),
  );
  if (geom.attributes.uv) {
    disposeAttr(prim.getAttribute('TEXCOORD_0'));
    prim.setAttribute(
      'TEXCOORD_0',
      doc.createAccessor().setType('VEC2').setArray(new Float32Array(geom.attributes.uv.array)),
    );
  }
  if (geom.index) {
    const idx = geom.index.array;
    prim.setIndices(
      doc.createAccessor().setType('SCALAR').setArray(
        idx.length > 65535 ? new Uint32Array(idx) : new Uint16Array(idx),
      ),
    );
  }
}

function smoothPrimitive(doc, prim) {
  const before = prim.getAttribute('POSITION').getCount();
  const geom = toCreasedNormals(primitiveToGeometry(prim), CREASE_RAD);
  const merged = mergeVertices(geom);
  geom.dispose();
  applyGeometry(doc, prim, merged);
  merged.dispose();
  return { before, after: prim.getAttribute('POSITION').getCount() };
}

if (!fs.existsSync(TARGET)) throw new Error(`Missing ${TARGET}`);

const sizeBefore = fs.statSync(TARGET).size;
const doc = await io.read(TARGET);
let prims = 0;
let vertsBefore = 0;
let vertsAfter = 0;

for (const mesh of doc.getRoot().listMeshes()) {
  for (const prim of mesh.listPrimitives()) {
    const report = smoothPrimitive(doc, prim);
    prims++;
    vertsBefore += report.before;
    vertsAfter += report.after;
  }
}

for (const ext of doc.getRoot().listExtensionsUsed()) {
  if (ext.extensionName === 'KHR_draco_mesh_compression') ext.dispose();
}

const out = await io.writeBinary(doc);
const backupDir = path.join(root, 'assets', '_backup', 'pre-smooth-wheel');
const backup = path.join(backupDir, 'HavalHEV-wheel.glb');
if (!fs.existsSync(backup)) {
  fs.mkdirSync(backupDir, { recursive: true });
  fs.copyFileSync(TARGET, backup);
}
fs.writeFileSync(TARGET, Buffer.from(out));

console.log(
  `patched wheels/HavalHEV-wheel.glb: ${prims} primitive(s), ` +
  `verts ${vertsBefore} -> ${vertsAfter} (${((1 - vertsAfter / vertsBefore) * 100).toFixed(1)}% fewer), ` +
  `${(sizeBefore / 1024).toFixed(1)} -> ${(out.byteLength / 1024).toFixed(1)} KB`,
);

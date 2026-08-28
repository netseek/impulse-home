/**
 * Smooth HEV/PHEV body paint geometry (Body/Roof materials only).
 *
 * The HEV export carries ~6k duplicate-position vertices on Body alone where
 * each copy keeps its own hard face normal — classic faceted shading. This
 * script re-averages normals (60° crease) then re-indexes. Rims live in
 * assets/wheels/HavalHEV-wheel.glb — see patch-hev-wheel-material.mjs for rim PBR.
 *
 * Writes SEPARATE packed accessors (same layout as build-fast-models) and
 * copies KTX2 texture payloads through unchanged.
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
const TARGETS = [
  path.join(root, 'assets', '_source', 'haval-h6-hev.glb'),
  path.join(root, 'assets', 'haval-h6-hev-lite.glb'),
  path.join(root, 'assets', '_blender', 'haval-h6-hev-for-blender.glb'),
];
const PAINT_MATS = new Set(['Body', 'Roof']);
const CREASE_RAD = Math.PI / 3; // 60° — Blender auto-smooth default

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

  const pos = geom.attributes.position.array;
  prim.setAttribute(
    'POSITION',
    doc.createAccessor().setType('VEC3').setArray(new Float32Array(pos)),
  );

  const nor = geom.attributes.normal.array;
  prim.setAttribute(
    'NORMAL',
    doc.createAccessor().setType('VEC3').setArray(new Float32Array(nor)),
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

for (const file of TARGETS) {
  if (!fs.existsSync(file)) {
    console.log(`skip: ${path.relative(root, file)} (missing)`);
    continue;
  }

  const sizeBefore = fs.statSync(file).size;
  const doc = await io.read(file);
  let prims = 0;
  let vertsBefore = 0;
  let vertsAfter = 0;

  for (const mesh of doc.getRoot().listMeshes()) {
    for (const prim of mesh.listPrimitives()) {
      const mat = prim.getMaterial();
      if (!mat || !PAINT_MATS.has(mat.getName())) continue;
      const report = smoothPrimitive(doc, prim);
      prims++;
      vertsBefore += report.before;
      vertsAfter += report.after;
    }
  }

  const out = await io.writeBinary(doc);
  const backupDir = path.join(root, 'assets', '_backup', 'pre-smooth-body');
  const rel = path.relative(path.join(root, 'assets'), file);
  const backup = path.join(backupDir, rel);
  if (!fs.existsSync(backup)) {
    fs.mkdirSync(path.dirname(backup), { recursive: true });
    fs.copyFileSync(file, backup);
  }
  fs.writeFileSync(file, Buffer.from(out));

  console.log(
    `patched ${rel}: ${prims} paint primitive(s), ` +
    `verts ${vertsBefore} -> ${vertsAfter} (${((1 - vertsAfter / vertsBefore) * 100).toFixed(1)}% fewer), ` +
    `${(sizeBefore / 1048576).toFixed(2)} -> ${(out.byteLength / 1048576).toFixed(2)} MB`,
  );
}

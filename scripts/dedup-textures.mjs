// Drops byte-identical duplicate textures from the shipped GLBs.
//
// The lite pipeline (build-lite-textures.mjs) re-encodes each texture slot
// independently, so a source image referenced by two different materials comes
// out as two identical KTX2 payloads. haval-h6-hev-lite.glb carries one such
// pair (a 1024² brushed-metal map used by two "Brushed metal" material
// instances); the full source has the same pair at 2048².
//
// Textures only — NOT accessors/meshes/materials. Those would also dedupe
// cleanly, but the viewer identifies parts by mesh and material NAME all over
// the place (profiles, hiddenMeshes, light groups, the arch-cap list), and
// collapsing two same-shaped meshes into one shared instance is exactly the
// kind of thing that quietly breaks a name lookup. Textures are addressed only
// by reference, so merging them is invisible to the app.
//
// Compression state is preserved: an uncompressed input is written back
// uncompressed (and SEPARATE — see the VertexLayout note below), a Draco input
// is re-encoded with Draco.
//
// Usage: node scripts/dedup-textures.mjs [file...]     (defaults to the lite pair)
import { NodeIO, PropertyType, VertexLayout } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import { dedup, draco } from '@gltf-transform/functions';
import draco3d from 'draco3dgltf';
import { MeshoptDecoder, MeshoptEncoder } from 'meshoptimizer';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const io = new NodeIO()
  // CRITICAL, same reason as build-fast-models.mjs: the viewer edits vertex
  // data on the CPU (_deformTireBore, computeVertexNormals) with code that
  // assumes packed arrays. gltf-transform defaults to INTERLEAVED, which
  // renders the whole car as vertical smears on the device.
  .setVertexLayout(VertexLayout.SEPARATE)
  .registerExtensions(ALL_EXTENSIONS)
  .registerDependencies({
    'draco3d.decoder': await draco3d.createDecoderModule(),
    'draco3d.encoder': await draco3d.createEncoderModule(),
    'meshopt.decoder': MeshoptDecoder,
    'meshopt.encoder': MeshoptEncoder,
  });

const DEFAULTS = ['assets/haval-h6-hev-lite.glb', 'assets/haval-h6-gt-lite.glb'];
const files = (process.argv.slice(2).length ? process.argv.slice(2) : DEFAULTS);

const texBytes = (doc) =>
  doc.getRoot().listTextures().reduce((a, t) => a + (t.getImage() ? t.getImage().byteLength : 0), 0);

let totalSaved = 0;
for (const rel of files) {
  const file = path.resolve(root, rel);
  if (!fs.existsSync(file)) { console.log(`  MISS  ${rel}`); continue; }

  const sizeBefore = fs.statSync(file).size;
  const doc = await io.read(file);
  const hadDraco = doc.getRoot().listExtensionsUsed()
    .some((e) => e.extensionName === 'KHR_draco_mesh_compression');

  const before = doc.getRoot().listTextures().length;
  const dupes = (() => {
    const seen = new Set();
    let n = 0;
    doc.getRoot().listTextures().forEach((t) => {
      const img = t.getImage(); if (!img) return;
      const h = crypto.createHash('sha1').update(img).digest('hex');
      if (seen.has(h)) n++; else seen.add(h);
    });
    return n;
  })();

  if (!dupes) {
    console.log(`  skip  ${rel.padEnd(30)} no duplicate textures`);
    continue;
  }

  const texBefore = texBytes(doc);
  await doc.transform(dedup({ propertyTypes: [PropertyType.TEXTURE] }));
  if (hadDraco) {
    await doc.transform(draco({
      method: 'edgebreaker', quantizePosition: 14, quantizeNormal: 10,
      quantizeTexcoord: 12, quantizeColor: 8,
    }));
  }
  const out = await io.writeBinary(doc);
  fs.writeFileSync(file, out);

  const saved = sizeBefore - out.byteLength;
  totalSaved += saved;
  console.log(
    `  ok    ${rel.padEnd(30)} textures ${before} -> ${doc.getRoot().listTextures().length} ` +
    `(${dupes} dup), payload ${(texBefore / 1048576).toFixed(2)} -> ${(texBytes(doc) / 1048576).toFixed(2)} MB, ` +
    `file ${(sizeBefore / 1048576).toFixed(2)} -> ${(out.byteLength / 1048576).toFixed(2)} MB` +
    `${hadDraco ? ' (draco re-encoded)' : ' (uncompressed, SEPARATE)'}  ${out.byteLength} bytes`,
  );
}
console.log(`\nSaved ${(totalSaved / 1048576).toFixed(2)} MB. Update HEV_BYTES/GT_BYTES in index.html if they changed.`);

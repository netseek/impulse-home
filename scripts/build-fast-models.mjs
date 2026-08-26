/**
 * Strip Draco compression from the boot-critical car models, leaving plain
 * float32 geometry the WebView can upload with no decode step at all.
 *
 * Draco is a *bandwidth* optimization, and this viewer has no bandwidth cost:
 * every GLB ships inside the APK and is served from local flash through
 * MainActivity's shouldInterceptRequest. Draco therefore buys nothing at
 * startup and costs a great deal — Draco decode was the single largest item in
 * the viewer's cold start.
 *
 * Measured on the target head unit (Snapdragon SA8155, WebView 91), parsing
 * haval-h6-hev.glb end to end (fetch excluded; includes JPEG texture decode and
 * scene/material setup):
 *
 *   Draco, JS decoder (was shipping)   5.48 MB   10326 ms
 *   Draco, WASM decoder                5.48 MB    3422 ms
 *   meshopt + quantized                7.00 MB    1765 ms
 *   quantized, uncompressed            9.19 MB    1736 ms
 *   raw float32                       10.79 MB    1771 ms   <- used here
 *
 * ~1.75s of that is texture decode and scene setup, so every uncompressed
 * option hits the same floor and the only real question is file size.
 *
 * WHY ONLY THESE FILES, and why float32 rather than the smaller meshopt:
 *
 *  - Uncompressed geometry is big. Decompressing *everything* took the asset
 *    tree from 21 MB to 81 MB, mostly because the GT car (605k tris) expands to
 *    19 MB and the wheel catalog has 25 entries. Only the HEV car is on the cold
 *    path — the GT is lazy-loaded on the first MODEL toggle and the wheels are
 *    small — so only the HEV pair is converted. Everything else keeps Draco and
 *    now decodes with the WASM decoder (index.html), which is ~3x faster than
 *    the JS one that was previously forced on Android.
 *
 *  - meshopt would be 3.8 MB smaller at the same speed, but it requires
 *    quantized attributes, and quantization rewrites POSITION as normalized
 *    SHORT with the dequantization folded into the node transform. three.js
 *    r137's `Vector3.fromBufferAttribute()` returns the *raw* stored value and
 *    does not denormalize, so roughly fifteen sites in index.html that walk
 *    vertices on the CPU — headlight/fog component splitting, door-hinge
 *    detection, tire and wheel fitting, the uploaded-wheel normalizer — would
 *    silently compute in 0..32767 space instead of metres. The uploaded-wheel
 *    path measured a radius of 16071 instead of ~0.24 when this was tried.
 *    Adopting meshopt means auditing and fixing those reads first (or
 *    dequantizing to float32 at load); it is a worthwhile follow-up for APK
 *    size, but it needs on-car verification. Plain float32 changes no attribute
 *    semantics, so none of that code is affected.
 *
 * Idempotent: files carrying no compression extension are skipped. Originals
 * are copied to assets/_backup/pre-decompress/ on first run.
 *
 * Usage: npm run build:fast-models
 */
import { NodeIO, VertexLayout } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import draco3d from 'draco3dgltf';
import { MeshoptDecoder, MeshoptEncoder } from 'meshoptimizer';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const assetsDir = path.join(root, 'assets');
const backupDir = path.join(assetsDir, '_backup', 'pre-decompress');

/**
 * Boot-critical models only — these are what componentDidMount loads before the
 * loader overlay can clear (HEV_URL, picked by the perf tier). Keep in sync with
 * the HEV_URL/HEV_BYTES constants in index.html.
 */
const TARGETS = [
  'haval-h6-hev-lite.glb',
];

const COMPRESSION_EXTENSIONS = [
  'KHR_draco_mesh_compression',
  'EXT_meshopt_compression',
];

const io = new NodeIO()
  // CRITICAL: write tightly-packed, non-interleaved attributes.
  //
  // gltf-transform defaults to VertexLayout.INTERLEAVED, which packs POSITION /
  // NORMAL / TEXCOORD into one bufferView with a byteStride (24 or 32 bytes
  // here). That is valid glTF and three.js loads it happily as
  // InterleavedBufferAttribute — but the viewer edits vertex data on the CPU
  // (_deformTireBore, computeVertexNormals, `position.needsUpdate = true`) with
  // code that assumes a packed array, so those writes land in the wrong slots
  // and corrupt neighbouring attributes. On the car this rendered the whole
  // model as vertical smears.
  //
  // Draco decoding produces separate packed arrays, which is why the compressed
  // build never hit this. SEPARATE reproduces that layout exactly.
  .setVertexLayout(VertexLayout.SEPARATE)
  .registerExtensions(ALL_EXTENSIONS)
  .registerDependencies({
    'draco3d.decoder': await draco3d.createDecoderModule(),
    'draco3d.encoder': await draco3d.createEncoderModule(),
    'meshopt.decoder': MeshoptDecoder,
    'meshopt.encoder': MeshoptEncoder,
  });

/** Read the glTF JSON chunk out of a .glb without a full parse. */
function glbExtensions(file) {
  const buf = fs.readFileSync(file);
  if (buf.length < 12 || buf.readUInt32LE(0) !== 0x46546c67) return null;
  let off = 12;
  while (off + 8 <= buf.length) {
    const len = buf.readUInt32LE(off);
    const type = buf.readUInt32LE(off + 4);
    if (type === 0x4e4f534a) {
      return JSON.parse(buf.slice(off + 8, off + 8 + len).toString('utf8')).extensionsUsed || [];
    }
    off += 8 + len;
  }
  return [];
}

let converted = 0, skipped = 0, before = 0, after = 0;

for (const rel of TARGETS) {
  const file = path.join(assetsDir, rel);
  if (!fs.existsSync(file)) { console.log(`  MISS  ${rel} (not found)`); skipped++; continue; }

  const exts = glbExtensions(file);
  if (exts === null) { console.log(`  skip  ${rel} (not a GLB)`); skipped++; continue; }

  const compressed = exts.filter((e) => COMPRESSION_EXTENSIONS.includes(e));
  if (compressed.length === 0) {
    console.log(`  skip  ${rel} (already uncompressed)`);
    skipped++;
    continue;
  }

  const sizeBefore = fs.statSync(file).size;
  const doc = await io.read(file);

  // io.read() has already decoded the compressed accessors into memory, so
  // dropping the extension object simply stops it being re-encoded on write and
  // clears it from extensionsUsed/extensionsRequired. Geometry is unchanged:
  // no requantization, no attribute-type change.
  for (const ext of doc.getRoot().listExtensionsUsed()) {
    if (COMPRESSION_EXTENSIONS.includes(ext.extensionName)) ext.dispose();
  }

  const out = await io.writeBinary(doc);

  const backup = path.join(backupDir, rel);
  if (!fs.existsSync(backup)) {
    fs.mkdirSync(path.dirname(backup), { recursive: true });
    fs.copyFileSync(file, backup);
  }
  fs.writeFileSync(file, out);

  before += sizeBefore;
  after += out.byteLength;
  converted++;
  console.log(
    `  ok    ${rel.padEnd(28)} ` +
    `${(sizeBefore / 1048576).toFixed(2)} -> ${(out.byteLength / 1048576).toFixed(2)} MB ` +
    `(was ${compressed.join('+')})  ${out.byteLength} bytes`,
  );
}

console.log(
  `\n${converted} converted, ${skipped} skipped. ` +
  `${(before / 1048576).toFixed(2)} -> ${(after / 1048576).toFixed(2)} MB. ` +
  `Originals in assets/_backup/pre-decompress/\n` +
  `Remember to update HEV_BYTES in index.html to the byte counts above.`,
);

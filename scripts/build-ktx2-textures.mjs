// Convert the GLB textures to KTX2 / UASTC (KHR_texture_basisu).
//
// WHY
// ---
// The target head unit is a Snapdragon SA8155 / Adreno 640 — a fill-rate and
// bandwidth bound mobile GPU. The HEV model ships 8 textures at 2048², stored as
// JPEG in the GLB. JPEG is a *transport* format only: the GPU cannot sample it,
// so every one is decoded to RGBA8 at load and lives in VRAM uncompressed.
//
//     8 x 2048x2048 x 4 bytes  = 128 MB
//     + the 1024² and odd-size maps and mip chains  = ~175 MB resident
//
// on a GPU that shares system memory. That is the single largest cost in the
// frame: every fragment of the car samples several of these and misses cache.
//
// KTX2/UASTC transcodes on device to ASTC 4x4 (Adreno 640 supports ASTC
// natively), which the GPU samples *in compressed form* at 1 byte/texel:
//
//     8 x 2048x2048 x 1 byte   = 32 MB      (4x less, mips included ~44 MB)
//
// Quality is essentially unchanged — UASTC is the high-quality Basis mode, and
// unlike the -lite build it does NOT reduce resolution. Cold start should also
// improve slightly: the file is bigger but ASTC transcode is far cheaper than
// 8 full-size JPEG decodes.
//
// The trade is on-disk size: UASTC + Zstd supercompression is roughly 2.9 MB per
// 2048² map against ~0.5 MB for the JPEG, so the GLB grows. That is a deliberate
// trade of APK bytes (cheap, one-off) for VRAM bandwidth (the actual bottleneck).
//
// MIPMAPS ARE BAKED HERE ON PURPOSE
// ---------------------------------
// three.js can generate mipmaps for uncompressed textures at runtime, but NOT
// for compressed ones. Shipping a KTX2 without a mip chain would leave every
// minified surface sampling the top level — worse cache behaviour than the JPEGs
// it replaced. generateMipmap is therefore not optional.
//
// ORDERING CONSTRAINT
// -------------------
// Run this AFTER scripts/build-lite-textures.mjs. That script reads the full-size
// GLBs with sharp to produce the -lite variants, and sharp cannot decode KTX2.
// This script converts all four GLBs, so the -lite files get ASTC too (at 1024²
// that is 1 MB/texture — 16x less than the original 2048² RGBA8).
//
// WHY RAW GLB SURGERY AND NOT gltf-transform
// ------------------------------------------
// Same reason build-lite-textures.mjs does it this way: routing the model through
// gltf-transform rewrites the vertex layout, and scripts/build-fast-models.mjs
// documents that anything other than VertexLayout.SEPARATE breaks the viewer's
// CPU-side vertex edits (_deformTireBore, computeVertexNormals) and renders the
// car as vertical smears. This script only ever rewrites image bufferViews and
// the texture/extension JSON — vertex data is copied through byte for byte.
//
// Idempotent: files whose images are already image/ktx2 are skipped. Originals
// are backed up to assets/_backup/pre-ktx2/ on first run.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';
import { encodeToKTX2 } from 'ktx2-encoder';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const assetsDir = path.resolve(scriptDir, '..', 'assets');
const backupDir = path.join(assetsDir, '_backup', 'pre-ktx2');

const TARGETS = [
  'haval-h6-hev.glb',
  'haval-h6-hev-lite.glb',
  'haval-h6-gt.glb',
  'haval-h6-gt-lite.glb',
];

const JSON_CHUNK = 0x4e4f534a;
const BIN_CHUNK = 0x004e4942;
const align4 = (value) => (value + 3) & ~3;

function parseGlb(buffer) {
  if (buffer.readUInt32LE(0) !== 0x46546c67) throw new Error('Not a GLB file.');
  let offset = 12;
  let jsonBuffer;
  let binBuffer;
  while (offset < buffer.length) {
    const length = buffer.readUInt32LE(offset);
    const type = buffer.readUInt32LE(offset + 4);
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    if (type === JSON_CHUNK) jsonBuffer = data;
    if (type === BIN_CHUNK) binBuffer = data;
    offset += 8 + length;
  }
  if (!jsonBuffer || !binBuffer) throw new Error('GLB is missing JSON or BIN data.');
  return { json: JSON.parse(jsonBuffer.toString('utf8')), binBuffer };
}

function buildGlb(json, binBuffer) {
  const rawJson = Buffer.from(JSON.stringify(json), 'utf8');
  const jsonBuffer = Buffer.alloc(align4(rawJson.length), 0x20);
  rawJson.copy(jsonBuffer);
  const paddedBin = Buffer.alloc(align4(binBuffer.length));
  binBuffer.copy(paddedBin);
  const header = Buffer.alloc(12);
  const jsonHeader = Buffer.alloc(8);
  const binHeader = Buffer.alloc(8);
  const total = 12 + 8 + jsonBuffer.length + 8 + paddedBin.length;
  header.writeUInt32LE(0x46546c67, 0);
  header.writeUInt32LE(2, 4);
  header.writeUInt32LE(total, 8);
  jsonHeader.writeUInt32LE(jsonBuffer.length, 0);
  jsonHeader.writeUInt32LE(JSON_CHUNK, 4);
  binHeader.writeUInt32LE(paddedBin.length, 0);
  binHeader.writeUInt32LE(BIN_CHUNK, 4);
  return Buffer.concat([header, jsonHeader, jsonBuffer, binHeader, paddedBin]);
}

// Which material slots each image feeds. Drives the colour-space and normal-map
// encoder flags — getting these wrong is the classic way to wreck a Basis encode
// (sRGB-encoding a normal map, or linear-encoding albedo).
function collectImageRoles(json) {
  const roles = new Map();
  const add = (textureInfo, role) => {
    if (!textureInfo) return;
    const texture = json.textures?.[textureInfo.index];
    if (!texture) return;
    const source = texture.source ?? texture.extensions?.KHR_texture_basisu?.source;
    if (source === undefined) return;
    if (!roles.has(source)) roles.set(source, new Set());
    roles.get(source).add(role);
  };
  for (const material of json.materials || []) {
    add(material.pbrMetallicRoughness?.baseColorTexture, 'base');
    add(material.pbrMetallicRoughness?.metallicRoughnessTexture, 'metalRough');
    add(material.normalTexture, 'normal');
    add(material.emissiveTexture, 'emissive');
    add(material.occlusionTexture, 'occlusion');
  }
  return roles;
}

// Node has no ImageBitmap; the encoder needs raw RGBA. sharp is already a
// devDependency (build-lite-textures.mjs uses it).
const imageDecoder = async (buffer) => {
  const image = sharp(Buffer.from(buffer));
  const { width, height } = await image.metadata();
  const data = await image.ensureAlpha().raw().toBuffer();
  return { width, height, data: new Uint8Array(data) };
};

async function encodeImage(bytes, roles) {
  const isNormal = roles.has('normal');
  // baseColor and emissive carry sRGB colour; normal / metalRough / occlusion
  // are linear data channels and must not get the sRGB transfer function.
  const isColor = roles.has('base') || roles.has('emissive');
  return encodeToKTX2(new Uint8Array(bytes), {
    isUASTC: true,             // high-quality mode; ETC1S would visibly band the paint
    uastcLDRQualityLevel: 2,   // 0-3, quality vs encode time
    needSupercompression: true, // Zstd on top of UASTC — disk only, free at runtime
    generateMipmap: true,      // mandatory, see header
    isKTX2File: true,
    isNormalMap: isNormal,
    isPerceptual: isColor,
    isSetKTX2SRGBTransferFunc: isColor,
    imageDecoder,
  });
}

async function convert(fileName) {
  const filePath = path.join(assetsDir, fileName);
  if (!fs.existsSync(filePath)) {
    console.log(`${fileName}: not found, skipping`);
    return;
  }
  const source = fs.readFileSync(filePath);
  const { json, binBuffer } = parseGlb(source);
  const images = json.images || [];

  if (images.length && images.every((img) => img.mimeType === 'image/ktx2')) {
    console.log(`${fileName}: already KTX2, skipping`);
    return;
  }

  const rolesByImage = collectImageRoles(json);
  const imageViews = new Set(images.map((image) => image.bufferView));
  const replacements = new Map();
  // Only textures whose image really became KTX2 may be rewritten to point
  // through the extension. Marking one that is still JPEG would make every
  // loader read it as Basis and fail.
  const convertedImages = new Set();

  let before = 0;
  let after = 0;
  for (let index = 0; index < images.length; index++) {
    const image = images[index];
    if (image.mimeType === 'image/ktx2') { convertedImages.add(index); continue; }
    const view = json.bufferViews[image.bufferView];
    const offset = view.byteOffset || 0;
    const bytes = binBuffer.subarray(offset, offset + view.byteLength);
    const roles = rolesByImage.get(index) || new Set();
    const meta = await sharp(bytes).metadata();

    const started = Date.now();
    const encoded = await encodeImage(bytes, roles);
    replacements.set(image.bufferView, Buffer.from(encoded));
    image.mimeType = 'image/ktx2';
    convertedImages.add(index);
    before += bytes.length;
    after += encoded.byteLength;

    const roleList = [...roles].join(',') || 'unused';
    console.log(
      `${fileName} image ${index}: ${meta.width}x${meta.height} ${roleList} ` +
      `${(bytes.length / 1024).toFixed(0)}KB -> ${(encoded.byteLength / 1024).toFixed(0)}KB ` +
      `(${((Date.now() - started) / 1000).toFixed(1)}s)`,
    );
  }

  if (!replacements.size) {
    console.log(`${fileName}: no convertible images`);
    return;
  }

  // Point every texture at its image through the extension. When the extension
  // is required the plain `source` must go, otherwise a loader without basisu
  // support would silently read KTX2 bytes as JPEG.
  let allConverted = true;
  for (const texture of json.textures || []) {
    if (texture.source === undefined) continue;
    if (!convertedImages.has(texture.source)) { allConverted = false; continue; }
    texture.extensions = texture.extensions || {};
    texture.extensions.KHR_texture_basisu = { source: texture.source };
    delete texture.source;
  }
  json.extensionsUsed = [...new Set([...(json.extensionsUsed || []), 'KHR_texture_basisu'])];
  // "required" is only honest when nothing JPEG is left. A mixed file must stay
  // loadable by a viewer without basisu support for the parts that are still JPEG.
  if (allConverted) {
    json.extensionsRequired = [...new Set([...(json.extensionsRequired || []), 'KHR_texture_basisu'])];
  } else {
    console.warn(`${fileName}: some textures kept their original format; extension marked used but not required`);
  }

  // Rebuild BIN in original bufferView order so nothing but the image payloads
  // moves. Vertex/index views are copied byte for byte.
  const sortedViews = json.bufferViews
    .map((view, index) => ({ ...view, index }))
    .sort((a, b) => (a.byteOffset || 0) - (b.byteOffset || 0));
  const chunks = [];
  let writeOffset = 0;
  for (const view of sortedViews) {
    const aligned = align4(writeOffset);
    if (aligned > writeOffset) chunks.push(Buffer.alloc(aligned - writeOffset));
    writeOffset = aligned;
    const bytes = imageViews.has(view.index) && replacements.has(view.index)
      ? replacements.get(view.index)
      : binBuffer.subarray(view.byteOffset || 0, (view.byteOffset || 0) + view.byteLength);
    json.bufferViews[view.index].byteOffset = writeOffset;
    json.bufferViews[view.index].byteLength = bytes.length;
    chunks.push(Buffer.from(bytes));
    writeOffset += bytes.length;
  }

  const rebuiltBin = Buffer.concat(chunks, writeOffset);
  json.buffers[0].byteLength = rebuiltBin.length;
  const output = buildGlb(json, rebuiltBin);

  fs.mkdirSync(backupDir, { recursive: true });
  const backupPath = path.join(backupDir, fileName);
  if (!fs.existsSync(backupPath)) fs.writeFileSync(backupPath, source);
  fs.writeFileSync(filePath, output);

  console.log(
    `${fileName}: ${(source.length / 1048576).toFixed(2)} MiB -> ${(output.length / 1048576).toFixed(2)} MiB; ` +
    `images ${(before / 1048576).toFixed(2)} MiB -> ${(after / 1048576).toFixed(2)} MiB\n`,
  );
}

for (const target of TARGETS) await convert(target);

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const assetsDir = path.resolve(scriptDir, '..', 'assets');
// Inputs are the full-texture originals, which are build sources and are not
// shipped — only the -lite outputs are served (see HEV_URL/GT_URL in index.html).
const targets = [
  ['_source/haval-h6-hev.glb', 'haval-h6-hev-lite.glb'],
  ['_source/haval-h6-gt.glb', 'haval-h6-gt-lite.glb'],
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

function collectImageRoles(json) {
  const roles = new Map();
  const add = (textureInfo, role) => {
    if (!textureInfo) return;
    const texture = json.textures?.[textureInfo.index];
    if (!texture || texture.source === undefined) return;
    if (!roles.has(texture.source)) roles.set(texture.source, new Set());
    roles.get(texture.source).add(role);
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

async function resizeInteriorTexture(bytes, roles) {
  const metadata = await sharp(bytes).metadata();
  if (metadata.width !== 2048 || metadata.height !== 2048) {
    return { bytes, before: metadata, after: metadata, changed: false };
  }

  const isNormal = roles.has('normal');
  const isData = isNormal || roles.has('metalRough') || roles.has('occlusion');
  const quality = isNormal ? 92 : isData ? 90 : 88;
  const chromaSubsampling = isData ? '4:4:4' : '4:2:0';
  const resized = await sharp(bytes)
    .resize(1024, 1024, { fit: 'fill' })
    .jpeg({ quality, mozjpeg: true, chromaSubsampling })
    .toBuffer();
  const after = await sharp(resized).metadata();
  return { bytes: resized, before: metadata, after, changed: true };
}

async function buildLiteAsset(inputName, outputName) {
  const source = fs.readFileSync(path.join(assetsDir, inputName));
  const { json, binBuffer } = parseGlb(source);
  const rolesByImage = collectImageRoles(json);
  const imageViews = new Set((json.images || []).map((image) => image.bufferView));
  const replacements = new Map();
  const canonicalByHash = new Map();
  const canonicalImage = new Map();

  let encodedBefore = 0;
  let encodedAfter = 0;
  for (let index = 0; index < (json.images || []).length; index++) {
    const image = json.images[index];
    const view = json.bufferViews[image.bufferView];
    const bytes = binBuffer.subarray(view.byteOffset || 0, (view.byteOffset || 0) + view.byteLength);
    const result = await resizeInteriorTexture(bytes, rolesByImage.get(index) || new Set());
    replacements.set(image.bufferView, result.bytes);
    encodedBefore += bytes.length;
    encodedAfter += result.bytes.length;

    const hash = crypto.createHash('sha256').update(result.bytes).digest('hex');
    if (canonicalByHash.has(hash)) canonicalImage.set(index, canonicalByHash.get(hash));
    else canonicalByHash.set(hash, index);

    if (result.changed) {
      console.log(`${inputName} image ${index}: 2048x2048 ${bytes.length}B -> 1024x1024 ${result.bytes.length}B`);
    }
  }

  // Point exact duplicate textures at one image. GLTFLoader caches by image
  // bufferView + sampler, eliminating the duplicate JPEG decode and GPU upload.
  for (const texture of json.textures || []) {
    if (canonicalImage.has(texture.source)) texture.source = canonicalImage.get(texture.source);
  }

  const sortedViews = json.bufferViews
    .map((view, index) => ({ ...view, index }))
    .sort((a, b) => (a.byteOffset || 0) - (b.byteOffset || 0));
  const chunks = [];
  let writeOffset = 0;
  for (const view of sortedViews) {
    const aligned = align4(writeOffset);
    if (aligned > writeOffset) chunks.push(Buffer.alloc(aligned - writeOffset));
    writeOffset = aligned;
    const bytes = imageViews.has(view.index)
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
  fs.writeFileSync(path.join(assetsDir, outputName), output);
  console.log(`${outputName}: ${(source.length / 1048576).toFixed(2)} MiB -> ${(output.length / 1048576).toFixed(2)} MiB; images ${(encodedBefore / 1048576).toFixed(2)} MiB -> ${(encodedAfter / 1048576).toFixed(2)} MiB`);
}

for (const [input, output] of targets) await buildLiteAsset(input, output);

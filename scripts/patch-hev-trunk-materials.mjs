/**
 * Bake a few trunk-related material fixes into the HEV/PHEV body GLB so they
 * survive export without a Blender pass:
 *   - Plastic Black roughness 0 -> 0.5
 *   - Trunk Cover Matte.001 base color -> black
 *   - BuildingMesh-00023_1 / BuildingMesh-00024_1 -> Glass (Rear)
 *
 * IMPORTANT: patches JSON only and copies the BIN chunk through unchanged.
 * Do NOT round-trip through gltf-transform read/write — that rewrites vertex
 * layout and can disturb KTX2/Draco payloads. See CLAUDE.md "Editing GLB assets".
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const TARGETS = [
  path.join(root, 'assets', '_source', 'haval-h6-hev.glb'),
  path.join(root, 'assets', 'haval-h6-hev-lite.glb'),
  path.join(root, 'assets', '_blender', 'haval-h6-hev-for-blender.glb'),
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

function patchJsonMaterials(json) {
  const materials = json.materials || [];
  const byName = (name) => materials.find((m) => m.name === name);

  const plasticBlack = byName('Plastic Black');
  if (plasticBlack?.pbrMetallicRoughness) {
    plasticBlack.pbrMetallicRoughness.roughnessFactor = 0.5;
  } else {
    throw new Error('Plastic Black material not found');
  }

  for (const paintName of ['Body', 'Roof']) {
    const paint = byName(paintName);
    if (paint?.pbrMetallicRoughness) {
      paint.pbrMetallicRoughness.metallicFactor = 0.63;
      paint.pbrMetallicRoughness.roughnessFactor = 0.38;
    }
  }

  let trunk001Index = materials.findIndex((m) => m.name === 'Trunk Cover Matte.001');
  if (trunk001Index < 0) {
    const trunkMatteIndexes = materials
      .map((m, i) => (m.name === 'Trunk Cover Matte' ? i : -1))
      .filter((i) => i >= 0);
    if (trunkMatteIndexes.length < 2) {
      throw new Error(`Expected 2 Trunk Cover Matte materials, found ${trunkMatteIndexes.length}`);
    }
    // Blender names the second clone "Trunk Cover Matte.001" — on this model that
    // slot is the fixed cargo-floor cover (BuildingMesh-00101), which appears as
    // the first Trunk Cover Matte entry in the exported materials array.
    trunk001Index = trunkMatteIndexes[0];
    materials[trunk001Index].name = 'Trunk Cover Matte.001';
  }
  materials[trunk001Index].pbrMetallicRoughness.baseColorFactor = [0, 0, 0, 1];

  const glassRearIndex = materials.findIndex((m) => m.name === 'Glass (Rear)');
  if (glassRearIndex < 0) throw new Error('Glass (Rear) material not found');

  for (const node of json.nodes || []) {
    if (node.name !== 'BuildingMesh-00023_1' && node.name !== 'BuildingMesh-00024_1') continue;
    const mesh = json.meshes?.[node.mesh];
    if (!mesh) continue;
    for (const prim of mesh.primitives || []) {
      prim.material = glassRearIndex;
    }
  }

  return {
    plasticBlackRoughness: plasticBlack.pbrMetallicRoughness.roughnessFactor,
    trunk001Index,
    glassRearIndex,
  };
}

function patchJsonGlb(file) {
  const source = fs.readFileSync(file);
  const { json, binBuffer } = parseGlb(source);
  const report = patchJsonMaterials(json);
  const output = buildGlb(json, binBuffer);
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, output);
  fs.renameSync(tmp, file);
  return { ...report, binUnchanged: extractBin(source).equals(binBuffer) };
}

function extractBin(buf) {
  let off = 12;
  while (off + 8 <= buf.length) {
    const len = buf.readUInt32LE(off);
    const type = buf.readUInt32LE(off + 4);
    if (type === BIN_CHUNK) return buf.subarray(off + 8, off + 8 + len);
    off += 8 + len;
  }
  throw new Error('BIN chunk missing');
}

for (const file of TARGETS) {
  if (!fs.existsSync(file)) {
    console.log(`skip: ${file} (missing)`);
    continue;
  }
  const report = patchJsonGlb(file);
  console.log(`patched ${path.relative(root, file)}:`, JSON.stringify(report));
}

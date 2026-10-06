/**
 * Set HEV stock rim PBR to the tint used on the car (Appearance override):
 *   base color #121212, metallic 1.0, roughness 0.14
 *
 * JSON-only patch — BIN chunk copied through unchanged.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const TARGET = path.join(root, 'assets', 'wheels', 'HavalHEV-wheel.glb');
const RIM_BASE = [0x12 / 255, 0x12 / 255, 0x12 / 255, 1];

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

function patchJson(json) {
  let patched = 0;
  for (const mat of json.materials || []) {
    if (mat.name !== 'Rim Metal') continue;
    mat.pbrMetallicRoughness = {
      ...mat.pbrMetallicRoughness,
      baseColorFactor: RIM_BASE,
      metallicFactor: 1.0,
      roughnessFactor: 0.14,
    };
    patched++;
  }
  if (!patched) throw new Error('Rim Metal material not found');
  return patched;
}

const source = fs.readFileSync(TARGET);
const { json, binBuffer } = parseGlb(source);
const count = patchJson(json);
const output = buildGlb(json, binBuffer);
const tmp = `${TARGET}.tmp`;
fs.writeFileSync(tmp, output);
fs.renameSync(tmp, TARGET);
console.log(`patched ${path.relative(root, TARGET)}: ${count} Rim Metal slot(s) -> #121212 / metal 1.0 / rough 0.14`);

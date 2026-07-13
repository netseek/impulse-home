/**
 * Surgically shrink the JPEG textures embedded in the Draco-compressed GLB
 * car models, WITHOUT touching any geometry bytes.
 *
 * This does NOT use gltf-transform's read/write round-trip (that would
 * re-quantize/re-encode the Draco geometry and can shift vertices — see the
 * git history of PHEV regressions caused by exactly that). Instead it parses
 * the GLB container by hand:
 *
 *   - 12-byte header (magic/version/length)
 *   - JSON chunk (type 0x4E4F534A)
 *   - BIN chunk   (type 0x004E4942)
 *
 * Every bufferView that is NOT referenced by `images[]` is copied byte-for-byte
 * unchanged into the rebuilt BIN chunk. Only bufferViews referenced by
 * `images[]` are replaced with resized/recompressed JPEG bytes. Because
 * accessors store their byteOffset *relative to their bufferView* (not the
 * buffer), shrinking an image bufferView never requires touching accessor
 * data — only bufferView.byteOffset values downstream need to shift.
 *
 * Usage: node scripts/shrink-textures.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ASSETS = path.resolve(__dirname, '..', 'assets');
const BACKUP = path.join(ASSETS, '_backup');

const TARGETS = ['haval-h6-hev.glb', 'haval-h6-gt.glb'];

const MAX_DIM = 2048;
const JPEG_QUALITY = 85;
const SMALL_SKIP_BYTES = 64 * 1024; // under ~64KB: only re-encode if it shrinks

const JSON_CHUNK_TYPE = 0x4e4f534a; // 'JSON'
const BIN_CHUNK_TYPE = 0x004e4942; // 'BIN\0'

function align4(n) {
  return (n + 3) & ~3;
}

function parseGlb(buf) {
  const magic = buf.readUInt32LE(0);
  if (magic !== 0x46546c67) throw new Error('Not a GLB file (bad magic)');
  const version = buf.readUInt32LE(4);
  const totalLength = buf.readUInt32LE(8);

  let offset = 12;
  let jsonChunk = null;
  let binChunk = null;
  while (offset < buf.length) {
    const chunkLength = buf.readUInt32LE(offset);
    const chunkType = buf.readUInt32LE(offset + 4);
    const chunkData = buf.subarray(offset + 8, offset + 8 + chunkLength);
    if (chunkType === JSON_CHUNK_TYPE) jsonChunk = chunkData;
    else if (chunkType === BIN_CHUNK_TYPE) binChunk = chunkData;
    offset += 8 + chunkLength;
  }
  if (!jsonChunk || !binChunk) throw new Error('Missing JSON or BIN chunk');

  const json = JSON.parse(jsonChunk.toString('utf8'));
  return { version, totalLength, json, binChunk };
}

function buildGlb(json, binBuffer) {
  const jsonStr = JSON.stringify(json);
  let jsonBuf = Buffer.from(jsonStr, 'utf8');
  const jsonPadded = align4(jsonBuf.length);
  if (jsonPadded !== jsonBuf.length) {
    const padded = Buffer.alloc(jsonPadded, 0x20); // space-pad per glTF spec
    jsonBuf.copy(padded);
    jsonBuf = padded;
  }

  const binPadded = align4(binBuffer.length);
  let binBuf = binBuffer;
  if (binPadded !== binBuffer.length) {
    const padded = Buffer.alloc(binPadded, 0x00);
    binBuffer.copy(padded);
    binBuf = padded;
  }

  const header = Buffer.alloc(12);
  const totalLength = 12 + 8 + jsonBuf.length + 8 + binBuf.length;
  header.writeUInt32LE(0x46546c67, 0); // magic 'glTF'
  header.writeUInt32LE(2, 4); // version
  header.writeUInt32LE(totalLength, 8);

  const jsonChunkHeader = Buffer.alloc(8);
  jsonChunkHeader.writeUInt32LE(jsonBuf.length, 0);
  jsonChunkHeader.writeUInt32LE(JSON_CHUNK_TYPE, 4);

  const binChunkHeader = Buffer.alloc(8);
  binChunkHeader.writeUInt32LE(binBuf.length, 0);
  binChunkHeader.writeUInt32LE(BIN_CHUNK_TYPE, 4);

  return Buffer.concat([header, jsonChunkHeader, jsonBuf, binChunkHeader, binBuf]);
}

async function shrinkImage(bytes, label) {
  const meta = await sharp(bytes).metadata();
  const { width, height } = meta;
  const maxDim = Math.max(width, height);
  const needsResize = maxDim > MAX_DIM;

  let pipeline = sharp(bytes);
  if (needsResize) {
    pipeline = pipeline.resize({
      width: width >= height ? MAX_DIM : undefined,
      height: height > width ? MAX_DIM : undefined,
      fit: 'inside',
      withoutEnlargement: true,
    });
  }
  const out = await pipeline
    .jpeg({ quality: JPEG_QUALITY, mozjpeg: true, chromaSubsampling: '4:2:0' })
    .toBuffer();

  const outMeta = await sharp(out).metadata();

  // Never bloat: if re-encoding without resizing made it bigger, keep original.
  if (!needsResize && out.length >= bytes.length) {
    return {
      bytes,
      before: { width, height, bytes: bytes.length },
      after: { width, height, bytes: bytes.length },
      skipped: true,
    };
  }

  return {
    bytes: out,
    before: { width, height, bytes: bytes.length },
    after: { width: outMeta.width, height: outMeta.height, bytes: out.length },
    skipped: false,
  };
}

async function processFile(fileName) {
  const srcPath = path.join(ASSETS, fileName);
  const backupPath = path.join(BACKUP, fileName.replace(/\.glb$/, '.pre-texshrink.glb'));

  const originalBuf = fs.readFileSync(srcPath);

  if (!fs.existsSync(backupPath)) {
    fs.copyFileSync(srcPath, backupPath);
    console.log(`  backed up -> ${path.relative(process.cwd(), backupPath)}`);
  } else {
    console.log(`  backup already exists, leaving it in place -> ${path.relative(process.cwd(), backupPath)}`);
  }

  const { json, binChunk } = parseGlb(originalBuf);

  const imageBufferViewIndices = new Set((json.images || []).map((img) => img.bufferView));

  console.log(`\n=== ${fileName} ===`);
  console.log(`  original size: ${(originalBuf.length / 1024 / 1024).toFixed(2)} MB`);

  // Process each image bufferView (indexed by bufferView index -> new bytes)
  const newImageBytes = new Map();
  let totalBefore = 0;
  let totalAfter = 0;

  for (let i = 0; i < json.images.length; i++) {
    const img = json.images[i];
    const bvIdx = img.bufferView;
    const bv = json.bufferViews[bvIdx];
    const srcBytes = binChunk.subarray(bv.byteOffset, bv.byteOffset + bv.byteLength);

    const label = img.name || `image[${i}]`;
    const result = await shrinkImage(srcBytes, label);
    newImageBytes.set(bvIdx, result.bytes);

    totalBefore += result.before.bytes;
    totalAfter += result.after.bytes;

    const beforeKB = (result.before.bytes / 1024).toFixed(1);
    const afterKB = (result.after.bytes / 1024).toFixed(1);
    const tag = result.skipped ? ' (kept original, re-encode did not shrink)' : '';
    console.log(
      `  image[${i}] "${label}" bufferView ${bvIdx}: ` +
        `${result.before.width}x${result.before.height} ${beforeKB}KB -> ` +
        `${result.after.width}x${result.after.height} ${afterKB}KB${tag}`
    );
  }

  // Rebuild the BIN chunk: walk bufferViews sorted by original byteOffset,
  // copy non-image bytes unchanged, splice in new image bytes, recompute offsets.
  const bvList = json.bufferViews.map((bv, idx) => ({ idx, ...bv }));
  const sorted = [...bvList].sort((a, b) => a.byteOffset - b.byteOffset);

  const chunks = [];
  let writeOffset = 0;
  const newOffsets = new Map(); // idx -> new byteOffset
  const newLengths = new Map(); // idx -> new byteLength

  for (const bv of sorted) {
    // Align each bufferView start to 4 bytes (matches original convention).
    const alignedOffset = align4(writeOffset);
    if (alignedOffset !== writeOffset) {
      chunks.push(Buffer.alloc(alignedOffset - writeOffset, 0x00));
      writeOffset = alignedOffset;
    }

    let bytes;
    if (imageBufferViewIndices.has(bv.idx)) {
      bytes = newImageBytes.get(bv.idx);
    } else {
      bytes = binChunk.subarray(bv.byteOffset, bv.byteOffset + bv.byteLength);
    }

    newOffsets.set(bv.idx, writeOffset);
    newLengths.set(bv.idx, bytes.length);
    chunks.push(Buffer.from(bytes)); // copy, not a view into original buffer
    writeOffset += bytes.length;
  }

  const newBinChunk = Buffer.concat(chunks, writeOffset);

  // Update JSON bufferViews with new offsets/lengths.
  for (let idx = 0; idx < json.bufferViews.length; idx++) {
    json.bufferViews[idx].byteOffset = newOffsets.get(idx);
    json.bufferViews[idx].byteLength = newLengths.get(idx);
  }

  // Update buffer length.
  json.buffers[0].byteLength = newBinChunk.length;

  const newGlb = buildGlb(json, newBinChunk);

  console.log(`  texture bytes: ${(totalBefore / 1024 / 1024).toFixed(2)} MB -> ${(totalAfter / 1024 / 1024).toFixed(2)} MB`);
  console.log(`  new file size: ${(newGlb.length / 1024 / 1024).toFixed(2)} MB (was ${(originalBuf.length / 1024 / 1024).toFixed(2)} MB)`);

  fs.writeFileSync(srcPath, newGlb);
  console.log(`  wrote -> ${path.relative(process.cwd(), srcPath)}`);

  return { fileName, backupPath, srcPath };
}

function verifyGeometryIdentical(fileName, backupPath, outputPath) {
  const backupBuf = fs.readFileSync(backupPath);
  const outputBuf = fs.readFileSync(outputPath);

  const backupParsed = parseGlb(backupBuf);
  const outputParsed = parseGlb(outputBuf);

  const backupImageBvs = new Set((backupParsed.json.images || []).map((img) => img.bufferView));

  // Sanity: same number of bufferViews, same non-image bufferView byteLengths.
  if (backupParsed.json.bufferViews.length !== outputParsed.json.bufferViews.length) {
    console.log(`  GEOMETRY IDENTICAL: no (bufferView count mismatch: ${backupParsed.json.bufferViews.length} vs ${outputParsed.json.bufferViews.length})`);
    return false;
  }

  let ok = true;
  let checked = 0;
  for (let idx = 0; idx < backupParsed.json.bufferViews.length; idx++) {
    if (backupImageBvs.has(idx)) continue; // image bufferView, expected to differ

    const bBv = backupParsed.json.bufferViews[idx];
    const oBv = outputParsed.json.bufferViews[idx];

    if (bBv.byteLength !== oBv.byteLength) {
      console.log(`  MISMATCH bufferView ${idx}: byteLength ${bBv.byteLength} vs ${oBv.byteLength}`);
      ok = false;
      continue;
    }

    const bBytes = backupParsed.binChunk.subarray(bBv.byteOffset, bBv.byteOffset + bBv.byteLength);
    const oBytes = outputParsed.binChunk.subarray(oBv.byteOffset, oBv.byteOffset + oBv.byteLength);

    if (!bBytes.equals(oBytes)) {
      console.log(`  MISMATCH bufferView ${idx}: byte content differs (byteLength ${bBv.byteLength})`);
      ok = false;
      continue;
    }
    checked++;
  }

  // Also verify every other top-level JSON field besides images/bufferViews/buffers is untouched.
  const stripForCompare = (j) => {
    const clone = JSON.parse(JSON.stringify(j));
    delete clone.images;
    delete clone.bufferViews;
    delete clone.buffers;
    return clone;
  };
  const backupRest = JSON.stringify(stripForCompare(backupParsed.json));
  const outputRest = JSON.stringify(stripForCompare(outputParsed.json));
  if (backupRest !== outputRest) {
    console.log(`  MISMATCH: non-bufferView/image/buffer JSON differs between original and output`);
    ok = false;
  }

  console.log(`  GEOMETRY IDENTICAL: ${ok ? 'yes' : 'no'} (${checked} non-image bufferViews byte-compared)`);
  return ok;
}

async function main() {
  const results = [];
  for (const fileName of TARGETS) {
    const result = await processFile(fileName);
    results.push(result);
  }

  console.log('\n=== VERIFICATION ===');
  for (const { fileName, backupPath, srcPath } of results) {
    console.log(`\n${fileName}:`);
    verifyGeometryIdentical(fileName, backupPath, srcPath);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});

import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import { draco, prune, simplify, textureCompress } from '@gltf-transform/functions';
import draco3d from 'draco3dgltf';
import { MeshoptSimplifier } from 'meshoptimizer';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectDir = path.resolve(scriptDir, '..');
const sourcePath = path.join(projectDir, 'assets', '_source', 'wheels', 'HavalPHEV-wheel.glb');
const outputPath = path.join(projectDir, 'assets', 'wheels', 'HavalPHEV-wheel-lite.glb');

const io = new NodeIO()
  .registerExtensions(ALL_EXTENSIONS)
  .registerDependencies({
    'draco3d.decoder': await draco3d.createDecoderModule(),
    'draco3d.encoder': await draco3d.createEncoderModule(),
  });

const document = await io.read(sourcePath);
await document.transform(
  simplify({
    simplifier: MeshoptSimplifier,
    ratio: 0.5,
    error: 0.001,
    lockBorder: true,
  }),
  textureCompress({
    encoder: sharp,
    targetFormat: 'jpeg',
    resize: [1024, 1024],
    quality: 88,
    chromaSubsampling: '4:2:0',
  }),
  prune(),
  draco({
    method: 'edgebreaker',
    quantizePosition: 14,
    quantizeNormal: 10,
    quantizeTexcoord: 12,
  }),
);

await io.write(outputPath, document);
console.log(`Wrote ${path.relative(projectDir, outputPath)}`);

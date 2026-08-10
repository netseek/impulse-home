import { NodeIO, PropertyType } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import { prune } from '@gltf-transform/functions';
import draco3d from 'draco3dgltf';
import path from 'node:path';

const [, , inputArg, outputArg] = process.argv;
if (!inputArg || !outputArg) {
  throw new Error('Usage: node scripts/strip-embedded-hev-wheels.mjs <input.glb> <output.glb>');
}

const inputPath = path.resolve(inputArg);
const outputPath = path.resolve(outputArg);
if (inputPath === outputPath) {
  throw new Error('Input and output paths must be different.');
}

const io = new NodeIO()
  .registerExtensions(ALL_EXTENSIONS)
  .registerDependencies({
    'draco3d.decoder': await draco3d.createDecoderModule(),
    'draco3d.encoder': await draco3d.createEncoderModule(),
  });

const document = await io.read(inputPath);
const embeddedRims = document.getRoot().listNodes().filter((node) =>
  /^Custom_Wheel_Mesh_(fl|fr|rl|rr)_tripo/i.test(node.getName()),
);

if (embeddedRims.length !== 4) {
  throw new Error(`Expected exactly four embedded stock rims, found ${embeddedRims.length}.`);
}

embeddedRims.forEach((node) => node.dispose());

// Remove only resources orphaned by the four rim nodes. NODE is deliberately
// excluded so named empty transform nodes used by runtime feature matching are
// preserved byte-for-byte in the scene hierarchy.
await document.transform(prune({
  propertyTypes: [
    PropertyType.MESH,
    PropertyType.PRIMITIVE,
    PropertyType.PRIMITIVE_TARGET,
    PropertyType.MATERIAL,
    PropertyType.TEXTURE,
    PropertyType.ACCESSOR,
    PropertyType.BUFFER,
  ],
}));

await io.write(outputPath, document);
console.log(`Removed ${embeddedRims.length} embedded rims and wrote ${outputPath}`);

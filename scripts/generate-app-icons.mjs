import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const sharp = require('sharp');

const source = path.resolve('app-icon-source.png');
const resourceRoot = path.resolve('app/src/main/res');
const densities = {
  'mipmap-mdpi': 48,
  'mipmap-hdpi': 72,
  'mipmap-xhdpi': 96,
  'mipmap-xxhdpi': 144,
  'mipmap-xxxhdpi': 192,
};

await Promise.all(Object.entries(densities).flatMap(([directory, size]) => {
  const destination = path.join(resourceRoot, directory);
  return [
    sharp(source).resize(size, size).png()
      .toFile(path.join(destination, 'ic_launcher.png')),
    sharp(source).resize(size, size).png()
      .toFile(path.join(destination, 'ic_launcher_round.png')),
    sharp(source).resize(Math.round(size * 2.25), Math.round(size * 2.25)).png()
      .toFile(path.join(destination, 'ic_launcher_foreground.png')),
  ];
}));

console.log('Generated Android launcher icons from app-icon-source.png');

import fs from 'fs';
import path from 'path';

const [,, outRel, dataUrl] = process.argv;
if (!outRel || !dataUrl?.startsWith('data:')) {
  console.error('Usage: node scripts/save-data-url.mjs <out.png> <data:image/png;base64,...>');
  process.exit(1);
}
const b64 = dataUrl.split(',')[1];
const out = path.resolve(outRel);
fs.writeFileSync(out, Buffer.from(b64, 'base64'));
console.log('wrote', out, fs.statSync(out).size);

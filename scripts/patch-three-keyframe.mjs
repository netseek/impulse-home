import fs from 'node:fs';
import path from 'node:path';

const threePath = path.resolve('node_modules/three/build/three.module.js');
const src = fs.readFileSync(threePath, 'utf8');
const needle =
  "if ( times === undefined || times.length === 0 ) throw new Error( 'THREE.KeyframeTrack: no keyframes in track named ' + name );";
const repl =
  'if ( times === undefined || times.length === 0 ) { times = [0]; if ( !values || values.length === 0 ) values = [0, 0, 0, 1]; }';

const mode = process.argv[2] || 'apply';
if (mode === 'apply') {
  if (!src.includes(needle)) {
    const i = src.indexOf('no keyframes in track');
    console.error('needle not found; context:', JSON.stringify(src.slice(Math.max(0, i - 80), i + 120)));
    process.exit(1);
  }
  if (src.includes(repl)) {
    console.log('already patched');
    process.exit(0);
  }
  fs.writeFileSync(threePath + '.bak', src);
  fs.writeFileSync(threePath, src.replace(needle, repl));
  console.log('patched', threePath);
} else if (mode === 'restore') {
  const bak = threePath + '.bak';
  if (!fs.existsSync(bak)) {
    console.error('no bak');
    process.exit(1);
  }
  fs.writeFileSync(threePath, fs.readFileSync(bak));
  fs.unlinkSync(bak);
  console.log('restored');
} else {
  console.error('usage: apply|restore');
  process.exit(1);
}

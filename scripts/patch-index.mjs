/**
 * Patch index.html to remove all rear-fix GLB references.
 * The fix is now baked directly into haval-h6.glb.
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const filePath = path.resolve(__dirname, '..', 'index.html');

let src = fs.readFileSync(filePath, 'utf-8');
const lines = src.split('\n');

// --- Edit 1: Replace the loadMain wrapper + rear-fix loader (lines 5661–5683) ---
// Find the exact start and end
const edit1Start = lines.findIndex(l => l.includes('// Load the rear-light fix source FIRST'));
const edit1End = lines.findIndex((l, i) => i > edit1Start && l.includes("(err) => { console.warn('rear-fix source failed to load"));
if (edit1Start === -1 || edit1End === -1) { console.error('Edit 1: markers not found'); process.exit(1); }
// Also grab the closing ");" on the next line
const edit1EndActual = edit1End + 1; // the ");" line

// The loadMain body we want to keep (just need to un-wrap it from `const loadMain = () =>`)
const loadMainStart = lines.findIndex(l => l.includes("const loadMain = () => loader.load("));
const loadMainEnd = lines.findIndex((l, i) => i > loadMainStart && l.trimEnd() === '      );');

// Replace: remove the comment + loadMain wrapper, make it a direct call, remove the rear-fix loader
const replacement1 = [
  '      loader.load(',
  '        HEV_URL,',
  "        (gltf) => { this.onModelLoaded(gltf.scene, profiles.hev, { primary: true }); loadGT(); },",
  '        (e) => {',
  '          let p;',
  '          if (e.lengthComputable && e.total) p = (e.loaded / e.total) * 100;',
  '          else p = Math.min(99, (e.loaded / 20787604) * 100);',
  '          this.setState({ progress: p });',
  '        },',
  '        (err) => {',
  "          console.error('GLB load error', err);",
  "          this.setState({ error: 'The model failed to load. Check the file path and that the Draco decoder is reachable.' });",
  '        }',
  '      );',
];

console.log(`Edit 1: Replacing lines ${edit1Start + 1}–${edit1EndActual + 1} with direct loader.load()`);
lines.splice(edit1Start, edit1EndActual - edit1Start + 1, ...replacement1);

// --- Edit 2: Remove entire _swapRearLights method ---
const methodStart = lines.findIndex(l => l.includes('_swapRearLights(obj) {'));
if (methodStart === -1) { console.error('Edit 2: _swapRearLights not found'); process.exit(1); }
// Walk backward to find the comment block start
let commentStart = methodStart;
while (commentStart > 0 && (lines[commentStart - 1].trim().startsWith('//') || lines[commentStart - 1].trim() === '')) {
  commentStart--;
}
// The blank line before the comment block
if (lines[commentStart].trim() === '') commentStart++;

// Walk forward to find the closing brace of the method
let braceDepth = 0;
let methodEnd = methodStart;
for (let i = methodStart; i < lines.length; i++) {
  for (const ch of lines[i]) {
    if (ch === '{') braceDepth++;
    if (ch === '}') braceDepth--;
  }
  if (braceDepth === 0) { methodEnd = i; break; }
}

console.log(`Edit 2: Removing _swapRearLights method (lines ${commentStart + 1}–${methodEnd + 1})`);
lines.splice(commentStart, methodEnd - commentStart + 1);

// --- Edit 3: Remove applyRearFix: true ---
const rearFixTrue = lines.findIndex(l => l.includes('applyRearFix: true,'));
if (rearFixTrue !== -1) {
  console.log(`Edit 3a: Removing 'applyRearFix: true,' at line ${rearFixTrue + 1}`);
  lines.splice(rearFixTrue, 1);
}

// --- Edit 4: Remove applyRearFix: false ---
const rearFixFalse = lines.findIndex(l => l.includes('applyRearFix: false,'));
if (rearFixFalse !== -1) {
  console.log(`Edit 3b: Removing 'applyRearFix: false,' at line ${rearFixFalse + 1}`);
  lines.splice(rearFixFalse, 1);
}

// --- Edit 5: Remove the call site ---
const callSite = lines.findIndex(l => l.includes("if (profile.applyRearFix) this._swapRearLights(obj);"));
if (callSite !== -1) {
  // Also remove the 2 comment lines before it
  let removeStart = callSite;
  if (callSite > 0 && lines[callSite - 1].includes('// the same treatment as the rest of the car')) removeStart--;
  if (removeStart > 0 && lines[removeStart - 1].includes('// Splice the corrected rear-light clusters')) removeStart--;
  const removeCount = callSite - removeStart + 1;
  console.log(`Edit 4: Removing swap call site (lines ${removeStart + 1}–${callSite + 1})`);
  lines.splice(removeStart, removeCount);
}

// Write the patched file
fs.writeFileSync(filePath, lines.join('\n'), 'utf-8');
console.log(`\n✅ Patched index.html (${lines.length} lines)`);

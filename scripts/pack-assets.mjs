#!/usr/bin/env node
// Builds the asset bundle that fetch-assets.mjs downloads.
//
//   node scripts/pack-assets.mjs --version 1.0.0 [--src ../private-tree/assets] [--lock]
//
// The bundle holds exactly what the APK would carry from assets/: everything
// except path segments starting with "_" and the entries in assets-exclude.txt.
// It is a .tar.gz because `tar` ships with Windows 10+ and every Linux runner,
// so this needs no npm dependency.
//
// --lock rewrites assets.lock.json with the new version and sha256.

import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';
import { spawnSync } from 'node:child_process';

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : fallback;
};
const version = opt('version');
if (!version) {
  console.error('usage: pack-assets.mjs --version <x.y.z> [--src <assets dir>] [--lock]');
  process.exit(2);
}
const root = resolve(import.meta.dirname, '..');
const src = resolve(opt('src', join(root, 'assets')));
if (!existsSync(src)) {
  console.error(`assets directory not found: ${src}`);
  process.exit(2);
}

const globToRegex = (g) =>
  new RegExp('^' + g.split('*').map((p) => p.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('[^/]*') + '$');
const excludes = readFileSync(join(root, 'scripts', 'assets-exclude.txt'), 'utf8')
  .split(/\r?\n/)
  .map((l) => l.trim())
  .filter((l) => l && !l.startsWith('#'))
  .map(globToRegex);

const files = [];
(function walk(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith('_') || entry.name === '.bundle') continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full);
      continue;
    }
    const rel = relative(src, full).split(sep).join('/');
    if (excludes.some((re) => re.test(rel))) continue;
    files.push(rel);
  }
})(src);
files.sort();

const dist = join(root, 'dist');
mkdirSync(dist, { recursive: true });
const name = `assets-${version}.tar.gz`;
writeFileSync(join(dist, `assets-${version}.list`), files.join('\n') + '\n');

// Relative paths + cwd: GNU tar on Windows reads "C:" as a remote host name.
const tar = spawnSync('tar', ['-czf', `dist/${name}`, '-C', src, '-T', `dist/assets-${version}.list`], {
  cwd: root,
  stdio: 'inherit',
});
if (tar.status !== 0) process.exit(tar.status ?? 1);

const sha256 = createHash('sha256').update(readFileSync(join(dist, name))).digest('hex');
const bytes = statSync(join(dist, name)).size;
console.log(`${name}: ${files.length} files, ${(bytes / 1048576).toFixed(1)} MB`);
console.log(`sha256 ${sha256}`);

if (args.includes('--lock')) {
  const lockPath = join(root, 'assets.lock.json');
  const lock = JSON.parse(readFileSync(lockPath, 'utf8'));
  Object.assign(lock, { version, file: name, sha256, bytes });
  writeFileSync(lockPath, JSON.stringify(lock, null, 2) + '\n');
  console.log('assets.lock.json updated');
}

#!/usr/bin/env node
// Downloads the asset bundle pinned in assets.lock.json into assets/, after
// checking its sha256. The lock file is what makes a build reproducible: the
// code at a given commit always asks for the same bundle.
//
//   node scripts/fetch-assets.mjs                      # download via `gh` (needs read access)
//   node scripts/fetch-assets.mjs --from file.tar.gz   # use a local bundle instead
//   node scripts/fetch-assets.mjs --force              # re-extract even if already current
//
// In CI, `gh` authenticates through the GH_TOKEN environment variable.

import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const args = process.argv.slice(2);
const opt = (name) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : undefined;
};
const root = resolve(import.meta.dirname, '..');
const lock = JSON.parse(readFileSync(join(root, 'assets.lock.json'), 'utf8'));

if (!lock.version || !lock.sha256) {
  console.error(
    'assets.lock.json has no pinned bundle yet. Run `node scripts/pack-assets.mjs --version <v> --lock` first.',
  );
  process.exit(1);
}

const assetsDir = join(root, 'assets');
const marker = join(assetsDir, '.bundle');
if (!args.includes('--force') && existsSync(marker) && readFileSync(marker, 'utf8').trim() === lock.sha256) {
  console.log(`assets already at ${lock.version}`);
  process.exit(0);
}

const archive = opt('from') ? resolve(opt('from')) : join(root, 'dist', lock.file);
if (!opt('from')) {
  mkdirSync(join(root, 'dist'), { recursive: true });
  const gh = spawnSync(
    'gh',
    ['release', 'download', `assets-v${lock.version}`, '-R', lock.repo, '-p', lock.file, '-D', 'dist', '--clobber'],
    { cwd: root, stdio: 'inherit' },
  );
  if (gh.status !== 0) {
    console.error(
      `could not download ${lock.file} from ${lock.repo}. Is \`gh\` installed and logged in with access to it?`,
    );
    process.exit(1);
  }
}

const digest = createHash('sha256').update(readFileSync(archive)).digest('hex');
if (digest !== lock.sha256) {
  console.error(`sha256 mismatch for ${archive}\n  expected ${lock.sha256}\n  got      ${digest}`);
  process.exit(1);
}

mkdirSync(assetsDir, { recursive: true });
const tar = spawnSync('tar', ['-xzf', relative(root, archive), '-C', 'assets'], { cwd: root, stdio: 'inherit' });
if (tar.status !== 0) process.exit(tar.status ?? 1);
writeFileSync(marker, lock.sha256 + '\n');
console.log(`assets ${lock.version} ready (${lock.sha256.slice(0, 12)}...)`);

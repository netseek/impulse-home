#!/usr/bin/env node
// Writes the manifest that Impulse reads to learn what the newest release is.
// It is published next to the APK, so this URL never changes:
//   https://github.com/<repo>/releases/latest/download/latest.json
//
//   node scripts/make-latest-json.mjs --apk impulse-home.apk --version 1.2.0 \
//        --code 10200 --repo netseek/impulse-home --signer <sha256 hex> \
//        [--channel stable|preview] [--notes-file notes.md] --out latest.json

import { createHash } from 'node:crypto';
import { readFileSync, statSync, writeFileSync } from 'node:fs';

const args = process.argv.slice(2);
const opt = (n, d) => {
  const i = args.indexOf(`--${n}`);
  return i >= 0 ? args[i + 1] : d;
};
const need = (n) => {
  const v = opt(n);
  if (!v) {
    console.error(`missing --${n}`);
    process.exit(2);
  }
  return v;
};

const apk = need('apk');
const version = need('version');
const repo = need('repo');
const signer = need('signer').replace(/[: ]/g, '').toLowerCase();

const manifest = {
  versionName: version,
  versionCode: Number(need('code')),
  apkUrl: `https://github.com/${repo}/releases/download/v${version}/impulse-home.apk`,
  sha256: createHash('sha256').update(readFileSync(apk)).digest('hex'),
  signerSha256: signer,
  bytes: statSync(apk).size,
  channel: opt('channel', 'stable'),
  // Version of the Impulse <-> Impulse Home contract (<meta-data impulse.api>).
  impulseApi: 2,
  notes: opt('notes-file') ? readFileSync(opt('notes-file'), 'utf8').trim() : '',
  publishedAt: new Date().toISOString(),
};
writeFileSync(opt('out', 'latest.json'), JSON.stringify(manifest, null, 2) + '\n');
console.log(`latest.json: ${manifest.versionName} (${manifest.versionCode}), ${manifest.sha256.slice(0, 12)}...`);

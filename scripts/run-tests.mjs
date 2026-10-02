#!/usr/bin/env node
// Runs every scripts/test-*.mjs and decides pass/fail against two lists:
//
//   scripts/tests-known-failing.txt  tests that already fail and are tracked as debt
//   scripts/tests-need-assets.txt    tests that read the asset bundle
//
//   * a test that fails and is NOT on the known list  -> the run fails (a regression)
//   * a test that is on the known list and now passes -> the run fails (remove it from the list)
//   * a test that is on the known list and still fails -> reported, does not fail the run
//   * a test that needs the bundle, when assets/.bundle is absent -> skipped, not failed
//
// The known list exists because several contract tests pin exact strings and fell
// behind the UI. Hiding them would lose the information; making them block every
// commit would train people to ignore CI. This keeps them visible and the run
// honest in both directions.

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = resolve(import.meta.dirname, '..');

const readList = (name) =>
  new Set(
    readFileSync(join(root, 'scripts', name), 'utf8')
      .split(/\r?\n/)
      .map((l) => l.trim())
      .filter((l) => l && !l.startsWith('#')),
  );
const known = readList('tests-known-failing.txt');
const needAssets = readList('tests-need-assets.txt');
const haveAssets = existsSync(join(root, 'assets', '.bundle'));

const tests = readdirSync(join(root, 'scripts'))
  .filter((f) => /^test-.*\.mjs$/.test(f))
  .sort();

const regressions = [];
const stale = [];
const stillFailing = [];
const skipped = [];
let passed = 0;

for (const t of tests) {
  if (needAssets.has(t) && !haveAssets) {
    skipped.push(t);
    continue;
  }
  const r = spawnSync('node', [join('scripts', t)], { cwd: root, encoding: 'utf8', timeout: 180_000 });
  const ok = r.status === 0;
  if (ok && known.has(t)) stale.push(t);
  else if (ok) passed++;
  else if (known.has(t)) stillFailing.push(t);
  else {
    regressions.push(t);
    const tail = (r.stderr || r.stdout || '').trim().split('\n').slice(-3).join('\n');
    console.error(`FAIL ${t}\n${tail}\n`);
  }
}

console.log(
  `${passed}/${tests.length} passed, ${stillFailing.length} known-failing, ` +
    `${skipped.length} skipped (need the asset bundle), ${regressions.length} regressions`,
);
for (const t of stillFailing) console.log(`  known failing: ${t}`);
for (const t of skipped) console.log(`  skipped: ${t}`);
for (const t of stale) console.error(`  ${t} now PASSES - remove it from scripts/tests-known-failing.txt`);

process.exit(regressions.length || stale.length ? 1 : 0);

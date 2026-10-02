#!/usr/bin/env node
// Runs every scripts/test-*.mjs and decides pass/fail against a list of KNOWN
// failures (scripts/tests-known-failing.txt).
//
//   * a test that fails and is NOT on the list  -> the run fails (a regression)
//   * a test that is on the list and now passes -> the run fails (remove it from the list)
//   * a test that is on the list and still fails -> reported, does not fail the run
//
// The list exists because several contract tests pin exact strings and fell
// behind the UI. Hiding them would lose the information; making them block every
// commit would train people to ignore CI. This keeps them visible and the
// run honest in both directions.

import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = resolve(import.meta.dirname, '..');
const known = new Set(
  readFileSync(join(root, 'scripts', 'tests-known-failing.txt'), 'utf8')
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#')),
);

const tests = readdirSync(join(root, 'scripts'))
  .filter((f) => /^test-.*\.mjs$/.test(f))
  .sort();

const regressions = [];
const stale = [];
const stillFailing = [];
let passed = 0;

for (const t of tests) {
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

console.log(`${passed}/${tests.length} passed, ${stillFailing.length} known-failing, ${regressions.length} regressions`);
for (const t of stillFailing) console.log(`  known failing: ${t}`);
for (const t of stale) console.error(`  ${t} now PASSES - remove it from scripts/tests-known-failing.txt`);

process.exit(regressions.length || stale.length ? 1 : 0);

// Guards the documentation and what must never be committed.
//
//   1. every relative Markdown link and image resolves to a tracked file;
//   2. every Markdown file under docs/ is listed in docs/README.md;
//   3. no personal data, local path or private network name is tracked;
//   4. no AI-agent memory file or folder is tracked;
//   5. the repository root holds a single Markdown file, README.md: the rest lives under docs/.
//
// Run it with `node scripts/test-docs.mjs`; scripts/run-tests.mjs runs it with the rest.

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, posix } from 'node:path';

const tracked = execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8', maxBuffer: 1 << 26 })
  .split('\0')
  .filter(Boolean);
const trackedSet = new Set(tracked);
const problems = [];
const fail = (msg) => problems.push(msg);

const isVendor = (f) => f.startsWith('vendor/') || f === 'package-lock.json';
const binary = /\.(png|jpe?g|webp|gif|apk|glb|ktx2|hdr|bin|woff2?|wasm|mp4|ico|jar|keystore|jks)$/i;
const read = (f) => readFileSync(f, 'utf8').replace(/\r\n/g, '\n');

// ---- 1. links ---------------------------------------------------------------------------------
const markdown = tracked.filter((f) => f.endsWith('.md') && !isVendor(f));
const linkTargets = new Map(); // markdown file -> Set of resolved tracked paths it links to
for (const file of markdown) {
  const text = read(file)
    .replace(/```[\s\S]*?```/g, '') // fenced code
    .replace(/`[^`\n]*`/g, ''); // inline code
  const found = new Set();
  for (const m of text.matchAll(/!?\[[^\]]*\]\(\s*<?([^)\s>]+)>?[^)]*\)/g)) {
    let target = m[1];
    if (/^(https?:|mailto:|tel:|#)/i.test(target)) continue;
    target = target.split('#')[0].split('?')[0];
    if (!target) continue;
    const resolved = posix.normalize(posix.join(dirname(file), decodeURI(target)));
    if (resolved.startsWith('..')) continue; // GitHub-relative (../../releases): outside the tree
    found.add(resolved);
    if (!trackedSet.has(resolved) && !tracked.some((t) => t.startsWith(resolved + '/'))) {
      fail(`${file}: broken link -> ${m[1]}`);
    }
  }
  linkTargets.set(file, found);
}

// ---- 2. docs index ----------------------------------------------------------------------------
const index = linkTargets.get('docs/README.md');
assert.ok(index, 'docs/README.md must exist');
for (const f of markdown) {
  if (f.startsWith('docs/') && f !== 'docs/README.md' && !index.has(f)) {
    fail(`${f}: not listed in docs/README.md`);
  }
}

// ---- 3. personal data and local paths -----------------------------------------------------------
// Patterns are assembled from pieces so this file does not match itself.
const BANNED = [
  [new RegExp('[A-Za-z]:[\\\\/]+Us' + 'ers[\\\\/]', 'i'), 'a Windows user-profile path'],
  [new RegExp('/home/' + '[a-z]', 'i'), 'a Linux home path'],
  [new RegExp('NEWS' + 'EEK'), 'a private Wi-Fi name'],
  [new RegExp('Char' + 'itas'), 'a home location'],
  [new RegExp('\\bvan' + 'es\\b', 'i'), 'a local account name'],
  [new RegExp('Drop' + 'box'), 'a personal cloud folder'],
  [new RegExp('\\.cod' + 'ex[\\\\/]work' + 'trees'), 'an AI-agent worktree path'],
  [new RegExp('CODEX_' + 'NODE_MODULES'), 'an AI-agent environment variable'],
  [new RegExp('192\\.168\\.33\\.1' + '55'), 'a private LAN address'],
  [new RegExp('[A-Za-z0-9._%+-]+@(gmail|hotmail|outlook|yahoo)\\.com', 'i'), 'a personal e-mail address'],
];
const SKIP_CONTENT = new Set(['scripts/test-docs.mjs']);
for (const f of tracked) {
  if (isVendor(f) || binary.test(f) || SKIP_CONTENT.has(f)) continue;
  let text;
  try {
    text = read(f);
  } catch {
    continue;
  }
  for (const [re, what] of BANNED) {
    const m = re.exec(text);
    if (m) {
      const line = text.slice(0, m.index).split('\n').length;
      fail(`${f}:${line}: contains ${what}`);
    }
  }
}

// ---- 4. AI-agent memory -------------------------------------------------------------------------
const AGENT_PATHS = [
  /^\.(claude|cursor|codex|aider|windsurf|continue|gemini)\//,
  /^memory\//,
  /(^|\/)CLAUDE\.local\.md$/,
  /(^|\/)AGENTS\.local\.md$/,
  /(^|\/)\.cursorrules$/,
  /\.mdc$/,
  /(^|\/)(GEMINI|CODEX|COPILOT)[^/]*\.md$/,
];
for (const f of tracked) {
  if (AGENT_PATHS.some((re) => re.test(f))) fail(`${f}: AI-agent memory or config must not be committed`);
}
assert.ok(trackedSet.has('docs/AGENTS.md'), 'docs/AGENTS.md (the master guide) must exist');

// ---- 5. one Markdown entry point in the root -------------------------------------------------------
for (const f of tracked) {
  if (/^[^/]+\.md$/.test(f) && f !== 'README.md') {
    fail(`${f}: Markdown belongs under docs/ (README.md is the only one in the root)`);
  }
}

if (problems.length) {
  console.error(problems.join('\n'));
  console.error(`\n${problems.length} documentation problem(s)`);
  process.exit(1);
}
console.log(`docs ok: ${markdown.length} Markdown files, ${tracked.length} tracked files checked`);

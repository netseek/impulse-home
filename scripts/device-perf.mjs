// Run the viewer's standing perf battery against the car or the emulator and
// print it as a table.
//
//   node scripts/device-perf.mjs                 # 10 s window
//   node scripts/device-perf.mjs --sec 20
//   node scripts/device-perf.mjs --serial 192.168.33.2:5555
//   node scripts/device-perf.mjs --json          # raw, for diffing A/B runs
//   node scripts/device-perf.mjs --watch         # repeat until Ctrl-C
//
// This exists because the 2026-09-04 "why is it 4-5 fps" investigation had to
// hand-roll five throwaway CDP probes to reach a one-line answer. Everything
// they measured is now window.__diag(); this is the runner.
//
// READ THE VERDICT LINE FIRST. It encodes the decision tree that actually
// separates the failure modes — see the __diag() comment in index.html.
//
// Zero dependencies; Node 22's global WebSocket does the CDP transport.

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';

const PACKAGE = 'com.havalh6.viewer';

function findAdb() {
  const local = process.env.LOCALAPPDATA
    && path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe');
  if (local && fs.existsSync(local)) return local;
  const home = process.env.HOME || process.env.USERPROFILE;
  for (const p of [
    home && path.join(home, 'Android', 'Sdk', 'platform-tools', 'adb'),
    '/usr/local/bin/adb', '/usr/bin/adb',
  ]) if (p && fs.existsSync(p)) return p;
  return 'adb';
}
const ADB = findAdb();

const argv = process.argv.slice(2);
const flag = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 ? argv[i + 1] : dflt;
};
const has = (name) => argv.includes(name);
let SERIAL = flag('--serial', null);
const SECONDS = Number(flag('--sec', 10));
const AS_JSON = has('--json');
const WATCH = has('--watch');
// --assert turns this from a report into a GATE.
//
// Prose has already failed at stopping this bug class. docs/engineering said "if you
// add a signal handler that calls setState, throttle it the same way", and an
// agent then wrote the POWER card's "update DOM and React no more than four
// times a second" -- faithfully implementing the thing the file was warning
// about. Three separate handlers have now shipped the same defect
// (motionSpeed, widgetRev, media metadata). A check that exits non-zero is the
// only form of this lesson an agent cannot read past.
const ASSERT = has('--assert');
// Calibrated against real measured payloads, not guessed. A 5% ceiling was
// tried first and failed the CLEAN build: a 12 s window containing someone
// opening the config panel read 5.8% (configExpanded:4, panelOpen:4,
// tuningSubTab:2 -- user taps, ~0.33/s per key). The gate has to tell a human
// driving the UI apart from a signal driving the tree, so:
//
//   per-key rate  is the PRECISE check -- a repeating signal commits the same
//                 key over and over; a person does not. Measured: POWER 1.43/s,
//                 media 1.88/s, config-panel taps 0.33/s.
//   React %       is the blunt backstop for a commit storm that somehow spreads
//                 across keys. Measured: POWER 17%, media 15.9%, motionSpeed
//                 65.9%, config-panel 5.8%.
const MAX_REACT_PCT = Number(flag('--max-react-pct', 12));
const MAX_KEY_RATE = Number(flag('--max-key-rate', 1));

const adb = (args) => execFileSync(ADB, SERIAL ? ['-s', SERIAL, ...args] : args,
  { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();

function pickSerial() {
  if (SERIAL) return SERIAL;
  const rows = execFileSync(ADB, ['devices'], { encoding: 'utf8' })
    .split('\n').slice(1).map((l) => l.trim().split(/\s+/))
    .filter((p) => p.length >= 2 && p[1] === 'device').map((p) => p[0]);
  if (!rows.length) throw new Error('no adb device — connect the car (npm run car:connect) or start the Haval AVD');
  if (rows.length > 1) throw new Error(`several devices (${rows.join(', ')}) — pass --serial`);
  return rows[0];
}

function viewerPid() {
  const line = adb(['shell', 'ps', '-A']).split('\n').find((l) => l.trim().endsWith(` ${PACKAGE}`));
  return line ? Number(line.trim().split(/\s+/)[1]) : null;
}
const freePort = () => new Promise((res, rej) => {
  const s = net.createServer();
  s.once('error', rej);
  s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); });
});
const getJson = (url) => new Promise((res, rej) => {
  http.get(url, (r) => {
    let b = '';
    r.on('data', (c) => { b += c; });
    r.on('end', () => { try { res(JSON.parse(b)); } catch (e) { rej(e); } });
  }).on('error', rej);
});

SERIAL = pickSerial();
const pid = viewerPid();
if (!pid) throw new Error(`${PACKAGE} is not running on ${SERIAL}`);
const port = await freePort();
adb(['forward', `tcp:${port}`, `localabstract:webview_devtools_remote_${pid}`]);

let page = null;
for (let i = 0; i < 20 && !page; i++) {
  try { page = (await getJson(`http://127.0.0.1:${port}/json/list`)).find((t) => t.type === 'page'); } catch { /* not up yet */ }
  if (!page) await new Promise((r) => setTimeout(r, 400));
}
if (!page) throw new Error('no CDP page target on the WebView');

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('CDP socket refused')); });
let id = 0;
const pending = new Map();
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) {
    const { res, rej } = pending.get(m.id);
    pending.delete(m.id);
    m.error ? rej(new Error(m.error.message)) : res(m.result);
  }
};
const send = (method, params = {}) => new Promise((res, rej) => {
  const n = ++id; pending.set(n, { res, rej });
  ws.send(JSON.stringify({ id: n, method, params }));
});
const evaluate = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || 'eval threw');
  return r.result.value;
};

/**
 * The decision tree from the __diag() comment, applied. This is the part worth
 * automating: every wrong turn on 2026-09-04 came from reading one number in
 * isolation rather than the PAIR of them.
 */
function verdict(d) {
  const out = [];
  if (!d.rafAlive || d.visibility !== 'visible') {
    out.push('WEBVIEW HIDDEN — Chromium has stopped rAF and clamped timers to 1 Hz. '
      + 'The render loop is rAF-only, so it is not the scene: another app has focus.');
  }
  // React time dominates the verdict: a run measured 65.9% of wall clock in
  // commits while timerLagP50 sat at 49.9 ms, just under a flat >50 threshold,
  // and this printed "GPU/COMPOSITOR BOUND" for a textbook blocked main thread.
  // If we are spending a fifth of the frame committing, that IS the answer.
  const heavyReact = d.pctWallInCommits > 20;
  const blocked = heavyReact || (d.timerLagP50 != null && d.timerLagP50 > 50);
  const rafSlow = d.bareRafP50 != null && d.bareRafP50 > 40;
  if (blocked && rafSlow) {
    out.push(`MAIN THREAD BLOCKED — an empty rAF (${d.bareRafP50} ms) and a 0 ms timer `
      + `(${d.timerLagP50} ms) stall together, so this is long tasks, not the GPU.`);
    if (d.pctWallInCommits > 15) {
      out.push(`  -> React is ${d.pctWallInCommits}% of wall clock: ${d.commitsPerSec}/s commits `
        + `at ${d.commitMsP50} ms each. Driven by: ${Object.keys(d.setStateKeys).join(', ') || '(none seen)'}`);
    }
  } else if (rafSlow && !blocked) {
    out.push(`GPU / COMPOSITOR BOUND — rAF is slow (${d.bareRafP50} ms) but timers are not `
      + `(${d.timerLagP50} ms). Look at resolution, post-FX and what else is on the panel.`);
  }
  if (d.liveCommitsPerSec > 1) {
    out.push(`LIVE SEAM LEAKING — ${d.liveCommitsPerSec} commits/s from live values. `
      + 'A hot signal is passing { commit: true }; it should not.');
  }
  if (!out.length) {
    out.push(`healthy — ${d.realFps} fps, submit ${d.submitMsP50} ms, React ${d.pctWallInCommits}% of wall.`);
  }
  return out;
}

/**
 * Returns a list of failures. Empty means the gate passes.
 *
 * Deliberately NOT keyed on frame rate: fps legitimately reads ~1 on a parked,
 * idle car because the render loop is on-demand, and 0.37 fps with a 59/s bare
 * rAF is the system working correctly. What is always wrong, at any frame rate,
 * is re-rendering the React tree from a repeating signal.
 */
function assertions(d) {
  const fails = [];

  // A hidden WebView makes every timing number meaningless, so refuse to pass
  // OR fail on one -- reporting "healthy" from a 1 Hz clamp would be worse
  // than reporting nothing.
  if (!d.rafAlive || d.visibility !== 'visible') {
    fails.push('INCONCLUSIVE: the WebView is hidden, so no timing here is valid. '
      + 'Bring the viewer to the foreground and re-run.');
    return fails;
  }

  if (d.pctWallInCommits > MAX_REACT_PCT) {
    fails.push(`React is ${d.pctWallInCommits}% of wall clock (limit ${MAX_REACT_PCT}%), `
      + `${d.commitsPerSec}/s at ${d.commitMsP50} ms p50.`);
  }

  // The attribution line is the part that names the culprit, so assert on it
  // directly: any single state key committing repeatedly is the signature.
  const secs = d.windowSec || 1;
  for (const [key, n] of Object.entries(d.setStateKeys || {})) {
    const rate = n / secs;
    if (rate > MAX_KEY_RATE) {
      fails.push(`setState key "${key}" fired ${n}x in ${secs}s (${rate.toFixed(1)}/s, `
        + `limit ${MAX_KEY_RATE}/s) -- a repeating signal is re-rendering the tree.`);
    }
  }

  if (d.liveCommitsPerSec > 1) {
    fails.push(`live seam committed ${d.liveCommitsPerSec}/s; a hot signal is passing `
      + '{ commit: true } when it should only paint.');
  }
  return fails;
}

const rows = (d) => [
  ['frame', `${d.realFps} fps   submit ${d.submitMsP50} ms   dpr ${d.dpr}   tier ${d.resTier}`],
  ['bare rAF', `p50 ${d.bareRafP50} ms   p90 ${d.bareRafP90} ms   ${d.bareRafPerSec}/s   alive=${d.rafAlive}`],
  ['timer lag', `p50 ${d.timerLagP50} ms   p90 ${d.timerLagP90} ms`],
  ['React', `${d.commitsPerSec}/s   p50 ${d.commitMsP50} ms   p90 ${d.commitMsP90} ms   ${d.pctWallInCommits}% of wall`],
  ['setState', Object.entries(d.setStateKeys).map(([k, v]) => `${k}:${v}`).join('  ') || '(none)'],
  ['live seam', `${d.liveWritesPerSec} writes/s   ${d.liveCommitsPerSec} commits/s`],
  ['post-FX', `${d.postFxRebuildsPerSec} rebuilds/s   ${d.postFxCacheHitsPerSec} cache hits/s`],
  ['scene', `${d.drawCalls} calls   ${d.triangles} tris   centerFill=${d.centerFill}   night=${d.nightMode}`],
  ['bus', `${d.busPerSec}/s   speed ${d.speedPerSec}/s   visibility=${d.visibility}`],
];

async function once() {
  const d = await evaluate(`window.__diag ? __diag(${SECONDS * 1000}) : Promise.resolve({ error: 'no __diag — the build on this device predates it' })`);
  if (d && d.error) throw new Error(d.error);
  if (AS_JSON) { console.log(JSON.stringify(d, null, 2)); return; }
  console.log(`\n── ${PACKAGE} on ${SERIAL} — ${d.windowSec}s window ─────────────`);
  for (const [k, v] of rows(d)) console.log(`  ${k.padEnd(10)} ${v}`);
  console.log('');
  for (const line of verdict(d)) console.log(`  ${line}`);
  console.log('');

  if (ASSERT) {
    const fails = assertions(d);
    if (fails.length) {
      console.log('  PERF GATE FAILED');
      for (const f of fails) console.log(`    - ${f}`);
      console.log('');
      console.log('  See docs/engineering, "A hot CAN signal must never reach setState at all".');
      console.log('  Throttling is not the fix -- three handlers have tried it. Paint the DOM');
      console.log('  directly and commit only at settle points.');
      console.log('');
      process.exitCode = 1;
    } else {
      console.log(`  PERF GATE PASSED — React ${d.pctWallInCommits}% of wall `
        + `(limit ${MAX_REACT_PCT}%), no setState key above ${MAX_KEY_RATE}/s.`);
      console.log('');
    }
  }
}

if (WATCH) { for (;;) await once(); } else { await once(); ws.close(); }

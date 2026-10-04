import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

// Run small DOM/compiler checks in a real browser without a Node browser driver.
export function runBrowserTest(check, ...args) {
  const candidates = process.env.CHROME_BIN ? [process.env.CHROME_BIN]
    : ['google-chrome', 'chromium', 'chromium-browser', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'];
  const browser = candidates.find((candidate) => {
    try { execFileSync(candidate, ['--version'], { stdio: 'ignore', timeout: 5000 }); return true; }
    catch { return false; }
  });
  assert.ok(browser, 'Chrome/Chromium is required for DOM regression tests; set CHROME_BIN to its executable');
  const dir = mkdtempSync(join(tmpdir(), 'impulse-browser-test-'));
  try {
    const payload = JSON.stringify(args).replaceAll('<', '\\u003c');
    const script = '(' + check.toString() + ')(...' + payload + ')'
      + '.then(value => ({ok:true,...value}), error => ({ok:false,error:error.stack}))'
      + '.then(result => { document.documentElement.innerHTML = "<head></head><body></body>";'
      + ' const pre=document.createElement("pre"); pre.id="result";'
      + ' pre.textContent=btoa(unescape(encodeURIComponent(JSON.stringify(result)))); document.body.appendChild(pre); });';
    const page = join(dir, 'test.html');
    writeFileSync(page, '<!doctype html><html><head><meta charset="utf-8"></head><body><script>' + script + '</script></body></html>');
    const output = execFileSync(browser, ['--headless', '--no-sandbox', '--disable-gpu',
      '--no-first-run', '--disable-background-networking', '--no-default-browser-check',
      '--user-data-dir=' + join(dir, 'profile'), '--dump-dom', pathToFileURL(page).href],
    { encoding: 'utf8', timeout: 45000, maxBuffer: 12 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
    const encoded = output.match(/<pre id="result">([A-Za-z0-9+/=]+)<\/pre>/)?.[1];
    assert.ok(encoded, 'browser returned a completed DOM test result');
    const result = JSON.parse(Buffer.from(encoded, 'base64').toString('utf8'));
    assert.equal(result.ok, true, result.error);
    return result;
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const java = readFileSync(new URL('../app/src/main/java/com/havalh6/viewer/MainActivity.java', import.meta.url), 'utf8');
function check(page, native) {
  assert.match(page, /onClick="{{ openHomeSettings }}" disabled="{{ !homeSettingsAvailable }}"/);
  assert.match(page, /homeSettingsAvailable: !!\(window.AppLauncherBridge && typeof window.AppLauncherBridge.openHomeSettings === 'function'\)/);
  assert.match(page, /openHomeSettings: \(\) => window.AppLauncherBridge.openHomeSettings\(\)/);
  assert.match(native, /@JavascriptInterface\s+public void openHomeSettings\(\)\s*{\s*runOnUiThread/);
  assert.match(native, /startActivity\(new Intent\(android.provider.Settings.ACTION_HOME_SETTINGS\)\)/);
  assert.match(native, /catch \(android.content.ActivityNotFoundException \| SecurityException e\)/);
}
check(html, java);
for (const token of ['onClick="{{ openHomeSettings }}"', 'homeSettingsAvailable:', 'openHomeSettings: ()']) {
  assert.throws(() => check(html.replaceAll(token, 'BROKEN'), java));
}
for (const token of ['public void openHomeSettings()', 'Settings.ACTION_HOME_SETTINGS', 'ActivityNotFoundException | SecurityException']) {
  assert.throws(() => check(html, java.replaceAll(token, 'BROKEN')));
}
console.log('PASS home settings UI/bridge contract + negative controls (firmware behavior requires a device)');

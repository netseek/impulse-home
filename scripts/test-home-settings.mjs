import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// Home selection lives in Impulse. The viewer must not grow a settings button
// that opens Android's home chooser.
const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const java = readFileSync(new URL('../app/src/main/java/com/havalh6/viewer/MainActivity.java', import.meta.url), 'utf8');

function check(page, native) {
  assert.doesNotMatch(page, /openHomeSettings/);
  assert.doesNotMatch(page, /homeSettingsAvailable/);
  assert.doesNotMatch(page, /Definir como tela inicial/i);
  assert.doesNotMatch(page, /DEFINIR COMO TELA INICIAL/);
  assert.doesNotMatch(native, /ACTION_HOME_SETTINGS/);
  assert.doesNotMatch(native, /openHomeSettings/);
}

check(html, java);
assert.throws(() => check(html + '\nopenHomeSettings', java));
assert.throws(() => check(html, java + '\nACTION_HOME_SETTINGS'));
console.log('PASS home settings stay out of the viewer');

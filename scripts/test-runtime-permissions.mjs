import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// Location, storage and notification access stay on screen until granted.
const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const java = readFileSync(new URL('../app/src/main/java/com/havalh6/viewer/MainActivity.java', import.meta.url), 'utf8');

const box = html.match(/class="hv-perm-offer-box"[\s\S]*?<\/div>\s*<\/div>/);
assert.ok(box, 'the permission prompt has a box');
assert.equal((box[0].match(/<button/g) || []).length, 1, 'the prompt has a single action');
assert.match(box[0], />Permitir<\/button>/);
assert.match(box[0], /acesso à mídia/);
assert.match(box[0], /A localização mostra a cidade/);
assert.match(box[0], /O armazenamento guarda/);
assert.match(box[0], /continua até cada permissão ser concedida/);
assert.doesNotMatch(box[0], /Agora não|Pular|Não perguntar/);
assert.match(html, /runtimePermShowMedia:/, 'media is one of the rows');
assert.match(html, /_runtimePermBlocked\(\)/, 'the prompt waits out splash and first-run');

assert.match(java, /missing\.append\("media"\)/, 'notification access is part of the missing list');
assert.match(java, /ACTION_NOTIFICATION_LISTENER_SETTINGS/, 'Permitir opens notification access after the runtime grants');
assert.match(java, /ACTION_APPLICATION_DETAILS_SETTINGS/, 'a permanent denial opens the app permission screen');
assert.match(java, /notifyRuntimePermOffer\(\)/, 'coming back rechecks the prompt');
assert.doesNotMatch(java, /placeLocationAsked/, 'a denial is not latched for the process');
assert.doesNotMatch(java, /A denial is not retried/);

assert.throws(() => {
  assert.match(java.replace('missing.append("media")', 'missing.append("nope")'), /missing\.append\("media"\)/);
});

console.log('PASS runtime permission prompt includes media and does not skip');

#!/usr/bin/env node
/*
 * Idle media and navigation cards launch a saved default app.
 * With none saved, the tap opens the installed-app list; the same list is
 * the ⋯ row while the rail is in card edit.
 *
 * Run: node scripts/test-default-app.mjs
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const html = readFileSync(resolve(root, 'index.html'), 'utf8').replace(/\r\n/g, '\n');
const java = readFileSync(
  resolve(root, 'app/src/main/java/com/havalh6/viewer/MainActivity.java'), 'utf8',
).replace(/\r\n/g, '\n');

function blockFrom(source, token, label = token) {
  const start = source.indexOf(token);
  assert.ok(start >= 0, `missing ${label}`);
  const brace = source.indexOf('{', start);
  let depth = 0;
  for (let i = brace; i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  throw new Error(`unterminated ${label}`);
}

const openMedia = blockFrom(html, '  _openMediaApp(ev) {', 'media opener');
assert.match(openMedia, /s\.mediaCanLaunch && s\.mediaPackageName/,
  'a launchable session still opens the playing app');
assert.match(openMedia, /this\._launchPackage\(s\.mediaPackageName\)/,
  'the playing package is what gets launched');
assert.match(openMedia, /this\._openFocusedCard\('media'\)/,
  'a visible track that cannot be launched still opens the MEDIA popup');
assert.match(openMedia, /this\._launchDefaultOrPick\('media'\)/,
  'an idle media card launches the saved default or asks for one');

const openNav = blockFrom(html, '  _openNavigationApp() {', 'navigation opener');
assert.match(openNav, /this\._aaSession === 'active'/,
  'a live Android Auto session still raises projection');
assert.match(openNav, /B\.launchProjection\('AA'\)/,
  'guidance launches the projection, not a saved app');
assert.match(openNav, /this\._launchDefaultOrPick\('navigation'\)/,
  'an idle navigation card launches the saved default or asks for one');
assert.ok(!openNav.includes('CAR_NAV_PACKAGE'),
  'the idle navigation card must not launch the fake navigation package');

const picker = blockFrom(html, '  _launchDefaultOrPick(kind) {', 'default launch');
assert.match(picker, /this\._launchPackage\(saved\.packageName\)/,
  'a saved default is launched');
assert.match(picker, /this\._openDefaultAppPicker\(kind, true\)/,
  'with nothing saved, the tap opens the list and will launch the pick');

const fromMenu = blockFrom(html, '  dockCommand(cmd) {', 'dock command');
assert.match(fromMenu, /case 'pickDefaultApp:media':\s*this\._openDefaultAppPicker\('media', false\)/,
  'the media ⋯ row opens the list without launching');
assert.match(fromMenu, /case 'pickDefaultApp:navigation':\s*this\._openDefaultAppPicker\('navigation', false\)/,
  'the navigation ⋯ row opens the list without launching');

const menu = blockFrom(html, '  _railEditMenuRows(cardId) {', 'rail edit menu');
assert.match(menu, /cardId === 'media' \|\| cardId === 'navigation'/,
  'only media and navigation offer a default app');
assert.match(menu, /command: 'pickDefaultApp:' \+ cardId/,
  'the ⋯ row names the card whose default it edits');

assert.match(html, /localStorage\.getItem\('h6_default_apps'\)/,
  'the choice is read back from the launcher preferences');
assert.match(html, /localStorage\.setItem\('h6_default_apps'/,
  'the choice is written where PersistBackup already mirrors localStorage');
assert.match(html, /APP PADRÃO · MÍDIA/, 'the media list names itself');
assert.match(html, /APP PADRÃO · NAVEGAÇÃO/, 'the navigation list names itself');
const choices = blockFrom(html, '  _defaultAppChoices(kind, catalog) {', 'default app choices');
assert.match(choices, /kind !== 'navigation'\) return list/,
  'the media list is the installed-app catalog unchanged');
const pinnedAt = choices.indexOf('const pinned = [');
const carPlayAt = choices.indexOf("label: 'CarPlay'");
const aaAt = choices.indexOf("label: 'Android Auto'");
assert.ok(pinnedAt >= 0 && carPlayAt > pinnedAt && aaAt > carPlayAt,
  'navigation leads with CarPlay, then Android Auto');
assert.match(choices, /packageName: 'com\.ts\.carplay\.app'/,
  'CarPlay is the projection package, not a guessed launcher entry');
assert.match(choices, /packageName: CAR_AA_PACKAGE/,
  'Android Auto uses the same package the live session already launches');
assert.match(html, /class="hv-default-app-pop"/, 'the list is the default-app popup');

assert.match(java, /private boolean isDefaultAppCommand\(String command\)/,
  'native must recognise the default-app command');
assert.match(java, /"pickDefaultApp:media"\.equals\(command\)/,
  'native relays the media row');
assert.match(java, /"pickDefaultApp:navigation"\.equals\(command\)/,
  'native relays the navigation row');
assert.match(java, /!isDefaultAppCommand\(command\)\) continue/,
  'a row native does not recognise is still dropped');

console.log('default-app contracts: ok');

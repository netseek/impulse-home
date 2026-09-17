#!/usr/bin/env node
// Source-level guard for MainActivity's evaluateJavascript shell-layout payload.
// It intentionally needs neither an Android device nor a Gradle build.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
// Line endings are normalised on read. index.html and MainActivity.java are
// stored LF and checked out CRLF on Windows, so a source contract that
// hardcodes either one passes or fails depending on which command last
// rewrote the file. Two of these tests had already broken that way.
const source = fs.readFileSync(
  path.join(root, 'app/src/main/java/com/havalh6/viewer/MainActivity.java'), 'utf8').replace(/\r\n/g, '\n');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8').replace(/\r\n/g, '\n');

if (!source.includes('popupVerticalBoundsToCssFragment()')) {
  throw new Error('Shell-layout popup geometry must be emitted as a property fragment.');
}
if (source.includes('popupVerticalBoundsToCssJson().substring(1)')) {
  throw new Error('A braced popup object cannot be concatenated into the shell-layout object.');
}
if (!source.includes('return "popupTop:" + cssPx(popupTop) + ",popupBottom:" + cssPx(popupBottom);')) {
  throw new Error('Popup geometry helper must return unbraced popupTop/popupBottom properties.');
}
if (!source.includes('applyImpulseReserveBandGeometry()')
    || !source.includes('new int[] { 0x52000000, 0xA6000000 }')) {
  throw new Error('Impulse reserve must be represented by the translucent native bottom band.');
}
if (!source.includes('alignQuickCardsToWidgetBoard()')
    || !source.includes('Math.round(5 * density)')
    || !/DOCK_SURFACE_CARDS\.equals\(dockSurfaceMode\)\s*\?\s*0\s*:\s*Math\.round\(12 \* density\)/.test(source)) {
  throw new Error('Quick cards must align to the widget board and retain the requested 5dp offset.');
}
if (!html.includes('const leftRect = this._rectCss(this._shellLeftBounds, { l: 48, t: 40, r: 740, b: 500 });')
    || !html.includes('const stageControlLeft = boardLeft + (occupiedCols > 0 ? occupiedWidth + 10 : 0);')
    || !/stageAestheticStyle: 'left:' \+ Math\.round\(stageControlLeft\) \+ 'px;top:'\s*\+ Math\.round\(stageControlBottom - 42\)/.test(html)
    || !/stageCameraStyle: 'left:' \+ Math\.round\(stageControlLeft \+ \d+\) \+ 'px;top:'\s*\+ Math\.round\(stageControlBottom - 42\)/.test(html)) {
  // Widget board shares the freeform band; config/camera sit at the occupied edge.
  throw new Error('Widget board and dynamic config/camera row contract is missing.');
}
if (!source.includes('class QuickCardGraphicView extends View')
    || !source.includes('case "navigation": drawNavigation')
    || !source.includes('case "tires": drawTires')
    || !source.includes('case "driveMode": drawDriveMode')
    || !source.includes('case "powerMode": drawPowerMode')
    || !source.includes('case "regen": drawRegen')
    || !source.includes('case "roof": drawRoof')
    || !source.includes('quickCardGraphics.put(descriptor.id, graphic)')) {
  throw new Error('CoffeeOS-style native card graphics contract is missing.');
}
if (!source.includes('android.graphics.Path body = new android.graphics.Path()')
    || !source.includes('android.graphics.Path shell = new android.graphics.Path()')
    || !source.includes('float[] xs = {w * .29f')) {
  throw new Error('Vehicle top-view graphics for tyres and panoramic roof are missing.');
}
if (!source.includes('payload.optString("artDataUrl", "")')
    || !source.includes('BitmapFactory.decodeByteArray')) {
  throw new Error('Native media card must render artwork from the now-playing payload.');
}
if (!source.includes('isProbablyEmulator()')
    || !source.includes('isProbablyEmulator() ? "&demo=1" : "&demo=0"')) {
  throw new Error('Demo telemetry must be explicitly limited to emulator builds.');
}
if (!source.includes('int max = Math.min(rawCards.length(), 16);')) {
  throw new Error('Native payload limit must retain the complete functional card catalog.');
}

const fragment = 'popupTop:32,popupBottom:410';
const payload = `({left:false,right:"idle",launcherBottom:108,${fragment},uiMode:"dark"})`;
const parsed = Function(`return ${payload}`)();
if (parsed.popupTop !== 32 || parsed.popupBottom !== 410 || parsed.uiMode !== 'dark') {
  throw new Error('Shell-layout payload regression: popup boundaries did not parse correctly.');
}

console.log('native shell-layout payload syntax: OK');

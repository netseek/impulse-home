/*
 * Source-level contract for the ENERGY workspace (card / widget type
 * `consumption`): widget, popup, native rail card and trip map snapshot.
 *
 * Every check is a function of the source text, and each one is also run
 * against a deliberately broken copy: a check that still passes on the broken
 * copy is decoration, not a gate (docs/engineering, "negative-control every assertion
 * you add").
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const read = (p) => readFileSync(resolve(root, p), 'utf8').replace(/\r\n/g, '\n');
const SOURCE = {
  html: read('index.html'),
  java: read('app/src/main/java/com/havalh6/viewer/MainActivity.java'),
  renderer: read('app/src/main/java/com/havalh6/viewer/TripMapRenderer.java'),
  store: read('app/src/main/java/com/havalh6/viewer/TripStore.java'),
  recorder: read('app/src/main/java/com/havalh6/viewer/TripRecorder.java'),
  provider: read('app/src/main/java/com/havalh6/viewer/TripExportProvider.java'),
  manifest: read('app/src/main/AndroidManifest.xml'),
  gwm: read('app/src/main/java/com/havalh6/viewer/GwmRebootService.java'),
  link: read('app/src/main/java/com/havalh6/viewer/TripExportLink.java'),
};

function method(text, name) {
  const start = text.indexOf(`  ${name}(`);
  if (start < 0) return null;
  const brace = text.indexOf('{', start);
  let depth = 0;
  for (let i = brace; i < text.length; i++) {
    if (text[i] === '{') depth++;
    if (text[i] === '}' && --depth === 0) return text.slice(start, i + 1);
  }
  return null;
}

// Every <style> block: the card rules do not live in the first one, and
// scanning only that made a descendant-span check pass vacuously once.
// A Java method from its signature to its closing brace.
function javaMethod(text, signature) {
  const start = text.indexOf(signature);
  if (start < 0) return null;
  const brace = text.indexOf('{', start + signature.length);
  let depth = 0;
  for (let i = brace; i < text.length; i++) {
    if (text[i] === '{') depth++;
    if (text[i] === '}' && --depth === 0) return text.slice(start, i + 1);
  }
  return null;
}
// eslint-disable-next-line no-new-func
const pure = (html, name) => new Function(`return function ${(method(html, name) || '').trim()}`)();

const css = (html) => (html.match(/<style[^>]*>[\s\S]*?<\/style>/g) || []).join('\n');
const noCommit = (body) => !!body && !/setState\s*\(/i.test(body);

const CHECKS = {
  // A per-signal / per-second path must never reach React (197 ms per commit on the car).
  'graph writes do not commit': ({ html }) => noCommit(method(html, '_setGraphValue')),
  'live paint path does not commit': ({ html }) =>
    ['_energyTick', '_paintEnergyCards', '_energyModel', '_energyReadLive', '_paintEnergyChart', '_energySample'].every((n) => noCommit(method(html, n))),
  'no consumptionRev state': ({ html }) => !/consumptionRev\s*:|\.consumptionRev\b/.test(html),
  // Replacing a parent's text would detach React's interpolation span.
  'paint writes inside the interpolation span': ({ html }) =>
    (method(html, '_paintEnergyCards') || '').includes(".querySelector('.sc-interp')"),
  // Owner decision 2026-09-13: km/L by default, L/100 km selectable.
  'fuel defaults to km/L': ({ html }) =>
    /fuel:\s*this\._energyFuelUnit === 'l100' \? 'l100' : 'kml'/.test(method(html, '_energyUnits') || ''),
  // The electric instant figure is derived, never a vehicle reading.
  'derived electric figure is labelled EST': ({ html }) =>
    /nowEvUnit:[^\n]*' · EST'/.test(method(html, '_energyModel') || ''),
  // {{ value }} renders as <span class="sc-interp">, so a descendant span rule
  // also restyles the value inside a sibling element.
  'no descendant span rules on the card': ({ html }) => css(html).split('}').every((rule) => {
    const selector = rule.slice(0, rule.indexOf('{'));
    return !/\.hv-energy[\w-]*\s+span/.test(selector);
  }),
  // A card id with no `case` falls through to a generic ring (docs/architecture/ui-surfaces.md).
  'rail card has its own native painter': ({ java }) =>
    /case "consumption": drawEnergy\(/.test(java) && /private void drawEnergy\(/.test(java),
  'native parses the seven-day bars': ({ java }) =>
    /descriptor\.energyBars = parseEnergyBars\(raw\.optString\("energyBars"/.test(java),
  // A data URL always contains ";", and the template splits an interpolated
  // style on it (docs/architecture/ui-surfaces.md): the map must be an <image href> (in the
  // route svg, so tiles and route letterbox together), never a style.
  'trip map is an image href, not a style': ({ html }) =>
    html.includes('<image class="hv-energy-map" href="{{ focusedConsumptionDetailMap }}"')
    && !/style="[^"]*focusedConsumptionDetailMap/.test(html),
  // The OSM tile licence requires attribution; it is drawn into the saved image.
  'map snapshot carries OSM attribution': ({ renderer }) =>
    renderer.includes('"© OpenStreetMap contributors"') && /canvas\.drawText\(ATTRIBUTION/.test(renderer),
  // The OSM tile usage policy requires an identifying User-Agent.
  'tile requests identify the app': ({ renderer }) =>
    /setRequestProperty\("User-Agent", USER_AGENT\)/.test(renderer),
  // The interactive map is driven imperatively: a drag must never commit.
  'live map does not commit': ({ html }) => ['_energyMapCreate', '_energyMapSync'].every((n) => noCommit(method(html, n))),
  // OSM licence: attribution on the interactive map too, not only on the snapshot.
  'live map carries OSM attribution': ({ html }) =>
    /credit\.textContent = '© OpenStreetMap contributors';/.test(method(html, '_energyMapCreate') || ''),
  // Owner, 2026-09-14: a board widget 4x2 or wider shows map / graph and data
  // side by side with no toggle; the 3x2 popup keeps its toggles.
  'wide widget shows both panes': ({ html }) => {
    const trips = method(html, '_energyTripsView') || '';
    const pop = method(html, '_energyPopupView') || '';
    return (method(html, '_consumptionWidgetView') || '').includes('const wide = popup ? this._energyPopupWideOn() : (w >= 4 && h >= 2);')
      && trips.includes("consumptionTripShowMap: !!t && (wide || tripView === 'map')")
      && trips.includes("consumptionTripShowData: !!t && (wide || tripView === 'data')")
      && pop.includes("consumptionHistShowData: wide || histView === 'data'")
      && html.includes("this._consumptionWidgetView(entry.item, 'popup')");
  },
  // Owner, 2026-09-19: no + / - anywhere (pinch and wheel zoom); both maps offer
  // fit-the-route once the view has moved; the pane opens the full map on a tap.
  'no zoom buttons, fit on the full map, tap opens it': ({ html }) => {
    const body = method(html, '_energyMapCreate') || '';
    return !body.includes('H6_ENERGY_ICONS.add') && !body.includes('H6_ENERGY_ICONS.remove')
      && /followBtn = button\(H6_ENERGY_ICONS\.fitScreen,/.test(body)
      && /followBtn\.style\.display = moved\(\) \? '' : 'none';/.test(body)
      && /if \(tap && !maxMode\) this\._setEnergyMapMax\(true\);/.test(body);
  },
  // FINALIZAR VIAGEM ends a trip for good: the button only opens a confirmation.
  'finishing a trip asks for confirmation': ({ html }) => {
    const ask = method(html, '_finishEnergyTrip') || '';
    const confirm = method(html, '_confirmFinishEnergyTrip') || '';
    return /energyFinishConfirm: true/.test(ask) && !/finishTrip\(\)/.test(ask)
      && /b\.finishTrip\(\)/.test(confirm)
      && html.includes('FinishConfirm }}">FINALIZAR</button>');
  },
  // EXPORTAR VIAGENS: a long history takes seconds to write. On the recorder's
  // worker it would hold back the signals being integrated, on the bridge thread
  // it would freeze the page; it gets a thread of its own and the page polls.
  'export runs on its own thread': ({ html, recorder }) => {
    const start = javaMethod(recorder, 'synchronized String exportTrips(') || '';
    const page = method(html, '_startEnergyExport') || '';
    return start.includes('new Thread(() -> runExport(s, only), "h6-trip-export")') && start.includes('exportThread.start();')
      && !/^\s*runExport\(/m.test(start)
      && /setTimeout\(poll, 1000\)/.test(page) && page.includes('b.getExportStatus()');
  },
  // "Everything recorded": the export reads every sample, never a strided subset.
  'export keeps every sample': ({ store }) => {
    const body = javaMethod(store, 'ExportCount exportTrips(') || '';
    return /FROM trip_points"\s*\+\s*" WHERE start_ms = \? ORDER BY t"/.test(body)
      && !/stride|% \w+ != 0/.test(body) && body.includes('tripWithRoute(startMs, true)');
  },
  // Written to .tmp and renamed when complete: a power cut mid-export leaves no
  // file that looks like a finished export.
  'a half-written export never looks finished': ({ recorder }) => {
    const body = javaMethod(recorder, 'private void runExport(') || '';
    return body.includes('new java.io.FileOutputStream(jsonTmp)') && body.includes('new java.io.FileOutputStream(csvTmp)')
      && body.includes('jsonTmp.renameTo(json)') && body.includes('csvTmp.renameTo(csv)');
  },
  'export note states what was written and where': ({ html }) => {
    const text = pure(html, '_energyExportText');
    const done = text({ state: 'done', trips: 3, points: 1200, dir: '/storage/emulated/0/HavalH6Viewer/exports',
      json: 'viagens-1.json', csv: 'viagens-1.csv' });
    return done.startsWith('3 viagens e 1200 amostras') && done.includes('/sdcard/HavalH6Viewer/exports')
      && done.includes('viagens-1.json') && done.includes('.csv')
      && /permissão/.test(text({ state: 'error', reason: 'permission' }))
      && html.includes('<sc-if value="{{ focusedConsumptionExportAvailable }}"');
  },
  // ENVIAR POR BLUETOOTH: the provider is the only door to shared storage it
  // opens, so it serves export zips by bare name, read-only, to granted callers only.
  'export provider serves only export zips, read-only': ({ provider, manifest }) => {
    const resolve = javaMethod(provider, 'private static File resolve(') || '';
    const open = javaMethod(provider, 'public ParcelFileDescriptor openFile(') || '';
    const entry = (manifest.match(/<provider\s+android:name="\.TripExportProvider"[^>]*>/) || [''])[0];
    return resolve.includes('!TripExport.isZipName(name)') && resolve.includes('uri.getPathSegments().size() != 1')
      && open.includes('if (!"r".equals(mode)) throw') && open.includes('MODE_READ_ONLY')
      && entry.includes('android:exported="false"') && entry.includes('android:grantUriPermissions="true"');
  },
  // The send button appears only where the recorder found a Bluetooth share,
  // and the page never asks the package manager while it renders.
  'bluetooth send only where the head unit has it': ({ html, recorder }) =>
    /o\.put\("bluetooth", TripExportProvider\.canSendBluetooth\(app\)\);/.test(javaMethod(recorder, 'private void runExport(') || '')
    && (method(html, '_energyExportCanSend') || '').includes("st.bluetooth === true")
    && html.includes('<sc-if value="{{ focusedConsumptionExportCanSend }}"')
    && !/bluetoothTarget|sendExportBluetooth/.test(method(html, '_consumptionWidgetView') || ''),
  // EXPORTAR VIAGEM / SELECIONAR: the trip in view, or the ticked ones, and nothing else.
  'one trip or a selection exports only those trips': ({ html, store }) => {
    const start = method(html, '_startEnergyExport') || '';
    const trips = method(html, '_energyTripsView') || '';
    const body = javaMethod(store, 'ExportCount exportTrips(') || '';
    return start.includes("b.exportTripsOf(starts.join(','))")
      && trips.includes("this._startEnergyExport(ev, t && !row.live ? [String(t.startMs)] : [])")
      && trips.includes('this._startEnergyExport(ev, pick.slice())')
      && body.includes('if (only == null || only.contains(c.getLong(0))) starts.add(c.getLong(0));');
  },
  // Daily and all-time totals cover every trip: a selection carrying them would misstate it.
  'a selection never carries the all-time totals': ({ store }) =>
    /\(only == null \? ",\\"totals\\":" \+ totalsJson\(\)/.test(javaMethod(store, 'ExportCount exportTrips(') || ''),
  // While selecting, a tap ticks a saved trip; it never opens one, and the open trip is not saved yet.
  'selecting ticks saved trips only': ({ html }) => {
    const trips = method(html, '_energyTripsView') || '';
    const toggle = method(html, '_toggleEnergyTripPick') || '';
    return trips.includes('onClick: (ev) => (pick ? this._toggleEnergyTripPick(r.key, r.live, ev) : this._selectEnergyTrip(r.key, ev)),')
      && /if \(live\) return;/.test(toggle) && noCommit(method(html, '_energyExportText'));
  },
  // The MMI ships the Bluetooth share unexported, so it is started through
  // Shizuku, as root there: no shell, a share activity and an export zip only.
  'Shizuku share runs no shell and checks its arguments': ({ gwm }) => {
    const body = javaMethod(gwm, 'public int shareFile(') || '';
    return body.includes('!COMPONENT.matcher(component).matches()') && body.includes('!EXPORT_DIR.matcher(parent).matches()')
      && body.includes('!TripExport.isZipName(zip.getName())') && body.includes('exec(new String[] {')
      && !/"(sh|\/system\/bin\/sh)"|"-c"/.test(body);
  },
  // QR CODE: the server answers only on the unit's Wi-Fi, never the car's internal networks.
  'export link answers only on Wi-Fi': ({ link }) =>
    (javaMethod(link, 'private static void serve(') || '').includes('if (!onWifi(conn.getLocalAddress())) continue;')
    && (javaMethod(link, 'private static List<InetAddress> wifiAddresses(') || '').includes('TripExport.isWifiInterface(ni.getName())'),
  // Driving settings (TripSetting): the recorder listens for them and every export carries them,
  // so a result can be put down to the mode it was driven in.
  'settings reach the recorder and the export': ({ recorder, store }) =>
    /TripSetting\.KEY_DRIVE_MODE, TripSetting\.KEY_POWER_MODE, TripSetting\.KEY_RESERVE, TripSetting\.KEY_SOC_TARGET\)\);/.test(recorder)
    && (javaMethod(store, 'ExportCount exportTrips(') || '').includes('putSettings(trip);')
    && (javaMethod(store, 'String tripJson(') || '').includes('putSettings(trip);'),
  // Closing the code, or the popup, stops the server at once rather than after 5 minutes.
  'closing the code stops the link': ({ html }) =>
    (method(html, '_closeEnergyExportQr') || '').includes('b.stopExportLink()')
    && html.includes('energyTripPick: null, energyExportDialog: false, energyExportQr: null'),
};

const MUTANTS = {
  'graph writes do not commit': (s) => ({ ...s, html: s.html.replace(
    '    this._graphLiveAt[key] = Date.now();\n', '    this._graphLiveAt[key] = Date.now();\n    this.setState({ x: 1 });\n') }),
  'live paint path does not commit': (s) => ({ ...s, html: s.html.replace(
    '      this._paintEnergyCards(m);\n', '      this._paintEnergyCards(m);\n      this._uiOnlySetState({ widgetRev: 1 });\n') }),
  'no consumptionRev state': (s) => ({ ...s, html: s.html + '\nthis.setState((s) => ({ consumptionRev: 1 }));' }),
  'paint writes inside the interpolation span': (s) => ({ ...s, html: s.html.replace(
    "      const target = el.querySelector('.sc-interp') || el;\n", '      const target = el;\n') }),
  'fuel defaults to km/L': (s) => ({ ...s, html: s.html.replace(
    "fuel: this._energyFuelUnit === 'l100' ? 'l100' : 'kml',", "fuel: this._energyFuelUnit === 'kml' ? 'kml' : 'l100',") }),
  'derived electric figure is labelled EST': (s) => ({ ...s, html: s.html.replace(" + (nowEv === '—' ? '' : ' · EST'),", ',') }),
  'no descendant span rules on the card': (s) => ({ ...s, html: s.html.replace(
    '.hv-energy-tkv li > small {', '.hv-energy-tkv span, .hv-energy-tkv li > small {') }),
  'rail card has its own native painter': (s) => ({ ...s, java: s.java.replace(
    'case "consumption": drawEnergy(canvas, w, h, accent, muted, strong); break;', '') }),
  'native parses the seven-day bars': (s) => ({ ...s, java: s.java.replace(
    'descriptor.energyBars = parseEnergyBars(raw.optString("energyBars", ""));', '') }),
  'trip map is an image href, not a style': (s) => ({ ...s, html: s.html.replace(
    '<image class="hv-energy-map" href="{{ focusedConsumptionDetailMap }}"',
    '<div class="hv-energy-map" style="background-image:url({{ focusedConsumptionDetailMap }})"') }),
  'map snapshot carries OSM attribution': (s) => ({ ...s, renderer: s.renderer.replace(
    'canvas.drawText(ATTRIBUTION, WIDTH - tw - 5, HEIGHT - 5, text);', '') }),
  'tile requests identify the app': (s) => ({ ...s, renderer: s.renderer.replace(
    'conn.setRequestProperty("User-Agent", USER_AGENT);', '') }),
  'live map does not commit': (s) => ({ ...s, html: s.html.replace(
    '    const schedule = () => { if (!st.raf) st.raf = requestAnimationFrame(render); };',
    '    const schedule = () => { this.setState({ x: 1 }); if (!st.raf) st.raf = requestAnimationFrame(render); };') }),
  'live map carries OSM attribution': (s) => ({ ...s, html: s.html.replace(
    "credit.textContent = '© OpenStreetMap contributors';", "credit.textContent = '';") }),
  'wide widget shows both panes': (s) => ({ ...s, html: s.html.replace(
    "consumptionTripShowData: !!t && (wide || tripView === 'data')", "consumptionTripShowData: !!t && tripView === 'data'") }),
  'no zoom buttons, fit on the full map, tap opens it': (s) => ({ ...s, html: s.html.replace(
    "    followBtn = button(H6_ENERGY_ICONS.fitScreen,",
    "    button(H6_ENERGY_ICONS.add, 'Aproximar', () => zoomAt(1));\n    followBtn = button(H6_ENERGY_ICONS.fitScreen,") }),
  'finishing a trip asks for confirmation': (s) => ({ ...s, html: s.html.replace(
    '    this._uiOnlySetState({ energyFinishConfirm: true });', '    this._confirmFinishEnergyTrip(ev);') }),
  'export runs on its own thread': (s) => ({ ...s, recorder: s.recorder.replace(
    '        exportThread.start();\n', '        runExport(s, only);\n') }),
  'export keeps every sample': (s) => ({ ...s, store: s.store.replace(
    '                    while (c.moveToNext()) {\n                        if (n.points > before)',
    '                    int stride = 2, i = 0;\n                    while (c.moveToNext()) {\n                        if (i++ % stride != 0) continue;\n                        if (n.points > before)') }),
  'a half-written export never looks finished': (s) => ({ ...s, recorder: s.recorder.replace(
    'new java.io.FileOutputStream(jsonTmp)', 'new java.io.FileOutputStream(json)') }),
  'export note states what was written and where': (s) => ({ ...s, html: s.html.replace(
    "(trips === 1 ? '1 viagem' : trips + ' viagens')", "(trips === 1 ? '1 viagem' : points + ' viagens')") }),
  'export provider serves only export zips, read-only': (s) => ({ ...s, provider: s.provider.replace(
    'if (!TripExport.isZipName(name) || uri.getPathSegments().size() != 1)', 'if (name == null)') }),
  'bluetooth send only where the head unit has it': (s) => ({ ...s, html: s.html.replace(
    "return st.state === 'done' && st.bluetooth === true &&", "return st.state === 'done' &&") }),
  'one trip or a selection exports only those trips': (s) => ({ ...s, store: s.store.replace(
    'if (only == null || only.contains(c.getLong(0))) starts.add(c.getLong(0));', 'starts.add(c.getLong(0));') }),
  'a selection never carries the all-time totals': (s) => ({ ...s, store: s.store.replace(
    '(only == null ? ",\\"totals\\":" + totalsJson()', '("" == null ? "" : ",\\"totals\\":" + totalsJson()') }),
  'selecting ticks saved trips only': (s) => ({ ...s, html: s.html.replace(
    '    if (live) return;\n    this._uiOnlySetState((st) => {', '    this._uiOnlySetState((st) => {') }),
  'Shizuku share runs no shell and checks its arguments': (s) => ({ ...s, gwm: s.gwm.replace(
    '                || !TripExport.isZipName(zip.getName()) || !zip.isFile()) return -1;', '                || !zip.isFile()) return -1;') }),
  'export link answers only on Wi-Fi': (s) => ({ ...s, link: s.link.replace(
    '                    if (!onWifi(conn.getLocalAddress())) continue;\n', '') }),
  'settings reach the recorder and the export': (s) => ({ ...s, store: s.store.replace(
    '                if (trip == null) continue;\n                putSettings(trip);\n', '                if (trip == null) continue;\n') }),
  'closing the code stops the link': (s) => ({ ...s, html: s.html.replace(
    "    try { if (b && typeof b.stopExportLink === 'function') b.stopExportLink(); } catch (e) {}\n", '') }),
};

const changed = (a, b) => Object.keys(a).some((k) => a[k] !== b[k]);

for (const [name, check] of Object.entries(CHECKS)) {
  assert.ok(check(SOURCE), `energy contract: ${name}`);
  const mutate = MUTANTS[name];
  assert.ok(mutate, `negative control missing for: ${name}`);
  const broken = mutate(SOURCE);
  assert.ok(changed(SOURCE, broken), `negative control did not change the source: ${name}`);
  assert.ok(!check(broken), `negative control still passes (check does not bite): ${name}`);
}

console.log(`energy (consumption) card contracts: ok (${Object.keys(CHECKS).length} checks, each negative-controlled)`);

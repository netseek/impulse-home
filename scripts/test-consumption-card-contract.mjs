/*
 * Source-level contract for the ENERGY workspace (card / widget type
 * `consumption`): widget, popup, native rail card and trip map snapshot.
 *
 * Every check is a function of the source text, and each one is also run
 * against a deliberately broken copy: a check that still passes on the broken
 * copy is decoration, not a gate (CLAUDE.md, "negative-control every assertion
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
const css = (html) => (html.match(/<style[^>]*>[\s\S]*?<\/style>/g) || []).join('\n');
const noCommit = (body) => !!body && !/setState\s*\(/i.test(body);

const CHECKS = {
  // A per-signal / per-second path must never reach React (197 ms per commit on the car).
  'graph writes do not commit': ({ html }) => noCommit(method(html, '_setGraphValue')),
  'live paint path does not commit': ({ html }) =>
    ['_energyTick', '_paintEnergyCards', '_energyModel', '_energyReadLive'].every((n) => noCommit(method(html, n))),
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
  // A card id with no `case` falls through to a generic ring (docs/ui-surfaces.md).
  'rail card has its own native painter': ({ java }) =>
    /case "consumption": drawEnergy\(/.test(java) && /private void drawEnergy\(/.test(java),
  'native parses the seven-day bars': ({ java }) =>
    /descriptor\.energyBars = parseEnergyBars\(raw\.optString\("energyBars"/.test(java),
  // A data URL always contains ";", and the template splits an interpolated
  // style on it (docs/ui-surfaces.md): the map must be an <image href> (in the
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
  // Owner, 2026-09-14: zoom buttons only on the maximised map; the pane pans,
  // pinches and opens the full map on a tap.
  'zoom buttons only on the maximised map': ({ html }) => {
    const body = method(html, '_energyMapCreate') || '';
    return /if \(maxMode\) \{\s*button\('\+'/.test(body) && /if \(tap && !maxMode\) this\._setEnergyMapMax\(true\);/.test(body);
  },
  // FINALIZAR VIAGEM ends a trip for good: the button only opens a confirmation.
  'finishing a trip asks for confirmation': ({ html }) => {
    const ask = method(html, '_finishEnergyTrip') || '';
    const confirm = method(html, '_confirmFinishEnergyTrip') || '';
    return /energyFinishConfirm: true/.test(ask) && !/finishTrip\(\)/.test(ask)
      && /b\.finishTrip\(\)/.test(confirm)
      && html.includes('FinishConfirm }}">FINALIZAR</button>');
  },
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
    '.hv-energy-stats li > small {', '.hv-energy-stats span, .hv-energy-stats li > small {') }),
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
  'zoom buttons only on the maximised map': (s) => ({ ...s, html: s.html.replace('    if (maxMode) {\n      button(', '    if (true) {\n      button(') }),
  'finishing a trip asks for confirmation': (s) => ({ ...s, html: s.html.replace(
    '    this._uiOnlySetState({ energyFinishConfirm: true });', '    this._confirmFinishEnergyTrip(ev);') }),
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

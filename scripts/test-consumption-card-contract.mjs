/*
 * Source-level contract for the CONSUMPTION card (widget + popup + rail payload).
 *
 * Every check is a function of the source text, so each one is also run against
 * a deliberately broken copy of the source: a check that still passes on the
 * broken copy is decoration, not a gate (CLAUDE.md, "negative-control every
 * assertion you add").
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const source = readFileSync(resolve(root, 'index.html'), 'utf8').replace(/\r\n/g, '\n');

function method(html, name) {
  const start = html.indexOf(`  ${name}(`);
  if (start < 0) return null;
  const brace = html.indexOf('{', start);
  let depth = 0;
  for (let i = brace; i < html.length; i++) {
    if (html[i] === '{') depth++;
    if (html[i] === '}' && --depth === 0) return html.slice(start, i + 1);
  }
  return null;
}

// Every <style> block, not just the first: the card rules live in a later one,
// and scanning only the first made this check pass vacuously (its negative
// control caught that).
const css = (html) => (html.match(/<style[^>]*>[\s\S]*?<\/style>/g) || []).join('\n');

const CHECKS = {
  // A bus / per-frame write must never reach React. evTrip is written every
  // frame by _graphDeriveConsumption while the graph loop runs.
  'graph writes do not commit': (html) => {
    const body = method(html, '_setGraphValue');
    return !!body && !/setState\s*\(/i.test(body);
  },
  'refresh path paints without committing': (html) => ['_queueConsumptionRefresh',
    '_commitConsumptionRefresh', '_paintConsumptionCards'].every((name) => {
    const body = method(html, name);
    return !!body && !/setState\s*\(/i.test(body);
  }),
  'refresh is queued from graph writes': (html) =>
    /_queueConsumptionRefresh\(\)/.test(method(html, '_setGraphValue') || ''),
  'no consumptionRev state': (html) => !/consumptionRev\s*:|\.consumptionRev\b/.test(html),
  // Replacing a parent's textContent would detach React's interpolation span.
  'paint writes inside the interpolation span': (html) =>
    (method(html, '_paintConsumptionCards') || '').includes(".querySelector('.sc-interp')"),
  // The derived pack-power estimate must not be labelled as a vehicle aggregate.
  'VEHICLE energy requires the bus value': (html) => {
    const body = method(html, '_consumptionWidgetView') || '';
    return /liveEv\s*=\s*evFresh\s*&&\s*!!this\._graphBusEvTrip/.test(body)
      && body.includes("'ESTIMATE ·");
  },
  // {{ value }} renders as <span class="sc-interp">, so a descendant span rule
  // also restyles the value inside a sibling <strong>.
  'no descendant span rules on the card': (html) => css(html).split('}').every((rule) => {
    const selector = rule.slice(0, rule.indexOf('{'));
    return !/\.hv-consumption[\w-]*\s+span/.test(selector);
  }),
};

const MUTANTS = {
  'graph writes do not commit': (h) => h.replace(
    "if (key === 'evTrip' || key === 'fuelAvg') this._queueConsumptionRefresh();",
    "if (key === 'evTrip' || key === 'fuelAvg') this.setState({ x: 1 });"),
  'refresh path paints without committing': (h) => h.replace(
    '      this._paintConsumptionCards(view);',
    '      this._paintConsumptionCards(view);\n      this._uiOnlySetState({ widgetRev: 1 });'),
  'refresh is queued from graph writes': (h) => h.replace(
    "if (key === 'evTrip' || key === 'fuelAvg') this._queueConsumptionRefresh();", ''),
  'no consumptionRev state': (h) => h + '\nthis.setState((s) => ({ consumptionRev: (s.consumptionRev || 0) + 1 }));',
  'paint writes inside the interpolation span': (h) => h.replace(
    "const target = el.querySelector('.sc-interp') || el;", 'const target = el;'),
  'VEHICLE energy requires the bus value': (h) => h.replace(
    'const liveEv = evFresh && !!this._graphBusEvTrip;', 'const liveEv = evFresh;'),
  'no descendant span rules on the card': (h) => h.replace(
    '.hv-consumption-kpis > div > span,', '.hv-consumption-kpis span,'),
};

for (const [name, check] of Object.entries(CHECKS)) {
  assert.ok(check(source), `consumption contract: ${name}`);
  const mutate = MUTANTS[name];
  assert.ok(mutate, `negative control missing for: ${name}`);
  const broken = mutate(source);
  assert.notEqual(broken, source, `negative control did not change the source: ${name}`);
  assert.ok(!check(broken), `negative control still passes (check does not bite): ${name}`);
}

console.log(`consumption-card contracts: ok (${Object.keys(CHECKS).length} checks, each negative-controlled)`);

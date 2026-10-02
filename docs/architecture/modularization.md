# Modularising the viewer

> Companion to `CLAUDE.md`. That file says what the car does under load; this
> one says where code goes. Read both before a structural change.

> **STATUS 2026-09-04 — none of §8 has been done, and the file has grown 45%
> since this was written.** §1's measurements are stale: `index.html` is now
> **28,908 lines / 1.28 MB**, not 19,881 / 922 KB. There is no `src/`. Re-measure
> the block table before quoting it.
>
> One thing did land, and it is worth knowing about because it is the pattern
> for hot-signal work: the **`_live*` seam** (`_liveDefine` / `_liveSet` /
> `_liveGet`, beside `_currentMotionSpeed`). CAN signals that drive the scene no
> longer travel through `setState` — see CLAUDE.md, "A hot CAN signal must never
> reach `setState` at all". It is not part of this plan and does not depend on
> it; it is a seam inside the monolith, not a step toward splitting it.
>
> **Step 1 (build harness, no code moves) is still the right next move** and is
> the cheapest thing here: it is designed to be behaviour-preserving by
> construction, and it de-risks everything after it.

## 1. What we actually have (measured, 2026-08-26 — see STATUS above, now stale)

`index.html` was 19,881 lines / 922 KB in four blocks:

| Lines | Block | Contents |
|---|---|---|
| 1–487 | `<head>` + boot | splash IIFE, `support.js` (dc-runtime), boot CSS |
| 490–2490 | `<style>` | ~2,000 lines of `hv-*` CSS, one flat sheet |
| 2491–3461 | `<x-dc>` | ~970 lines of template markup |
| 3462–19872 | `<script type="text/x-dc">` | **one `class Component extends DCLogic` with 467 methods** |

Three contention points, all single-file:

- **The class.** 467 methods on one prototype. Every feature edit lands in the
  same file, usually within a few hundred lines of every other feature edit.
- **`state`.** One flat object, ~120 keys, declared as a single class field.
  Two agents adding a state key collide on the same line region every time.
- **`renderVals()`.** 554 lines, one `return { ... }`. Same problem, worse:
  it is one expression, so a merge conflict there is not resolvable by hunk.

Largest single methods: `_setResTier` (~1293), `onModelLoaded` (739),
`_handleUploadedWheelModel` (555), `renderVals` (554), `onWheelStyle` (517),
`_collectWheelMeshes` (433).

Duplication worth naming: the left and right widget boards are ~200 lines of
markup each and **differ by 22 diff lines**.

## 2. The three asks have one root cause

Conflicts, missing component patterns, and the inability to preview a widget
in isolation are all the same fact: *there is no unit smaller than the whole
app*. Fix the unit and all three follow.

We do **not** need a new framework to do it. `support.js` already supports
splitting, in two ways we currently use zero times:

- `<Name/>` in markup → runtime-fetches `./Name.dc.html`, which is its own
  `<x-dc>` template + `<script data-dc-script> class Component extends DCLogic`.
  (`COMPONENT_DIR = "."`, `ensureFetched()` at support.js:1346.)
- `<x-import from="./foo.jsx" component="Foo">` → Babel-transpiled at runtime.

Both are **runtime fetches**, and on the car that is the wrong trade: boot cost
is already bad enough to need a splash video to hide it, and runtime Babel is
worse. So:

> **Split the source, bundle at build time.** Ship one `index.html` to the car,
> exactly as today. `scripts/prepare-android-www.mjs` already extracts the
> `data-dc-script` block and rewrites it through esbuild — the bundle step goes
> there, and esbuild is already a devDependency.

This keeps the boot path equivalent in kind, so the split itself carries
**zero runtime delta by construction**. That matters: CLAUDE.md's one rule is
measure-before-and-after, and a refactor that cannot change the frame is a
refactor that does not need a car to approve it.

## 3. Target layout

```
src/
  index.html.in            ← shell: <head>, boot splash, mount points
  core/
    component.js           ← class Component extends DCLogic — host only
    state.js               ← composeState(): merges per-feature state slices
    telemetry.js           ← _wireCarTelemetry / _applyCarSignal dispatch
    signals.js             ← CAR_SIGNALS + constants
  scene/                   ← Three.js: renderer, camera, resolution ladder
    renderer.js  camera.js  restier.js  lighting.js  postfx.js
  features/
    wheels/    { logic.js  view.js  wheels.css  states.js }
    graphs/    { logic.js  paint.js  viz.js  view.js  graphs.css  states.js }
    power/     { logic.js  view.js  power.css  states.js }
    modes/     { logic.js  view.js  modes.css  states.js }
    widgets/   { board.js  registry.js  menu.js  widgets.css }
    camera/    { presets.js  events.js }
  ui/
    templates/             ← markup partials, one per feature
  mock/
    telemetry.js           ← fake window.carTelemetry
    scenarios/             ← drive-cycle.js, charging.js, parked.js …
preview.html               ← generated harness (replaces the four *-preview.html)
```

Feature sizes, so nobody plans a two-day job for a 200-line one:
graphs ≈ 1,430 lines, wheels ≈ 2,100, widgets ≈ 290, power ≈ 200,
modes ≈ 195, camera ≈ 170. The rest is scene/render core.

## 4. Pattern A — feature modules (solves: conflicts)

The unit is a **feature module**, installed onto the host prototype. Not a React
component: the scene, renderer and camera are one shared resource, and the
render loop is on-demand (`requestRender()`), so introducing React boundaries
changes *when things draw*. That is a behavioural change requiring a car
measurement. Prototype installation is not — it produces the same prototype
with the same call sites.

The method prefixes already are the module boundaries (`_graph*`, `_power*`,
`_mode*`, `_widget*`, `_cam*`, `_wheel*`, `_viz*`, `_paint*`), so most of this
migration is mechanical.

```js
// src/features/power/logic.js
export const PowerFeature = {
  name: 'power',

  // Merged into the host's state by composeState(). Namespaced by convention:
  // every key a feature owns starts with its name.
  state: { powerSoc: null, powerFlow: 0, powerTone: 'idle' },

  // CAN keys this feature consumes. Used by the mock bus and the preview
  // harness to know which sliders to render — see §6.
  signals: ['batterySoc', 'evConsume', 'driveState', 'architecture'],

  // Installed with Object.assign(Component.prototype, ...). Same `this`,
  // same prototype, same call sites as today.
  methods: {
    _powerModel(t) { /* … */ },
    _powerResolveFlow(drive) { /* … */ },
    _powerWidgetView(item) { /* … */ },
  },

  // This feature's slice of renderVals(). The host concatenates slices; no
  // agent ever edits the same 554-line return expression again.
  view(s, host) {
    return { powerTone: s.powerTone, powerKw: host._powerFmtNum(s.powerKw) };
  },
};
```

```js
// src/core/component.js
import { PowerFeature } from '../features/power/logic.js';
import { GraphsFeature } from '../features/graphs/logic.js';
// …
const FEATURES = [PowerFeature, GraphsFeature, ModesFeature, WheelsFeature];

class Component extends DCLogic {
  state = composeState(FEATURES, BASE_STATE);
  renderVals() {
    const s = this.state;
    const vals = baseVals(this, s);
    for (const f of FEATURES) Object.assign(vals, f.view(s, this));
    return vals;
  }
}
for (const f of FEATURES) Object.assign(Component.prototype, f.methods);
```

**Why this kills conflicts:** a power change touches `features/power/*` only.
`core/component.js` changes once per *new feature*, not per feature edit — and
the `FEATURES` array is an append-only list, the cheapest possible merge.

**Rules**
- A feature never reads another feature's state keys directly. If power needs a
  graph value, graphs exposes a method and power calls it through `this`.
- A feature never touches `scene/` internals. It calls `this.requestRender()`.
- CSS: each feature owns `feature.css`, concatenated at build. Selectors must
  be prefixed `hv-<feature>-`. `hv-widget-*` is shared chrome, owned by widgets.

## 5. Pattern B — the widget contract (solves: visual component patterns)

There is already an implicit widget contract — `item.type` of
`power|graphs|modes`, a `_xWidgetView(item)` returning a flat vals object, `w`/`h`
sizing, hold-to-menu, resize, move. It is implicit, so every new widget is
copy-paste archaeology. Make it a registry:

```js
// src/features/widgets/registry.js
export const WidgetRegistry = new Map();
export function registerWidget(def) { WidgetRegistry.set(def.type, def); }
```

```js
// src/features/power/widget.js
registerWidget({
  type: 'power',
  label: 'POWER',
  sizes: ['1x1', '2x1', '2x2'],   // replaces the ad-hoc _widgetResizeSizes switch
  defaultSize: '2x1',
  view: (item, host) => host._powerWidgetView(item),
  onTap: (host) => host._launchEnergyAssistant(),
  states: POWER_STATES,           // ← §6. Named demo states, the preview source.
});
```

`_widgetRenderFields` (currently a chain of
`if (item.type === 'modes') …` at index.html:18776) becomes a registry lookup,
so **adding a widget stops being an edit to shared code**.

And the two 200-line board blocks that differ by 22 lines collapse into one
markup partial parameterised by side. That is the single highest
duplication-per-line win in the file.

## 6. Pattern C — mock bus + generated preview (solves: simulation)

Today there are four hand-written harnesses — `power-preview.html`,
`powertrain-preview.html`, `modes-preview.html`, `dyad-paint-preview.html` —
each with CSS and markup **copy-pasted out of index.html**. They drift the
moment the real widget changes, which makes them worse than nothing: they
show you a state the app can no longer produce.

The fix has two halves.

### 6a. A mock telemetry bus

`window.carTelemetry` is already a clean seam — the whole app consumes it
through exactly two calls, `onUpdate(cb)` and `get(key, default)`, both in
`_wireCarTelemetry` / `_replayCarTelemetry`. So a fake is a drop-in:

```js
// src/mock/telemetry.js — installed only when ?mock=<scenario> is present
window.carTelemetry = {
  onUpdate(cb) { subscribers.push(cb); },
  get(key, dflt) { return key in values ? values[key] : dflt; },
};
```

A scenario is a timeline of signal writes:

```js
// src/mock/scenarios/drive-cycle.js
export default {
  label: 'Drive cycle — cold start to 80 km/h',
  steps: [
    { at: 0,     set: { gear: 3, vehicle_speed: 0, batterySoc: 62 } },
    { at: 2000,  set: { gear: 4, driveState: 14 } },
    { at: 4000,  ramp: { vehicle_speed: [0, 80] }, over: 12000 },
    { at: 16000, set: { evConsume: 18.4 } },
  ],
};
```

This is worth building **even setting the preview aside**. CLAUDE.md records
that the CAN bus is nearly silent parked (0.3 signals/sec) — so today a
driveway test cannot exercise any driving state at all. `?mock=drive-cycle`
works on the car, on the emulator, and in the browser, and it drives the real
code paths rather than a copy of them.

Keep `_replayCarTelemetry` honest: the mock must answer `get()` for every key
in `_carSignalKeys()`, or boot-time replay silently sees defaults.

### 6b. A generated harness

One `preview.html`, not four. It imports the **same** feature module and the
**same** CSS, so it cannot drift:

```
preview.html?widget=power              → every declared state, side by side
preview.html?widget=power&size=2x2     → one size
preview.html?widget=graphs&mock=drive-cycle&scrub=1  → timeline scrubber
```

It renders, for the selected widget: one card per entry in the registry's
`states`, at every size in `sizes`; a slider per key in the feature's
`signals`; and a scrub bar when a scenario is named. `states` is the single
place a state is declared — the harness reads it, and so can a screenshot test.

```js
// src/features/power/states.js
export const POWER_STATES = [
  { name: 'parked',        set: { driveState: 0,  batterySoc: 62 } },
  { name: 'ev-drive',      set: { driveState: 14, batterySoc: 48 } },
  { name: 'regen',         set: { driveState: 16, evConsume: -22 } },
  { name: 'series-hybrid', set: { driveState: 41, batterySoc: 12 } },
  { name: 'soc-critical',  set: { batterySoc: 4 } },   // battery clip-path edge
];
```

`CAR_POWER_DEMO_STATES` (index.html:152) is already exactly this list, minus
the names. Naming those nine states is most of the work done.

Wire it to the existing dev loop — `scripts/device-cdp.mjs` already serves the
working tree to the car over `adb reverse`, so `preview.html` runs **on the
real panel at the real frame rate** with no rebuild, which is the only place
some of these states are honestly reviewable.

## 7. Build integration

`scripts/prepare-android-www.mjs` already does the surgery: it finds
`<script type="text/x-dc" data-dc-script>`, esbuild-transforms the body to
`chrome69`, and writes a rewritten `index.html`. Change it to **bundle first**:

```js
const bundled = await esbuild.build({
  entryPoints: ['src/core/component.js'],
  bundle: true, format: 'iife', target: 'chrome69', write: false,
});
```

then inline `bundled.outputFiles[0].text` into the script block, concatenate
`src/**/*.css` into the `<style>` block, and assemble the `<x-dc>` markup from
`src/ui/templates/`. `app/build.gradle` keeps its existing include list
unchanged, because the output is still one `index.html`.

Add `npm run build:viewer` (bundle to a root `index.html` for the CDP dev
loop) and `npm run watch:viewer`. Both `car:deploy` and `device-cdp.mjs serve`
then consume a generated `index.html` rather than a hand-edited one.

**`index.html` becomes a build artifact.** Add it to `.gitignore` in the same
commit that lands the last extracted feature — not before, or the working tree
is unbuildable in between.

## 8. Migration order

Strictly incremental. Each step ships and is revertible on its own; the app
boots at every step.

1. **Build harness first, no code moves.** `prepare-android-www.mjs` bundles
   `src/core/component.js`, which for now contains the entire class verbatim.
   Deploy, confirm the car behaves identically. This de-risks everything after.
2. **CSS split.** ~2,000 lines → `features/*/*.css` by prefix. Purely
   mechanical, no JS risk, and immediately removes a large conflict surface.
3. **Extract `graphs`** (~1,430 lines, ~28 methods + 20 `_paint*` + 7 `_viz*`).
   Best first feature: largest, most self-contained, its own canvas, and it
   owns the most contended state.
4. **Widget registry + board dedup.** Collapse the two boards; convert
   `_widgetRenderFields` to a lookup.
5. **Mock bus + `preview.html`**, with graphs and power as the first two
   registered. Delete the four hand-written harnesses in the same commit.
6. **Extract `power`, `modes`, `camera`** — small, and the pattern is proven by
   then.
7. **Extract `wheels`** (~2,100 lines) last. It is the most physics-entangled
   code in the repo (rim repeat measurement, first-harmonic axis correction,
   blur sprite capture) and CLAUDE.md documents two separate agents breaking
   the blur shader. Move it only once the pattern is boring.
8. **`scene/`** — renderer, camera, `_setResTier`, post-FX. Do this last and
   **measure on the car**: it is the only step that touches the frame.

Steps 1–7 are prototype-shape-preserving and should not move a single number in
CLAUDE.md's table. If one moves, something else changed — find it.

## 9. Rules for agents after this lands

- Work in `src/`. **Never hand-edit a generated `index.html`.**
- A feature edit touches exactly one `src/features/<name>/` directory. If it
  touches two, say so in the PR — that is a design smell worth a second look.
- New state key → the owning feature's `state`, prefixed with the feature name.
- New widget → `registerWidget()` + a `states.js`. A widget with no declared
  states is not reviewable and should not merge.
- New CAN signal → `core/signals.js`, plus the feature's `signals` array, plus
  a value in every mock scenario.
- Before a scene/ or render-loop change, re-read CLAUDE.md §"The one rule" and
  measure on the car. Feature-level changes do not need that; scene ones do.

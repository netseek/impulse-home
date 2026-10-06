# Testing

The test base of this repository and how to run it.

## The test base

| Layer | Run it with | What it covers |
|---|---|---|
| Node contract tests | `node scripts/run-tests.mjs` | Every `scripts/test-*.mjs`: card, widget and popup contracts read from the source text, plus pure logic (energy insights and map maths, power behaviour, clock labels and sweep, the climate SYNC mirror). |
| JVM unit tests | `./gradlew :app:testDebugUnitTest` | The pure-Java logic of the native side: `TripEngineTest`, `TripSignalsTest`, `TripMapMathTest`, `RangeLedgerTest`. Nothing in them may touch an Android stub. |
| Docs guard | `node scripts/test-docs.mjs` (also run by `run-tests.mjs`) | Every relative link in the Markdown resolves; every file under `docs/` is listed in `docs/README.md`; no personal data, local paths or AI-agent memory files are tracked; the repository root holds a single Markdown file, `README.md`. |
| Device harness | `npm run car:perf`, `scripts/device-cdp.mjs` | Frame rate, main-thread blocking and commit cost, on the car or the emulator. Read the signals in pairs; see [measuring-and-performance](measuring-and-performance.md). |

`scripts/run-tests.mjs` reads two lists next to it:

- `scripts/tests-known-failing.txt`: tests that already fail and are tracked as debt. A listed test
  that still fails is reported but does not fail the run; a test that is not listed and fails is a
  regression; a listed test that now passes fails the run until it is removed from the list. The list
  may only shrink.
- `scripts/tests-need-assets.txt`: tests that read the asset bundle. They are skipped when
  `assets/.bundle` is absent (a fork's pull request cannot read the private bundle) and run wherever
  `node scripts/fetch-assets.mjs` has fetched it.

CI (`.github/workflows/ci.yml`) runs two jobs: `tests` (the Node suite, with the bundle when the
token exists) and `android` (`assembleDebug` and the JVM tests, built without the bundle: that proves
the code compiles and the logic passes, not that the models load).

The template-event and canvas-visibility tests launch headless Chrome/Chromium
to exercise actual HTML parsing and DOM behavior. They use the browser CLI,
without a Node browser-driver dependency. Install Chrome/Chromium on `PATH`, or
set `CHROME_BIN` to its executable when running `node scripts/run-tests.mjs`.
An unavailable browser fails these tests; it is not counted as an asset skip.

### Keeping the base current

- A change that alters behaviour ships with a test for it, or with a stated reason why none is
  possible. Put logic in a pure function or method so it can be tested without a device.
- Negative-control every new assertion: break the source and confirm the test then fails.
- Never edit a test until it passes. If a deliberate change fails a contract test, fix the property
  the test should have asserted (see the next section).
- Layout and function can be checked on the emulator; frame rate, load time and memory only count
  when measured on the car. Say which one you did.

## The card contract tests pin literals, and that makes them lie twice

`scripts/test-*-card-contract.mjs` are the local gate and worth keeping, but
several assertions pin an exact value rather than the property that matters.
Both failure modes turned up in a single session:

- **They fail on deliberate change.** An exact `VIEWER_ASSET_REVISION` string,
  an exact `left:-52%`, exact `imageH = h * 1.05f` and `w * .31f` multipliers.
  Every intentional retune failed the contract, which trains you to edit the
  test until it passes -- the opposite of what a gate is for. Assert the shape
  instead: the revision parses and has increased, the offset is negative, the
  size derives from `w` / `h`.
- **They pass vacuously.** An assertion that the quick-card canvas sets
  `FILTER_BITMAP_FLAG` searched the whole file and matched an unrelated paint
  that already had it, so it could never have failed. Scope the match to the
  block or class it is about.

**Negative-control every assertion you add**: mutate the source to break it and
confirm it then fails. Two checks written that session did not bite, and the
first control run was itself mis-scoped and reported a false failure. It costs
one `node -e`, and it is the only thing separating a gate from decoration.

These tests read source text, `MainActivity.java` has **mixed line endings** and git can flip
`index.html` between LF and CRLF -- a patch keyed to one will silently fail to match a region
written with the other. Try LF and CRLF before concluding the text moved.

And two ways to invalidate your own experiment:

- **Setting `.visible = false` on wheel meshes does nothing.**
  `_applyWheelTransforms` rewrites visibility on every disc and spoke mesh each
  frame from the current speed. To take a mesh out of a test, detach it from its
  parent and re-add it afterwards.
- **Poking texture state from devtools corrupts the run.** See the render-target
  `needsUpdate` note below; after any such poke, reload before believing
  anything.

## Readability snapshots (no 3D bundle, no device)

```bash
node scripts/snapshot-readability.mjs --out <dir>              # ~190 PNGs + metrics.json
node scripts/snapshot-readability.mjs --out <dir> --only widget-power,popup-clima
node scripts/snapshot-readability.mjs --list                   # the surface names
```

`scripts/snapshot-readability.mjs` serves the working tree, opens the real `index.html` in headless
Chrome as the Android shell (`?android&demo=1`), hides the model canvas and the load-error overlay, and
drives the live `__app` to put one surface on screen at a time: every widget at every size
`_widgetCatalog()` allows, the card popups, the layout manager tabs, the pickers, the wallpaper picker
and the settings panels, in the dark and the light widget theme (plus live-signal and no-signal states
for the cards that have them). The markup and CSS are the shipped ones, so it needs no assets.

Widgets render in a 342x194 cell (8 px gap, the 1x1 budget the type scale is written against);
everything else renders at 1920x720. `metrics.json` records, per surface, the smallest text, the text
drawn below 18 px or with opacity below 1, text that overflows its box, and whether a banned phrase is
on screen, including clock Shadow DOM text measured through the SVG screen transform and buttons below
44×44px. Contrast records compose CSS colors only when a known opaque ancestor backs the
text; gradients, photos and unknown translucent backings require visual review. A before/after
run is a diff of numbers. Run it before and after a typography or copy
change and read the PNGs; the demo signals keep moving, so compare layout, not bytes. It does not
cover the 3D scene, the native rail, real signals or the car's GPU.

## Checking a change without rebuilding the APK

`scripts/device-cdp.mjs` wraps the CDP harness this file keeps referring to and
works against the emulator or the car:

```bash
node scripts/device-cdp.mjs serve        # host server + adb reverse + navigate
node scripts/device-cdp.mjs eval "__wheelSpin()"
node scripts/device-cdp.mjs spin 3 12,20,27,35,45 --fps 18
node scripts/device-cdp.mjs shot out.png
node scripts/device-cdp.mjs reset        # WebView back on the APK's own copy
```

`serve` is the fast loop: the app loads index.html through a WebViewAssetLoader
from inside the APK, so `serve` puts the working tree on an `adb reverse`d port
and navigates the WebView at it. Edit, then `eval "location.reload()"`. No
gradle, no install. `--serial` picks the device; nothing is hardcoded.

**The emulator is not a performance proxy.** It runs Android 16 / WebView 150 on
a desktop GPU and renders this scene at 40-60 fps against the MMI's 14-22, and
its WebView is 59 versions newer than the car's. What it IS good for is anything
frame-rate *dependent*, because `spin --fps <n>` gates rAF in the page and
reproduces the car's sampling rate exactly — and holds it steady, which the car
does not. Confirm the final numbers on the car regardless.

**Emulator deploy uses the `Haval` AVD only** (`scripts/deploy-emulator.ps1`
auto-starts it). Do not deploy to a phone/tablet AVD — use the head-unit profile.

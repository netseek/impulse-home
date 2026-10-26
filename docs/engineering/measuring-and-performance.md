# Measuring and performance

The viewer runs in the head unit's system WebView on a **Snapdragon SA8155 / Adreno 640**,
WebView 91, driving a 1920x720 panel. Everything in these notes was **measured on that
hardware**, not assumed. Please keep it that way: they exist because several rounds of
plausible-sounding optimisation were undone by a single measurement.

## The one rule

**Measure on the car before and after. Never optimise from reasoning alone.**

Both of these turned out to be wrong when finally measured:

- "It is fill-rate bound, so cut resolution." One benchmark said native cost
  only 20% of the frame rate; a properly settled re-run said **35%** and was
  reproducible within 1%. The first run was contaminated by `_onResize()`
  reallocating every render target immediately before the measured window.
- "The post-FX passes are the expensive part." They measured **2.0 ms/frame,
  5.6%** of the frame. A planned rewrite to merge them was abandoned because the
  realistic upside was ~1 ms for an invasive change to the post-FX core.
  That was the lighter model. On the current one (~1.2M visible triangles, 157
  draw calls) the two passes cost ~6 ms of a ~15 ms submit, because each renders
  the whole scene again; see "Post-FX rebuilds while the camera moves" below.

### How to measure

**Start here — one command, against the car or the emulator:**

```bash
npm run car:perf
```

`scripts/device-perf.mjs` runs `window.__diag()` and prints the table plus a
verdict line. Use `--sec 20`, `--json` (for diffing A/B runs), `--watch`.

`__diag(ms)` is the standing battery. It exists because the investigation that
found the 197 ms React commit had to hand-roll five throwaway CDP probes to
reach a one-line answer. **Read these as PAIRS, never in isolation** — every
wrong turn in that session came from one number read alone:

| Signal | Reading |
|---|---|
| `bareRafP50` **and** `timerLagP50` both high | main thread blocked by long tasks — look at `commitMsP50` and `setStateKeys` |
| `bareRafP50` high, `timerLagP50` low | GPU / compositor bound — resolution, post-FX, other apps on the panel |
| `bareRafPerSec: 0`, `timerLagP50 ≈ 1000` | the WebView is **hidden** — see below. Not a rendering problem at all |
| `liveCommitsPerSec` > ~1 | a hot signal is passing `{ commit: true }` through the `_live` seam |

`bareRafP50` is an **empty** rAF callback and `timerLagP50` a `setTimeout(0)`.
Neither touches WebGL, which is exactly what makes them able to tell "our frame
is expensive" apart from "we are never given a frame".

`window.__perf()` exposes `fps`, `submitMs`, `cadenceMs`, `drawCalls`,
`triangles`, `resTier`, `busPerSec`, `speedPerSec`, `rendersPerSec`. A debug
build ships it to logcat every 2 s:

```bash
adb logcat -s H6Perf
```

**`drawCalls` / `triangles` come from `_perf.mainCalls`, captured inside the
loop before the overlay passes.** Do not read `renderer.info` yourself after a
frame: `info.autoReset` resets it on every `render()`, so you get only the last
pass — 1 call and 2 triangles for the shadow-overlay quad, which reads as an
empty scene and has cost a real detour.

For A/B work, drive the camera from devtools over CDP rather than by hand.
The harness used for every number in this file is `scripts/device-cdp.mjs`.

**Benchmark hygiene, learned the hard way:**

- **Settle before measuring.** Allow ~2.5 s after any resolution or render
  target change, then measure for ~9 s. Frames straddling `_onResize()` take
  200-300 ms and will wreck a short window.
- **Pin the adaptive ladder** (`_maybeAdaptDPR = () => {}`) or it moves under you.
- **Repeat the run.** Numbers on this unit vary with thermal and foreground app
  state. Two runs within ~1% is the bar; a single run is not evidence.
- **Use a median, not a mean,** for anything driving a decision. Resize spikes
  dragged a 10-sample mean over a 100 ms threshold and falsely triggered the
  emergency resolution tier at a real ~19 fps.
- **Check the window is actually on screen.** A collapsed or backgrounded viewer
  renders at 1x1, every frame is instant, and every derived number is garbage.
- **This unit drifts ~2x over MINUTES, so sequential arms cannot be compared.**
  Measured 2026-09-04: the same scene, same DPR, same tier, camera driven
  identically, read 19-26 fps in one run and 31-39 fps eight minutes later. Two
  back-to-back repeats of one arm differed by 11%. A "before" and an "after"
  taken minutes apart are measuring the drift, not the change.
  **Interleave the arms** (A,B,A,B...) and compare PAIRED deltas. A real effect
  shows the same sign in every pair; the magnitude will still wander. This
  produced one false finding before it was caught ("the wallpaper costs 7.4
  ms/frame" — it costs nothing measurable) and explains an inexplicable
  "pinning DPR halved the frame rate" result from the same session.

- **The GPU is not the bottleneck, and `gl.finish()` proves it in one line.**
  There is no `EXT_disjoint_timer_query` on this driver, so time `gl.finish()`
  instead: it blocks until the GPU queue drains, so its duration IS the GPU
  backlog. Measured **0.1 ms p50 across eight independent arms** — the GPU is
  idle, waiting for us. Frame cost here is CPU-side: GL driver command
  translation and Chromium compositing, both of which land in `(program)` in a
  CPU profile (62% of wall). Before optimising anything for fill rate, re-run
  that check; "it is fill-rate bound" has now been wrong twice in this file.

## Known performance characteristics

| Condition | Result |
|---|---|
| DPR 0.75 (motion tier) | ~22 fps |
| DPR 1.0 native | ~14 fps (2.04x pixels, +26 ms/frame) |
| All post-FX (bloom + streak) | +2.0 ms/frame on the earlier model; ~6 ms on the current one (bloom 3.8, streak 2.2) |
| MSAA | 4x, and `MAX_SAMPLES` is 4 — already at the driver ceiling |
| Main pass | ~210 draw calls, ~374k triangles |

**Measured 2026-09-04: the WebGL canvas costs ~19 ms/frame of MAIN-THREAD
time** (median of 4 interleaved pairs, all positive: 11.5, 7.3, 19.0, 23.7),
while the GPU backlog stays at 0.1 ms. With the canvas hidden the page runs
at 16.6-25.7 ms/frame. So that 19 ms is CPU-side driver + compositing, not
GPU execution — which is why moving the render off the main thread is worth
considering, and why cutting resolution is not the lever it looks like.

Resolution is close to linear in pixel count, so it IS fill-rate sensitive —
but the frame also carries ~20-25 ms of `submitMs`, so neither axis alone
explains it. Measure the specific change; do not reason from this table.

## Architecture facts that trip people up

- **The render loop is on-demand.** It only draws when something asks
  (`requestRender()`), so an idle scene costs nothing. Anything that calls
  `setState` per event will therefore re-render the whole React tree AND dirty
  post-FX at that event's rate.
- **The CAN bus is nearly silent parked** (measured 0.3 signals/sec) and busy
  while driving. Bench numbers taken in a driveway are blind to bus load. Guard
  every hot signal handler with a change threshold — `_setMotionSpeed` had none
  and re-rendered per `vehicle_speed` frame; `_applyCarSteering` has a 0.5 deg
  deadband for exactly this reason.
- **A hot CAN signal must never reach `setState` at all — throttling it is not
  enough.** This one was got wrong twice, and the second time only because the
  first fix was measured on the emulator.

  A React commit in this app costs **197 ms p50 / 250 ms p90, measured on the
  car while driving** (2026-09-04, HEV, mixed centerFill). `renderVals()` is a
  single ~554-line return expression that recomputes everything, and React then
  reconciles the whole tree. So the arithmetic that matters is:

  | | commits/s | ms each | share of wall clock |
  |---|---|---|---|
  | `_setMotionSpeed`, throttled to 4 Hz | 3.0 | 197 | **56%** |
  | after moving it off React | 0 | — | 0% |

  At 56% of the main thread the render loop cannot get frames: rAF and
  `setTimeout(0)` were both delayed ~210-250 ms and the panel sat at **4-5 fps
  while `submitMs` was only 20.5 ms**. The renderer was never the problem.

  The earlier note here claimed a commit cost ~2 ms (one post-FX rebuild) and
  concluded that throttling to 4 Hz was sufficient. That 2 ms came from the
  emulator. **There is no safe rate at which to re-render this tree from the
  bus.**

  The fix is the `_live*` seam (`_liveDefine` / `_liveSet` / `_liveGet`, next to
  `_currentMotionSpeed`). A live value is written on every bus frame into
  `this._live` — free, and what the render loop already read — and anything
  visible is painted straight into the DOM by its declared `paint()`. React is
  committed to only at settle points (`{ commit: true }`): drag end, nudge,
  STOP, restore-from-saved.

  **If you add a signal handler, route it through `_liveSet`, not `setState`.**
  `__diag()` reports `commitsPerSec`, `commitMsP50` and the state keys driving
  them, plus `liveCommitsPerSec` — which should stay near zero. The cost is
  invisible parked and only bites on a moving car, so a driveway test will not
  find this for you.

  **This is not a one-off, and a "threshold" is the tell.** Within a day of
  fixing the speed signal, the SAME failure was measured again on
  `mediaPositionMs` — the media progress bar. It had a 400 ms threshold and the
  comment "keep React state in sync so re-renders don't reset the bar to 0",
  and both were wrong the same way the speed deadband was:

  - a threshold only ever protects a PAUSED player / a PARKED car. Anything
    actually running crosses it on every tick. Measured while driving: 23
    commits in a 15 s window, React back to **65.9% of wall clock** at 105 ms
    p50 / 957 ms p90.
  - the state it was "keeping in sync" was already redundant — `renderVals`
    prefers `this._mediaPositionMs` over `s.mediaPositionMs`, so a re-render
    could not have reset the bar anyway.

  Removing that one commit, measured on the car:

  | | before | after |
  |---|---|---|
  | fps | 6.66 | **11.43** |
  | React share of wall | 65.9% | **10.3%** |
  | `timerLagP50` | 49.9 ms | **6.2 ms** |
  | post-FX served from cache | 50% | **81%** |

  So: when you find a per-tick `setState` guarded by a magnitude threshold,
  the threshold is the bug, not the fix. Check whether `renderVals` already
  reads a live mirror — twice now, it did.

  **A setState callback is a root commit too.** Two bus paths still committed
  the root on every frame. `_refreshTireTelemetry` ran a plain `setState` per
  `tpms_status` or tyre-warning frame, and `_commitNavUi` passed
  `_syncDockIndicators` as a callback with each guidance update (about 1 Hz).
  `_uiOnlySetState` sends any update that has a callback to the root path. The
  callback was redundant: both the root's and the desktop stage's
  `componentDidUpdate` already sync the dock. Both paths now go through
  `_uiOnlySetState` with no callback, so they repaint only the desktop stage, are
  dropped during a slide (teardown refreshes), and fall back to the root only
  while a popup or editor is open. Tyre frames also coalesce into one trailing
  refresh 250 ms later, because `tpms_status` and its warning keys arrive
  together. Nothing in the 3D scene reads tyre data.

  Measured in headless Chrome, not on the car, with interleaved A/B/A/B arms at
  4x CPU throttle and simulated traffic of 60 signals/s:

  | | before | after |
  |---|---|---|
  | root commits/s, mixed bus incl. `tpms_status` | 4.78 / 4.78 | **0.30 / 0.30** |
  | script time per 10 s window | 1.12 / 0.82 s | 0.83 / 0.57 s |
  | root commits/s from guidance, navigation widget placed | 0.9 / 0.9 | **0** |

  The 0.30 that remains is start-up `lights`/`splashActive` commits, present in
  both arms. The simulated tyre value changed on every frame, which is harsher
  than a real tyre. The real `tpms_status` rate while driving has not been
  measured, so the effect on the car's frame rate is unknown. What the table
  does establish is the count: one ~197 ms root commit per tyre frame, and one
  per second of guidance, are gone. `test-bus-widget-refresh.mjs` checks the
  coalescing, the stage-only path, the slide drop, unmount and the dock sync,
  with negative controls.

  The same traffic on an emulator with a navigation widget placed: Android 16 and
  WebView 133, 4 vCPU and 4 GB at 1920x720, the emulator process held on the host's
  efficiency cores, and the DPR ladder pinned. Debug APKs, a warm-up run discarded,
  then A/B/A/B:

  | | before | after |
  |---|---|---|
  | root commits/s | 4.8 / 5.1 | **0.6 / 0.4** |
  | share of wall clock in commits | 21.3% / 19.0% | **2.8% / 1.5%** |
  | fps | 41.4 / 48.3 | 46.3 / 50.2 |
  | `timerLagP90` | 40.6 / 33.8 ms | 23.9 / 18.7 ms |
  | signals the page kept up with in 10 s | 346 / 376 | 411 / 468 |

  The injector runs on the page's main thread, so the busier arm received fewer
  signals and still committed more. The Android 9 emulator image (WebView 66)
  cannot run the viewer at all (`String.prototype.matchAll` needs Chrome 73), so it
  could not be used.

  On the car (WebView 91), parked, these two paths could not be exercised.
  `tpms_status` arrived in 1 of 21 ten-second windows, and there was no guidance,
  so the tyre and navigation A/B still needs a drive. The native batching
  (`TelemetryBatch`, see [android-shell](../architecture/android-shell.md)) did show
  on the parked bus: a burst of 141 broadcasts reached the page in one script
  execution, where each would have been its own `evaluateJavascript`. A cold-start
  A/B with and without the batching, three interleaved pairs on a rested unit,
  showed no measurable change: `viewerReady` at 10.53 s against 10.60 s (medians),
  with pairs of both signs, and the same intro frame rate (26-27 fps).

- **A hidden WebView is still on the panel, and its render loop is dead.** The
  loop is driven only by `requestAnimationFrame`. When another app takes window
  focus — a freeform app in a launcher slot, the OEM scene manager — Android
  marks our window not-visible, the WebView sets `document.visibilityState =
  'hidden'`, and **Chromium stops servicing rAF entirely and clamps timers to
  1 Hz**. Measured on the car: `bareRafPerSec: 0`, `timerLagP50: 999.7 ms`,
  while the car was plainly still drawn on screen.

  This reads exactly like a rendering problem and is not one. `__diag()` reports
  `visibility` and `rafAlive` so it can be told apart in one look. Note
  `applyLauncherFocusPolicy()` (MainActivity) *deliberately* drops focus while a
  freeform overlay is open, so this is reachable by design, not only by
  accident. `componentDidUpdate` already documents the same hazard for the
  splash hand-off, which is why that path has a timeout floor rather than
  trusting rAF.

- **`resScaleMode: 'off'` does not stop everything adapting.** It stops the
  MOTION tier reducing, but `_maybeAdaptDPR` still walks `_dprIdx` on its own,
  and every step calls `_onResize()`. `'max'` is the mode that pins the ladder
  outright — reach for it when A/B-ing anything frame-rate sensitive, so the
  resolution cannot move under the measurement.
- **Resolution is tiered, not fixed.** `_setResTier` runs the ladder's step
  while the camera moves and the ladder's top step once it settles, because an
  idle scene draws nothing and a crisp frame there is free. `resScaleMode`
  (OFF / strong / lite / on) gates it; **OFF is the default** and means never
  reduce.
- **Tier switches call `_onResize()`**, which reallocates every render target.
  They are debounced (90 ms to enter motion, 220 ms to refine) for that reason.
  Do not switch resolution per frame.
- **The blur-sprite capture is expensive and debounced.**
  `_generateBlurredWheelTexture` measured **62 ms/call on the emulator** (so
  likely 150-250 ms on the MMI) and the wheel colour / roughness / metalness
  sliders used to call it per `input` event. It now goes through
  `_requestBlurCapture`, a trailing debounce (`_wheelSpinTune.captureDebounceMs`,
  200 ms) — same "cheap work immediate, expensive refresh deferred, final one
  guaranteed" shape as `_requestTweenShadow` / `_settleTweenShadow`. The
  material change itself is still immediate. Anything that must be exact right
  now (rim swap, wheel size, HDRI, night mode, `__wheelSpin` arc retune) still
  calls the capture directly, and that call cancels any queued trailing one.
- **`shadowMap.autoUpdate` is off.** Shadows refresh only on explicit
  `needsUpdate`. During body tweens that is throttled to every 3rd frame with a
  guaranteed final update — see `_requestTweenShadow` / `_settleTweenShadow`.
- **The HDRI on the boot path must be packaged, not fetched.** A remote HDRI
  measured 5-10 s from the car and fails outright offline.
- **Textures are KTX2/UASTC** and transcode to ASTC on the Adreno 640. This grew
  the models roughly 3x on disk (HEV 11.3 -> 34.6 MB) in exchange for 4 MB of
  VRAM per 2048 map instead of 16 MB.

## Cold start: the download is not I/O bound, it is React bound

**Measured 2026-09-11.** A cold boot had drifted to ~29 s against a splash clip
that holds at ~18-22 s, i.e. the car arrived AFTER its own intro. Almost all of
it was one line.

The boot GLB is 13.5 MB. Its fetch measured **14.4-15.0 s** during boot, and
**0.36 s** when the same file was re-fetched from the idle, already-booted app.
So it was never I/O: the APK asset read runs at ~40 MB/s.

The cause was the loader's `onProgress`, which called
`setState({ progress, processing })` on every XHR chunk. A commit here costs
~197 ms (see the `_live` seam section above), and the damage is not just the
commit time — **each commit hands the main thread back mid-transfer**, so the
transfer itself stretches. Interleaved A/B on the car, same file, same page:

| | run 1 | run 2 | run 3 | progress events |
|---|---|---|---|---|
| plain XHR | 405 ms | 695 ms | 726 ms | 9-14 |
| + `setState` per chunk | 8811 ms | 10053 ms | 9636 ms | 91-97 |

Repeated later on a hot device: 758/1492/972 against 15432/18896/18432. Every
pair the same sign; the ratio is 13-19x. Note the **event count** is itself the
tell — yielding per chunk makes Chromium deliver ~7x more, smaller chunks.

**Nothing about that readout needed React.** On Android the percent is drawn
NATIVELY (`AppLauncherBridge.setBootProgress` — the splash `<video>`
hole-punches through the page, so the HTML loader is not even on screen), and
the HTML fallback is one bar's width. It now goes through the `_live` seam like
every other hot signal: `_setLoadProgress` → `_liveSet('progress')` → a `paint`
that writes the bar and calls `_syncBootHud`. React sees progress only at the
settle points that already commit. Measured after: **70 HUD paints, 1 React
commit** for a whole boot, against ~95 commits before.

`_loadProgressPct()` / `_loadProcessing()` are the live readers, and
`renderVals` uses them — same lesson as `mediaPositionMs`: if a re-render from
some other cause reads `s.progress`, the bar snaps back.

### The transfer also started 4 s late

Even fixed, the GLB request could not be issued until three.js and GLTFLoader
were on the page (~4.8 s), with the network idle until then. A `<head>` script
(`window.__bootGlb`) now starts the fetch at ~0.9 s and the boot loader calls
`loader.parse()` on **its buffer** rather than requesting the file again —
WebViewAssetLoader sends no caching headers, so a second request is not
guaranteed to be served from memory. Two things that matter:

- the preload resolves the body the same way `_resolvedBootVariant()` does
  (only a GT trim/variant is not the HEV body). A disagreement is not a bug:
  the URLs will not match, and the loader falls through to its own fetch.
- it reports bytes as **plain numbers** that the app samples on a 120 ms timer.
  A per-chunk callback into the app is the exact regression above.

### Numbers, and why they need pairing

Interleaved before/after APK installs, three pairs, ready-to-drive in ms:

| pair | before | after |
|---|---|---|
| 1 | 38662 | 27735 |
| 2 | 46832 | 16111 |
| 3 | 32266 | 18046 |

On a rested unit the same build boots in **14.6-14.9 s**; the table above was
taken after ~15 consecutive cold starts had pushed the MMI to 5.6/6.4 GB used
with 223 MB swapped, and EVERYTHING slowed with it — the loader `<script>`s
went from ~120 ms each to ~1.3 s. So: **a cold-start number taken after a
string of restarts is measuring the memory pressure you just created.** Reboot
or rest the unit before quoting an absolute, and compare arms in PAIRS
regardless.

`scripts/device-cdp.mjs` cannot see this on its own. What found it was a CDP
probe installed with `Page.addScriptToEvaluateOnNewDocument` (NOT a plain
evaluate — the WebView navigates after attach and wipes anything you injected
into the first document), recording `performance.getEntriesByType('resource')`
plus a `longtask` observer and a poll for `__app._viewerReady`.

## Post-FX rebuilds while the camera moves

Measured on the car, camera orbiting (`camOrbitEnabled`), per frame: the scene was
rendered three times, once for the frame and once inside each of the bloom and
streak composers, for ~14 ms of the ~15 ms submit. `gl.finish()` stayed at 0.1 ms,
so this is CPU-side again. A frame just over 16.7 ms lands on every other vsync,
which is why the orbit sat at ~28-30 fps with the main thread still ~37% idle.

The orbit dirties post-FX on every frame, so it rebuilt on every frame.
`_postFxRebuildNow` now rebuilds on one frame in three (`POSTFX_MOTION_EVERY`)
while `cameraBusy`, and reuses the cached overlay quads in between. A skipped
frame keeps `_postFxDirty` set and queues one more frame with
`requestRender(1, true)`. So the first frame after the camera settles always
rebuilds: the resting glow is exact, and only a moving one can trail by up to two
frames. With the camera still, nothing changed.

Same car, same scene, the pre-change rule swapped in at runtime for arm A, 8 s
windows, eight valid interleaved pairs (two windows dropped: another app hid the
WebView):

| | before | after | pairs with that sign |
|---|---|---|---|
| fps, camera orbiting | 29.8 | **35.5** | 8/8 |
| post-FX rebuilds/s | 30.0 | **12.1** | 8/8 |
| script time, ms per s | 456 | **385** | 8/8 |
| `timerLagP90` | 19.9 ms | **16.6 ms** | 8/8 |
| submit | 13.5 ms | 10.1 ms | 6/8 |

It does not reach 60 fps: the main pass alone is ~4.7 ms of JS and the frame still
crosses a vsync. The next levers are a bloom pass that does not re-render the
whole scene, and fewer triangles.

On r137, three.js draws a transparent `DoubleSide` material twice per render and
sets `needsUpdate` on each pass, which re-derives its program parameters on every
frame. Drawing those meshes single-sided saved 7 draw calls and about 1 fps,
within noise, so it was not shipped. `test-postfx-motion-throttle.mjs` checks the
one-in-three cadence, the queued settle frame, the still camera and the loop
wiring, with negative controls.

## Anything Hz-denominated must run off the wall clock

`_tickPowertrainFx` accumulated the render loop's `_dt`, which was clamped to
`[8 ms, 50 ms]`. Below 20 fps — most of this panel's range — the clock advanced
50 ms per frame while real time advanced 200+, so `cellEdgeHz: 7.0` was not
7 Hz, and the rate it actually ran at moved with the frame rate. The symptom is
"the blink is tied to fps, not time", and it applies to every Hz-denominated
animation behind that clock. It now derives from `now` directly, seeded so the
phase does not jump.

Watch for the same shape anywhere a per-frame delta is clamped "for safety":
the clamp is correct for tweens and physics and wrong for anything periodic.

## Things that look like wins and are not

- **More MSAA.** `MAX_SAMPLES` is 4 on this GPU. You are already there.
- **FXAA.** The main scene renders straight to the default framebuffer, where
  the context grants real 4x MSAA. Routing through a render target to run FXAA
  forfeits that MSAA for an edge-detect blur. Strictly worse here.
- **Intermediate DPR rungs.** 0.85 measured 20.4 fps against 1.0 at 20.2 —
  within noise of each other, for visibly worse output. The ladder is
  deliberately two-state: `[0.75, 1.0]`.
- **Per-object motion blur.** A velocity pass plus a multi-tap resolve would
  cost an estimated 8-15 ms against a ~36 ms budget. The pre-baked wheel blur
  sprite is the cheap equivalent: 8 discs, 256 triangles total, against 374k in
  the main pass.


## Desktop switch update scheduling

Native card synchronization is scheduled after a React commit, rather than
performed inside `renderVals`. Calls in the same task share one pending timer;
the flush reads the latest state. While a desktop slide is active, the timer
waits until teardown before constructing card views and crossing the native
bridge. This does not delay vehicle commands or their pending-state guards.

Widget field builders also reuse each visited desktop's last fields during a
slide. Teardown invalidates the active board and recomputes current values.
First visits still build fields, and deleted desktops evict their field cache.

`test-desktop-update-cost.mjs` checks grouped calls, latest-state delivery,
slide deferral, unmount, render-side effects and field reuse, with negative
controls. Native cards retain their fade; React still renders the root shell.

A runtime A/B/B/A comparison on the car (same warmed desktop transition,
three-second windows) measured total card-data preparation at 54/21/16/20 ms,
respectively: lower in both paired comparisons. Frame p95 was
150/18/83/50 ms and maximum gaps were 367/483/366/183 ms. Frame pacing did
not improve consistently; these changes remove redundant work, but do not
establish that the remaining stalls are fixed. The comparison used temporary
method wrappers for scheduling and field reuse; it was not a React-removal test.


### Hidden panel renders and drag invalidations

Settings, desktop management, wallpaper selection and the focused-card popup
now use the same memoized-template mechanism as desktop boards. Closed panels
retain their DOM and last view. Closing updates the visibility class without
removing their contents, preserving CSS exit transitions; reopening recomputes
the current fields. Closed Studio thumbnail, accent and clock galleries are
not rebuilt. Hidden panel clocks and imperative widget painters are excluded
until the panel opens again.

During a desktop gesture, `_uiOnlySetState` drops only callback-free updates
whose sole key is `widgetRev`. This key requests a redraw, not a vehicle-state
change; slide teardown already refreshes the latest live data. Vehicle values,
commands, acknowledgements and updates with commit callbacks are unchanged.
`test-hidden-panels.mjs` verifies closing contents, memo identity, reopening and
visual-only deferral, with negative controls.

A controlled A/B/B/A car test bypassed panel memoization and redraw deferral
in A, then enabled both in B. Each three-second window included a 500 ms
desktop drag with simulated widget-redraw requests every 80 ms. Drag renders
were 6/0/0/3 and total renders 8/2/2/6. Maximum frame intervals were
400/117/100/234 ms; full-window p95 was 33/33/17/83 ms. Both paired maximum
gaps improved, but pauses remain and the short drag p95 was mixed. This
is a controlled invalidation test, not a claim that all real swipes are smooth
or a comparison with React removed.


### Independent desktop widget updates

The desktop pages now live in a child React component using the existing
memoized board template. Callback-free `widgetRev` invalidations refresh that
child's widget fields and paint lifecycle without calling root `renderVals`.
During a slide these redraw requests still wait for teardown. Changes to
vehicle state, theme, layout and open panels use the root lifecycle. Popup,
configuration, widget-editing and apps-only modes deliberately use the full
render so their controls cannot show stale fields.

A new parent view takes precedence over previously computed child fields.
The child refreshes climate mounts and native-card synchronization after its
own commits; parent commits keep the original root lifecycle. Widget-only
updates do not schedule desktop persistence or recompute closed-panel fields.
`test-desktop-stage.mjs` checks independent refresh, parent invalidation,
popup/theme/vehicle-state/callback fallback and unmount, with negative controls.

A controlled three-second A/B/B/A car test requested widget redraws every
250 ms. A forced full-root updates; B used the independent desktop stage.
Root renders were 11/0/0/11; child-only paints were 0/11/11/0. Long tasks
above 50 ms were 5/0/0/1. Frame p95 was 34/33/33/33 ms, so this establishes
less JavaScript work, not elimination of all frame gaps.

The same run separately compared normal widget backdrop blur with a temporary
CSS override disabling it during the same warmed desktop swipe, in A/B/B/A
order. The WebView supported blur and the active cards used
`blur(14px) saturate(1.18)`. Frame p95 was 67/68/52/67 ms and maximum gaps
183/867/133/183 ms. The benefit did not repeat consistently, so blur remains
enabled; the temporary stylesheet was removed. The desktop still has one
root commit at the switch and other remaining native/compositing costs.


### Native bottom-card reuse across desktop themes

The Android bottom rail reuses card views by card ID when normal desktop
configuration changes. Workspace stays attached; matching cards keep their
views, new cards are constructed once, and removed cards remain in a cache
bounded to 16 identities (the same maximum as the parsed payload). Reordering
moves existing views instead of rebuilding all cards. Clock face options and
live values update the existing custom views through `setDescriptor`.

Title or action changes, duplicate IDs, cache overflow and rail editing fall
back to the original rebuild path so listeners and edit controls remain correct.
Color/accent changes retint existing surfaces, text and media controls and
invalidate custom graphics; they no longer force an entire row rebuild.
Unchanged theme signatures skip repeated background/drawable creation on
telemetry updates. Detached card backgrounds are retinted when reattached.

`test-native-card-reuse.mjs` checks source contracts with negative controls;
JVM tests validate existing native pure logic. Android view identity and actual
rendering require the device. Debug-only `H6RailPerf` logs expose rebuilds,
retints and reused/new card counts without vehicle or user data.

An interleaved APK A/B/B/A comparison isolated native rail color updates by
sending the same card payload ten times, alternating light/dark and accent
every 350 ms. `dumpsys gfxinfo` was reset before each window. Old/new/new/old
median native frame times were 57/23/14/101 ms; p95 was 113/46/34/150 ms.
Slow-UI-frame counts were 14/11/8/13. These short windows (24–28 native frames)
show improvement in both paired color-update comparisons; they do not measure
the entire desktop swipe or prove every stall is gone.


### Unchanged native card payloads

Native graphics and clock descriptor updates compare normalized scalar and array
values before invalidating or rebuilding a clock snapshot. New descriptor objects
with identical contents keep their current drawing. The latest descriptor is
still assigned, so actions and metadata follow the current payload. Theme retints,
rendered clock face delivery, attachment and minute/second clock ticks retain
independent invalidation paths.

`test-native-card-redraw.mjs` compiles the actual pure descriptor class and checks
all 55 fields independently, equal distinct objects and null descriptors. Negative
controls remove a field comparison and each view guard; clock tick and refresh
contracts remain checked. This test needs a JDK; the standard suite discovers it.

An interleaved old/new/new/old APK comparison repeated an unchanged native
payload 60 times at 100 ms intervals. Native frame p95 was 42/53/38/22 ms;
frame counts were 390/393/411/61 and slow-UI counts 21/42/12/11. There was
no repeatable frame-time improvement, and the last arm had substantially less
frame activity, so these windows cannot establish an FPS benefit. The tested
benefit is skipping descriptor-triggered invalidation and clock snapshot work
for equal payloads; real desktop-swipe smoothness still requires evaluation.


### Desktop fade without inherited animation state

Desktop fades use the `hv-desk-faded` body class and the existing CSS opacity
transitions. The former `--hv-desk-fade` custom property was inherited throughout
the document, including retained desktop widgets. Each endpoint change caused
broad style recalculation even though only the shell chrome consumed the value.
The class targets that chrome directly. Slide and cancellation keep their
160/220 ms transitions; teardown removes the fade state.

A four-switch car trace measured 782 ms total in style recalculation, with a
69 ms maximum, while individual root React renders took 29–32 ms. An isolated
runtime A/B/B/A comparison of the old property and new class measured style
totals of 157/83/82/141 ms per programmatic switch. Maximum frame intervals were
150/67/67/135 ms. All runs used warmed desktop pages; an earlier run with the
WebView hidden behind another app was discarded.

A second A/B/B/A comparison included a 500 ms simulated drag in each direction.
Leftward maximum frame intervals were 167/117/132/150 ms; rightward intervals
were 133/83/83/133 ms. All four paired maxima improved. Style totals and frame
p95 did not improve in every drag pair, and these are short controlled runs,
not a claim of continuous 60 fps. Separate brightness-cache and viewport-read
experiments did not show consistent frame gains and were not shipped.

`test-desktop-slide.mjs` checks fade endpoints, cancellation/restoration and
teardown without inherited style writes, plus retained CSS transitions, with
negative controls. Device verification remains necessary for frame pacing.


### Open popups and the widget loop

Three paths kept doing work while nothing on screen needed it.

- **POWER popup.** An open popup sits over the board, so `_canRefreshDesktopStage()`
  is false. Every `widgetRev` bump from `_commitPowerRefresh` (4 Hz from the bus) and
  from the 1 Hz history ticker was therefore a root commit, for as long as the popup
  stayed open. The popup is a `.hv-power` card, so `_updatePowerCards` already paints
  its readings, and it now also paints the stage label and the peak. `_refreshPowerPopup`
  commits only when what a render draws changes: the chart's data-or-placeholder
  `sc-if`, its caption, or the DEMO badge. `_focusedCardRenderFields` records the last
  shape that was drawn.
- **Roof popup.** `_updateHotspotLayer` runs on every loop tick while the roof popup
  is open, and it ended with `requestRender(2)`. The scene and post-FX redrew every
  frame until the popup closed. The popup is fixed DOM and follows nothing in 3D.
  Opening, camera input, camera tweens, the roof tweens and resize request their own
  frames.
- **Widget loop.** `_drawGraphs` derived consumption and smoothed the series on every
  frame before it checked for a graph canvas, so a board with POWER but no graph paid
  for a series that nothing read. Sampling now runs only while a graph canvas is
  mounted, and still runs every frame then, because the EMA and the 40 ms bucket are
  tuned to it. A graph on a desktop not visited yet therefore starts its history at
  the first visit. RANGE no longer keeps the loop alive: since `f471c24` it reads
  `_rangeTelemetry`, not `_graphNow`, which was written and never read and is gone.

Measured on the HavalPerf emulator (Android 16, WebView 133, 4 vCPU, 4 GB,
1920x720), not on the car. Debug APKs, DPR ladder pinned, 10 s windows, a warm-up arm
discarded, three interleaved A/B pairs. The table shows the median of each arm:

| Scenario | Metric | before | after | same sign in every pair |
|---|---|---|---|---|
| POWER popup open, bus traffic | root commits/s | 3.25 | **0.7** | yes |
| | share of wall clock in commits | 38.6% | **8.4%** | yes |
| | main-thread task time, ms per s | 835 | **712** | yes |
| | `timerLagP90` | 102 ms | **66.5 ms** | yes |
| Roof popup open, camera still | rendered fps | 21.6 | **2** (idle) | yes |
| | main-thread task time, ms per s | 819 | **555** | yes |
| | `timerLagP90` | 44.7 ms | **17.9 ms** | yes |
| RANGE-only board | widget loop calls/s | 25.5 | **0** | yes |
| | widget loop time, ms per s | 6.2 | **0** | yes |

About 0.7-0.8 root commits/s remain in every scenario and in both builds. They come
from an unrelated background commit. The POWER popup's frame rate did not change
consistently, and its painter costs slightly more per call (about 4.7 to 5.3 ms),
because it now does the caption and peak work the root commits used to do. On a board
with both POWER and RANGE, skipping graph sampling showed no measurable effect: it
removes work that nothing reads, without a measured gain.
`test-power-popup-refresh.mjs`, `test-roof-popup-render.mjs` and
`test-graph-loop-cadence.mjs` check each path, with negative controls.

Measured on the car (WebView 91), parked, with one installed build and the pre-change
method bodies swapped in at runtime for arm A, so the arms alternate every window
without a reload (the method-wrapper approach above). 10 s windows, DPR ladder pinned,
six interleaved pairs per scenario, median of each arm:

| Scenario | Metric | before | after | same sign in every pair |
|---|---|---|---|---|
| POWER popup open, the car's own parked bus | root commits/s | 1.45 | **0** | 6/6 |
| | script time, ms per s | 581 | **502** | 6/6 |
| POWER popup open, plus power traffic at driving rates | root commits/s | 3.88 | **0** | 6/6 |
| | share of wall clock in commits | 27.5% | **0%** | 6/6 |
| | fps | 14.7 | **17.3** | 6/6 |
| | `timerLagP90` | 32.5 ms | **25.1 ms** | 6/6 |
| | main-thread task time, ms per s | 721 | **582** | 6/6 |
| Roof popup open, camera still | rendered fps | 26.0 | **0** (idle) | 6/6 |
| | main-thread task time, ms per s | 518 | **314** | 6/6 |
| | `timerLagP90` | 20.8 ms | **7.9 ms** | 6/6 |
| RANGE-only board, camera still | widget loop calls/s | 60.1 | **0** | 6/6 |
| | main-thread task time, ms per s | 235 | **204** | 5 of 6 |

**The camera orbit hides two of these.** With `camOrbitEnabled` on, the scene renders
every frame anyway and `_applyGraphLoopGate` holds the widget loop while the camera
moves. So with the orbit on, the roof and RANGE changes measured no difference
(both arms about 26-28 fps, widget loop 0 in both). "Camera still" holds
`_syncCamOrbit` off for the run only. The POWER popup result does not depend on it:
it was measured with the orbit on.

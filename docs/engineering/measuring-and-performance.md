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
| All post-FX (bloom + streak) | +2.0 ms/frame |
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

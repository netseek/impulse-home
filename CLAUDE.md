# Haval H6 3D — working notes for agents

The viewer runs in the head unit's system WebView on a **Snapdragon SA8155 /
Adreno 640**, WebView 91, driving a 1920x720 panel. Everything below was
**measured on that hardware**, not assumed. Please keep it that way: this file
exists because several rounds of plausible-sounding optimisation were undone by
a single measurement.

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

`window.__perf()` exposes `fps`, `submitMs`, `cadenceMs`, `drawCalls`,
`triangles`, `resTier`, `busPerSec`, `speedPerSec`, `rendersPerSec`. A debug
build ships it to logcat every 2 s:

```bash
adb logcat -s H6Perf
```

For A/B work, drive the camera from devtools over CDP rather than by hand.
The harness used for every number in this file is in the scratchpad pattern:
forward `webview_devtools_remote_<pid>`, then `Runtime.evaluate`.

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

## Known performance characteristics

| Condition | Result |
|---|---|
| DPR 0.75 (motion tier) | ~22 fps |
| DPR 1.0 native | ~14 fps (2.04x pixels, +26 ms/frame) |
| All post-FX (bloom + streak) | +2.0 ms/frame |
| MSAA | 4x, and `MAX_SAMPLES` is 4 — already at the driver ceiling |
| Main pass | ~210 draw calls, ~374k triangles |

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
- **Resolution is tiered, not fixed.** `_setResTier` runs the ladder's step
  while the camera moves and the ladder's top step once it settles, because an
  idle scene draws nothing and a crisp frame there is free. `resScaleMode`
  (OFF / strong / lite / on) gates it; **OFF is the default** and means never
  reduce.
- **Tier switches call `_onResize()`**, which reallocates every render target.
  They are debounced (90 ms to enter motion, 220 ms to refine) for that reason.
  Do not switch resolution per frame.
- **`shadowMap.autoUpdate` is off.** Shadows refresh only on explicit
  `needsUpdate`. During body tweens that is throttled to every 3rd frame with a
  guaranteed final update — see `_requestTweenShadow` / `_settleTweenShadow`.
- **The HDRI on the boot path must be packaged, not fetched.** A remote HDRI
  measured 5-10 s from the car and fails outright offline.
- **Textures are KTX2/UASTC** and transcode to ASTC on the Adreno 640. This grew
  the models roughly 3x on disk (HEV 11.3 -> 34.6 MB) in exchange for 4 MB of
  VRAM per 2048 map instead of 16 MB.

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

## Visual gotchas in the wheel blur

The spin sprite has burned two separate agents. Both failures were in the
capture shader, not in the placement:

- **Alpha IS the mask.** Transparent spoke gaps contribute `(0,0,0,0)` to the
  angular average, so the result is already premultiplied by coverage and the
  alpha channel is the holes in the rim. Un-premultiplying (`rgb / a`) is
  unbounded as coverage approaches zero and blows the colour out to white;
  boosting alpha (`pow(a,0.6)*1.5`) fills the holes in. Together they turn a
  dark translucent blur into a pale opaque disc. Keep `gl_FragColor =
  color / float(SAMPLES)`.
- **A full 2*PI sweep is axisymmetric**, so the disc's rotation is invisible and
  there is no speed sensation above the cutover where the sharp spokes hide.
  `BLUR_ARC_RAD` must stay **below the rim's spoke pitch**; 60 deg suits a 5-8
  spoke wheel. Measured angular variation went from cv 0.18 to cv 0.40.
- Sweep the arc with `float(i) / float(SAMPLES - 1)`, not `/ float(SAMPLES)`,
  or the smear lands off-centre and the wheel looks subtly unbalanced.

## Media visualisers

They have **no audio input**. `_graphVizLevel` derives its level from EV power
draw (`evPowerKw`) and a playing flag; `_vizBands` and `_stepVizBars` synthesise
bass/mid/treble from `Math.sin(t * ...)`. There is no `AnalyserNode` and no
native capture. They cannot react to the music, and no amount of tuning the
paint path will change that — the paint path is already adaptive
(`minDt = fastPaint ? 16 : 33`, so visualiser panes run at 60 Hz).

Real reactivity needs `android.media.audiofx.Visualizer` on session 0, which
means `RECORD_AUDIO`, and the MMI is Android 9 so `AudioPlaybackCapture` (API
29+) is unavailable. Prototype whether session 0 yields non-silent data before
building anything on top of it.

## Device access

`adb` is not on PATH:

```
C:\Users\<user>\AppData\Local\Android\Sdk\platform-tools\adb.exe
```

The head unit's IP is DHCP and changes constantly — it moved six times in a
single session. Never hardcode it:

```bash
adb devices | awk '$2=="device" && $1 ~ /:5555$/ {print $1; exit}'
```

If nothing is listed, check `arp -a` for a live neighbour on your own subnet;
the unit sometimes reappears on a different network where ICMP passes but TCP
5555 does not. `pidof com.havalh6.viewer` can return the notification-listener
service rather than the activity — prefer
`ps -A | grep "com.havalh6.viewer$"`.

**Ask before any test that needs a human at the car.** A timed capture started
in the same message as the instruction expires before it is read, and a capture
that returns nothing is ambiguous between "signal absent" and "nobody pressed
anything".

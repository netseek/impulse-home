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
- **A change threshold is not enough on its own for speed.** `_setMotionSpeed`'s
  0.005-unit (0.2 km/h) deadband only ever protected a parked or dead-steady
  car; real driving crosses it on nearly every bus frame, so it went back to
  setState at CAN rate — and each one is `componentDidUpdate` -> `requestRender()`
  -> `_postFxDirty`, i.e. the whole React tree plus a post-FX rebuild. Simulated
  at 20 Hz on the emulator:

  | | postFX rebuilds/s | postFX cache hits/s | setState/s |
  |---|---|---|---|
  | rolling, quiet bus | 0.2 | 57.5 | 0 |
  | rolling, speed @20 Hz | 16.8 | 38.1 | 18 |
  | after the throttle | 5.0 | 54.5 | 5 |

  The wheels never needed it: the render loop reads `_liveMotionSpeed`, which is
  written exactly on every bus frame. Only the km/h label needs the state, so the
  setState is throttled to 4 Hz with a trailing commit (stops still apply
  immediately). **If you add a signal handler that calls setState, throttle it
  the same way** — the render cost is invisible parked and only bites on a
  moving car.

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
- **`shadowMap.autoUpdate` is off.** Shadows refresh only on explicit
  `needsUpdate`. During body tweens that is throttled to every 3rd frame with a
  guaranteed final update — see `_requestTweenShadow` / `_settleTweenShadow`.
- **The HDRI on the boot path must be packaged, not fetched.** A remote HDRI
  measured 5-10 s from the car and fails outright offline.
- **Textures are KTX2/UASTC** and transcode to ASTC on the Adreno 640. This grew
  the models roughly 3x on disk (HEV 11.3 -> 34.6 MB) in exchange for 4 MB of
  VRAM per 2048 map instead of 16 MB.

## Editing GLB assets

Shipped car models are tuned for **load time**, not just appearance. Before
saving any `.glb` change, preserve whatever compression and layout the file
already carries:

| File | Geometry | Textures | Notes |
|---|---|---|---|
| `assets/haval-h6-hev-lite.glb` (boot path) | raw float32, **no Draco** | KTX2/UASTC @ 1024² | Parsed ~1.7 s on the MMI |
| `assets/_source/haval-h6-hev.glb` | raw float32 | KTX2/UASTC @ 2048² | Build source for `-lite` |
| Wheels / GT | Draco or meshopt | KTX2 or JPEG | See each script's header |

**Do not round-trip material-only edits through `@gltf-transform` read/write.**
It rewrites the BIN chunk (vertex layout, bufferViews, sometimes texture
packing) even when you only change a roughness value. Measured: a gltf-transform
save of `haval-h6-hev.glb` altered 33 MB of binary payload; JSON-only surgery
changed **zero** bytes of BIN. The viewer's CPU-side vertex edits
(`_deformTireBore`, `computeVertexNormals`) assume `VertexLayout.SEPARATE`
packed arrays — gltf-transform defaults to interleaved and has rendered the
car as vertical smears before.

**Safe pattern for material / node / metadata edits:** parse the GLB JSON chunk,
patch in place, copy the BIN chunk through unchanged. See
`scripts/patch-hev-trunk-materials.mjs`, `scripts/build-lite-textures.mjs`, and
`scripts/build-ktx2-textures.mjs` (which document the same constraint).

**Rebuild order when textures or resolution change:**

1. `npm run build:lite-textures` — downscale JPEG sources (`sharp`; cannot read KTX2)
2. `npm run build:ktx2-textures` — UASTC encode (idempotent; skips already-KTX2)
3. `npm run build:fast-models` — strip Draco from boot-critical `-lite` only

Never save a boot-critical model with Draco re-introduced, and never replace KTX2
with raw JPEG in the shipped `-lite` file without re-measuring cold start.

## Standalone body loading

**Goal:** boot or toggle to GT and fetch **only that body's `-lite` GLB**
plus the small catalog rim — no silent second body.

**`?model=gt`** enables standalone GT boot (dev / pre-car-input). The app loads
`haval-h6-gt-lite.glb` + `HavalGT-wheel.glb` only — no HEV donor preload.
Native GT tire clusters and calipers are classified in `_buildGtNativeWheelCorners`.

**Saved-settings GT** (localStorage, no URL) still preloads HEV as donor until
car-side trim input replaces the URL preset.

**HEV/PHEV** (`?model=phev34` etc.) were already standalone: one `-lite` body +
stock rim GLB. Embedded tires/brakes stay; stock *rims* were stripped from the
body GLB long ago.

**When HEV is already loaded** (HEV boot → MODEL toggle to GT), GT still inherits
HEV wheel pivots via `inheritWheelsFrom: 'hev'` — no second download.

Hook points: `_gtBootStandalone()`, `_gtUsesNativeWheels()`, `_whenHevDonorReady`.

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

## Wheel spin is a sampling problem, not a throughput one

A rendered wheel is a strobe. The eye only ever sees it at the frame rate, so
if the rim advances close to one whole repeat of its own pattern between two
frames it looks **frozen**, and past half a repeat it looks like it is turning
**backwards**. On a panel that swings between 14 and 22 fps the apparent rate —
and the apparent *direction* — swing with it. That is the whole "the wheel
doesn't spin / seems to struggle" symptom.

It cannot be fixed by making the frame cheaper, and it cannot be fixed by moving
the transform to the GPU: the GPU renders the same discrete frames. Measured,
`_applyWheelTransforms` costs **0.05 ms/call for 52 meshes** (desktop; not yet
re-measured on the car) — about 0.14% of a 36 ms frame. The post-FX passes cost
2.0 ms and were still not worth rewriting, so this is nowhere near worth moving.

The fix is to keep the per-frame step under the rim's own pitch:

- `_measureRimRepeatRad` measures each rim's **rotational repeat** — the finest
  rotation that leaves it looking identical — from the real installed geometry,
  once per rim swap (cached on mesh identity, because the capture also runs on
  every colour/roughness slider event).
- The loop clamps the per-frame roll to `stepFrac` (0.45) of that period. The
  clamp uses the **actual** frame interval, not a smoothed one: aliasing is
  decided by the real gap between the two frames the eye sees.
- `_blurSpeedWindow` starts the blur sprite at the speed where that rim begins
  to alias at the frame rate we are actually getting, and finishes it 1.7x
  later. All of it is live-tunable from devtools via `window.__wheelSpin()`.

### Measured repeat periods

`npm run analyze:rims` dumps the table for the whole catalogue. The spread is
the point — one fixed 30 -> 60 km/h crossover could never have suited all of it:

| Rim | Repeat | Aliases from (18 fps) |
|---|---|---|
| Haval HEV / PHEV / GT, most 5-spokes | 72 deg | ~27 km/h |
| Vossen VFS1 / VFS4, VPS 310T | 36 deg | ~13 km/h |
| Concept L | 20 deg | ~7 km/h |
| Forgiato Multato | 12 deg | ~4.5 km/h |

The stock rims were only marginally affected — they alias at ~27 and the blur
used to start at 30 — which is why this looked like an occasional glitch rather
than a systematic one. The fine-patterned rims spent most of their speed range
visibly stuck.

## A swapped rim sitting low in its tyre is a ride-height frame bug

`_applyWheelTransforms` counter-shifts the wheels by `-carHeightOffset` so they
stay grounded when the body moves. That only cancels against a rest pose
captured at the SAME ride height. The stock meshes cache theirs in `cacheInit`
at whatever height the app booted with; the custom rim clones were compensating
by the ABSOLUTE `carHeightOffset`, i.e. assuming the stock capture was at zero.

Boot with a saved ride height and the two frames differ by exactly that height,
so every swapped rim sits displaced by it — permanently, at every later height
setting, on every rim, from every camera angle. Measured on the emulator:
**-29.6 mm at boot height -30 mm, 0.0 mm at boot height 0**. `cacheInit` now
records `_wheelInitHeightOffset` and the clone compensates by the delta.

**This one hides from an axle-on screenshot AND from a world-space fit.** It
cost several rounds of wrong answers: a head-on capture makes it look mild, a
circle fit through a whole tyre mesh reports the wrong centre (that mesh spans
tread, sidewalls and bore — an algebraic fit over a wide radial band is biased),
and a brightness centroid measures the highlight, not the geometry. What finally
worked, and what to reach for first: **extent (min/max) of the rim and of the
tyre in the plane perpendicular to the axle**, compared to each other. No
fitting, no rendering, no lighting. Four metrics agreed on it to 0.1 mm.

## Unbalanced-looking rims are a normalisation bug

A swapped rim is centred on its **bounding box** and aligned by comparing bbox
dimensions. Both are proxies, and a GLB carrying anything off-axis (a caliper, a
stray tab, an asymmetric hub) drags both off the rim's real axis of revolution.
The rim then spins about a tilted, offset line and visibly wobbles — a 2 deg
tilt throws a 0.35 m lip a centimetre in and out per turn.

`_handleUploadedWheelModel` step 7b now measures this the way a wheel shop does:
the **first harmonic of the outer lip**. Fit `z ~ z0 + A*cos(t) + B*sin(t)` over
the lip band — a seated wheel has A = B = 0, and a tilt is exactly this
once-per-revolution term. The fitted plane's normal is the true axis; the same
fit on the lip's radius gives the centre offset. Corrected, then renormalised so
the native radius stays exactly 1.0 as everything downstream assumes.

**The lip band has to be found by iterating, and getting this wrong is worse
than not correcting at all.** The band can only be selected by radius, and
radius is measured from whatever centre you currently believe. On an off-centre
model the first selection is not a ring — it is the far-side arc — and a
first-harmonic fit over an arc is ill-conditioned and returns nonsense. Shipped
once as a single-shot fit, it read a spurious offset on the BMW 19" rim (whose
bbox is dragged up by a badge mesh floating above the hub) and "corrected" it by
visibly dropping the rim in the tyre at every wheel size — a rim that was fine
as authored. Re-selecting the band around each new estimate fixes it; it
converges in a few passes, because every correction makes the band more like a
full ring.

**Check this one from PIXELS, not from a world-space fit.** Verifying it by
fitting circles to `matrixWorld`-transformed vertices gave the same wrong answer
for both the broken and the fixed build, and only a head-on screenshot of the
two side by side settled it. `scripts/device-cdp.mjs` will frame and grab one:
point the camera down the axle from `_pivot`, and compare the black tyre band
above the rim against the band below.

Measured tilt, bbox axle vs true axis: under 0.5 deg for most of the catalogue,
but **11.3 deg on Vorsteiner V-FF109**, 3.9 on Vossen VPS 310T, 3.7 on VPS
315T, 3.5 on Forgiato Multato, 2.8 on VFS4, 1.6 on HF-5, 1.1 on Rotiform KPS. Those are exactly the rims that read as
unbalanced. Corrections under 0.5 deg are skipped and over 20 deg are refused —
past that the bbox axle was probably not the axle at all.

**And it is currently OFF (`_wheelSpinTune.runoutFix = false`), because it made
every rim it touched WORSE.** Residual lateral runout of the *installed* rim
about the axis it actually spins on, correction on vs off:

| rim | on | off |
|---|---|---|
| Vorsteiner V-FF109 | 19.98 deg | 9.47 |
| Forgiato Multato | 24.47 deg | 5.04 |
| Vossen VPS 310T | 13.50 deg | 1.97 |
| Vossen HF-5 | 3.34 deg | 1.34 |
| Rotiform KPS | 1.76 deg | 1.00 |

Every rim it leaves alone sits at 0.00-0.21 deg, so the rims it fires on are the
only unbalanced ones — and it is the cause, not the cure. The MEASUREMENT that
finds the tilt is sound (it agrees with an independent offline fit in
`scripts/analyze-rim-symmetry.mjs`); the bug is in turning that measurement into
a rotation — wrong direction or wrong magnitude. Do not switch it back on
without re-running that table.

## The sprite is a flat disc pretending to be a wheel — its frame matters

Four separate bugs came out of how the four discs are positioned and oriented.
All of them looked like "the animation is broken" and none of them were in the
blur maths.

- **`depthTest` must stay ON** (`depthWrite` stays off). With it off the sprite
  ignores the depth buffer and paints over whatever is in front of it — the
  far-side wheels drew on top of the bonnet and the door, and the far sprite
  showed through the near one as a "ghost disc" that cost a long detour to
  diagnose.
- **Seat the disc at the RIM's face, not the tyre's.** It used to target the
  tyre's bore-edge face minus a 4 mm recess, which put it 8.4 mm further in than
  the rim it stands in for (measured: rim face 148.4 mm outward, tyre 144.0,
  sprite 140.0). Harmless while `depthTest` was off; the moment depth testing
  went on, the tyre's bore lip occluded the sprite's outer edge and the rim's
  border vanished at speed. It now measures the rim's own outward face in the
  same radial band and sits 0.5 mm proud of it.
- **Build the disc's frame explicitly: +Z outward, +Y world up.**
  `setFromUnitVectors` returns the MINIMAL rotation, and the two sides' outward
  directions are opposite, so the two minimal rotations differ by a 180-degree
  flip about an arbitrary perpendicular axis. That shipped as an upside-down
  sprite on the right-hand wheels. The left/right mirroring that remains after
  this is correct — wheels on opposite sides really do turn opposite ways seen
  from their own outside.
- **Derive the lighting layer's counter-rotation sign from geometry**
  (`normal . axle`), never from the side name. An `isRight ? -1 : 1` guess was
  wrong because all four discs are built the same way round, and it made the
  right-hand pair's lighting sweep at twice the roll rate instead of standing
  still — which reads as those wheels wobbling while the left pair looks fine.
- **`_resizeBlurredDiscs` must use the magnitude of the parent's world scale.**
  The rear suspension nodes are mirrored, so their scale is negative; dividing by
  it gave the rear discs a negative `_baseGeoRadius`, and `CircleGeometry(-0.3)`
  draws an inverted disc. A negative baseline also fails the `> 1e-6` validity
  test, so the self-heal re-fired on every capture.

## The static hub behind the sprite

The sprite's smear is semi-transparent by design (alpha IS coverage), so
whatever sits behind it shows through — and the brake and hub do not rotate,
which reads as a dead gap inside a spinning wheel. `_wheelSpinTune.hubMode`:

- **`'hide'` (default)** — hide the brake/hub meshes once the sprite is fully
  faded in, restore them as it fades out. Simplest and looks right.
- `'off'` — previous behaviour, static brake visible through the smear.
- `'capture'` — bake the brake into the sprite so it smears too. Physically the
  most honest (a rotor does spin with the wheel) but it looked bad; kept only
  for A/B.
- `'cover'` — **does not work, and the geometry says so up front.** The backing
  disc sits BEHIND the sprite, so growing it cannot hide anything the smear
  still lets through; `off` and `cover` render nearly identically. Any occluder
  has to be in front of the brake, which is what `'hide'` achieves for free.

**Do not try to roll the brake rotor while leaving the caliper still.** It is
not separable: `Break_Disks` is one mesh, one material, no geometry groups, and
its 821 verts fall into **30 connected components of which none is a full ring**
(the largest covers 74% of the circle). Rolling the whole mesh spins the
caliper, which is visibly wrong.

## The two-layer sprite: what spins and what stays put

`_wheelSpinTune.twoLayer` (on by default). The rim is captured twice — once
flat-lit (materials swapped for unlit `MeshBasicMaterial` clones carrying the
same map/colour/alpha, so coverage is byte-identical) and once under the scene.
The flat layer is smeared and SPINS; the lighting layer is a shading RATIO that
is counter-rotated in the sprite shader so it STAYS PUT. A highlight then sits
where the light is and the rim turns underneath it, which is what a reflection
does.

Measured: how much the sprite changes between roll 0 and roll 90 drops from mean
|delta| 13.35 to 2.10 on the front-left, a 84% reduction, with the spoke pattern
still changing as it should.

Three things that are easy to get wrong here, each of which shipped once:

- **Ratio, not difference.** A difference can only ADD light, so every shadowed
  region is lost — it recovered about an eighth of the shading (light mean 5.7
  against a lit mean of 44) and the sprite came back flat.
- **Divide AFTER the smear, not per tap.** The mean of a ratio is not the ratio
  of the means. Averaging `lit/flat` tap by tap gave 0.87 on the lip where the
  ratio of the smeared images is 1.11, so the sprite DARKENED the rim's outer
  border by 11-23% instead of leaving it alone. Accumulate both smears
  separately and divide once at the end; then `base * ratio` reproduces the
  smeared lit capture exactly when nothing is rotated, which is the contract the
  whole split depends on.
- **Level-match the two captures first.** Raw albedo comes out roughly twice as
  bright as the lit render (86.1 vs 44.0 mean), so an unscaled comparison is
  meaningless. `uFlatGain` is computed from a centre strip of both targets — a
  quarter of the pixels, on the readback that dominates the capture's cost.

Cost: ~30 ms/capture against ~10 ms single-layer. That is fine ONLY because
`_requestBlurCapture` debounces it — it is one capture per interaction, never
per frame. Keep it that way.

What it still cannot fix: **the capture is taken from the FRONT-LEFT wheel and
reused on all four.** The right-hand side therefore carries the left wheel's
lighting, and anchors measurably worse (roll 0->90 delta 8.55 against 2.10 on
the left). Fixing that means a second capture per side, roughly doubling the
cost.

## Measuring wheel changes without fooling yourself

This subsystem has now burned several agents, and in almost every case the wrong
answer came from a BROKEN MEASUREMENT that was believed over the user's eyes.
Reach for these in this order.

1. **Extent in the plane perpendicular to the axle.** Min/max of the rim and of
   the tyre, compared to each other. No fitting, no rendering, no lighting. This
   is the metric that finally found the 30 mm rim/tyre offset after three others
   had failed.
2. **A screenshot with the projected pivot drawn on it.** Project `_pivot`
   through the camera and draw a crosshair there; then concentricity is a thing
   you can see rather than infer.
3. **A/B the same frame with one flag flipped**, and diff the two images. Every
   real conclusion in this subsystem came from a pair of renders that differed
   in exactly one thing.

Metrics that have actively lied here, all of which looked reasonable:

- **Least-squares circle fits over a whole tyre or rim mesh.** Those meshes span
  tread, sidewalls and bore; an algebraic fit over a wide radial band is biased
  by vertex distribution. One put the front-left centre 30 mm out.
- **Single-shot fits over a "band selected by radius".** The band is selected
  from the centre you currently believe, so on an off-centre model it is an arc,
  not a ring, and the fit is ill-conditioned. Iterate the selection.
- **Brightness centroids.** They measure where the highlight is, not where the
  geometry is.
- **`gl.readPixels` on the default framebuffer** returns empty — the drawing
  buffer is not preserved. Use `adb exec-out screencap`.
- **`cmp index.html <packaged asset>`** always differs: the build strips
  comments (21152 lines becomes 18991). It is not a staleness test.

And two ways to invalidate your own experiment:

- **Setting `.visible = false` on wheel meshes does nothing.**
  `_applyWheelTransforms` rewrites visibility on every disc and spoke mesh each
  frame from the current speed. To take a mesh out of a test, detach it from its
  parent and re-add it afterwards.
- **Poking texture state from devtools corrupts the run.** See the render-target
  `needsUpdate` note below; after any such poke, reload before believing
  anything.

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
  The arc must stay **below the rim's spoke pitch**; 60 deg suits a 5-8 spoke
  wheel. Measured angular variation went from cv 0.18 to cv 0.40. It is now the
  `uArcRad` uniform, set per rim to `0.8 x` the measured repeat and clamped to
  20-60 deg — a 30-spoke rim would want 12 deg by that rule, which is no blur at
  all, so it is floored and that rim honestly does smear to a plain disc.
- Sweep the arc with `float(i) / float(SAMPLES - 1)`, not `/ float(SAMPLES)`,
  or the smear lands off-centre and the wheel looks subtly unbalanced.
- **The arc cannot remove the lighting lobe, and the sprite rotates.** An arc
  average attenuates the Nth angular harmonic by `sinc(N*arc/2)`. The arc has
  to stay under the spoke pitch (above), so at 57.6 deg it knocks a 5-spoke
  rim's h5 down 4x but leaves h1 at **96%** of what it was. Measured on the
  emulator: h5 0.742 -> 0.149, h1 0.194 -> 0.187. That surviving h1 is the
  scene's specular highlight — one bright side of the rim — and because the
  sprite spins with `_rollAngle` it orbits the hub, which reads as a wheel out
  of balance. A real highlight is a reflection: it stays put while the wheel
  turns past it.

  Correcting only h1 was not enough — measured after that first attempt, h1 was
  down to 0.026 but h2 0.071 and h3 0.085 survived against a spoke signal of
  h5 0.144, so most of what was still rotating was lighting. **Anything below
  the rim's own repeat order cannot be part of an N-fold symmetric rim**, so
  `uHarm[8]` now carries harmonics 1..N-1 and the shader divides the whole band
  out, leaving N and above alone. On the BMW rim (order 10) that takes the
  sub-order band to <= 0.07 against a spoke signal of 0.275. rgb only — alpha is
  coverage, which lighting does not change — with the divisor clamped and the
  result clamped back under alpha, because unbounded division here is exactly
  what blew this sprite out to a pale disc once before. Cap the correction on
  the reconstructed profile's PEAK, not the sum of amplitudes: the harmonics
  peak at different angles, and summing them halved the correction.

  **And the correction has to be per RADIUS, not just per angle.** Flattening the
  azimuthal mean corrects brightness averaged across the disc, so the bright
  specular ring around the outer lip — which lives at one radius — survived it
  and kept orbiting. `uLobeTex` is now a 64x32 (angle x radius) map, POT so
  WebGL1 on the car will take REPEAT wrap on the angle axis. Measured per band
  on the 10-fold rim, worst sub-order harmonic: 0.156 -> 0.107, 0.133 -> 0.128,
  0.212 -> 0.110, 0.397 -> 0.263, with the spoke signal at N preserved or up.

  Three traps in building that map, all of which showed as "correction makes it
  worse" or "correction does nothing":
  - The map is indexed from angle 0; the shader's `ang/2PI + 0.5` put the lookup
    half a turn out and DOUBLED the asymmetry (outer band 0.397 -> 0.499).
    `wrapS = Repeat` handles the negative coordinate on its own.
  - Read brightness UN-PREMULTIPLIED (rgb/alpha, alpha >= 64 only). Raw rgb
    mixes shading with coverage, and it is the shading that is stuck to the
    scene rather than to the wheel.
  - Accumulate harmonics over the bins that have material, normalised by how
    many there were. Mean-filling the gaps and running a full-circle DFT biases
    every amplitude toward zero in proportion to how open the rim is — exactly
    backwards. A 0.6 coverage bar also skipped whole bands of an open-spoke rim
    outright; it needs to be ~0.2.

  What this still cannot fix: the sprite bakes a static reflection into a
  rotating texture, and this only removes the part of it that is separable as
  sub-order angular structure. Residual is ~0.26 at the lip. Fixing it properly
  means not rotating the lighting at all — capture flat-lit and composite a
  static lighting layer over the spinning one.

## Do not set `needsUpdate` on a render target's texture

`_rtSource` / `_rtBlurred` are `WebGLRenderTarget`s. Setting `.texture
.needsUpdate = true` tells three to re-upload the texture from `texture.image`,
which for a render target is empty — it wipes the GL texture. The symptom is
brutal to debug: `readRenderTargetPixels` still returns the old contents, the
mesh still draws (a flat colour test passes), the material still points at the
right texture object, and the sprite renders **pure black**. Two hours went into
chasing a "regression" that was one debug line. If a capture looks wrong,
reload the page before believing any measurement taken after you have poked at
texture state from devtools.

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

## Device access

Talk to the **car itself** over TCP ADB on the local LAN. Do not use a
Raspberry Pi, Tailscale jump host, or `scripts/car-gateway/` — those are stale.

`adb` is usually not on PATH:

```
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
```

The MMI is dual-homed and exposes `:5555` on whichever interface is up:

| Network | Typical subnet |
|---|---|
| Home Wi-Fi (`<home-ssid>*`) | `192.168.1.0/24` |
| Car AP (`HAVAL_SEEK`) | `192.168.33.0/24` |

DHCP moves every session. Never hardcode an IP. `.car-adb-serial` is only a
last-success hint. Discovery (`scripts/car-adb-common.ps1`) detects whether
this PC is on **HAVAL_SEEK** (`192.168.33`) or the **home LAN** (`192.168.1`),
scans that subnet first, then the other, and confirms the MMI (`gwm` /
`msmnile_gvmq`, or `com.havalh6.viewer` if already installed).
Both routes are TCP `adb install` / scrcpy — home LAN is the remote APK path;
you do not need to join the car AP.

Beantechs blocks a bare `adb install` of this package (`beantechs disallow apk
com.havalh6.viewer`). Deploy uses installer identity `com.autolink.installer`.
A signature mismatch needs uninstall + that same `-i` reinstall — a plain
uninstall without Autolink cannot put the app back.

Cursor / VS Code tasks (Terminal → Run Task):

| Task | Script |
|---|---|
| Car: connect | `scripts/connect-car.ps1` |
| Car: share displays | `scripts/share-car-displays.ps1` |
| Car: deploy | `scripts/deploy-car.ps1` |
| Car: deploy (skip build) | `scripts/deploy-car.ps1 -SkipBuild` |
| Car: deploy and share displays | `scripts/deploy-car.ps1 -Share` |

Same via npm: `npm run car:connect` / `car:share` / `car:deploy` /
`car:deploy:skip-build`.

**Not every `:5555` is the MMI.** Other boxes on the LAN (T-Box / Beceem-class
hosts) accept TCP 5555 and show `offline`. A live car is `device` **and**
`pm path com.havalh6.viewer` succeeds. ICMP can fail while ADB still works.

If this PC's ADB key is `unauthorized`, the MMI often never shows the RSA
dialog. Root telnet on the same IP (`:23`, already a root shell, no password)
can append this machine's `~\.android\adbkey.pub` to
`/data/misc/adb/adb_keys` and restart `adbd`.

`pidof com.havalh6.viewer` can return the notification-listener service rather
than the activity — prefer `ps -A | grep "com.havalh6.viewer$"`.

### Share displays 0 and 3

The panel is two 1920×720 surfaces. **0** is the built-in ("Tela integrada");
**3** is HDMI ("Tela HDMI"). Portable scrcpy lives at
`%LOCALAPPDATA%\scrcpy\scrcpy-win64-v4.1\scrcpy.exe`.

```powershell
.\scripts\share-car-displays.ps1
```

That rediscovers the car and opens **Haval-Display0** and **Haval-Display3**.
`--no-audio` is required (Android 9). Mouse→touch works on display 0 only;
secondary-display input needs Android 10+. `screencap -d 3` is often black
even when scrcpy is not — dumpsys is the fallback for "what is on D3".

**Ask before any test that needs a human at the car.** A timed capture started
in the same message as the instruction expires before it is read, and a capture
that returns nothing is ambiguous between "signal absent" and "nobody pressed
anything".

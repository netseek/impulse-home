# Wheels

Spin, rim fitting, the blur sprite and how to measure any of it without fooling yourself.
This subsystem has burned several agents; most wrong answers came from a broken measurement
that was believed over the user's eyes.

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

### Two-layer sprite — NOT YET MEASURED ON ANY DEVICE

The paragraph above is now implemented, on branch `feat/wheel-sprite-two-layer`.
**Nothing in this subsection has been verified on the car or on the emulator** —
it was written without device access. Treat every claim here as a design
intention until `__wheelSpin()` and a screenshot say otherwise, and do not copy
any number out of it.

The rim is captured twice, from the same camera inside the same visibility
block: once lit (`_rtSource`, unchanged) and once with every rim material
swapped for an unlit `MeshBasicMaterial` carrying only colour + map
(`_rtSourceFlat`). Both are smeared over the same arc into `_rtBlurred` /
`_rtBlurredFlat`. The sprite shader (injected into the disc's existing
`MeshBasicMaterial` via `onBeforeCompile`, so three's tone mapping, encoding,
opacity fade and PREMULTIPLIED_ALPHA handling stay exactly as they shipped)
then composites:

- **rotating layer** — the albedo smear, sampled at plain `vUv`. The disc mesh
  is still rolled by `_applyWheelTransforms` exactly as before, so this half of
  the sprite is unchanged.
- **static layer** — `lit / flat`, sampled at a uv COUNTER-rotated by `uRoll`,
  so it stands still in the world while the albedo turns under it.

Both smears are premultiplied by the same coverage, so coverage cancels in the
ratio and there is **no un-premultiplying anywhere** — the trap above still
holds. The divisor is floored against alpha, the ratio clamped at both ends,
and the product clamped back under alpha.

Why `MeshBasicMaterial` and not "zero `envMapIntensity` / drop the lights": the
scene is IBL-lit, so killing the environment leaves the rim nearly black, the
flat layer would carry almost nothing, and almost the whole picture would end
up in the static layer — i.e. the wheel would stop reading as turning at all.
(r137 only feeds `scene.environment` to `MeshStandardMaterial`, so a bare
`MeshBasic` picks up no IBL either.)

`uLobeTex` is **kept but gated off** whenever the two-layer path is live. It
exists to divide lighting out of a rotating layer that no longer carries any,
and applying it to only the lit half would corrupt the ratio. It is still the
correction for the `twoLayer:false` fallback, so both paths stay coherent and
never fight; `__wheelSpin().lobeCorrectionOn` reports which is in force.

Known limits of the split, by construction:

- A rim's real shading is a function of the surface normal, which DOES rotate.
  Treating the whole lit/flat ratio as static is an approximation, exact only
  for a body of revolution. `lightMix` dials it back if it reads as too static.
- A chrome rim's albedo is nearly featureless, so its rotating layer is carried
  almost entirely by the spoke-gap alpha. That is still the strongest motion
  cue, but this is the case to look at first if the spin stops reading.
- The lighting is still baked: it does not respond to the camera orbiting, and
  all four wheels wear the FL rim's capture.
- The static layer is placed using a basis measured from the disc's own world
  matrix against the capture camera's (`__wheelSpin().lightBasisM`). The disc's
  quaternion is `setFromUnitVectors(+Z, axle)` and the camera is placed down the
  axle with up = world +Y, and those two frames are **not** the same — expect
  `lightBasisMirrored: true`. `lightBasis: 0` puts the static layer back in raw
  capture space, which makes the composite at roll 0 identical to the
  single-layer sprite; `lightRotDeg` dials the azimuth by eye.

Everything is live from devtools with no rebuild: `__wheelSpin({twoLayer:false})`
is the A/B, and `lightMix`, `ratioMin/Max/Floor`, `lightBasis`, `lightRotDeg`
are the knobs. `twoLayer`, `lightBasis` and `lightRotDeg` re-capture; the rest
take effect next frame.

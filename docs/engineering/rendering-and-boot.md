# Rendering and boot

How the scene reaches the panel: the boot intro and hand-off, the transparent canvas, the
post-boot swaps, the x-ray model and the tone grade. Read the measuring notes first
([measuring-and-performance.md](measuring-and-performance.md)).

## The boot intro: a warm reload lies, and "after the intro" needs a real test

**Measured 2026-09-22 on the car.** The intro looked fine on a page reload
(34 fps) and ran at **0.4-3.6 fps on a real cold start**, with a 2-4 s freeze
before the car appeared. A reload keeps the GPU driver's program cache hot;
only a force-stop + launch reproduces a real boot. The probe that does that,
attributes every long task and can A/B, is `scripts/device-boot-probe.mjs`:

```bash
node scripts/device-boot-probe.mjs --cold --arms default,gt,hev --reps 2
node scripts/device-boot-probe.mjs --cold --arms default,default@h6_revealPrewarm=0 --reps 3
node scripts/device-boot-probe.mjs --cold --arms default --profile   # V8 profile per long task
```

It restores `h6_settings_v1`, waits 6 s after any localStorage write before
force-stopping (WebView persists it lazily -- skip that and every "GT" arm
silently boots the saved body), and reports `vis`/focus: if another app has
focus at launch the page stays `hidden` and **boot makes no progress at all**
(no `__app` after 40 s). Measured with YouTube, the OEM HVAC and media windows.

Five separate things were landing in the ~2.5 s intro. All fixed; after, all
three bodies measured **2.5 s, 9-17 fps, worst frame 281-354 ms**:

| Cost in the intro | Fix |
|---|---|
| Car's first visible draw (upload + link): 1.6-2.0 s, clip frozen | `_prewarmCarReveal` draws it in the splash gap (1x1 scissor, REAL framebuffer: in r137 a non-XR render target forces LinearEncoding into the program key, so a scratch target compiles the wrong programs). Off: `localStorage.h6_revealPrewarm='0'` |
| First camera tile render 0.8-1.8 s; light warm-up keys 0.4-0.6 s | gated on `_revealSettled()` |
| X-ray boot desktop: material-clone ghost recompiled every car program, 1.8-2.4 s | `_notifyViewerReady` fetches rig + ghost but shows x-ray only after settle |
| Native layout reply to `revealLauncher()` committed React mid-orbit | held in `_introShellPatch`, flushed by `_endIntroAnimation` |
| `_preloadPowertrain` / `_preloadXrayAssets` on flat timers from model-ready | gated on `_revealSettled()` |

**`!_introActive` does not mean "after the intro".** It is also false for the
4-6 s between the model landing and the clip ending, which is exactly how the
tile render and the warm-up both got in. Use `_revealSettled()`: intro played,
not active, and `REVEAL_SETTLE_MS` since it ended (without the settle, every
held job resumed on the landing frame and froze it 1.1-2.7 s, right as the
widget boards fade in). And a job that re-arms its own timer has to re-check
on every step, not only when the chain starts.

When a long task has no app method in it, profile before guessing. The last
stall here was blamed on the SVG grain layer, which an A/B cleared in one
run; the profile named it at once (`loop > render > ... > Ps`, a program link)
and `renderer.info.programs` diffed per frame named the material.

## Mixed centerFill: additive materials silently destroy the alpha channel

`centerFill: 'mixed'` puts the Bing wallpaper behind a TRANSPARENT WebGL canvas
(`scene.background = null`, `setClearColor(0, 0)`), so the drawing buffer's alpha
is load-bearing: it is the car's coverage mask, and anything that writes alpha
where the car is not paints the wallpaper out.

**`THREE.AdditiveBlending` writes alpha.** It is
`blendFuncSeparate(SRC_ALPHA, ONE, SRC_ALPHA, ONE)`, so `dst.a = src.a^2 + dst.a`
and any additive surface drives alpha to 1 across every pixel it covers —
whether or not it adds visible light there. On an opaque background that is
completely invisible, which is why it survived so long. Two surfaces were doing
it, and each one alone turned the whole car pane opaque black:

- the post-FX **bloom / streak overlay quads**, which are full-screen and whose
  composers run `RenderPass` with `clearAlpha = 1`, so their targets carry
  alpha 1 over the entire frame. Triggered by *any* light being on — the
  reported symptom was "no wallpaper", and the headlights happened to be on.
- the **night floor**, a plane 12x the car's size, once it was allowed to draw
  in mixed mode.

`setAdditiveKeepAlpha(THREE, mat)` is the fix: identical RGB factors, alpha
factors `(Zero, One)` so `dst.a` survives untouched. Reach for it for any new
additive material that can cover background pixels. The canvas is
premultiplied-alpha, so colour written where alpha stayed 0 still composites
additively onto the wallpaper — which is exactly right for a light pool falling
on the ground.

**Shadows and light pools are wanted in mixed mode; only the solid floor is
not.** `shadowCatcher` (`ShadowMaterial`, alpha-only) and `nightFloor` (additive)
both composite correctly over the wallpaper. `floorMesh` is opaque asphalt and
must stay hidden — and it has **three** writers, not one: `_setCarSceneVisible`
plus two in the night-mode path. Miss one and the floor reappears on the next
day/night toggle.

**How to bisect this class of bug** (this is what found it, in about four
captures): stop the loop with `cancelAnimationFrame(__app._raf)`, then re-run
the render loop's passes ONE AT A TIME from devtools, screenshotting after each
with `adb exec-out screencap`. Clear-only, scene-only and scene+vignette all
composited over the wallpaper correctly; adding the overlay quad blacked out the
frame. Note `elementsFromPoint` will NOT show `.hv-wallpaper` — it is
`pointer-events: none` — so it cannot tell you whether that layer is painting;
bump its `z-index` above the canvas instead and look.

## Anything that renders outside the loop inherits `autoClear = true`

The main loop ends every frame with `renderer.autoClear = true` (it flips it
off only around the overlay and vignette passes, and puts it straight back).
Anything that renders from OUTSIDE the loop therefore starts by CLEARING the
panel — and if what it then draws is the additive bloom/streak overlay, the
frame is a full-screen flash. That is a one-frame flicker at 14-22 fps, i.e.
50-70 ms, and it is plainly visible.

`_warmupOverlayOnce` did exactly this. It rendered `overlayScene` to the
DEFAULT framebuffer to force the overlay programs to compile, and it fires on
both warm-up paths — the background one after boot, and the synchronous one
that runs when a second body arrives. Symptom: "the screen flickers while
something is loading", which reads like a shadow or mask problem and is not.

Fix, and the pattern for any future warm-up: render into a **4x4 scratch render
target** and restore the previous target. Shader compilation does not care
about the target's size, so a compile-only render never needs the real one. The
same applies to any diagnostic or capture you add — `_generateBlurredWheelTexture`
already gets this right, saving and restoring both the target and the clear
colour around its work.

## The boot hand-off: three things the panel shows before the car does

All three were measured on the car 2026-09-11 with `adb exec-out screencap`
bursts across a cold start. **`screencap` cannot see the hole-punched <video>**,
so the clip itself reads as pure black in every capture — which is exactly what
makes the bursts useful: anything NON-black during the clip is the page leaking
over it.

- **A promoted page layer draws OVER the clip; ordinary page paint does not.**
  The splash goes `background: transparent` (`hv-splash-playing`) so the video
  overlay can show, and the WebView itself is `setBackgroundColor(0)`. The car
  fill's flat `#efefef` on `#hv-root` is punched away correctly and never
  appeared. `.hv-wallpaper` is not: it animates opacity, so Chromium gives it
  its own compositor layer, and the Bing photo was captured covering the whole
  1920x720 panel **4.6 s into the clip**. `.bg-grain::after` is promoted for the
  same reason (it runs an animation).

  `body.hv-splash-up` (set in the markup, cleared by `HavalSplash.releasePage()`
  from `fadeOut` / `dropNow` / the no-splash paths) holds all three back. Add
  any new full-bleed layer to that rule, not just to `hv-boot` — `hv-boot` is
  removed ~2 s AFTER the fade and is about the widget boards.

- **The loading background was flat, because the vignette lives in a shader.**
  `scene.background` was a flat colour and `vignetteIntensity` sat at 0 until
  `_applyBackground` ran, which happened only once the model landed. Measured
  with SKIP during a cold boot: **8.6 s of 239,239,239 across the whole panel**,
  then a step to the settled 156 in the corners / 222 in the middle. That step
  is the "it goes white while the model loads" report. `_applyBackground` now
  runs at scene init (right after the vignette material exists), and
  `_shellBgCss` puts the matching radial on the CSS shell surface for the frames
  before the canvas draws. Residual after: ~6%, ~1.4 s — see that method.

- **`_applyShellViewOffset(0)` does not mean centred; it adds `_userPanPx`.**
  The intro's splash-matching front pose inherited the pan of whatever camera
  the layout restored at boot. Measured against the clip's last frame, same
  metric on both (dark features, y 120-500): clip cx **964.5** top **246**;
  intro cx **827.5** top **183** — 135 px left, 62 px up, exactly the saved pan.
  Width was identical at 274 px, which is why this read as "the car sits a bit
  far" rather than as a shift, and why chasing `INTRO_FRONT_DIST_MUL` or the FOV
  would have been wasted. The intro now zeroes the pan for the hold and eases it
  back over the orbit: measured after, cx **962.5** top **245**.

  **Measure this one from the clip, not from the camera.** `ffmpeg -sseof -0.2`
  gives the hold frame; both silhouettes then come from one thresholding pass.
  Driving `_parkIntroCamera()` from devtools does NOT reproduce the intro —
  `_tickFitCamera` re-applies the layout's view offset the moment
  `_introActive` is false, so the first measurement taken that way was
  contaminated until `_introActive = true` was set by hand.

## Post-boot swaps are badged, not veiled; a new load path has to opt in

Cold boot hides the car until it is finished (`_pendingIntro` ->
`_finishLoading` -> intro). Post-boot loads had nothing, so a MODEL toggle
showed the new body land in the scene, get drawn by the light warm-up, and then
stand there wheel-less for as long as its stock rim took to fetch.

`_beginCarSwap(kind)` / `_endCarSwap()` bracket that window. **They used to
veil it** — canvas host faded to opacity 0, scene culled behind it, finished car
faded back in. That fade was removed on request: over the light day scene it
read as the whole screen washing out to white. So the rebuild IS visible again,
by choice. What is left is `_endCarSwap`'s flush of the debounced blur capture
(so a new rim never appears wearing the previous rim's smear) and the badge.
The veil's CSS (`.hv-swapping` / `.hv-swap-in`) is still in the file; restoring
it is re-adding the two class toggles in `_beginCarSwap` / `_endCarSwap`.

**The badge is the only feedback a post-boot load has.** `.hv-boot-center`
carries both it and the cold-boot spinner, keyed on `_carSwapLabel`:

- no label (cold boot) — bare ring, flex-centred on the panel, unchanged.
- label — small pill (`.hv-badge`, ring + `LOADING MODEL` / `LOADING WHEEL`)
  placed by `_positionSwapBadge`.

Two things that shipped wrong here, both of which look fine on a dark screen:

- **The bare ring is invisible in day mode.** Its track is
  `rgba(255,255,255,.14)` and the caption 62% white — styled for the dark boot
  shell, then reused over a near-white day scene and, in mixed centerFill, over
  an arbitrary Bing photo. Hence the pill's backing fill. Not
  `backdrop-filter`: that buys nothing a flat fill does not, and costs a blur
  of the live canvas on the MMI.
- **Do not anchor the badge on the world point under the car's centre.** The
  camera looks DOWN at the car, so `(fitCenter.x, bottom, fitCenter.z)`
  projects onto the middle of the bodywork and the badge lands across the
  doors. `_positionSwapBadge` projects all eight fit-box corners and takes the
  lowest one on SCREEN, which is under the car from any angle. Nor is the panel
  centre right — `_shellCameraTarget` shifts the car into whatever gap the
  widget boards leave, so a centred indicator is neither on the car nor
  obviously about it.

**If you add a path that sets `loading: true` after boot, call
`_beginCarSwap`.** The end is usually free — every normal path funnels through
`_finishLoading`, which calls `_endCarSwap` — but a path that clears `loading`
on its own must call it too. A 25 s safety timer clears the badge if one is
missed, so the failure mode is a stuck badge for 25 s, not forever.

**`loading: true` is not what raises the badge — `_carSwapLabel` is.** So a
path that fetches something WITHOUT touching `loading` still has to opt in,
and the X-RAY toggle is exactly that one: `_applyPowertrainVisibility` pulls
its own GLB + manifest the first time it is opened and had no feedback of any
kind. It now calls `_beginCarSwap('xray')` and deliberately does NOT set
`loading` — `_syncCarSceneForShell` reads `loading` as "keep the car hidden",
which is the opposite of what a toggle wants. Three things that path has to
get right, and any similar one will too:

- **Badge only when the work is really cold.** `_ensurePowertrain` resolves
  synchronously once the rig exists, so bracketing every press would flash the
  badge for `MIN_BADGE_MS` on every toggle. Test the thing itself
  (`!this._powertrainRig`), not whether a toggle happened.
- **Do not adopt someone else's swap.** `_beginCarSwap` early-returns when one
  is already active; without also testing `!this._carSwapActive` the caller
  would go on to `_endCarSwap` a swap it never started.
- **Give the promise a rejection handler.** A failed load otherwise leaves the
  badge up until the 25 s safety timer fires.

The badge deliberately outlives `loading: false` (`bootCenterDisplay` also keys
off `_carSwapLabel`, and `MIN_BADGE_MS` holds it 340 ms), so a cached swap
still reads as a transition instead of a one-frame blink.

One thing NOT to gate chrome visibility on: `s.loading`. That is what made the
config dock close and re-open on every rim swap;
`!(s.loading && !this._viewerReady)` is the test the rest of the chrome uses.

## X-ray mode is a second model, not a transparency effect

`xray` is `state.powertrainOn`, and it does three separate expensive things.
Measured on the car, interleaved pairs, rig confirmed visible in every ON arm:

| | x-ray off | x-ray on |
|---|---|---|
| draw calls | 178 | **383** |
| triangles | 344,716 | **497,362** |
| `submitMs` | 27.3 | 35.9 |
| GPU backlog (`gl.finish`) | 0.1 ms | 0.1 ms |

1. **It adds the powertrain rig** — 131 meshes, 115,328 triangles. "The car is
   almost transparent so it should be cheap" is backwards: transparency removes
   early-Z and *adds* a whole drivetrain behind the shell.
2. **It never lets the scene idle.** `_tickPowertrainFx` returns true whenever
   the rig is animating, i.e. always. Camera still, nothing moving, three
   interleaved pairs: **4.74 renders/s with x-ray off, 9.69 with it on**, and
   `timerLagP50` 49.3 ms → 105.9 ms.
3. **It used to rebuild post-FX on every single frame.** The tell is
   `postFxPerSec` exactly equalling `rendersPerSec` (9.69 = 9.69 in all three
   arms) while x-ray off ran 1.58 rebuilds against 4.74 renders.

   **`requestRender()` sets `_postFxDirty` unconditionally**, so throttling at
   the call site does nothing — the very next line undoes it. Measured with a
   trap on the flag: `requestRender` was setting it 17.45x/s against the
   throttle's intended 4.49x/s. Hence `requestRender(n, keepPostFx)`; the x-ray
   tick passes `true` and a 5 Hz throttle (`PT_POSTFX_HZ`) is then the only
   thing that invalidates the overlay.

### The ghost body

`_setBodyGhost` used to keep the whole body and clone all ~42 materials into
transparent ones — full draw-call count, no early-Z, and ~44 transparent meshes
in the per-frame depth sort. It is now a purpose-built asset,
`scripts/build-xray-ghost.mjs` (`npm run build:xray-ghost`):

| | draw calls | triangles | size |
|---|---|---|---|
| HEV ghost | 132 → **2** | 142,332 → 58,766 | 12.9 MB → **0.29 MB** |
| GT ghost | 99 → **2** | 128,581 → 48,305 | 6.6 MB → **0.20 MB** |

**Merging matters more than decimating.** The GPU is idle here (backlog
0.1 ms), so the cost is CPU-side draw submission — a 5k-triangle ghost split
across 40 draw calls would be WORSE than a 100k-triangle ghost in one. One
material, no textures, Draco (legitimate: x-ray is not boot-critical).

`window.__xrayGhost(false)` reverts to the material-clone path for A/B.

Three classifier traps, all caught by `--dry` before anything was written:

- a bare `/disc/` matches **"discoloration"** and threw three body panels out.
- the HEV spells its brake discs **`Break_Disks`**, and its meshes are
  **unnamed** — only the node name identifies them. The GT's wheels are
  identifiable **only by material name** (`Wheel`).
- consulting material names then breaks lamps: a GT rear cluster carries
  `Chrome | BrakeLight | PositionLight_Rear`. Structural names decide first;
  materials are a fallback, must ALL read as wheel parts, and a
  `light|lamp|lens` match vetoes.

And two integration bugs worth not repeating:

- **hide the ghost unconditionally when x-ray closes**, not inside the
  "did we hide the shell" branch — the asset arriving mid-toggle re-runs the
  pair and leaves a second see-through car inside the real one.
- **guard the "asset not ready yet" retry.** With `__xrayGhost(false)`,
  `_useGhostBodyModel()` always returns false, the cached promise resolves
  instantly, and the retry re-enters `_setBodyGhost(true)` forever. That hangs
  the page and the WebView reloads under it — which reads as random crashes
  during an A/B, not as a loop.

## X-ray is a draw-call problem, and rendering harder does not help

Measured on the car 2026-09-11, parked, interleaved arms so drift cancels
(`window.__xrayGhost(false)` reverts to the material-clone ghost):

| | material ghost | single-mesh ghost |
|---|---|---|
| frame | 20.94 / 22.99 fps | **46.00 / 46.17 fps** |
| submit | 23.5 / 41.0 ms | 8.0 / 10.9 ms |
| draw calls | 380 | **118** |
| triangles | 497,356 | 271,426 |

The old ghost kept the whole body and cloned all 42 materials into
transparent ones, so x-ray paid the full draw-call count AND lost early-Z AND
added ~44 transparent meshes to the per-frame depth sort. The replacement is a
purpose-built asset -- ONE mesh, ONE material, no textures
(`scripts/build-xray-ghost.mjs`) -- and 141 shell meshes are hidden behind it.

**Optimise draw calls here, not polygons.** `gl.finish()` measures 0.1 ms, so
the GPU is idle and triangle throughput is not the constraint; a 5k-triangle
ghost split across 40 draw calls would be WORSE than a 100k-triangle ghost in
one.

### "Make the loop infinite so it always renders at max FPS" does not work

Frame rate is frame COST, not frame DEMAND. X-ray already renders continuously
(`_tickPowertrainFx` returns true every frame, so the beads and glow keep
asking), and it still sat at 21 fps with the old ghost -- asking more often
could not have helped, because each frame genuinely cost ~45 ms.

Measured the other way round too: parked and idle with x-ray off, a bare rAF
ticks at **59/s with 16.7 ms p50 and 5.1 ms timer lag**. The browser is already
offering ~60 frames a second; the on-demand loop uses one of them because
nothing changed. There is no headroom to unlock by looping -- the frames are
on offer and being declined on purpose.

Rendering unconditionally would cost real things: the main thread is the
bottleneck (GPU idle at 0.1 ms), this SoC is shared with Android Auto and
navigation, and the unit already drifts ~2x thermally over minutes, so
sustained load trades a stable frame rate for a decaying one. A continuous
mode is a BENCH tool -- drive the camera so every frame renders and demand
stops being a variable -- not a shipping mode.

## A tone grade does not need a render target

ENV → TONE (contrast, whites) is Levels, and Levels is affine, so it runs
entirely in the BLEND stage over the default framebuffer: a REVERSE_SUBTRACT
quad lifts the black point, then a `(DST_COLOR, ONE)` quad multiplies. No
texture read, so the 4x MSAA that FXAA would forfeit survives, and the cost is
three cheap draws. See `_applyGrade` for the pass order (lift BEFORE gain, or
every white is clipped then pulled down), the alpha handling for mixed
centerFill, and the 2x ceiling on one gain pass. Anything non-affine (an S-curve,
saturation) cannot be done this way and would need a real target.

It grades the car only. The quads sit at depth 1.0 with `GREATER`, so anything
with `depthWrite: false` (background, shadow planes, night pools) is skipped for
free. The floor does write depth, so it is re-rasterised into stencil on
`GRADE_MASK_LAYER` just before the grade. **Do not replace that pass with a
stencil flag on the floor material:** part roots carry `renderOrder` (lit lamps
go to 14), so car parts drawn after the floor would inherit its mark. A new
ground-like surface that writes depth has to join `GRADE_MASK_LAYER`.

**Not yet measured on the car.** Verified visually on desktop only. Ships at
0/0, which skips every pass.

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

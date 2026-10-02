# Power flow (ENERGIA card)

The widget, the expanded popup and the native bottom card all draw the same schematic top-down
chassis with a thin overlay on top. The chassis is raster art and the overlay is lightweight SVG
(web) or Canvas (native): no extra WebGL scene runs on the head unit. The artwork is schematic,
not OEM CAD; battery and engine placement are not a service diagram, and the modules shown are
not individual cells.

## Data contract

| Input | Display and limits |
|---|---|
| `car.ev_info.cur_battery_power_percentage` | Battery SOC, 0-100%. |
| `car.ev_info.power_battery_voltage` | Pack voltage in V, as published. |
| `car.ev_info.cur_charge_current` | Current in A, as published; its polarity is **not** interpreted as charge or discharge. |
| `haval.power.flow` | Categorical engine / front / rear activity reported by Impulse. Not measured axle power. |

- Battery power is `abs(voltage x current) / 1000` kW. That is electrical pack power, not wheel
  power, engine output, torque split or grip. Negative current never causes regeneration arrows by
  itself.
- Impulse's `PowerFlowMapper` maps the OEM energy states but can also infer both-axle activity
  from speed and electrical power for unknown or idle values, and its
  `v1|state|ice|front|rear` packet carries no measured-or-inferred flag. The popup therefore calls
  the activity *reported* and says it may be estimated. The viewer adds no inference of its own and
  never turns categorical values into percentages.
- Do not infer regeneration from current polarity, nor engine-to-battery flow from the engine
  being on. No per-wheel torque, traction percentage or per-cell voltage exists on the bus.
- Snapshots expire after 120 s. A stale flow cannot keep arrows active while other readings stay
  fresh, and a stale SOC shows a dash. Invalid packets are rejected. LIVE / PARTIAL / STALE /
  UNAVAILABLE describe what was received, not whether the upstream interpretation is right.
- Never use the presence of the OEM energy package as proof that power data is available.

### Session graph

The last-60-seconds battery-load graph records voltage/current pairs at most four times a second.
Both readings must be at most two seconds old and no more than 1.5 s apart. It keeps at most 241
samples, leaves a gap wherever there is more than two seconds without data, and never retrieves or
invents OEM history. The peak and the vertical scale refer to that window only. Demo mode draws a
separately labelled example and never inserts synthetic samples into the recorded history. Power
refreshes use the UI-only state path with a 250 ms throttle; while the popup is open a one-second
UI-only timer advances the window even when no sample arrives, and it stops when the popup closes.

## Variants

| Variant | Electric axles | Notes |
|---|---|---|
| PHEV 19 kWh | front | taller pack faces |
| PHEV 34 kWh and GT | front and rear | independent front and rear power paths; the only variants with a rear electric drive unit |
| HEV (1.6 kWh) | front | smaller transverse pack; never shows plug-in charging |

Never default an unknown variant to AWD: use the model selection or show an unavailable state.
There is no longitudinal cardan shaft in the artwork.

## Chassis art and overlay

- The runtime chassis files are three 350 x 770 images with a transparent background, so a light
  card shows its own surface instead of a black rectangle. They are produced from the approved
  renders by `scripts/build-approved-chassis-assets.mjs`, which strips the baked labels, cuts the
  studio backdrop and prints the overlay geometry that the web (`CAR_POWER_GRAPHICS`) and native
  (`drawPower`) painters share. Regenerate and paste that geometry; do not nudge numbers by eye.
- The backdrop is not colour-keyed (frame openings and silver castings overlap the ground in
  brightness). The ground is flood-filled across smooth neutral pixels only and becomes black with
  an alpha equal to the darkening, so contact shadows survive as real shadows on both themes.
- The chassis stays hidden until the first web overlay is ready, and an opaque panel covers the
  artwork's own indicator, so a baked percentage never appears as a fallback reading.
- If the chassis image is missing, keep the battery and the textual state; never hide the whole
  card or silently switch model.

### SOC modules

Ten fixed-size modules, 2 x 5 on the pack, filled from the rear row forward. For module `i`
(0-9) the intensity is `clamp(SOC / 10 - i, 0, 1)`; fractional SOC is preserved for brightness and
only the displayed percentage is rounded. At 12% the first face is fully lit, the second is at 20%
and eight are dark. Faces never resize with charge. Unknown, non-numeric, out-of-range or stale SOC
is dark with a dash, never a fabricated zero; 0% is shown as `0%`.

### Flow and motion

- **Standby:** steady, no animation. **Charging or recovery:** the boundary module breathes between 30% and
  100% of its fraction and a shimmer climbs the lit modules; the pack rim takes the direction
  colour. **Supplying:** the shimmer runs back toward the rear row.
- **Routes:** a soft ribbon, a core line and a comet (tail and bright head) every 48 units, ordered
  in the direction energy travels. Regeneration reverses every segment, wheel branches included,
  from the same geometry: never draw separate regen arrows. The web speed follows pack power in
  three tiers (`flowTier`); native uses the middle one.
- **Driven wheels:** a ring radiates out on drive and gathers in on regeneration. Working motors and
  a running engine get a breathing outline.
- Mixed axle directions leave the pack edge neutral, because the net battery direction is not
  known. Stale flow clears routes and highlights on its own, without touching a fresh SOC.
- **No SVG filters or markers.** Every animated frame repaints the SVG and a blur is re-rasterised
  each time. Everything is paused unless the graphic has the `.is-running` class, and it pauses
  when hidden or backgrounded. With reduced motion requested the phase freezes and the static
  direction chevrons remain. Native redraws only the small visible graphic, at up to 30 fps while a
  pulse is active, respects the system animation scale and resumes on focus.

### Colours

Drive `#22c9ff`, regeneration `#53ed91`, engine `#ffb65c`. Colour never carries state alone:
direction chevrons and text accompany it.

## Responsive behaviour and accessibility

The graphic's own height, not the viewport, decides what is readable:

- 600 px and taller: the percentage can sit inside the graphic (about 13-16 px).
- 240-599 px: hide the internal percentage and show SOC outside at 14 px or more.
- Under 240 px (the native rail): external SOC and state are required; keep routes at least 3
  screen pixels wide and reduce the chevron count.
- Reserve the full width of `100%` and of the dash so SOC is never truncated.
- The graphic has no interaction of its own; the card opens the popup. The SVG carries a title with
  the SOC and the front/rear state, and nothing announces every percent tick.

## Reference graphics kit

`assets/power/graphics/` holds an earlier, standalone kit that is kept as a reference adapter and a
test fixture, not as the production painter: `manifest.json` (canvas `0 0 600 1000`, projection
`x = 300 + 240 * worldX`, `y = 500 + 240 * worldZ`, anchors, routes), `graphics.mjs` (a
dependency-free pure SVG adapter) and per-variant chassis and overlay files. Position the image and
the SVG in the same 3:5 parent and scale that parent, never the children separately. Regenerate and
check it with:

```text
node scripts/build-power-graphics.mjs
node scripts/test-power-graphics.mjs
node scripts/review-power-graphics.mjs
```

These need local Chrome and Playwright. The bake uses the vendored three.js and the original model;
nothing is fetched.

## Verification

- `scripts/test-power-behavior.mjs`: intensity, ten faces, state and freshness boundaries, stale
  flow and SOC, independent axle activity, current polarity, malformed packets, sample alignment,
  gaps and demo separation.
- `scripts/review-power-layout.mjs`: renders every size and both themes, asserts the chassis has
  transparent corners and opaque vehicle pixels, samples real CSS opacity at 0/900/1800 ms, and
  checks visibility pause and reduced motion.
- `scripts/review-power-emulator.mjs <serial>`: reviews the packaged APK on an emulator with an
  explicitly labelled temporary simulation, and rejects an unrelated installed build. The
  simulation ends when the app restarts and never sends a vehicle command.

Still to confirm on the vehicle: voltage and current units and packet transitions on the installed
Impulse (engine charging, single-axle regeneration, expiry with no bus), contrast and native layout
on the physical panel. Browser and emulator captures validate layout, not CAN semantics or SA8155
timing.

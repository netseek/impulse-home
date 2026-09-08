# DRIVING card — drive mode, power mode and energy recovery

Replaces the three separate quick cards (`driveMode`, `powerMode`, `regen`) and
sits alongside the older `modes` widget rather than deleting it. Composition
follows the recommendation in `docs/oem-apk-can-reference.md`: Vehicle Center
groups driving-related settings onto one surface, so the glance card anchors on
the selected drive mode and everything else is progressively disclosed in the
popup.

## What is connected

| Control | CAN key | Evidence level |
| --- | --- | --- |
| Drive mode | `car.drive_setting.drive_mode` | Bridge-supported (read + allow-listed write) |
| Power mode | `car.ev_setting.power_model_config` | Bridge-supported |
| Energy recovery | `car.ev_setting.energy_recovery_level` | Bridge-supported |
| Steering assist | `car.drive_setting.steering_wheel_assist_mode` | Bridge-supported |
| Stability control | `car.drive_setting.esp_enable` | Bridge-supported |
| One-pedal driving | `car.ev.setting.pedal_control_enable` | Bridge-supported (Impulse read + write) |

One-pedal is the only key this work added, and it is evidenced rather than
invented: Impulse lists `car.ev.setting.pedal_control_enable` in **both**
`ThemeBridgeImpl.getAvailableKeys()` (readable) and `CarDataWriteReceiver`
(writable), and its `RegenScreen` toggles it with `"1"`/`"0"` on a long press —
the same key, values and gesture used here. **Note the separators:** it is
`car.ev.setting` with dots where its neighbours are `car.ev_setting` with an
underscore. That is not a typo, and "fixing" it silently breaks the write.

The other five already existed in `CAR_MODE_GROUPS` / `CAR_MODE_ESP` and are written
through the same `_setCarMode` path the `modes` widget uses. **No telemetry
contract was changed and no new key was invented.** The values and their labels
are the ones Impulse's settings menu uses.

Groups are addressed through `CAR_MODE_GROUP_INDEX` (`{ drive, power, steer,
regen }`) rather than bare array positions, because reordering `CAR_MODE_GROUPS`
would otherwise silently repoint a control at a different CAN key — the old
call sites read `CAR_MODE_GROUPS[3]` for regeneration with nothing to catch it.

## Source vocabulary

The badge is computed over the three *primary* groups (drive, power,
recovery) and follows `docs/widget-data-audit.md` rule 5:

| State | Badge | When |
| --- | --- | --- |
| demo | `DEMO · SIMULATED · NOT VEHICLE` | `_demoPreview` (emulator) |
| browser | `LOCAL PREVIEW · NOT A VEHICLE SETTING` | not the Android shell |
| not ready | `UNAVAILABLE · VEHICLE NOT READY` | Android, no `READY` or no `setCarData` |
| pending | `PENDING · AWAITING VEHICLE STATE` | a write is out, not yet acknowledged |
| live | `VEHICLE · LIVE` | all three reported within 2 min |
| partial | `PARTIAL · VEHICLE STATE` | some reported within 2 min |
| stale | `STALE · VEHICLE STATE` | reported, but older than 2 min |
| none | `UNAVAILABLE · NO VEHICLE STATE` | nothing ever reported |

The predecessor `_modeCardVisual` abbreviated the demo state to
`DEMO · LOCAL PREVIEW`; the full badge is required and the contract test now
asserts the short form cannot come back.

**A group the vehicle has not reported stays `—` on a head unit.** The demo
default is only consulted when the app is explicitly a preview
(`!this._androidApp || this._demoPreview`) — on the car, a plausible-looking
"Normal" would be indistinguishable from a real reading.

## Controls

Every option is a button that writes its own raw value. It is deliberately not
a cycle: cycling steps the vehicle through modes the driver did not ask for,
and the three legacy quick cards did exactly that. The legacy `cycleDriveMode`
/ `cyclePowerMode` / `cycleRegenMode` dock commands stay wired and allow-listed
for native shells installed before this merge.

Writes are disabled — not silently dropped — when the vehicle is not `READY` or
the installed `TelemetryBridge` has no `setCarData`. Nothing reaches the bridge
in that state.

## Gestures

Each rail tile carries up to three:

| Gesture | What it does |
| --- | --- |
| tap the card body | opens the DRIVING popup |
| tap the graphic | quick change for that one mode |
| hold Energy recovery | opens the popup at the one-pedal control |

The drive icon cycles **Eco → Normal → Sport only**. Stepping a driver into
Neve/Areia/Lama from a rail tap is a surprise, not a quick action; the terrain
modes are a deliberate choice and stay in the popup. From a terrain mode the
cycle re-enters at Normal, and the three dots under the glyph light none —
which is true, the toggle is not on any of its own positions.

The recovery icon steps the level, unless one-pedal is on: then it turns
one-pedal **off and restores the level selected before it was enabled**. That
memory (`_regenLevelBeforeOnePedal`) is ours. Nothing on the bus reports a
"previous" level, so when none was ever reported the level is left alone.

## Glyphs come from a real icon set

The mode glyphs are [Tabler Icons](https://github.com/tabler/tabler-icons)
(MIT, © 2020-2024 Paweł Kuna) on the same 24x24 / 2px grid the cards already
used: `leaf`, `road`, `bolt`, `snowflake`, `ripple`, `droplets`, `car-suv`.

`car-4wd` was the semantically exact icon for AWD, but its pill-shaped wheels
read as blobs at 76dp beside six crisp abstract glyphs. The SUV silhouette
carries the same "the whole car is driving" meaning with edges that survive
the size.

They were hand-drawn first and it did not work. The shapes kept colliding with
each other — tyre tread read as a barcode, a hub circle read as an eye, four
wheels read as a window grid — and each fix was another guess. Worse, the web
table and the native card each carried their own copy of every shape, so the
two drifted.

`scripts/build-drive-mode-glyphs.mjs` flattens each icon to absolute M/L/C/Z
with a space after every command letter: relative commands resolved, shorthand
expanded, and **elliptical arcs converted to cubics at build time** rather than
on the head unit. That subset is why the rail card can draw the same art from a
ten-line tokenless parser instead of an SVG engine, and why there is one table
instead of two. The path travels in the rail payload and is validated on arrival
like any other untrusted string (`sanitizeGlyphPath`).

Re-run the script to change an icon; do not hand-edit the table.

## One visual language across the three surfaces

The widget and the popup drew the same control two different ways: the popup's
tiles read as buttons, the widget's dimmed flat chips read as labels. The
popup's treatment won, and the widget chip is now that tile at a smaller size —
same border, same fill, same accent ring when selected. An unselected chip is
muted through **colour**, not opacity, because opacity also dims its icon.

Three details that came out of using it:

- **A press on a chip lit the whole widget.** `:active` applies to every
  ancestor of the pressed element, and `.hv-widget-card:active` scales the card
  and rings it in the accent — right for a card that is one tap target, wrong
  for one full of buttons. `:has()` would be the tidy fix but the car runs
  WebView 91, so the card marks itself `has-controls` and opts out. Feedback
  lives on the chip instead.
- **Three rows contain an option called "Normal" and two contain "Sport".** The
  drive row — the one with seven options — carries each mode's own glyph beside
  the word, and every row head prints its current value in bold on the right.
- **A lit border is a weak way to say "on".** The two booleans spell it out:
  `ONE-PEDAL  OFF`, `ESP  ON`.

Booleans are one tile that lights rather than competing ON/OFF pairs, in the
popup as well as the widget. That is what lets the popup fit on one page: the
groups run in two columns with DRIVE spanning both, so ASSIST — the only way to
reach one-pedal from there — is no longer below the fold.

**Every options grid needs its `cols-N` rule.** `.cols-2` was missing, so the
two ASSIST toggles silently fell back to the four-column default and clipped
`ONE-PEDAL` to `O…`. The class was present in the markup and the container was
the right width; only the rule was absent, which is a hard thing to spot by
reading.

## Layout

`1x1`, `1x2`, `2x1`, `2x2`, `3x1`, `3x2`.

The widget is a **control surface, not a readout** — it shows the same option
groups as the popup. It shipped once as a hero-plus-two-cells glance card, which
made it the only surface where a mode could be seen but not changed; the retired
MODES widget had this right, compacting its buttons all the way down to 1x1.

- one row per group, and rows claim height in proportion to the chip lines they
  need. Equal shares squeezed the seven-option drive row into one line's worth
  of space and the buttons overlapped.
- `2x1` / `3x1` run the groups as columns; five stacked rows do not fit a
  one-row-tall slot.
- `1x1` shows the three road drive modes, power and recovery. Seven buttons in
  a 1x1 slot are unreadable.
- one-pedal and ESP are booleans, so each is a single chip that lights when it
  is on, sharing one row — a row cheaper than rendering ON and OFF pairs.
- there is no foot line. The header badge is the source claim; repeating it
  below, next to a "TAP TO OPEN" for an affordance the whole card already has,
  spent height the controls wanted.

Colours resolve through `--hv-widget-fg`, `--hv-accent`, `--hv-frost-edge`,
`--hv-frost-inner` and `--hv-frost-fill-strong`, so the card works on both
boards and honours the configured accent. The older `.hv-modes-chip` rules
hard-code `rgba(255,255,255,…)` for border, fill and text and are unreadable on
the light board — the contract test fails if that pattern is copied into
`.hv-driving-*`.

## Bottom-bar migration

`H6_DRIVING_LEGACY_CARD_IDS` maps `driveMode` / `powerMode` / `regen` to
`driving` inside `_normalizeBottomCards`, so a desktop saved before the merge
keeps a rail slot instead of losing up to three entries. Three legacy ids
collapse to one card; the existing dedupe drops the extras.

## The template engine wraps every {{ value }} in a span — and that bites

`{{ wg.drivingMode }}` is not rendered as a bare text node. The `text/x-dc`
compiler emits `<strong><span class="sc-interp">Normal</span></strong>`, so a
DESCENDANT rule like `.hv-driving-name span { font: 7px ... }` — written to
style the kicker underneath — also matches the interpolated text inside the
hero and silently shrinks it to 7px.

The symptom is nasty because `getComputedStyle(strong).fontSize` still reports
the 42px you asked for: the rule lands on the inner span, not the `<strong>`.
Setting an inline `style.fontSize` on the `<strong>` changes nothing either.
`document.elementFromPoint()` over the painted glyphs is what identifies it in
one call — it returns `SPAN.sc-interp` with the wrong `font-size`.

Use a child combinator (`.hv-driving-name > span`) for any rule that styles a
span next to interpolated content. `small`, `em` and `i` are safe, which is why
the Tires card never hit this.

**This is not only our bug.** The same descendant pattern is live in three
shared card rules and shrinks their values the same way:

| Rule | Card | Value affected |
| --- | --- | --- |
| `.hv-status-cell span` | Vehicle Status | BATTERY / GEAR / STATE |
| `.hv-range-stat span` | Range | EV / GAS km |
| `.hv-consumption-kpis span` | Consumption | FUEL / ENERGY |

Confirmed visually for Vehicle Status on the emulator (the cell values render
at 7px instead of 14px). Left alone deliberately: those cards are owned by
other agents right now and a one-character selector change belongs in their
branch, not this one.

## Screenshots

`docs/driving-card/`, captured on the Haval emulator at 1920x720 through
`scripts/device-cdp.mjs serve` (no APK install):

| File | What |
| --- | --- |
| `driving-dark-2x1-1x1-3x1.png` | dark, row variants |
| `driving-dark-2x2-1x2.png` | dark, tall variants with the quick chips |
| `driving-dark-popup.png` | dark popup |
| `driving-light-2x1-1x1-3x1.png` | light, row variants |
| `driving-light-2x2-1x2.png` | light, tall variants |
| `driving-light-popup.png` | light popup |
| `driving-unavailable-dark.png` | vehicle not ready — card |
| `driving-unavailable-popup.png` | vehicle not ready — every option disabled |

Accent was verified separately by setting `accentColor` to `#e0674a`: the mode
glyph, the selected option ring and the recovery bars all follow it.

## Known limitations

1. **No round-trip verification.** These are level-2 (bridge-supported) keys.
   Nothing here has been observed on a vehicle across H6 variants, so the
   `PENDING` state is a UI guard, not proof the write was accepted. Per the OEM
   audit's follow-up list, drive/power/recovery reads still need capturing on
   the car before the writes can be called round-trip verified.
2. **No variant filtering.** All seven drive modes are offered because
   `CAR_MODE_GROUPS` offers them. Vehicle Center filters mode arrays by the
   vehicle's observed capabilities and we cannot yet: a variant without, say,
   AWD will show an option that does nothing. Filtering needs an observed
   capability signal first.
3. **The older `modes` widget still exists** and still uses its dark-only chip
   styling. It was left alone deliberately — retiring it is a separate change
   that touches saved widget layouts.
4. **ESP OFF is offered.** It was already reachable from the `modes` widget and
   is allow-listed by the bridge; it is placed last in the popup, under
   `STABILITY CONTROL`, rather than on the glance card.

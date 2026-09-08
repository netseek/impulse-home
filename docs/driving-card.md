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

All five already existed in `CAR_MODE_GROUPS` / `CAR_MODE_ESP` and are written
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

## Layout

`1x1`, `1x2`, `2x1`, `2x2`, `3x1`, `3x2`.

- `2x1` / `3x1` put the hero and the POWER/REGEN cells side by side.
- `h >= 2` adds the three road-mode chips (Eco / Normal / Sport) for direct
  selection; the terrain modes stay in the popup, where there is room for them.
- `1x1` drops to a compact hero plus the two cells.

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

# OEM application and vehicle-data reference

Compiled from static inspection of the OEM software (package names, resource and
string tables), from observation on a vehicle through Impulse, and from the Impulse
source. It is a reference for naming and planning only: nothing from the OEM
packages is distributed, and OEM APKs, extracted files, device identifiers and dumps
must never be added to this repository.

This document is durable project context for planning cards and native bridges.
Strings and resources found in an OEM APK prove that the OEM software knows a
concept. They do **not** prove that `com.havalh6.viewer` can read it, write it, or
receive it on every Haval variant.

## Evidence levels

Use these levels when turning the findings below into UI:

1. **Discovered** — a property, action, or visual appears in APK resources or DEX
   strings.
2. **Bridge-supported** — our installed native bridge exposes the exact read or
   write operation.
3. **Vehicle-observed** — the value has been captured on the target vehicle and
   its units/states are understood.
4. **Round-trip verified** — a write is allow-listed, acknowledged by returned
   vehicle state, and tested safely on the vehicle.

Only levels 2-4 can power a live card. A discovered-only value remains
`UNAVAILABLE` or an explicitly labelled `DEMO`; it must never silently become a
plausible-looking vehicle value.

## OEM architecture

| Package | Role inferred from manifests, resources, and DEX | Integration implication |
| --- | --- | --- |
| `com.beantechs.intelligentvehiclecontrol` | Persistent system-UID vehicle-control hub. Defines the signature/privileged `bean.permission.INTELLIGENT_VEHICLE_CONTROL` permission and starts at boot. | Most direct binding or control is likely unavailable to an ordinary app unless the head unit grants privileged access. |
| `com.beantechs.hvac` | Thin HVAC client using `IntelligentVehicleControlClient`, with its own service and boot receiver. | Best semantic reference for climate states and controls, but package presence is not a public API contract. |
| `com.beantechs.vehiclecenter` | Thin client for drive, body, comfort, parking, lighting, and ADAS settings. | Best vocabulary and UI reference for Vehicle Status and Driving Controls cards. |
| `com.beantechs.energyassistant` | Energy/range/history UI using OEM SDK/provider services and Realm-backed local history. | Useful for data names and visual hierarchy. Its stored history is not currently exposed to our app. |
| `com.beantechs.extprovider` | Persistent shared provider layer with `com.beantechs.ext.bt`, `.common`, `.car`, and `.host` authorities. | Potential read path, but URI contracts, permission checks, and stability require runtime/decompilation verification before use. |
| `com.beantechs.mediacenter` | Main direct `android.car`/`CarPropertyManager` reference; handles radio, Bluetooth, USB, media sessions, and vehicle audio permissions. | Strong reference for media transport and property-subscription mechanics. It also relies on privileged car/audio permissions. |
| `com.beantechs.launcher` | OEM HOME launcher with day/night assets and plugin service. | Reference for theme-aware icons and Coffee OS card placement, not a telemetry source. |
| Autolink/TS Android Auto packages | Projection UI and service split, with system and vendor variants. | Separate projection stack; not a CAN or general vehicle-data source. |

The head unit can appear more than once in `adb devices`; always use an explicit
serial when inspecting or deploying.

## Vehicle-observed snapshot (H6 HEV)

Everything above this line is level 1 (**discovered**). This section is level 3
(**vehicle-observed**): captured from the car by asking Impulse to replay its
cache and reading what arrived —

```bash
adb shell am broadcast -a com.haval.vehicle.REQUEST_SNAPSHOT \
    -p br.com.redesurftank.havalshisuku --es requester adb-probe
adb logcat -d | grep CarSignal
```

That returns roughly 170 keys. Reach for it before concluding a signal does not
exist; two of the conclusions below were written off as missing by the APK audit
and are on the bus.

- **Per-window status exists, and its polarity is confirmed.**
  `car.basic.window_status` is a four-slot vector `{fl,fr,rl,rr}`. Captured both
  ways on the car by lowering the driver's window and raising it again: all shut
  is `{1,1,1,1}`, driver-down is `{0,1,1,1}`. So **1 = closed, 0 = open** — the
  INVERSE of door_status, where 0 is shut — and the slot order matches
  door_status, slot 0 being front left. Treat any other value as a state nobody
  has observed and leave the glass alone; do not invent an open percentage from
  a state signal. `car.basic.door_lock_status` (reads 1 parked) is still
  unconfirmed and stays unrendered.
- **TPMS is one interleaved key, not two arrays.** `car.basic.tpms_status` =
  `{2.48922,24.0,2.48922,24.0,2.28335,23.0,2.48922,24.0}` — (pressure bar,
  temperature C) per corner, FL, FR, RL, RR. `car.tpms.pressures` and
  `car.tpms.temperatures` are NOT REAL: they are in no CarConstants and nothing
  publishes them, yet the viewer subscribed them and a contract test asserted
  them, so the TIRES card read PRESSURE UNAVAILABLE on a car whose TPMS works.
- **HVAC is fully published and writable**: `car.hvac.power_mode`, `fan_speed`,
  `driver_temperature`, `pass_temperature`, `auto_enable`, `cycle_mode`,
  `anion_enable`, `blower_mode`, `front_defrost_enable`, `sync_enable`,
  `setting.comfort_curve`. The CLIMATE card's "CONTROLS UNAVAILABLE · NO VEHICLE
  SIGNAL" is a card that was never wired, not a missing signal.
- **The bus is not silent, but almost nothing changes.** Over 30 s parked, 309
  frames: 290 `car.basic.battery_voltage`, 19 `car.ev_info.cur_charge_current`,
  and nothing else. Latched state (doors, sunroof, tyre pressure, drive mode) is
  published once and then never again, so **per-key age is the wrong freshness
  test** — every card built on one went STALE two minutes after boot on a
  healthy bus. Battery voltage at ~10 Hz is the heartbeat; liveness is "have we
  heard anything at all", not "did this key change".

### The command API needs an Impulse build that has it

`ACTION_VEHICLE_COMMAND` (windows / sunroof / curtain / doors) and
`ACTION_UPDATE_CAR_DATA` (mode writes) are receivers in the Impulse **source**,
added in `7ba912c8` and `a0390c2e`. They are **not in the build installed on the
car** — `dumpsys package br.com.redesurftank.havalshisuku` lists its whole
receiver table and neither appears, and `pm dump` finds zero references to
either class. Every command the viewer sends is therefore broadcast into the
void, silently: `sendBroadcast` to a package with no matching receiver is not an
error, and nothing logs.

Check this FIRST when a command does nothing:

```bash
adb shell dumpsys package br.com.redesurftank.havalshisuku | grep -i Receiver
```

A working install shows `.broadcastReceivers.VehicleCommandReceiver` and
`.broadcastReceivers.CarDataWriteReceiver`. `ImpulseApiCallers` allowlists
`com.havalh6.viewer` by `PendingIntent.getCreatorPackage`, so once the receivers
are present the viewer needs no further registration.

### Outside-mirror fold (software write is dead on this unit)

- **Read:** `car.drive.setting.outside_view_mirror_fold_state` — live; `0` folded,
  `1` unfolded. Physical fold updates it.
- **Write via IVehicle:** `setRearViewMirrorFoldState` →
  `cmd.common.request.set` on that same key. Impulse returns `ok=true`; the
  value never flips. Measured 2026-09-10 with latest Impulse + viewer path.
- **Support gate in voice-adapter:** needs
  `persist.vendor.gwm.cfg.outside.rr.view.mirror ∈ [1,5]` (this car: 3) and
  `persist.vendor.gwm.cfg.osrvm.fold.virtual.sw.control == 1` (shipped 0; forcing
  to 1 and rebooting still did not actuate). Viewer hotspot + command names are
  commented out until a working write is found.

## Discovered vehicle properties relevant to cards

Property names below came from OEM DEX string tables. Exact types, enums, units,
freshness, and write semantics still need to be proven against the bridge and the
vehicle.

### Unified vehicle status

- Confirmed OEM concepts: `car.basic.door_control_action`,
  `car.basic.door_lock_status`, `car.basic.door_status`,
  `car.basic.sunroof_status`, and `car.basic.sunshade_status`.
- Tailgate may be encoded inside the general door status/control contract; its
  bit layout has not yet been documented here.
- Vehicle Center contains window behavior/control settings, and this audit did
  not establish a trustworthy per-window open percentage or open/closed status.
  **Superseded:** `car.basic.window_status` was later captured on the vehicle —
  see the observed-snapshot section above. **Polarity is now confirmed** too.
- Seat-belt reminder, vibration, slack reduction, driver-belt, and
  front-passenger-belt configuration concepts exist. This is not yet evidence of
  live buckle/occupancy status for each seat.
- UI rule: doors/locks/roof/sunshade may be mapped only after their native keys
  and enum values are observed. Windows and seat belts stay unavailable or
  clearly demo-labelled until an actual status signal is captured.

### Driving controls (recommended next card after Vehicle Status)

- `car.drive_setting.drive_mode`
- `car.drive.setting.drive_mode_memory`
- `car.drive_setting.steering_wheel_assist_mode`
- `car.ev_setting.energy_recovery_level`
- `car.ev_setting.energy_recovery_level_value`
- `car.ev_setting.power_model_config`
- `car.ev_setting.power_reserve_config`
- Related discovered concepts include ESP, DST, HDC, damper mode, accelerator
  sensitivity, speed limiter, charge target, EV priority, intelligent hybrid,
  ECO, and off-road modes.

Vehicle Center resources contain many powertrain-specific mode arrays. Do not
show a mode merely because it exists in an APK: variants must be filtered by
the vehicle's observed capabilities. The clean Coffee OS-inspired composition
is one card containing Drive mode, Power mode, and Regeneration, with secondary
settings progressively disclosed.

### Climate and comfort

- HVAC: `car.hvac.ac_enable`, `car.hvac.acmax_enable`,
  `car.hvac.auto_enable`, `car.hvac.power`, `car.hvac.sync`,
  `car.hvac.fan_speed`, `car.hvac.fan_speed_action`,
  `car.hvac.blower_mode`, `car.hvac.cycle_mode`, driver/passenger temperature
  values/actions, front/rear defrost, heating, AQS, anion, and PM2.5 concepts.
- Comfort: front/rear seat heating, ventilation and massage, steering-wheel
  heating, driver-seat control, seat memory, and welcome-seat behavior.

HVAC reads and writes are live through Impulse (see the observed snapshot above);
how the climate card treats them is in
[signals-and-limits](../signals-and-limits.md).

### Energy, range, and consumption

- Live/aggregate concepts include `cur_journey_odometer`,
  `kilometer_avg_elect_consumption`, `kilometer_avg_fuel_consumption`,
  `average_energy_consume_info`, `avg_energy_consume_info_since_reset`,
  `avg_energy_consume_info_since_startup`, `cycle_energy_consume_info`, and
  `cycle_fuel_consume_info`.
- Additional concepts include SOC, EV/fuel remaining range, battery current,
  motor power, charge state, charge connector/gun, remaining charge time,
  scheduled charge, charge-current configuration, V2L/V2V, battery heating, and
  insulation.
- The OEM energy application contains local history models and animated battery and
  energy assets. Its existence does not grant our app access to those records; see
  [energy-assistant](energy-assistant.md).
- Continue using the source and freshness rules in [signals-and-limits](../signals-and-limits.md); do not
  fabricate period charts from aggregate values.

### Media

MediaCenter contains day/night assets and states for radio, Bluetooth, USB
audio/video, play/pause, previous/next, shuffle, repeat, and media lists. It is
the most useful OEM reference for a later visual/functional media-card pass.
Our card must still disable transport when no active track and no working
`MediaBridge` are present.

### CarPlay UI request

The Impulse projection launcher uses the bound service `com.ts.carplay.CarPlayService`
and descriptor `com.ts.carplay.common.aidl.ICarPlayService`. `getLinkStatus`
(transaction `29`) must return `2` (activated) before `requestUi` (transaction
`20`) is sent synchronously with one integer argument, `0` (normal launch mode).
Both replies carry an exception header. This is a source-verified interoperability
contract, not a vehicle-verified launch from this viewer. Transactions `30` and
`31` are not UI-launch commands and must not be used as a show shortcut.

## Visual reference learnings

- OEM resources consistently provide paired day/night assets. New card imagery
  should preserve silhouette and state contrast in both themes rather than
  tinting one light-mode asset.
- Vehicle Center groups related controls into a single visual surface: driving
  modes together, body openings together, and seat/comfort functions together.
  This supports the current unified Vehicle Status card instead of separate
  door, roof, and seat-belt cards.
- Rich cards use a recognisable vehicle or component image as the primary
  information carrier, with labels and controls secondary. Use realistic local
  imagery where it materially clarifies state, while retaining lightweight SVG
  overlays for dynamic highlights.
- The OEM resource inventory is a visual reference, not permission to redistribute
  copyrighted assets. Generate or create project-owned equivalents rather than
  shipping extracted OEM bitmaps.

## Safe implementation rules

1. Cross-check every proposed key with the viewer bridge and Impulse's
   `ThemeBridgeImpl.getAvailableKeys()` before wiring the card.
2. Capture the value on the actual vehicle, including transitions, enum values,
   units, and stale/disconnected behavior.
3. Keep reads and writes separate. A readable property does not authorize a
   control command.
4. A write needs an explicit native allow-list, vehicle `READY` state, a pending
   UI state, timeout/error handling, and returned-state acknowledgement.
5. Preserve the card vocabulary: `LIVE`, `PARTIAL`, `STALE`, `UNAVAILABLE`, and
   `DEMO · SIMULATED · NOT VEHICLE`.
6. Throttle high-rate telemetry before React `setState`; the render loop can
   consume live fields independently where animation needs full bus cadence.
7. Validate each integrated card on the `Haval` emulator, then verify signal and
   command behavior on the car. The emulator validates layout and state logic,
   not permissions, CAN availability, or head-unit performance.

## Open questions

1. Door-status and tailgate bit layout, and the sunroof and sunshade enum values.
2. `car.basic.door_lock_status` polarity, and any real seat-belt buckle or occupancy signal
   (only `car.basic.seat_belt_warning` `{1,0,0,0,0}` has been seen).
3. Drive mode, power mode and regeneration reads across the supported H6 variants, with
   acknowledgements tested before any write is enabled.
4. Whether ExtProvider exposes a stable, permission-safe read path or is only an internal
   implementation detail of the OEM software.

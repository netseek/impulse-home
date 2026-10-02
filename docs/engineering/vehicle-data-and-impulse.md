# Vehicle data and Impulse

What the vehicle bus does and does not provide, how Impulse publishes it, and the places where
a plausible signal turned out not to exist. Check the live key list on the car before concluding
that a signal is missing.

## A refuel does not need the car switched off

`TripEngine.onLevel` only ever compared the gauge against the level at READY-off,
so a fill taken with the car still READY was discarded on its first line and no
`trip_stops` row was ever written for it. Measured on the car 2026-09-23, from
`trips.db`: the open trip ran 17:36 -> 19:47 with **1711 points and no gap over
11 s** (READY never dropped), its own `fuelPctStart`/`fuelPctEnd` recorded
**8 -> 100** correctly, and `trip_stops` held three rows, all `charge`, newest
three days earlier. `REFUEL_MIN_PCT` was never the obstacle — a 92-point jump
clears it 23x — the reading simply never reached the threshold test.

The evidence was already on disk one field over, which is the tell for this
class of bug: **when a detector is gated on a state transition, check whether
the thing it is trying to notice is already recorded by something that is not.**

There are now two baselines, and the in-trip one needs its own coarser gain:

| arm | baseline | min gain |
|---|---|---|
| parked | level at READY-off, at the parking spot | `REFUEL_MIN_PCT` 4.0 |
| in-trip | level once stood still `STAND_ARM_MS`, where it stood | `STAND_REFUEL_MIN_PCT` 10.0 |

`STAND_ARM_MS` (60 s) exists because a baseline taken the instant the wheels
stop is sometimes a slosh trough, and `STAND_REFUEL_MIN_PCT` is 10 points
(5.5 L) for the same reason — above any slosh, below any real fill. READY-off
has no such problem, hence the two thresholds. Driving off settles a pending
fill immediately; standing still settles it after `STAND_SETTLE_MS`.

Two traps found by the tests rather than by reading:

- **`duringStop` is also true for the 3 minutes after setting off**
  (`LEVEL_SETTLE_MS`). With no READY-off baseline there is nothing for the
  parked arm to compare against, so it must fall THROUGH to the in-trip arm
  rather than returning — a fill early in a trip was invisible until it did.
- A negative control is worth the minute it costs: disabling the in-trip arm
  fails exactly the two positive tests, and dropping the in-trip threshold to
  4.0 fails exactly the slosh test. Neither was decoration.

## There is no fuel-litres signal — it is derived, and the constant is shared

`CAR_SIGNALS` carries fuel *consumption* (`fuelInst`, `fuelTrip`, `fuelAvg`,
`cycleFuel`) and fuel *range* (`fuelRange`, km), and for a long time nothing
that says how much is actually in the tank. The level is
**`car.basic.remain_fuel_percentage`** — Impulse publishes it, and
`ThemeBridgeImpl.getAvailableKeys()` is where it is exposed to the cluster
themes, alongside `fuel_mode_remain_odometer`.

**Litres are not on the bus at all.** Impulse derives them in
`DashboardCardLayout.formatDashboardFuelLiters` as `percent * 55 / 100` at one
decimal, from `DASHBOARD_FUEL_TANK_CAPACITY_LITERS = 55f` in `BottomBarUI.kt`;
its dashboard card prints the pair as `"31.9 L - 210 km"`. The viewer subscribes
the level but does not currently display litres -- a floor badge that did was
removed as redundant with the POWER card. **If you add one back, derive them the
same way rather than picking a tank size**, or the two screens will quote
different amounts of fuel for one tank, which is invisible in either app alone.

Impulse is the sibling repo `haval-app-tool-multimidia`
(package `br.com.redesurftank.havalshisuku`). Its theme bridge is the reference
for which car keys are actually readable, so reach for it before concluding a
signal does not exist — this one was written off as missing more than once.

**Better than reading the bridge: ask the car.** Impulse replays its whole cache
on request, so the definitive list of ~170 live keys is one command away:

```bash
adb shell am broadcast -a com.haval.vehicle.REQUEST_SNAPSHOT -p br.com.redesurftank.havalshisuku --es requester probe
adb logcat -d | grep CarSignal
```

Two keys the viewer had invented (`car.tpms.pressures`, `car.tpms.temperatures`)
and one it had been told did not exist (`car.basic.window_status`) were both
settled this way in a minute. See `docs/oem-apk-can-reference.md` for the
capture, and for why a **per-key** freshness window is the wrong staleness test
on this bus.

**And check that the installed Impulse has the command receivers before
debugging a command that does nothing** — `sendBroadcast` to a package with no
matching receiver fails silently:

```bash
adb shell dumpsys package br.com.redesurftank.havalshisuku | grep -i Receiver
```

A working install lists `.broadcastReceivers.VehicleCommandReceiver` (windows /
sunroof / curtain / doors) and `.broadcastReceivers.CarDataWriteReceiver` (mode
writes). Measured 2026-09-09: the car had NEITHER, so every command and every
mode write was broadcast into the void. The classes were committed in Impulse
all along; only `VehicleCommandReceiver` was declared in its manifest. Fixed
upstream in `6d053976` on `feature/new-screen-enhancements-v8`.

### Software mirror fold is a no-op on this MMI (2026-09-10)

Status **read** works: `car.drive.setting.outside_view_mirror_fold_state`
(`0` = folded, `1` = unfolded). The viewer hotspot / Impulse
`fold_mirrors` / `unfold_mirrors` / `toggle_mirrors` path is wired end-to-end
(viewer allowlist → `ACTION_VEHICLE_COMMAND` + caller PendingIntent →
`VehicleCommandReceiver` → `IVehicle.setRearViewMirrorFoldState`), and Impulse
logs `ok=true`, but the bus value never changes.

What the OEM voice-adapter actually does:

- `setRearViewMirrorFoldState(n)` →
  `PlatformAdapterClient.requestCmdAsync("cmd.common.request.set",
  "car.drive.setting.outside_view_mirror_fold_state", String.valueOf(n))`
- `isSupportRearViewMirrorFold()` returns 1 only when
  `persist.vendor.gwm.cfg.outside.rr.view.mirror` is in **1..5** (this car: **3**)
  **and** `persist.vendor.gwm.cfg.osrvm.fold.virtual.sw.control == 1`
  (this car shipped at **0**).

Forcing the virtual-SW prop to `1` (persists across reboot) did **not** make
the set actuate. Physical fold still updates the status key. The mirror
hotspot and command allowlists are therefore commented out / hidden for
reference until a working write path is found (likely deeper than IVehicle —
`Its_IntelligentVehicleControlService` accepts the key in its subscribe list
but ignores the set).

**Do not** send a bare Android `stop` over the car's root telnet while probing
— that tears down zygote; recover with `start`.

**Installing Impulse does NOT need a release keystore — `assembleDebug` is
correct.** Corrected 2026-09-11; the previous note here sent a session hunting
for a keystore that was never involved.

`7e00ad11...` is not a release signer. Read the certificate rather than assuming:

```bash
adb pull "$(adb shell pm path br.com.redesurftank.havalshisuku | sed 's/^package://')" impulse-on-car.apk
"$LOCALAPPDATA/Android/Sdk/build-tools/36.1.0/apksigner.bat" verify --print-certs impulse-on-car.apk
```

That prints `CN=Android Debug`, SHA-256 `7e00ad11a25d1e24c7ea609123510b332b663cf0a3f655219666c2209533ee2a`
— which is **this machine's `~/.android/debug.keystore`** (`keytool -list -v
-keystore ~/.android/debug.keystore -storepass android` matches it exactly). So
`./gradlew assembleDebug` here reproduces the same signer and `pm install -r`
upgrades IN PLACE: bottom bar, cluster themes, overscan and the Shizuku grant
all survive. There is no `app/release.keystore` in the repo and the
`SIGNING_*` env vars are unset, so `assembleRelease` cannot be built anyway.

`6a44a729` was presumably a different machine's debug key. Debug keystores are
per-machine, so **the only safe check is to compare fingerprints**, never to
assume "debug" means one fixed key.

Two practical notes. `adb install` stalls silently on this unit — push then
`pm install` instead, with the Autolink installer identity:

```bash
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/impulse.apk
adb shell pm install -r -i com.autolink.installer /data/local/tmp/impulse.apk
```

And the APK is ~135 MB, so the push takes ~80 s at the LAN's ~1.7 MB/s. Pull
the installed APK BEFORE upgrading: it is signed with the same key, so it is a
one-command rollback.

### Hazard is not on the Android side at all (measured 2026-09-11)

Asked for repeatedly, because the lamps are plainly visible through the
windscreen. They do not reach the head unit. Measured across 95 min parked, a
20 min drive with 11 indicator uses, and two deliberate hazard presses:

- `car.basic.hazard_light_status` — **never fires.** It sits at `0` and is
  replayed as `0` in every snapshot. Do not build on it.
- `car.basic.left/right_turn_light_status` — **do not assert during hazard.**
  Both stay `0`.
- `car.drive.setting.outline_lamps_state` — does not move during hazard.

So the "position lamps go dark and flash amber" that you can SEE is done inside
the body control module and is never published. There is no key to find.

**What the turn keys DO give you, and it is better than a blink.** They report
STALK STATE, not the lamp flash: a single `1` held for the whole turn, then `0`.
Measured 11 turns at 2.0-10.4 s each, and the two sides never overlapped once —
so `left && right` WOULD have been an unambiguous hazard discriminator if hazard
drove them. It does not. Flash-to-pass is also readable: `high_beam` and
`low_beam` both `1` for ~100 ms, then both `0`.

**The trap that burned this session.** `car.ipk_info.warning_tts_notify` pulses
`1121 -> 0` every 5.58 s, and it happened to be running during the hazard test.
It looked conclusive — 0 occurrences in 115 min of earlier captures, 6 during
hazard, phase-locked to the millisecond. It is a repeating CLUSTER WARNING NAG
(the driver could read it on the panel) and it ran unbroken for 94 pulses across
8m39s, straight through hazard being switched OFF. Two lessons:

- **A periodic pulse is a nag timer, not a lamp circuit.** Lamps blink at
  ~1.5 Hz; 5.58 s is something the car is SAYING.
- **Controls recorded on a different day are not controls.** The absence of 1121
  in earlier captures meant only that the warning was not active then. A
  correlation needs the negative arm measured in the SAME session — here, simply
  watching it keep running with hazard off.

`warning_tts_notify` is an announcement-id channel (Impulse's
`ClusterWarningPolicy` lists it among transient alerts); it also carried `1083`
during normal driving. Any value there names something the cluster is
announcing, never a lamp state.

**How to re-test cheaply.** `Its_IntelligentVehicleControlService` logs
`onDataChanged` for every property the car pushes, upstream of Impulse:

```bash
adb logcat -v time -s Its_IntelligentVehicleControlService:W | grep onDataChanged
```

Two cautions. Filter out `battery_voltage` — it streams at 10 Hz and rotates the
ring buffer in about 20 s, so `logcat -d` cannot see an event from a minute ago;
only a live stream works. And keys arriving at the viewer WITHOUT a matching
`onDataChanged` are Impulse cache replays, not live events — they land within
~400 ms of a "Telemetry snapshot requested" line, which is how to tell.

## The navigation card's idle city needs a NETWORK geocoder, not the platform one

The card has two halves and they arrive by different routes. **TBT is fine and
was never the problem** — `app.androidauto.session` and
`app.navigation.directions` are Impulse keys on the same `EVENT_CHANGED` bus as
everything else, and both land. Verified on the car 2026-09-11 with a snapshot
request:

```
H6Viewer: CarSignal ... key=app.androidauto.session   value=stopped
H6Viewer: CarSignal ... key=app.navigation.directions value={"active":false}
```

The IDLE half — the city / street shown when nothing is guiding — is
`app.location.place`, which the viewer publishes to itself from `PlaceGlance`.
It was arriving never, for two independent reasons, and the card's honest
fallback for that is `—`. Both are worth knowing because both look like nothing:

- **A manifest `<uses-permission>` for location is not a grant.** targetSdk is
  28, so `ACCESS_FINE/COARSE_LOCATION` is a RUNTIME permission, and
  `MainActivity` requested only `RECORD_AUDIO`. `PlaceGlance.pollOnce` therefore
  returned on its first line on every 45 s tick, silently, forever. The tell is
  one line of `dumpsys package com.havalh6.viewer`:

  ```
  runtime permissions:
    android.permission.RECORD_AUDIO: granted=true
  ```

  `ensurePlaceLocationPermission()` now asks once at startup. A denial is not
  retried.

- **`android.location.Geocoder` does not work on this MMI**, and it fails in the
  most misleading way available: `Geocoder.isPresent()` returns **true**, and
  then every `getFromLocation` throws `Service not Available`. The reason is in
  `dumpsys location`:

  ```
  Overlay Provider Packages:
    network: null
  ```

  The geocode backend is supplied by whichever package implements the NETWORK
  location provider, and nothing here does — GMS on this unit is ReVanced-patched
  (`app.revanced.android.gms`) and registers neither. This is permanent, not a
  transient bind failure, so retrying buys nothing.

GPS itself is healthy and is NOT the missing piece: `dumpsys location` reports a
real fix with `hAcc=1` and 7 satellites. Only lat/lon -> name was missing.

`PlaceGlance` now tries the platform geocoder once, latches it off on failure
(`platformGeocoderDead`), and reverse-geocodes over HTTPS instead. Three things
that path has to keep right:

- **Gate it on movement, not just on the 45 s tick.** `REGEOCODE_MIN_MOVE_M`
  (120 m) skips the request when the car has not left the block, which keeps a
  parked unit off the network entirely and a moving one far inside Nominatim's
  1 req/s policy. Its usage terms also require an identifying `User-Agent`.
- **Nominatim's `address` object has no fixed shape.** Which key carries "where
  you are" depends on how the area was mapped, so walk them most-local-first —
  the same order the platform path walks `subLocality -> locality ->
  subAdminArea`. Measured at the car's own fix, `suburb` was the useful answer and
  `town` was an administrative region nobody says out loud.
- **Staying silent is correct when it fails.** Offline, the card shows `—`
  rather than a stale or invented city.

## Range forecast check: native records it, the expanded range popup draws it

**Added 2026-09-22, NOT YET SEEN ON THE CAR.** The history range estimate in
`_rangeTelemetry` used to be recomputed per render and thrown away, so there
was no way to tell whether it was right. `RangeLedger` (native, fed by
`TripRecorder`) now keeps one cycle per charge in `trips.db` (`range_cycles` /
`range_samples`, DB v5): the forecast at the start (the car's EV range off the
bus, and the page's history estimate), then a sample per SOC point. The range
popup's maximised view (`_rangeBurnView`) plots absolute SOC % vs distance, with
two diagonals from `(0, soc0)` to each starting forecast's empty point (history
and the car). Above a diagonal = lasting longer than that forecast.

Things that are easy to break:

- **The history estimate only exists in JS**, so `_startRangeForecastReporter`
  sends it down (`TripBridge.setRangeForecast`) every 15 s, with the SOC it was
  worked out for. The ledger ignores it when that SOC is not the car's current
  one; right after a charge the page is still quoting the old level. Do not copy
  the formula into Java instead: two copies of it will drift apart.
- **Charges are detected by the ledger itself** (SOC >= 8 points above the last
  sample), not by TripEngine's CHARGE stop, which becomes final only minutes into
  the next drive; by then that driving would be credited to the old cycle.
  `RangeLedgerTest` pins this.
- **An odometer jump that no trip accounts for ends the cycle as `gap`**, so
  driving the recorder missed is never read as a bad forecast.
- **Every cycle starts at a charge** (product decision): nothing is recorded before
  the first charge after install, and after `gap` or `depleted` the next cycle
  waits for a charge too. TripEngine's CHARGE stop (`onCharge`) is only the
  fallback for when there is no earlier SOC to compare against.

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

# Haval H6 3D Android APK

This directory is both the Three.js source and a minimal native Android app.
The APK runs entirely offline in the system WebView and starts the viewer in
regular rendering mode with an Android-specific decoder compatibility flag.

## Requirements

- Android SDK Platform 36 and Build Tools
- JDK 21
- Gradle 9.3.1 (or import the project into a compatible Android Studio version)

No Java/Kotlin library dependencies and no Android network permission are used.

## Build

From the repository root:

```powershell
gradle assembleDebug
```

The debug APK is created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

For a smaller signed release, configure a release signing key and run:

```powershell
gradle assembleRelease
```

The build copies `index.html`, `support.js`, `vendor/`, and production files
under `assets/` into the APK. It intentionally excludes `assets/_backup/`.

## Freeform popups (emulator)

Stock Android emulators often have freeform disabled, so app launches ignore
windowing-mode options and open fullscreen. Enable once per AVD:

```powershell
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
```

Then relaunch the viewer and tap an app icon — it should float over the launcher.
On the Haval MMI, freeform is typically already available from the OEM.

Maps / browser / generic apps open in the **left** freeform slot. Spotify, YouTube
Music, Deezer, and similar music apps open in the **right** slot. Re-tapping the
same music icon closes that window and restores the idle now-playing card.

## Now playing (notification listener)

The idle media card reads the active `MediaSession` via a notification listener
(session watch runs inside `MediaNotificationListener`). Without that access the
column shows “Notification access required”; music apps still launch into the
right slot. Enable once (re-run after each reinstall):

```powershell
adb shell cmd notification allow_listener com.havalh6.viewer/com.havalh6.viewer.MediaNotificationListener
```

Then reopen the viewer. Transport buttons (prev / play-pause / next) talk to the
session’s `MediaController`.

Opening **CONFIG** (top-right) hides the media rail and dismisses any right-slot
music app; closing CONFIG restores the idle rail.

## Runtime design

- Native Java activity; no Capacitor, Cordova, React Native, or AndroidX.
- Fullscreen immersive landscape with navigation/status bars hidden.
- Hardware-accelerated WebGL through the MMI's system WebView.
- Offline packaged resources with no `INTERNET` permission.
- `?android` keeps regular rendering while forcing the JavaScript Draco decoder
  required by the Haval MMI's older System WebView.
- Backup models are not packaged, keeping approximately 176 MB of development
  assets out of the APK.

The Three.js/WebGL feature set still depends on the Android System WebView
version installed by the MMI. Test the debug APK on the actual head unit before
locking the release SDK/signing configuration.

## Car telemetry

Transport (already in place):

```text
CAN bus → Shizuku IPC
  → havalshisuku ServiceManager.dispatchTelemetryOnly(key, value)
  → public broadcast {key, value}
  → MainActivity.telemetryReceiver  (registered in onCreate, held across pause)
  → window.onCarDataUpdate(key, value)
  → carTelemetry  →  Component._applyCarSignal
```

Two broadcast actions are accepted, because the havalshisuku source trees differ
on which one they send and both carry identical `key`/`value` string extras:

| Action | Sent by |
| --- | --- |
| `com.haval.vehicle.EVENT_CHANGED` | `haval-app-tool-multimidia` (`dispatchTelemetryOnly`) |
| `br.com.redesurftank.havalshisuku.CAR_DATA_UPDATE` | `haval-app-tool-multimidia-h6-3d` (`TelemetryPublisher`) |

Registering only one of them is silent failure — nothing arrives and the model
never moves. The per-key `android.intent.haval.*` broadcasts are **not** usable
here: they are `setPackage()`-scoped to havalshisuku itself.

### Freeform task ids (keeping popups on top)

The MMI is Android 9, where freeform windows are ordinary stacks in one z-list:
tapping the viewer focuses its stack and buries the popup. The viewer re-raises
the popup with `moveTaskToFront`, which its `REORDER_TASKS` permission allows —
but it cannot *discover* a foreign task id (`getRunningTasks` filters other apps
out, and `registerTaskStackListener` is denied without `MANAGE_ACTIVITY_STACKS`).
Haval Impulse has that reach via Shizuku and resolves the id on request:

| Action | Direction | Extras |
| --- | --- | --- |
| `…havalshisuku.ACTION_RESOLVE_TASK` | viewer → Impulse | `package`, `slot` |
| `…havalshisuku.ACTION_TASK_RESOLVED` | Impulse → viewer | `package`, `slot`, `taskId` |

Both are `setPackage()`-addressed to the *other* app, so they reach manifest and
runtime receivers under Android 8+ background limits. One lookup per popup
launch (and per failed raise) — never per tap: Impulse resolves it by parsing
`am stack list` through a Shizuku shell round trip (~150-200 ms), and hammering
that starves `shizuku_server`'s heap. The per-tap path stays in-process.

Without Impulse the viewer still works: `raiseOverlayTask` falls back to
re-starting the activity with `REORDER_TO_FRONT | NO_ANIMATION`, which restores
the window with a visible blink.

Keys are `CarConstants` values and every value arrives as a **string**. The bus
caches every key it sees; the viewer consumes only the ones in `CAR_SIGNALS`
(top of the `data-dc-script` block in `index.html`):

| Viewer effect | Car key |
| --- | --- |
| Doors + trunk | `car.basic.door_status` |
| Sunroof | `car.basic.sunroof_status` |
| Sunshade / curtain | `car.basic.sunshade_status` |
| Speed | `car.basic.vehicle_speed` |
| Steering (road wheels) | `car.basic.steering_wheel_angle` |
| Low beam (`headlight`) | `car.basic.low_beam_light_status` |
| High beam | `car.basic.high_beam_light_status` |
| Position / DRL | `car.basic.low_light_status` |
| Front / rear fog | `car.basic.front_fog_light_status`, `car.basic.rear_fog_light_status` |
| Hazard | `car.basic.hazard_light_status` |
| Turn L / R | `car.basic.left_turn_light_status`, `car.basic.right_turn_light_status` |
| Day/Night Auto | `isNight` |

### Value encodings

`car.basic.door_status` is **not** an integer bitmask. It is a brace-wrapped
per-door vector, captured off the car:

```text
{1,0,0,0,0,0}   driver door open
{0,0,0,0,0,0}   everything shut
```

`CAR_DOOR_SLOTS` maps opening → index:

| Slot | Opening | Status |
| --- | --- | --- |
| 0 | driver / front-left | **verified** |
| 1-3 | fr, rl, rr | assumed, conventional order |
| 4 | unidentified (likely bonnet) | nothing mapped to it |
| 5 | tailgate | **verified** |

Slot values are **not all binary**. The powered tailgate reports travel states —
a full open/close cycle was captured as `-1 → 3 → 1 → 5 → 4 → -1 → 0`. The
decoder therefore reads any positive value as "not shut", `0` as shut, and any
negative value as unknown (the mesh is left where it is rather than guessing).

`_applyCarDoors` also accepts a plain integer bitmask as a fallback, and
`_carBool` unwraps the same brace shape, since some keys are scalars
(`car.ipk_light.door_warning` → `1`) and others are vectors.

`car.basic.vehicle_speed` is still assumed to be km/h — unconfirmed, the car has
not moved during testing.

`car.basic.steering_wheel_angle` is the **steering wheel** angle (roughly +/-500
deg lock to lock), while the viewer's `wheelAngle` is the **road wheel** angle,
capped at +/-35. `CAR_STEERING_RATIO` (14.3) converts between them and is
UNCALIBRATED: turn the wheel to full lock with `?cartrace` on, read the peak
value, and set the ratio to peak/35. Sub-degree changes are ignored so a live bus
cannot re-run the wheel transform every frame.

### Reading raw values off the car

Debug builds log every incoming broadcast before any decoding:

```powershell
adb -s <device> logcat -s H6Viewer | Select-String CarSignal
```

> **This ROM silently drops `Log.i` for this app.** Zero info-level lines ever
> reach logcat, while `Log.w` and `Log.e` come through — which is why the capture
> line uses `Log.w`. If a native log seems to vanish, check its level before
> assuming the code did not run.

`?cartrace` on the viewer URL adds the JS-side view of the same stream (which
signals the router actually matched), useful when a key arrives but nothing moves.

Injecting a signal by hand, to test without the car changing state:

```bash
adb -s <device> shell "am broadcast -a com.haval.vehicle.EVENT_CHANGED --es key car.basic.door_status --es value '{1,0,0,0,0,0}'"
```

### Cold start needs a snapshot

The event stream is **change-only**. The viewer starts long after havalshisuku,
so a car parked with its lights on looks identical to one with them off — no
event ever fires for state that settled before launch. This was the "status at
startup" bug.

`ServiceManager.dispatchAllData()` already re-broadcasts every current value, but
nothing external could trigger it. There is now a public request:

| Action | Direction |
| --- | --- |
| `com.haval.vehicle.REQUEST_SNAPSHOT` | viewer -> havalshisuku |

`MainActivity` fires it on startup plus two retries (4 s, 15 s) because
havalshisuku may still be coming up. The request is `setPackage()`-addressed to
havalshisuku. It only reads: `dispatchAllData()` publishes through
`dispatchTelemetryOnly`, which broadcasts and caches but never actuates hardware.

**Requires a havalshisuku rebuild + install.** Until then the viewer asks and
nothing answers — harmless, and visible as `Telemetry snapshot requested` in the
viewer log with no matching `ServiceManager` line.

### Trunk opening angle is not available

There is no tailgate position or angle key anywhere in `CarConstants` — the only
tailgate signal is slot 5 of `door_status`, and it is a state enum, not a
percentage. So the viewer cannot mirror a partially-raised tailgate; it animates
its own open/close sweep toward fully-open or fully-shut. The travel states
(`3` opening, `4` closing) do tell us motion is happening, which is why they are
treated as "not shut" rather than ignored.

### There is no trunk key

`CarConstants` has no trunk/tailgate state key — `car.comfort_setting.auto_open_back_door`
is a setting, not a state. The tailgate rides in the `door_status` vector, which
is why six slots cover four doors. It is wired to `CAR_DOOR_SLOTS.trunk` (slot 4).

### Publisher gaps: sunroof, sunshade, `isNight`

`ServiceManager.DEFAULT_KEYS` in `haval-app-tool-multimidia` subscribes
`door_status`, `vehicle_speed` and all the `*_light_status` keys, so those flow
as soon as the actions match. Three signals do not:

- **`CAR_BASIC_SUNROOF_STATUS` / `CAR_BASIC_SUNSHADE_STATUS`** were absent from
  `DEFAULT_KEYS`, so havalshisuku never registered for them and the car service
  never sent them. Both are now added there (read-only: nothing in
  `OnDataChanged` branches on either key, and the `autoOpenSunroofCurtain`
  actuation is gated by startup flags plus a time/temperature window, not by
  these signals). **Requires a havalshisuku rebuild + install to take effect.**
- **`isNight`** exists only as a private extra on `AutoBrightnessManager`'s own
  alarm intent (`AutoBrightnessManager.kt:283,300`) and never reaches
  `dispatchTelemetryOnly`. Until it does, Time → **Auto** resolves to day.

The viewer decoders for all three are written and verified by injection, so they
start working the moment the car app publishes.

### Cold start

The broadcast path has no snapshot event, so `telemetryCache` starts empty and
the viewer only learns a signal once it changes. `_replayCarTelemetry()` pulls
whatever the cache holds through `TelemetryBridge.getCarData` when the scene
becomes ready, which covers events that arrive while the model is still loading —
but not state that last changed before the app started.

A full snapshot is available on the WebSocket path (`TelemetryPublisher` sends
`{event:'snapshot'}` on connect, `ws://127.0.0.1:8888`). The APK cannot use it:
loopback sockets still require `android.permission.INTERNET`, which this app
deliberately does not hold. Granting it would fix cold start properly.

## Time mode (Day / Night / Auto)

The viewer config panel exposes `timeMode`: `day`, `night`, or `auto`.

- **Day / Night** force the scene lighting locally.
- **Auto** follows the `isNight` key (`"true"` / `"false"`) — see the publisher
  gap above.

Persisted viewer preferences (including `timeMode`) are stored in WebView
`localStorage` under `h6_settings_v1` when the user taps **Save**. Live car
signals are applied after saved settings on load, so the car wins over them.

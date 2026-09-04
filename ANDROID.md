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

## Emulator deploy

Local deploy always uses the **`Haval` AVD** (head-unit profile, 1920×720). Do
not use a phone/tablet AVD such as `Medium_Phone_API_36.0` — aspect ratio and
freeform behaviour will not match the MMI.

```powershell
.\scripts\deploy-emulator.ps1
```

The script starts the Haval AVD if it is not already running, waits for boot,
then installs and launches. Reinstall only: `-SkipBuild`.

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
column shows “ENABLE MEDIA ACCESS”; music apps still launch into the
right slot.

`scripts/deploy-car.ps1` re-asserts the grant on every deploy
(`Grant-CarMediaAccess`), so normally there is nothing to do. Do it by hand only
when installing some other way:

```powershell
adb shell cmd notification allow_listener com.havalh6.viewer/com.havalh6.viewer.MediaNotificationListener
```

**The grant is keyed on the component and an uninstall drops it**, and
`Install-CarApk` uninstalls whenever the signature does not match. That is the
whole failure mode behind “the media widget stopped showing anything”: MediaCenter
still covers Android Auto and USB, so Android Auto keeps working while every app
with a real `MediaSession` — YouTube, Spotify, a browser — goes blank. Check it
with:

```bash
adb shell settings get secure enabled_notification_listeners
```

The viewer's component has to appear in that colon-separated list. A live
session shows `controllers: 2` (the app plus the viewer) under
`adb shell dumpsys media_session`.

Then reopen the viewer. Transport buttons (prev / play-pause / next) talk to the
session’s `MediaController`.

Opening **CONFIG** (top-right) hides the media rail and dismisses any right-slot
music app; closing CONFIG restores the idle rail.

### Projection audio never has a MediaSession

Android Auto and CarPlay play through the projection stack, which registers no
`MediaSession` at all — the notification listener sees nothing while either is
playing. Both are read straight off the head unit's own binders instead
(transaction ids and parcel layouts mirror Haval Impulse, which drives the same
services):

| Source | Class | Service | How |
|---|---|---|---|
| Android Auto, USB | `MediaCenterSource` | `com.beantechs.mediacenter` | poll `getCurrentSource`, then media info + play state for source `402` (AA) or `2` (USB) |
| CarPlay | `CarPlaySource` | `com.ts.carplay` | register a now-playing callback binder; artwork arrives as raw JPEG bytes |

`MediaNowPlaying` keeps the latest track per source and picks a winner —
playing beats paused, and between equals projection beats a plain session — so
a paused Android Auto session cannot hide the app you are actually listening to.
Play/pause routes back to whichever source won (MediaCenter `resume`/`pause` by
source, CarPlay HID over iAP); skip falls back to media keys.

### Cover art resolution

Bitmaps sent inside `MediaMetadata` are capped at 320dp by the framework, which
on this 160dpi unit means 320px stretched across a ~620px card. So art is taken
from the highest-resolution source available: an art *URI* is decoded locally
(uncapped) and wins over the parceled bitmap, and the encode cap is 720px.

The YouTube app is a special case — it publishes **no** artwork: no bitmap, no
art URI, not even a video id (`TITLE`, `ARTIST`, `ALBUM_ARTIST`, `DURATION` and
two video-size keys, and `largeIcon=null` on the notification). Its title is
exact, so the video is looked up by name and the `i.ytimg.com` thumbnail is used
— one request per track, cached, tried maxres → hq720 → hq → mq.

### Logging

The head unit sets `persist.log.tag=WARN`, so **`Log.i` never reaches logcat**.
Media diagnostics use `Log.w` for that reason. To see everything for a session:

```powershell
adb shell setprop log.tag.H6Media DEBUG
```

## Runtime design

- Native Java activity; no Capacitor, Cordova, React Native, or AndroidX.
- Fullscreen immersive landscape with navigation/status bars hidden.
- Hardware-accelerated WebGL through the MMI's system WebView.
- Offline packaged resources with no `INTERNET` permission.
- `?android` selects the mobile performance tier (`_perfMobile`): reduced
  post-processing resolution, fewer anamorphic streak taps, no transmission
  render target, no framebuffer preservation, and an adaptive pixel-ratio floor
  of 0.75. None of these change what the car looks like. There is no separate
  `?lite` / `_perfLow` quality hammer.
- Draco uses the WASM decoder with a single worker on Android. It was previously
  pinned to the JavaScript decoder after WASM worker crashes, but on
  WebView 91.0.4472.114 / SA8155 it measures clean and roughly 3x faster.
- Textures are KTX2/UASTC (`KHR_texture_basisu`) and transcode to ASTC 4x4 on
  the Adreno 640, so a 2048² map costs 4 MB of VRAM instead of 16 MB. Requires
  `vendor/basis/` in the APK — see `scripts/build-ktx2-textures.mjs`.
- Backup models are not packaged, keeping approximately 176 MB of development
  assets out of the APK.

Frame metrics reach logcat from debug builds via `TelemetryBridge.reportPerf`:

```bash
adb logcat -s H6Perf
```

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

### Vehicle body controls (viewer → Impulse)

The viewer sends body actions only through the explicit, package-scoped Impulse
contract below. The native bridge rejects commands outside this list before it
ever broadcasts, so an asset-page regression cannot become arbitrary command
injection.

| Action | Extras | Meaning |
| --- | --- | --- |
| `br.com.redesurftank.havalshisuku.ACTION_VEHICLE_COMMAND` | `caller`, `command`, optional `value` | Viewer → Impulse body-control request |

`command` may be one of these value-less actions:

- `open_windows`, `close_windows`, `open_sunroof`, `close_sunroof`, `open_curtain`, `close_curtain`
- Legacy-compatible: `toggle_windows`, `toggle_sunroof`, `toggle_curtain`, `toggle_doors_all`, `toggle_trunk`

For precise controls it may instead be `set_windows_level`,
`set_sunroof_level`, or `set_curtain_level`, with `value` as an integer from
`0` through `100`. The viewer debounces drag updates but flushes the final value
on release. Impulse should perform its normal safety/interlock checks, then
publish the resulting status through the usual telemetry path; that telemetry is
the authoritative state for the renderer.

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
| Position / clearance lamps | **UNRESOLVED** — see below |
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

### Sunroof is tilt + slide, not one axis

`getSkylightLevel()` reported **200 with the lid merely tilted** (raised at the
rear edge, not slid back), confirmed on the car. Scaling that linearly showed a
vent as fully open. `_applySunroofTransform` already models both phases — below
ratio 0.25 it lifts with no rearward slide, above it slides — so tilt maps onto
that lift point:

| Value | Meaning | Viewer |
| --- | --- | --- |
| `0` | shut | 0% |
| `200` | tilted / vented (**confirmed**) | 25% — lift only |
| `1-100` | slide position (**assumed**) | 25-100% |

The slide range still needs a full open sweep to confirm.

### Position lamps: the car exposes no AUTO-mode state

Established by capturing every signal the vehicle service pushed to every app
while cycling the stalk OFF -> position -> AUTO -> low beam -> AUTO. In that
whole window exactly **two** light keys moved, and nothing else:

| Key | Behaviour |
| --- | --- |
| `car.drive.setting.outline_lamps_state` | `1` while the stalk is on position, else `0` |
| `car.basic.low_beam_light_status` | `1` while the stalk is on low beam, else `0` |

Both read `0` on AUTO — even with the lamps visibly lit. `outline_lamps_state`
is the **stalk selection**, not lamp output, which is exactly why manual modes
looked right and AUTO did not.

Ruled out along the way:

- `car.basic.low_light_status` and `car.basic.head_light_status` — in
  `DEFAULT_KEYS`, but the vehicle returns empty for both, so `dispatchAllData`
  skips them. Dead on this car.
- `car.ipk_light.*` cluster tell-tales — no position-lamp indicator exists.

So the viewer derives what it can: position lamps are physically always lit when
a beam is on, giving

```text
position = stalk-selected OR low beam OR high beam
```

This is correct for every manual position and for AUTO at night. **AUTO in
daylight is not solvable from the available signals** — the lit lamps are most
likely DRLs, which are not published at all.

Untried next step, if it matters enough: add `CAR_CONFIGURE_AUTO_HEADLIGHT`,
`CAR_CONFIGURE_LIGHT_AUTO_SWITCH_SYSTEM`, `CAR_CONFIGURE_COMB_FRONT_LIGHT_SRC`
and `CAR_CONFIGURE_PARKING_LIGHT` to `DEFAULT_KEYS` and re-snapshot. They are
`configure.*` keys so they may only report the setting, but one of them might
expose the AUTO output. Needs a havalshisuku rebuild.

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

### The position / DRL lamp has no published signal

Verified exhaustively, not inferred. The front position lamp was observed
**physically lit** in four different vehicle states:

| State | `driving_ready` | `hvac.power_mode` | charging | stalk |
| --- | --- | --- | --- | --- |
| morning, AUTO | 1 | 1 | no | AUTO |
| midday, settling | 0 | 0 | no | AUTO |
| charging | 0 | 0 | yes | AUTO |
| OFF mode | 0 | 0 | yes | OFF |

In **all four**, every published light key read `0` — `low_beam`, `high_beam`,
`front_fog`, `rear_fog`, `hazard`, both turn keys, and
`car.drive.setting.outline_lamps_state`. `driving_ready_state` took both values
with the lamp lit, so it is not a proxy (a rule based on it was tried and
reverted — see `CAR_DRL_FOLLOWS_READY`). Charging is not a trigger either.

`car.basic.low_light_status` and `car.basic.head_light_status` are subscribed but
the vehicle returns **empty** for both, so `dispatchAllData` skips them — they are
dead on this car despite looking like the obvious candidates.

**Open lead:** `car.configure.comb_front_light_src` reads `3` (an enum, not a
flag) while the lamps are lit — the only non-zero light-ish signal found. It
needs a stalk cycle to confirm it tracks lamp output before anything is wired to
it. Subscribed as of this change, along with `auto_headlight` (reads 1, just the
feature toggle), `light_auto_switch_system` and `configure.parking_light` (both 0).

What the viewer does today: `position = stalk-selected OR low beam OR high beam`.
Correct in manual modes, misses AUTO/OFF where the lamps run with no signal.

### There is no brake-pedal signal

`CarConstants` has no brake-pedal key. The full candidate list:

| Key | Meaning | Verdict |
| --- | --- | --- |
| `car.basic.hand_brake_status` | parking brake | does not light brake lamps |
| `car.ipk_light.braking_system_indicator` | brake fault tell-tale | unrelated |
| `car.ipk_light.brake_energe_recycle` | regen tell-tale | indirect |
| `car.intelligent_driving_info.hazard_brake_state` | AEB | rare edge case |
| `car.ev_info.energy_recovery_info` | regen level (float, e.g. `0.34`) | closest proxy, now subscribed |

The viewer's `brake` light group therefore has no direct car source. The
recommended approach is to **derive it from deceleration** of the already-reliable
`car.basic.vehicle_speed`, with a short hold so it cannot strobe on sensor noise.
That matches what a brake lamp physically signifies and also covers strong regen,
which must legally light the lamps above ~1.3 m/s². Limitations: it cannot see
the pedal pressed while stationary, and it can only be calibrated while driving.

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

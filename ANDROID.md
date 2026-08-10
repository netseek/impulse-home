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

## Time mode (Day / Night / Auto)

The viewer config panel exposes `timeMode`: `day`, `night`, or `auto`.

- **Day / Night** force the scene lighting locally.
- **Auto** follows car telemetry key `isNight` (`"true"` / `"false"` strings).

Native path already exists: `havalshisuku` broadcast → `MainActivity` →
`onCarDataUpdate` → `carTelemetry`. Publish `isNight` from the car app for Auto
alignment; the viewer listens via `carTelemetry.onUpdate` / `carTelemetry.isNight()`.

Persisted viewer preferences (including `timeMode`) are stored in WebView
`localStorage` under `h6_settings_v1` when the user taps **Save**.

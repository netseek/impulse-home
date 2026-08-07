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

## Install on a normal Android test device

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.havalh6.viewer/.MainActivity
```

Validate touch orbit/zoom, model loading, wheel switching, and resume after
leaving the app before trying the vehicle MMI. The Haval-specific install flow
is deliberately not included here; use the `haval-app-tool-multimidia` project
for that later step.

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

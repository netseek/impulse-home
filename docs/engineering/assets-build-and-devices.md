# Assets, builds and devices

Where the assets live, how models are edited and retired, and how to reach the car or the emulator.

## Repository layout note: assets are fetched, not tracked

This repository holds code only. The 3D models, textures and images under
`assets/` come from a pinned bundle (`assets.lock.json`, fetched by
`node scripts/fetch-assets.mjs`) and are gitignored. The exception is the
JavaScript that lives there (`assets/clock-*.js`), which is source and is tracked.

Everything below that says "move a retired asset to `assets/_archive/`" describes
the maintainer's full working tree, where the originals and `_archive`/`_backup`/
`_source`/`_blender` live. They never enter the bundle (path segments starting
with `_` are excluded), so they never reach the APK or this repository. A build
without the bundle compiles and passes its tests but starts without the car.
Release flow: `docs/RELEASING.md`.

## Retiring an asset: move it, do not delete it

`aapt` drops any assets directory whose name starts with `_`, so **`assets/_archive/`
never reaches the APK** — same reason `_backup`, `_source` and `_blender` do not, and
it holds no matter what `app/build.gradle` excludes. Proof: the Gradle `syncViewerAssets`
task copies `assets/_blender/**` into the staging dir and the packaged APK still has no
entry for it.

So a superseded asset **moves to `assets/_archive/<group>/`**. It keeps the design history,
and it stops costing APK size the moment it lands there. `assets/_archive/README.md` holds
the log of what went where and why.

Measured 2026-09-24: 42 files, 17.2 MB of the web payload, were never referenced. Archiving
them took a clean debug APK from **75.05 MB to 57.06 MB (-24%)**.

**Find them against the built APK, not against the tree.** List `assets/www/**` out of the
APK and check each name against the source. Two traps, both of which produced false
positives here:

- **A path built by concatenation is invisible to a name search.** `backgrounds/`,
  `ui/widget-thumbs/`, `ui/clima/*-day|night.webp`, `wheel-thumbnails/` and `power/tex/`
  are all assembled at runtime or emitted by a build script. Treat those directories as
  live unless you have read the code that builds the name.
- **Scan the ROOT scripts too.** `support.js` loads React, ReactDOM and Babel from
  `vendor/react/`; a scan that only reads `index.html` reports 3 MB of live vendor code as
  dead.

Then rebuild and load the page: no request for an archived path, and the live ones still
200. That is the check that actually settles it.

## Editing GLB assets

Shipped car models are tuned for **load time**, not just appearance. Before
saving any `.glb` change, preserve whatever compression and layout the file
already carries:

| File | Geometry | Textures | Notes |
|---|---|---|---|
| `assets/haval-h6-hev-lite.glb` (boot path) | raw float32, **no Draco** | KTX2/UASTC @ 1024² | Parsed ~1.7 s on the MMI |
| `assets/_source/haval-h6-hev.glb` | raw float32 | KTX2/UASTC @ 2048² | Build source for `-lite` |
| Wheels / GT | Draco or meshopt | KTX2 or JPEG | See each script's header |

**Do not round-trip material-only edits through `@gltf-transform` read/write.**
It rewrites the BIN chunk (vertex layout, bufferViews, sometimes texture
packing) even when you only change a roughness value. Measured: a gltf-transform
save of `haval-h6-hev.glb` altered 33 MB of binary payload; JSON-only surgery
changed **zero** bytes of BIN. The viewer's CPU-side vertex edits
(`_deformTireBore`, `computeVertexNormals`) assume `VertexLayout.SEPARATE`
packed arrays — gltf-transform defaults to interleaved and has rendered the
car as vertical smears before.

**Safe pattern for material / node / metadata edits:** parse the GLB JSON chunk,
patch in place, copy the BIN chunk through unchanged. See
`scripts/patch-hev-trunk-materials.mjs`, `scripts/build-lite-textures.mjs`, and
`scripts/build-ktx2-textures.mjs` (which document the same constraint).

**Rebuild order when textures or resolution change:**

1. `npm run build:lite-textures` — downscale JPEG sources (`sharp`; cannot read KTX2)
2. `npm run build:ktx2-textures` — UASTC encode (idempotent; skips already-KTX2)
3. `npm run build:fast-models` — strip Draco from boot-critical `-lite` only

Never save a boot-critical model with Draco re-introduced, and never replace KTX2
with raw JPEG in the shipped `-lite` file without re-measuring cold start.

## Standalone body loading

**Goal:** boot or toggle to GT and fetch **only that body's `-lite` GLB**
plus the small catalog rim — no silent second body.

**`?model=gt`** enables standalone GT boot (dev / pre-car-input). The app loads
`haval-h6-gt-lite.glb` + `HavalGT-wheel.glb` only — no HEV donor preload.
Native GT tire clusters and calipers are classified in `_buildGtNativeWheelCorners`.

**Saved-settings GT** (localStorage, no URL) still preloads HEV as donor until
car-side trim input replaces the URL preset.

**HEV/PHEV** (`?model=phev34` etc.) were already standalone: one `-lite` body +
stock rim GLB. Embedded tires/brakes stay; stock *rims* were stripped from the
body GLB long ago.

**When HEV is already loaded** (HEV boot → MODEL toggle to GT), GT still inherits
HEV wheel pivots via `inheritWheelsFrom: 'hev'` — no second download.

Hook points: `_gtBootStandalone()`, `_gtUsesNativeWheels()`, `_whenHevDonorReady`.

## Device access

Talk to the **car itself** over TCP ADB on the local LAN. Do not use a
Raspberry Pi, Tailscale jump host, or `scripts/car-gateway/` — those are stale.

`adb` is usually not on PATH:

```
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
```

The MMI is dual-homed and exposes `:5555` on whichever interface is up:

| Network | Typical subnet |
|---|---|
| Home Wi-Fi | `192.168.1.0/24` |
| Car hotspot | `192.168.33.0/24` |

DHCP moves every session. Never hardcode an IP. `.car-adb-serial` is only a
last-success hint. Discovery (`scripts/car-adb-common.ps1`) detects whether
this PC is on the **car hotspot** (`192.168.33`) or the **home LAN** (`192.168.1`),
scans that subnet first, then the other, and confirms the MMI (`gwm` /
`msmnile_gvmq`, or `com.havalh6.viewer` if already installed).
Both routes are TCP `adb install` / scrcpy — home LAN is the remote APK path;
you do not need to join the car hotspot.

Beantechs blocks a bare `adb install` of this package (`beantechs disallow apk
com.havalh6.viewer`). Deploy uses installer identity `com.autolink.installer`.
A signature mismatch needs uninstall + that same `-i` reinstall — a plain
uninstall without Autolink cannot put the app back.

Cursor / VS Code tasks (Terminal → Run Task):

| Task | Script |
|---|---|
| Car: connect | `scripts/connect-car.ps1` |
| Car: share displays | `scripts/share-car-displays.ps1` |
| Car: deploy | `scripts/deploy-car.ps1` |
| Car: deploy (skip build) | `scripts/deploy-car.ps1 -SkipBuild` |
| Car: deploy and share displays | `scripts/deploy-car.ps1 -Share` |

Same via npm: `npm run car:connect` / `car:share` / `car:deploy` /
`car:deploy:skip-build`.

**Not every `:5555` is the MMI.** Other boxes on the LAN (T-Box / Beceem-class
hosts) accept TCP 5555 and show `offline`. A live car is `device` **and**
`pm path com.havalh6.viewer` succeeds. ICMP can fail while ADB still works.

If this PC's ADB key is `unauthorized`, the MMI often never shows the RSA
dialog. Root telnet on the same IP (`:23`, already a root shell, no password)
can append this machine's `~\.android\adbkey.pub` to
`/data/misc/adb/adb_keys` and restart `adbd`.

`pidof com.havalh6.viewer` can return the notification-listener service rather
than the activity — prefer `ps -A | grep "com.havalh6.viewer$"`.

### Share displays 0 and 3

The panel is two 1920×720 surfaces. **0** is the built-in ("Tela integrada");
**3** is HDMI ("Tela HDMI"). Portable scrcpy lives at
`%LOCALAPPDATA%\scrcpy\scrcpy-win64-v4.1\scrcpy.exe`.

```powershell
.\scripts\share-car-displays.ps1
```

That rediscovers the car and opens **Haval-Display0** and **Haval-Display3**.
`--no-audio` is required (Android 9). Mouse→touch works on display 0 only;
secondary-display input needs Android 10+. `screencap -d 3` is often black
even when scrcpy is not — dumpsys is the fallback for "what is on D3".

**Ask before any test that needs a human at the car.** A timed capture started
in the same message as the instruction expires before it is read, and a capture
that returns nothing is ambiguous between "signal absent" and "nobody pressed
anything".

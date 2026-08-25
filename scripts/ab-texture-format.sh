#!/usr/bin/env bash
# A/B the texture format: KTX2/UASTC (current) vs the pre-KTX2 JPEG originals.
#
# WHY: the KTX2 pass tripled the models (HEV 11.3 -> 34.6 MB) and pushed cold
# start to ~23s against a 15s splash video. It was adopted for VRAM (16 MB -> 4 MB
# per 2048 map) but its effect on FRAME RATE was never measured. This script makes
# that measurable instead of arguable.
#
#   ./scripts/ab-texture-format.sh jpeg     # swap in assets/_backup/pre-ktx2/*, build, install
#   ./scripts/ab-texture-format.sh ktx2     # restore the KTX2 models, build, install
#   ./scripts/ab-texture-format.sh status   # show which set is currently in assets/
#
# The KTX2 models are stashed under assets/_backup/ktx2/ the first time you swap
# to jpeg, so nothing is lost either way.
set -euo pipefail
cd "$(dirname "$0")/.."

ASSETS=assets
PRE=assets/_backup/pre-ktx2
KTX=assets/_backup/ktx2
MODELS=(haval-h6-hev.glb haval-h6-hev-lite.glb haval-h6-gt.glb haval-h6-gt-lite.glb)

is_ktx2() {
  # Parse the glTF JSON chunk properly - a byte-window grep misses the
  # extension string whenever the JSON chunk runs past the window.
  node -e '
    const fs = require("fs");
    const b = fs.readFileSync(process.argv[1]);
    let off = 12, json = null;
    while (off + 8 <= b.length) {
      const len = b.readUInt32LE(off), type = b.readUInt32LE(off + 4);
      if (type === 0x4E4F534A) { json = JSON.parse(b.slice(off + 8, off + 8 + len).toString("utf8")); break; }
      off += 8 + len;
    }
    const imgs = (json && json.images) || [];
    const ktx = imgs.some(i => i.mimeType === "image/ktx2");
    process.exit(ktx ? 0 : 1);
  ' "$1" 2>/dev/null
}

status() {
  for m in "${MODELS[@]}"; do
    f="$ASSETS/$m"
    [ -f "$f" ] || { printf "%-26s MISSING\n" "$m"; continue; }
    if is_ktx2 "$f"; then fmt=KTX2; else fmt=JPEG; fi
    printf "%-26s %-5s %10d bytes\n" "$m" "$fmt" "$(stat -c%s "$f")"
  done
}

case "${1:-status}" in
  jpeg)
    mkdir -p "$KTX"
    for m in "${MODELS[@]}"; do
      [ -f "$PRE/$m" ] || { echo "missing backup: $PRE/$m"; exit 1; }
      # Stash the KTX2 build once, so 'ktx2' can put it back.
      if [ -f "$ASSETS/$m" ] && is_ktx2 "$ASSETS/$m" && [ ! -f "$KTX/$m" ]; then
        cp "$ASSETS/$m" "$KTX/$m"; echo "stashed KTX2 $m"
      fi
      cp "$PRE/$m" "$ASSETS/$m"; echo "installed JPEG $m"
    done
    ;;
  ktx2)
    for m in "${MODELS[@]}"; do
      [ -f "$KTX/$m" ] || { echo "no stashed KTX2 for $m - run 'jpeg' first, or re-run scripts/build-ktx2-textures.mjs"; exit 1; }
      cp "$KTX/$m" "$ASSETS/$m"; echo "restored KTX2 $m"
    done
    ;;
  status) status; exit 0 ;;
  *) echo "usage: $0 {jpeg|ktx2|status}"; exit 2 ;;
esac

echo "--- current state ---"; status
echo "--- building ---"
JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}" ./gradlew assembleDebug --console=plain -q
ADB="${ADB:-C:/Users/<user>/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
DEV=$("$ADB" devices | awk '$2=="device" && $1 ~ /:5555$/ {print $1; exit}')
if [ -z "$DEV" ]; then
  echo "no device on 5555 - APK is at app/build/outputs/apk/debug/app-debug.apk"
  exit 0
fi
echo "--- installing to $DEV ---"
"$ADB" -s "$DEV" install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
"$ADB" -s "$DEV" shell am start -n com.havalh6.viewer/.MainActivity >/dev/null 2>&1
echo "done - let it load, then run the measure.js harness over CDP"

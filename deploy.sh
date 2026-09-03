#!/usr/bin/env bash
# Build + sideload SuperDuper onto the Supernote Nomad (SPEC.md §2.3, §10).
#
#   ./deploy.sh          build, install, launch
#   ./deploy.sh -l       ...then tail the app's logcat (Ctrl-C to stop)
#   ./deploy.sh -b       build only
set -euo pipefail

PKG="com.superduper.notes"
ACTIVITY=".MainActivity"
APK="app/build/outputs/apk/debug/app-debug.apk"
TAIL_LOG=false
BUILD_ONLY=false

while getopts "lbh" opt; do
  case $opt in
    l) TAIL_LOG=true ;;
    b) BUILD_ONLY=true ;;
    h) sed -n '2,8p' "$0"; exit 0 ;;
    *) exit 2 ;;
  esac
done

cd "$(dirname "$0")"

# --- locate adb: PATH, then ANDROID_HOME, then the Homebrew SDK location ---
if command -v adb >/dev/null 2>&1; then
  ADB=$(command -v adb)
elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
  ADB="$ANDROID_HOME/platform-tools/adb"
elif [ -x /opt/homebrew/share/android-commandlinetools/platform-tools/adb ]; then
  ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
else
  echo "error: adb not found. Install with: brew install --cask android-commandlinetools" >&2
  exit 1
fi

# --- 1. build ---
echo "==> Building debug APK"
./gradlew assembleDebug
[ -f "$APK" ] || { echo "error: expected APK at $APK" >&2; exit 1; }
printf '    %s (%s)\n' "$APK" "$(du -h "$APK" | cut -f1)"
$BUILD_ONLY && exit 0

# --- 2. find the device ---
echo "==> Checking for device"
"$ADB" start-server >/dev/null 2>&1 || true
DEVICES=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
COUNT=$(printf '%s\n' "$DEVICES" | grep -c . || true)

if [ "$COUNT" -eq 0 ]; then
  # Surface unauthorized/offline states explicitly — they look like "no device" otherwise.
  "$ADB" devices | awk 'NR>1 && NF' | sed 's/^/    /' || true
  cat >&2 <<'MSG'
error: no device in state "device".

On the Nomad:  Settings > Security & Privacy > Sideloading  -> ON
               (needs Chauvet 3.16.27 or newer)

If it still doesn't appear (community-reported quirks, SPEC.md §2.3):
  - use a USB-A -> USB-C cable rather than C -> C
  - prefer a USB 2.0 host port
  - toggle Sideloading off/on, then re-plug
MSG
  exit 1
fi
if [ "$COUNT" -gt 1 ]; then
  echo "error: $COUNT devices attached; disconnect the others (or set ANDROID_SERIAL):" >&2
  printf '    %s\n' $DEVICES >&2
  exit 1
fi
SERIAL=$(printf '%s\n' "$DEVICES" | head -1)
MODEL=$("$ADB" -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)
RELEASE=$("$ADB" -s "$SERIAL" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || true)
echo "    $SERIAL  ${MODEL:-unknown} (Android ${RELEASE:-?})"

# --- 3. install ---
echo "==> Installing"
"$ADB" -s "$SERIAL" install -r "$APK"

# --- 4. launch ---
echo "==> Launching $PKG$ACTIVITY"
"$ADB" -s "$SERIAL" shell am start -n "$PKG/$ACTIVITY"

# --- 5. optional log tail ---
if $TAIL_LOG; then
  echo "==> Tailing logcat (Ctrl-C to stop)"
  "$ADB" -s "$SERIAL" logcat -c || true
  "$ADB" -s "$SERIAL" logcat -s SuperDuper:V AndroidRuntime:E ActivityManager:W
fi

#!/bin/bash
# Clean-install the latest built Timbra APK on the connected device and restore the
# home-screen shortcut the user pinned. Clean install (uninstall+install) is intentional.

set -e
DIR="$(cd "$(dirname "$0")" && pwd)"

# Same toolchain resolution order as build.sh (for adb); falls back to adb on PATH.
if [ -n "$TIMBRA_ENV" ] && [ -f "$TIMBRA_ENV" ]; then
    source "$TIMBRA_ENV"
elif [ -f "$DIR/toolchain/env.sh" ]; then
    source "$DIR/toolchain/env.sh"
elif [ -f "$DIR/../toolchain/env.sh" ]; then
    source "$DIR/../toolchain/env.sh"
fi

NAME=$(sed -n 's/^appName=//p' "$DIR/gradle.properties" | tr -d '\r')
NAME_LC=$(echo "${NAME:-app}" | tr '[:upper:]' '[:lower:]')
PKG=$(sed -n 's/.*applicationId *= *"\(.*\)".*/\1/p' "$DIR/app/build.gradle.kts")
APK=$(ls -t "$DIR/${NAME_LC}-"*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && { echo "No $NAME_LC-*.apk at repo root — run ./build.sh first."; exit 1; }

echo "== Clean-installing $(basename "$APK") as $PKG =="
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$APK"

# --- Restore the home-screen shortcut at its fixed cell (row 3, col 2) ---
adb root >/dev/null 2>&1 || true
sleep 1
DB=/data/data/com.android.launcher3/databases/launcher.db
INTENT="#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;launchFlags=0x10200000;component=${PKG}/.ui.MainActivity;end"
# Force-stop the launcher first so it reloads from the DB (and won't overwrite our edit).
adb shell am force-stop com.android.launcher3
printf "%s\n" \
  "DELETE FROM favorites WHERE intent LIKE '%${PKG}/%';" \
  "INSERT INTO favorites (title,intent,container,screen,cellX,cellY,spanX,spanY,itemType,profileId) VALUES ('${NAME}','${INTENT}',-100,0,1,4,1,1,0,0);" \
  | adb shell "cat > /data/local/tmp/shortcut.sql"
adb shell "sqlite3 $DB < /data/local/tmp/shortcut.sql" && echo "shortcut row inserted" || echo "WARN: could not edit launcher.db (root/launcher differ?) — pin the shortcut manually"
adb shell "rm -f /data/local/tmp/shortcut.sql"
adb shell input keyevent KEYCODE_HOME

echo "== Installed. Launch: adb shell am start -n ${PKG}/.ui.MainActivity =="

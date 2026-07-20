#!/bin/bash
# Build Timbra (pure Kotlin/Android; no NDK/Go/Rust — the FFmpeg decoders ship prebuilt
# inside the nextlib AAR). Provisions its own toolchain when none is available.
#
# Usage: ./build.sh [gradle-task] [--no-install]   (default task: assembleRelease)
#   When a device is connected (adb, in "device" state), the built APK is installed
#   automatically afterwards (in-place update via ./install.sh); --no-install skips it.
#
# Toolchain resolution, in order:
#   1. $TIMBRA_ENV pointing at an env script to source
#   2. ./toolchain/env.sh   (repo-local toolchain, incl. one this script provisioned)
#   3. ../toolchain/env.sh  (a shared sibling toolchain next to the repo)
#   4. java + gradle + $ANDROID_HOME already usable on PATH
#   5. otherwise SELF-PROVISION into ./toolchain: downloads JDK $JDK_FEATURE,
#      Gradle $GRADLE_VERSION, Android cmdline-tools, then installs platform-tools,
#      platforms;android-$COMPILE_SDK and build-tools;$BUILD_TOOLS. Each component is
#      guarded by an existence check, so a partial/broken toolchain heals itself and
#      re-runs are fast no-ops. (Downloads are Linux x86_64; on other hosts provide a
#      toolchain via TIMBRA_ENV instead.)
set -eo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLCHAIN="${TOOLCHAIN_DIR:-$DIR/toolchain}"

# Args: an optional gradle task (positional) plus flags. Keeps `./build.sh
# assembleDebug` working while adding --no-install.
TASK=""
INSTALL=1
for arg in "$@"; do
    case "$arg" in
        --no-install) INSTALL=0 ;;
        --*) echo "unknown flag: $arg" >&2; exit 1 ;;
        *) TASK="$arg" ;;
    esac
done

JDK_FEATURE="${JDK_FEATURE:-17}"
GRADLE_VERSION="${GRADLE_VERSION:-8.13}"
CMDLINE_BUILD="${CMDLINE_BUILD:-11076708}"
# compileSdk is the single source of truth in app/build.gradle.kts; build-tools follows it.
COMPILE_SDK="$(sed -n 's/^ *compileSdk *= *\([0-9]\+\).*/\1/p' "$DIR/app/build.gradle.kts" | head -1)"
COMPILE_SDK="${COMPILE_SDK:-35}"
BUILD_TOOLS="${BUILD_TOOLS:-${COMPILE_SDK}.0.0}"

JDK_URL="https://api.adoptium.net/v3/binary/latest/${JDK_FEATURE}/ga/linux/x64/jdk/hotspot/normal/eclipse"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_BUILD}_latest.zip"

log() { printf '\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# Resolves adb (prefer the toolchain copy) and reports whether at least one
# device is connected and ready ("device" state — not unauthorized/offline).
adb_device_ready() {
    local adb="${ANDROID_HOME:+$ANDROID_HOME/platform-tools/adb}"
    [ -x "$adb" ] || adb="$(command -v adb 2>/dev/null || true)"
    [ -n "$adb" ] || return 1
    "$adb" devices 2>/dev/null | awk 'NR>1 && $2=="device"{f=1} END{exit !f}'
}

have_build_env() {
    command -v java >/dev/null 2>&1 && command -v gradle >/dev/null 2>&1 \
        && [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME" ]
}

provision_toolchain() {
    log "No toolchain found — provisioning one into $TOOLCHAIN"
    for t in curl unzip tar; do
        command -v "$t" >/dev/null 2>&1 || die "Required tool '$t' not found on PATH."
    done
    [ "$(uname -s)" = "Linux" ] && [ "$(uname -m)" = "x86_64" ] || die \
        "Self-provisioning downloads Linux x86_64 toolchains (detected $(uname -s)/$(uname -m)). Set TIMBRA_ENV to an env script for an existing toolchain instead."
    mkdir -p "$TOOLCHAIN"

    if [ ! -x "$TOOLCHAIN/jdk/bin/javac" ]; then
        echo "    downloading JDK $JDK_FEATURE (Adoptium)..."
        curl -fL -sS -o "$TOOLCHAIN/jdk.tgz" "$JDK_URL"
        rm -rf "$TOOLCHAIN/jdk" && mkdir -p "$TOOLCHAIN/jdk"
        tar -xzf "$TOOLCHAIN/jdk.tgz" -C "$TOOLCHAIN/jdk" --strip-components=1
        rm -f "$TOOLCHAIN/jdk.tgz"
    fi
    echo "    JDK: $("$TOOLCHAIN/jdk/bin/java" -version 2>&1 | head -1)"

    if [ ! -x "$TOOLCHAIN/gradle-$GRADLE_VERSION/bin/gradle" ]; then
        echo "    downloading Gradle $GRADLE_VERSION..."
        curl -fL -sS -o "$TOOLCHAIN/gradle.zip" "$GRADLE_URL"
        rm -rf "$TOOLCHAIN/gradle-$GRADLE_VERSION"
        unzip -q "$TOOLCHAIN/gradle.zip" -d "$TOOLCHAIN"
        rm -f "$TOOLCHAIN/gradle.zip"
    fi

    local SDK="$TOOLCHAIN/android-sdk"
    local SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
    if [ ! -x "$SDKMANAGER" ]; then
        echo "    downloading Android command-line tools..."
        curl -fL -sS -o "$TOOLCHAIN/cmdtools.zip" "$CMDLINE_URL"
        rm -rf "$SDK/cmdline-tools"
        mkdir -p "$SDK/cmdline-tools"
        unzip -q "$TOOLCHAIN/cmdtools.zip" -d "$SDK/cmdline-tools"
        mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
        rm -f "$TOOLCHAIN/cmdtools.zip"
    fi

    if [ ! -d "$SDK/platforms/android-$COMPILE_SDK" ] || [ ! -d "$SDK/build-tools/$BUILD_TOOLS" ] \
        || [ ! -d "$SDK/platform-tools" ]; then
        echo "    installing SDK packages (platform-tools, platforms;android-$COMPILE_SDK, build-tools;$BUILD_TOOLS)..."
        export JAVA_HOME="$TOOLCHAIN/jdk"
        yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
        "$SDKMANAGER" --sdk_root="$SDK" \
            "platform-tools" "platforms;android-$COMPILE_SDK" "build-tools;$BUILD_TOOLS" \
            > "$TOOLCHAIN/sdkmanager.log" 2>&1 \
            || die "sdkmanager failed — see $TOOLCHAIN/sdkmanager.log"
    fi

    # Write the env script so the next run takes the fast path (resolution step 2).
    cat > "$TOOLCHAIN/env.sh" <<EOF
# Build environment for Timbra (paths relative to this script; written by build.sh)
T="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")" && pwd)"
export JAVA_HOME=\$T/jdk
export ANDROID_HOME=\$T/android-sdk
export PATH=\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$T/gradle-$GRADLE_VERSION/bin:\$PATH
EOF
    echo "    toolchain ready: $TOOLCHAIN (env: $TOOLCHAIN/env.sh)"
}

# --- Toolchain resolution ---
if [ -n "$TIMBRA_ENV" ] && [ -f "$TIMBRA_ENV" ]; then
    source "$TIMBRA_ENV"
elif [ -f "$TOOLCHAIN/env.sh" ]; then
    source "$TOOLCHAIN/env.sh"
elif [ -f "$DIR/../toolchain/env.sh" ]; then
    source "$DIR/../toolchain/env.sh"
elif ! have_build_env; then
    provision_toolchain
    source "$TOOLCHAIN/env.sh"
fi
have_build_env || die "Toolchain still incomplete (java/gradle/ANDROID_HOME) — delete $TOOLCHAIN and retry, or set TIMBRA_ENV."

GRADLE="${GRADLE:-gradle}"

# Point AGP at the SDK regardless of the caller's environment.
if [ -n "$ANDROID_HOME" ]; then
    echo "sdk.dir=$ANDROID_HOME" > "$DIR/local.properties"
fi

# App name (single source of truth: appName in gradle.properties), lowercased for filenames.
NAME=$(sed -n 's/^appName=//p' "$DIR/gradle.properties" | tr -d '\r')
NAME_LC=$(echo "${NAME:-app}" | tr '[:upper:]' '[:lower:]')

TASK="${TASK:-assembleRelease}"
log "Building $NAME ($TASK)"
cd "$DIR"
"$GRADLE" "$TASK" --no-daemon

# Copy the built APK to the repo root, named "<app>-<version>.apk" (single sources of
# truth: appName in gradle.properties, versionName in app/build.gradle.kts).
VERSION=$(sed -n 's/.*versionName *= *"\(.*\)".*/\1/p' "$DIR/app/build.gradle.kts")
# Pick the APK for the variant we actually built (avoids grabbing a stale debug/release).
case "$TASK" in
    *[Rr]elease*) VARIANT=release ;;
    *) VARIANT=debug ;;
esac
BUILT=$(find "$DIR/app/build/outputs/apk/$VARIANT" -name '*.apk' 2>/dev/null | head -1)
if [ -n "$BUILT" ]; then
    # Remove older root APKs so only the current version stays at the root.
    find "$DIR" -maxdepth 1 -name "${NAME_LC}-*.apk" -delete
    OUT="$DIR/${NAME_LC}-${VERSION:-unknown}.apk"
    cp -f "$BUILT" "$OUT"
    echo
    log "APK output"
    ls -lh "$OUT"
    if [ "$INSTALL" = 1 ] && adb_device_ready; then
        log "Device detected — installing (in-place update)"
        "$DIR/install.sh"
    else
        echo "Install with: adb install -r $OUT   (or ./install.sh; --clean for a fresh install)"
    fi
fi

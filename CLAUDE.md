# Timbra — build & contribution guide

- **Name:** single source of truth is `appName` in `gradle.properties` (drives the in-app
  `app_name` resource via `resValue` in `app/build.gradle.kts`, and the APK filename in
  `build.sh`). To rename the app, change that one property.
- **Stack:** Kotlin + XML Views. Package `com.timbra`. minSdk 24, target/compile 35.
- **Audio:** Media3/ExoPlayer + nextlib FFmpeg decoder (`NextRenderersFactory`),
  bundled for all ABIs incl. `arm64-v8a`.
- **UI/theme:** classic matte dark skin, reusing the original `matte_*` PNG assets
  (in `app/src/main/res/drawable-*`). Nine-patches from the APK were pre-compiled, so
  they were recreated as XML shape drawables (`deck_bg`, `seek_progress`, etc.).

## Versioning (do this on EVERY change)

Single source of truth: `app/build.gradle.kts` → `defaultConfig`.
1. **Increment `versionCode` by 1** and **bump `versionName`** (semver: patch for
   fixes, minor for features) before building.
2. The version is surfaced **in the app** (Library screen toolbar subtitle, via
   `BuildConfig.VERSION_NAME`) and **in the APK filename**.

## Build

```bash
./build.sh                 # assembleRelease (default)
./build.sh assembleDebug   # debug variant
./build.sh --no-install    # build but don't touch the device
```
When a device is connected (adb, `device` state), `build.sh` installs the APK
automatically after building (in-place update via `./install.sh`); `--no-install` skips it.
`build.sh` is self-sufficient: toolchain resolution is `$TIMBRA_ENV` → `./toolchain/env.sh`
→ `../toolchain/env.sh` (shared sibling — the local dev setup) → PATH → **self-provision**
into `./toolchain/` (downloads JDK 17, Gradle 8.13, cmdline-tools, SDK platform/build-tools
matching `compileSdk`; Linux x86_64; no NDK needed — FFmpeg is prebuilt in the nextlib AAR).
It writes `local.properties`, builds, then copies the APK to the **repo root** as
`<app>-<versionName>.apk` (older root APKs are removed first).

## Install / run on device

A device is reachable over adb (`adb devices`). The dev phone is an **arm64 Treble GSI**.
```bash
./install.sh           # UPDATE in place (adb install -r); data + shortcut survive
./install.sh --clean   # fresh install (uninstall+install) + restores the home-screen shortcut
# manual:
adb install -r <app>-<version>.apk
adb shell am start -n com.timbra/.ui.MainActivity
# crash triage:
adb logcat -b crash -d | tail -60
```
Grant the audio permission on first launch. First run scans MediaStore.

## Verify a build (static, since CI has no emulator)

```bash
source toolchain/env.sh
APK=<app>-<version>.apk
$ANDROID_HOME/build-tools/35.0.0/aapt dump badging "$APK" | grep -E 'package:|native-code:'
```
Confirm `arm64-v8a` + the FFmpeg libs (`libavcodec`, `libmedia3ext`, `libswresample`).

## Layout of the code

- `player/` — `PlaybackService` (MediaSessionService + ExoPlayer/FFmpeg),
  `PlayerConnection` (MediaController wrapper + `UiPlayback` state), `PlayModes`.
- `data/` — `MediaRepository` (MediaStore), `FolderTreeBuilder` (virtual folder tree
  from file paths, no all-files permission), `Sorting` (+ `SortDefaults`: folders =
  hierarchy / by filename).
- `ui/` — `MainActivity` (toolbar + nav host + mini-player), `library/`, `folders/`,
  `list/` (shared `LibraryListAdapter`), `player/`, `dialogs/`, `ArtLoader`, `Format`.

## Deferred (not implemented yet)

Settings screen, EQ/tone, widgets, lyrics, theme switching, SAF filesystem browsing.
Playlists & per-track genres depend on legacy MediaStore tables and may be sparse on
Android 11+.

## Gotchas

- Nav-arg `defaultValue` for a `string` arg must be a **literal**, not `@string/...`
  (NavInflater rejects references → startup crash).
- media3 ExoPlayer/DefaultRenderersFactory are `@UnstableApi`; annotate classes that
  touch them with `@UnstableApi` (see `PlaybackService`).
- Dot-named styles (`Tb.Foo`) imply a parent style `Tb`; give leaf styles `parent=""`.
- Don't copy `.9.png` or compiled `.xml` drawables out of an APK — they're pre-compiled
  and aapt2 can't recompile them; recreate as source instead.

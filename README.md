# Timbra

Timbra is a free lightweight music player for Android inspired by Poweramp v2. It rebuilds
that look and feel as original code on a modern stack.
<p align="center">
<img width="32%" src="https://github.com/user-attachments/assets/463a373f-6382-42e1-852d-5e9f8d13a189" />
<img width="32%" src="https://github.com/user-attachments/assets/830ebfa9-d717-4039-827b-0211a5a64cf4" />
<br>
<img width="32%" src="https://github.com/user-attachments/assets/acfa607a-6471-4c10-84e6-5ffd2f8047e6" /> 
<img width="32%" src="https://github.com/user-attachments/assets/6413e68a-6ff0-4e83-b280-676fc4aa8389" />
</p>

## Features

- **Folder-first browsing** — a virtual folder tree built from file paths, so it works
  without the all-files-access permission.
- **Full library** — Folders, Albums, Artists, All Songs, Genres, Playlists, and the play
  Queue.
- **Wide format support** — FFmpeg-backed decoders (via Media3 + nextlib) play far more
  than the platform codecs, on all shipped ABIs including `arm64-v8a`.
- **Gapless & background playback** — a `MediaSessionService` keeps playing with lock-screen
  / notification controls and Bluetooth media keys.
- **Swipeable album-art deck** — swipe the cover art: left/right to change song,
  up/down to jump to the next/previous folder.
- **7-band equalizer** — a custom Media3 DSP processor independent of the device's framework
  audio effects.
- **Shuffle** — off · shuffle current list · shuffle all songs.
- **Repeat** — off · repeat list · advance list · repeat song.
- **Search** across the library.
- **Matte-dark theme** — reuses the classic `matte_*` skin assets, pure-black window for OLED.
- **No ads, no telemetry, no bullshit**.

## Requirements

- **Android 7.0 (API 24)** or newer.
- ABIs: `arm64-v8a`, `armeabi-v7a` (emulator-only x86/x86_64 are excluded).

## Tech stack

- **Language / UI:** Kotlin, Android XML Views (View Binding), AndroidX Navigation,
  RecyclerView + ViewPager2, Material 3.
- **Audio:** AndroidX **Media3 / ExoPlayer** `1.5.1` with the
  [nextlib](https://github.com/anilbeesetti/nextlib) FFmpeg extension (`NextRenderersFactory`).
- **Async:** Kotlin coroutines; Guava `ListenableFuture` for the `MediaController` handshake.
- Package `com.timbra`, single-module app.

## Building (Linux only for now)

`build.sh` builds **from scratch on a bare machine** — if no toolchain is found it
downloads and provisions one automatically (JDK 17, Gradle 8.13, Android cmdline-tools,
platform-tools, SDK platform 35 and build-tools 35) into `./toolchain/`, then builds and
copies the APK to the repo root as `timbra-<versionName>.apk`. No NDK, Android Studio or
pre-installed SDK required; the FFmpeg decoders come prebuilt inside the nextlib AAR.

```bash
./build.sh                  # assembleRelease (default)
./build.sh assembleDebug    # debug variant

# To use an existing toolchain instead, point TIMBRA_ENV at an env script to source:
TIMBRA_ENV=/path/to/toolchain/env.sh ./build.sh
```

Toolchain resolution order: `$TIMBRA_ENV` → `./toolchain/env.sh` → `../toolchain/env.sh`
(a shared sibling toolchain) → `java`/`gradle`/`$ANDROID_HOME` already on `PATH` → self-
provision. Provisioning is guarded per component, so a partial toolchain heals itself and
re-runs are fast no-ops. Self-provisioning downloads Linux x86_64 binaries and needs
`curl`, `unzip` and `tar`; on other hosts supply a toolchain via `TIMBRA_ENV`.

The release variant is minified (R8) and signed with the bundled `timbra.keystore` so the
APK has a stable signing identity and installs/updates directly over adb without any
keystore setup.

## Installing on a device

```bash
./install.sh
```

`install.sh` **updates in place** (`adb install -r`) with the most recently built APK, so
app data survives; pass `--clean` for a fresh install (uninstall + install). It finds
`adb` through the same toolchain resolution as `build.sh`. To install manually:

```bash
adb install -r timbra-<version>.apk
adb shell am start -n com.timbra/.ui.MainActivity
```

Grant media permission on first launch.

## Roadmap

Not yet implemented: lyrics, theme switching.

## License

Timbra is free software licensed under the **GNU General Public License v3.0 or later**
(GPLv3+). See [`LICENSE`](LICENSE) for the full text.

It bundles third-party components under their own licenses — Apache-2.0 for the AndroidX,
Media3/ExoPlayer, Material Components, Guava and kotlinx-coroutines libraries; and FFmpeg
(via [nextlib](https://github.com/anilbeesetti/nextlib)) under the LGPL/GPL. GPLv3 was
chosen deliberately: Apache-2.0 is one-way compatible with GPLv3 but **not** with GPLv2.

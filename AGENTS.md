# Timbra — build & contribution guide

- **Name:** single source of truth is `appName` in `gradle.properties` (drives the in-app
  `app_name` resource via `resValue` in `app/build.gradle.kts`, and the APK filename in
  `build.sh`). To rename the app, change that one property.
- **Audio:** Media3/ExoPlayer + nextlib FFmpeg decoder (`NextRenderersFactory`), bundled for the
  device ABIs `arm64-v8a` and `armeabi-v7a`. The emulator-only `x86`/`x86_64` libs are stripped
  (`packaging.jniLibs.excludes`) and those ABIs are excluded from the APK (`ndk.abiFilters`), so an
  x86 device fails at install time instead of installing and silently having no FFmpeg decoders.
- **UI/theme:** classic matte dark skin, reusing the original `matte_*` PNG assets
  (in `app/src/main/res/drawable-*`). Nine-patches from the APK were pre-compiled, so
  they were recreated as XML shape drawables (`deck_bg`, `seek_progress`, etc.).

## Versioning (do this on EVERY change)

Single source of truth: `app/build.gradle.kts` → `defaultConfig`.
1. **Increment `versionCode` by 1** and **bump `versionName`** (semver: patch for
   fixes, minor for features) before building.
2. The version is surfaced **in the app** (Library screen → overflow → About, via
   `BuildConfig.VERSION_NAME`) and **in the APK filename**.

## Build, install, test

See the `build-and-run` skill (`.claude/skills/build-and-run/SKILL.md`) for `build.sh`,
`install.sh`, the unit tests, and static APK verification.

## Design invariants

- `FolderAdvance` is THE shared folder move — both `PlaybackService` and the UI call it.
- `PlaybackSession` is the one atomic snapshot of queue facts shared by both owners.
- `MediaItems` is the single decode point for queue-item extras.
- `PlaybackStateStore` persists play modes by NAME, not ordinal.
- `MediaRepository`'s MediaStore caches are generation-guarded.
- `FolderTreeBuilder` derives the folder tree from file paths — no all-files permission.
- `PlaybackService` owns the Advance-List continuation for every non-gesture trigger, and the
  custom no-repeat shuffle engine.

## Deferred (not implemented yet)

Settings screen, widgets, lyrics, theme switching, SAF filesystem browsing.
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

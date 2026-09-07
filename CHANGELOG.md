# Timbra 0.9.16

### Playback

- Turning shuffle on or off saves the queue once instead of four times
- Closing the app right after the queue changed no longer resumes on the wrong song

### Player screen

- Changing the shuffle mode no longer makes the cover art vanish for a moment
- The album-art deck no longer shows a stray card for a frame when you change shuffle or repeat
- Swipes and cover art keep working after you turn shuffle off
- The shuffle and repeat messages now change with every tap, instead of lagging several taps behind while older messages play out
- The folder name shown after an up or down folder swipe is no longer hidden behind the sliding artwork

### Look and feel

- Messages like "added to queue", the rescan result and a failed delete now appear inside the app rather than as system pop-ups, so they never pile up or arrive late

### Text

- The About box is shorter, and links to the project page and to donations

### Behind the scenes

- Removed code comments that don't record a past bug
- Tidied the docs

# Timbra 0.9.8 — 2026-08-23

### Library updates itself

- The library refreshes on its own when songs are added, removed or moved
- A burst of changes becomes a single refresh, with a cap so long file copies still show up
- Coming back to the app checks for changes, and only reloads if something actually moved
- New Rescan item in the overflow menu, which reports how many songs were found

### Playback

- Shuffle, repeat and folder-sort choices no longer reset when the app updates
- Shuffle progress now survives closing the app: the play path and already-played songs come back
- Resuming after closing the app lands on the right song, even if files were deleted since
- Turning shuffle off no longer adds duplicate copies of songs you had queued
- A broken or unplayable file no longer freezes the player with a dead play button
- Next/Previous from the notification, lock screen or a Bluetooth remote now works at the end of a folder
- Playback keeps going more reliably with the screen off
- Skipping songs stays smooth on very long queues, even after hours of listening
- Some older MP3 files no longer stop with an error part way through
- Folder jumping can no longer jump twice or get stuck

### Library and lists

- Lists come back in the same order every time
- Songs with no title sort by filename — matching what you actually see
- Albums where only some tracks have a disc number no longer put those tracks before disc 1
- Rotating the screen no longer reloads your whole library and all album art
- Your sort choice survives rotating the screen and going back
- Changing sort or view quickly no longer shows the wrong list or plays the wrong song
- Genres and Playlists no longer crash the app on phones where Android doesn't provide them
- Playlists open faster; opening a genre no longer briefly blocks other loading
- Folder song counts are always correct
- The queue list updates much faster and keeps your scroll position
- The now-playing marker no longer makes the list twitch on every song change
- Long-pressing a row can no longer open the menu for something else
- Album art uses less memory, and no longer shows an old cover after a rescan

### Equalizer

- Boosting bands no longer causes distortion on loud tracks
- A slider no longer changes value if your gesture gets interrupted
- Damaged settings no longer shift gains onto the wrong bands

### Player screen

- The scrolling song/folder title works when tapped, even after leaving and coming back
- The up/down folder-swipe animation uses less memory
- The album-art deck no longer gets stuck showing the wrong card

### Look and feel

- Toolbar and mini-player are no longer hidden behind the system bars on Android 15
- Row text is no longer flush against the screen edge on Android 7 and 7.1
- The glow line under the playing song no longer looks muddy at its ends
- The search box's tap ripple stays inside its rounded corners

### Text

- "1 songs" now reads "1 song"
- The Info box and the folder title can now be translated
- Pressing Search on the keyboard hides the keyboard so you can see results
- The About box states the app's licence

### Permissions and privacy

- If you refuse the audio permission, the app explains why the library is empty and offers to open settings
- Deleting files no longer freezes the app, and it tells you if a delete failed
- Phone backups no longer restore a playback queue from a different phone

### Behind the scenes

- Dropped 84 unused translations pulled in by libraries — about 450 KB smaller
- Removed an icon that was never shown
- Added 40 automated tests
- Builds are faster; debug builds no longer overwrite or block the normal app
- On unsupported phones the app refuses to install rather than installing with no sound
- Fixed small problems in the build and install scripts, tidied the docs, added a licence note to every file

# Timbra 0.7.44 — 2026-07-30

### Playback robustness

- Play MPEG audio inside RIFF/WAVE containers (fmt tag 0x55/0x50) via a new RiffMpegExtractor — these files were claimed by WavExtractor and then rejected as a source error, so they simply refused to play.
- A track that errors out now skips to the next one instead of leaving the player IDLE with a dead play button.
- Tracks that wedge while buffering at the very end (rips whose reported duration overruns the actual audio) are finished by seeking past the last frame instead of hanging.
- A failed controller connection is retried rather than leaving the app silent.
- If the saved current track was deleted, playback restores the nearest surviving song in that list instead of coming up empty.

### Shuffle and "play next"

- "Play next" is honoured under shuffle: enqueued songs lead the shuffle order instead of joining the random pool, and the enqueued block stays contiguous.
- Cycling the shuffle button (Shuffle-Songs → OFF) no longer wipes the queue. The manually enqueued block is now lifted off the timeline and spliced back on both mode transitions, so it survives the round trip.
- A "play next" insertion is absorbed into the running shuffle session, so the no-repeat history is kept instead of reset. Enqueued duplicates and a completed shuffle pass no longer clear that history either.
- Play-next items are marked consumed once they play, fixing songs resurrecting after a shuffle change, after a reorder that crosses the current song, and Clear Queue cutting playback off.

### Advance-List

- The next-folder step lives in one shared implementation owned by the service, so queue ends, stuck tracks, and Next from the notification or Bluetooth all advance identically.

### Library, sorting and metadata

- Disc numbers are kept, so multi-disc albums stop interleaving their tracks; the track-1000 edge case is fixed too.
- Folder sort is persisted app-wide, so browse order and play order always agree.
- List rows and their tap targets are committed together, so a tap landing during a re-sort plays the song you actually touched.
- Repository caches are rescan-safe, and the album/artist groupings are shared rather than rebuilt.

### Equalizer

- Output is rounded instead of truncated, filter state resets when coefficients change, and coefficients are published atomically (no torn reads mid-block).
- The last fader step of a drag is persisted — previously it could be dropped on release.

### Artwork and UI

- Art decodes are sized to the target view and the cache is keyed by size; the legacy album-art path is sampled instead of decoded full-size.
- Pending marquee work is cancelled, so it can no longer overwrite another screen's toolbar title.
- Queue-screen updates that arrive mid-drag are re-applied afterwards, and taps are guarded by media id.
- Navigation launched after a suspending load is guarded against a stopped activity.
- Transport binding, dialogs, and the player controls layout are shared between portrait and landscape.

# Timbra 0.7.36 — 2026-07-26

- New landscape player: two-pane layout (art left, controls right).
- Smoother folder swipes — art no longer flashes or swaps mid-animation.
- Correct per-track covers in mini-player and queue; embedded-tag art used as fallback.
- Fixed stuck "advance to next folder" when swiping quickly.
- Central art band excluded from the system back-gesture.
- Build: scoped JitPack to com.github.* to fix nextlib resolution failures.

# Timbra 0.7.20 — 2026-07-22

Just bug fixes this time:

- Advance-List now rolls the last song of a folder into the next folder even when the app is in the background or the screen is off — previously it only continued while the player was on-screen.
- The album-art deck no longer plays a spurious flip animation when you return to the player after a track changed in the background; genuine live transitions still animate.

# Timbra 0.7.18 — 2026-07-22

### Equalizer

- 7-band graphic equalizer (custom Media3 biquad DSP), works on all devices/formats. On/Off toggle and per-band gains persist across restarts. Opens from the overflow menu on any screen.

### Player navigation

- Back arrow walks up the song's folder tree instead of navigation history.
- Toolbar shows the song's folder path; long titles/paths marquee-scroll.

### Playback

- Position is restored after the app is closed and reopened.

### Search

- Choose play scope on a result: all matches, or just that result's folder. More reliable keyboard, and the playing song is centered in its folder list.

### UI

- Full-player redesign: audio-format line (sample rate / bitrate / container), smaller play button, controls over pure black, app-mark glow instead of a generic art placeholder.

### Fixes

- Advance-List no longer stops at the end of a folder after the Activity is recreated.
- Progress bar and timestamp stay correct when switching apps.

# Timbra 0.6.9 — 2026-07-17

### New

- **Folder swipe navigation** — swipe the album art **up/down** to jump to the next/previous song folder (flat, filename-ordered traversal), with a finger-following slide and wrap-around animation. Horizontal swipes keep switching songs and now share the exact same transport path as the prev/next buttons.
- **Folder-aware back arrow** — the player's top-left arrow walks up the current song's folder tree instead of replaying navigation history (the system back gesture still does history).
- **Folder path in the toolbar** — the full player shows the song's folder path (root omitted), marquee-scrolling long paths once per song.
- **Audio format info** — the full player displays the decoded sample rate, bitrate and container of the current track.
- **Resume playback** — the queue, current song and position survive app restarts; reopening the app restores everything paused right where you left off.

### Improved

- Player look & feel: controls on pure black, smaller play button, and a glowing app mark instead of a generic placeholder for tracks without album art.
- `install.sh` now updates in place by default (app data and the home-screen shortcut survive); `--clean` forces a fresh install and restores the shortcut.

### Fixed

- The progress bar and timestamp (full player and mini-player) froze after switching to another app and back while a song was playing; they now keep advancing in real time.

# Timbra 0.4.2 — 2026-07-16

Timbra first release 🎉

A free, lightweight music player for Android, inspired by the classic Poweramp v2 look and feel, rebuilt from scratch as original code on a modern stack.

Highlights:

1. Folder-first browsing — no all-files permission needed
2. Full library: Folders, Albums, Artists, All Songs, Genres, Playlists, Queue
3. Wide format support via FFmpeg decoders
4. Gapless & background playback with lock-screen/Bluetooth controls
5. Swipeable album-art deck: left/right to change song, up/down to change folder
6. Shuffle & repeat modes, including Advance-List folder flow
7. Classic matte-dark theme, pure black for OLED
8. No ads, no telemetry, no bullshit

Requirements: Android 7.0+ (arm64-v8a / armeabi-v7a)

Licensed GPLv3+.

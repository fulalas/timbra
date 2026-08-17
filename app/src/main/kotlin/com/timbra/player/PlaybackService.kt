// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.player

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.timbra.app
import com.timbra.eqSettings
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    private var exoPlayer: ExoPlayer? = null

    private val eqProcessor = EqualizerAudioProcessor()

    /**
     * Service-side position persistence. The UI's PlayerConnection saves the position on
     * its own lifecycle events, but once the activity is gone the service can play for
     * hours with nobody recording progress — a later cold-start restore would then rewind
     * to wherever the SCREEN was last closed. So the service itself checkpoints the
     * position every few seconds while playing, on every pause, and at shutdown.
     */
    private val store get() = app.playbackStore

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val saveHandler = Handler(Looper.getMainLooper())
    private val positionSaver = object : Runnable {
        override fun run() {
            val player = exoPlayer ?: return
            store.checkpoint(player)
            if (player.isPlaying) saveHandler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
        }
    }

    /**
     * How many tracks in a row have been skipped because they failed to load. Reset by the first
     * track that actually reaches STATE_READY; caps the walk so a queue of nothing but broken
     * files (or a repeat-all loop over them) can't spin forever.
     */
    private var errorSkips = 0

    private val stallHandler = Handler(Looper.getMainLooper())

    private var stallMark = 0L

    // --- Custom shuffle engine ---
    // ExoPlayer's built-in shuffle is a fixed permutation, so Prev/Next just retrace it and can
    // revisit songs. Instead we drive ExoPlayer's shuffle ORDER ourselves so that: Next always
    // goes to a random UNPLAYED song (no repeats), Previous returns the actual song played before,
    // and once everything has played Next stops. State is per shuffle "session" and resets when
    // shuffle is toggled or the queue is replaced.
    private val shufHistory = mutableListOf<Int>()   // timeline indices, actual play path
    private var shufPos = 0                           // index of the current song within shufHistory
    private val shufPlayed = mutableSetOf<Int>()      // every index played this session (no-repeat)
    /** Timeline media ids at the last (re)build. Tells a real queue change from our own
     *  setShuffleOrder (which also fires onTimelineChanged, and would otherwise loop forever),
     *  and locates a "play next" insertion so the session can absorb it (see [insertionShift]).
     *  Ids alone can't identify songs — the same song may sit in the queue twice — so the
     *  engine keeps working in timeline indices and remaps them across inserts. */
    private var lastIds: List<String> = emptyList()

    /** How many items carried the enqueued flag at the last (re)build. An insertion is a "play
     *  next" one exactly when this grew by the number of inserted slots — which, unlike a
     *  per-slot flag check, stays right when the inserted song is a DUPLICATE of its neighbour
     *  (the id walk can't tell the two copies apart, and the old check then rejected a genuine
     *  enqueue and wiped the whole no-repeat history). */
    private var lastEnqueuedCount = 0

    override fun onCreate() {
        super.onCreate()

        // Apply persisted equalizer settings to the DSP before the pipeline starts. The
        // app-wide instance, not a second wrapper over the same prefs file.
        val eq = eqSettings
        eqProcessor.update(eq.enabled, eq.gains())

        // EXTENSION_RENDERER_MODE_ON: prefer the platform MediaCodec decoders (which do
        // true gapless — they read/trim encoder delay+padding) and fall back to the FFmpeg
        // decoders only for formats the device can't handle natively. PREFER routed every
        // track through FFmpeg, which left an audible gap between songs.
        // EqRenderersFactory splices the equalizer DSP into the audio sink.
        val renderers = EqRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val player = ExoPlayer.Builder(this, renderers)
            // TimbraExtractorsFactory adds RIFF-wrapped MPEG (WAVE format tag 0x55) on top of
            // the defaults — those files are named .mp3 but WavExtractor claims and then
            // rejects them, which used to kill the track with a source error.
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, TimbraExtractorsFactory()))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Matches the WAKE_LOCK permission the manifest declares: without this the
            // permission bought nothing, and long playback with the screen off is exposed to
            // doze-related stalls. WAKE_MODE_LOCAL is the right mode for on-device files.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exoPlayer = player

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveHandler.removeCallbacks(positionSaver)
                saveHandler.post(positionSaver)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) resetShuffleSession(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) errorSkips = 0
                // The queue ended: roll into the next folder. Owned here whether or not the UI
                // is attached — deciding that once, at the instant STATE_ENDED is observed, meant
                // the advance was lost for good if the app was backgrounded before the UI's own
                // copy of the event was delivered.
                if (playbackState == Player.STATE_ENDED) {
                    advanceFolder(player, forward = true) {
                        player.playbackState == Player.STATE_ENDED
                    }
                }
                watchForEndStall(player, playbackState)
            }

            override fun onPlayerError(error: PlaybackException) = skipStuckTrack(player)

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                // Act only when the media items actually changed (a real queue swap, or an
                // insertion), not for our own setShuffleOrder (which also fires this with the
                // same items) — otherwise it would loop forever (ANR).
                if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED ||
                    !player.shuffleModeEnabled
                ) return
                val ids = mediaIds(player)
                if (ids == lastIds) return
                // A "play next" insertion must fold INTO the running session (so the enqueued song
                // is what plays next and the no-repeat history survives); anything else is a queue
                // replacement and starts a fresh session.
                val shift = insertionShift(lastIds, ids)
                if (shift == null || !adoptEnqueueInsertion(player, shift)) resetShuffleSession(player)
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (!player.shuffleModeEnabled) return
                // REPEAT (repeat-one) stays on the same song; PLAYLIST_CHANGED is handled by the
                // timeline reset above. Everything else (AUTO advance, SEEK from Next/Prev/tap)
                // moves the current song, so update the shuffle path.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                ) return
                onShuffleAdvance(player, player.currentMediaItemIndex)
            }
        })

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                mediaSession?.setSessionExtras(Bundle().apply {
                    putInt(EXTRA_SAMPLE_RATE, format.sampleRate)
                    putInt(EXTRA_BITRATE, format.bitrate)
                })
            }

        })

        mediaSession = MediaSession.Builder(this, AdvancePlayer(player))
            .setCallback(eqCallback)
            .build()
    }

    /**
     * Wraps the player so Advance-List's queue-edge behaviour applies to EVERY transport source.
     *
     * It used to live only in the UI's [PlayerConnection], so the same Next issued from the
     * notification, the lock screen or a Bluetooth remote reached ExoPlayer directly — and since
     * Advance-List maps to REPEAT_MODE_OFF, seeking past the last item is simply a no-op, so
     * nothing happened. [getAvailableCommands] also keeps the buttons live at the edges, which is
     * what lets the command arrive at all.
     */
    private inner class AdvancePlayer(player: Player) : ForwardingPlayer(player) {

        override fun getAvailableCommands(): Player.Commands {
            val base = super.getAvailableCommands()
            if (!FolderAdvance.armed(this@PlaybackService)) return base
            return base.buildUpon()
                .addAll(
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean =
            availableCommands.contains(command)

        override fun seekToNext() {
            if (!advanceAtEdge(forward = true)) super.seekToNext()
        }

        override fun seekToNextMediaItem() {
            if (!advanceAtEdge(forward = true)) super.seekToNextMediaItem()
        }

        override fun seekToPrevious() {
            if (!advanceAtEdge(forward = false)) super.seekToPrevious()
        }

        override fun seekToPreviousMediaItem() {
            if (!advanceAtEdge(forward = false)) super.seekToPreviousMediaItem()
        }
    }

    /**
     * Handle a transport command that ran off the end (or the start) of the queue by stepping to
     * the neighbouring folder. Returns true when it took the command.
     *
     * Backward stepping is deliberately limited to the very start of the first song with shuffle
     * OFF: mid-song Previous must still restart the song, and under shuffle Previous only walks
     * back through the songs actually played this session.
     */
    private fun advanceAtEdge(forward: Boolean): Boolean {
        val player = exoPlayer ?: return false
        if (player.mediaItemCount == 0) return false
        if (!FolderAdvance.armed(this)) return false
        if (forward) {
            if (player.hasNextMediaItem()) return false
        } else {
            if (player.shuffleModeEnabled) return false
            if (player.hasPreviousMediaItem()) return false
            if (player.currentPosition > player.maxSeekToPreviousPosition) return false
        }
        // The return value matters: advanceFolder has its own early exits (nothing playing, an
        // item with no path), and claiming the command without acting on it left Next/Previous
        // silently dead instead of falling through to super.
        return advanceFolder(player, forward)
    }

    private val eqCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val connect = super.onConnect(session, controller)
            val commands = connect.availableSessionCommands.buildUpon()
                .add(SessionCommand(CMD_APPLY_EQ, Bundle.EMPTY))
                .add(SessionCommand(CMD_ADVANCE_FOLDER, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(commands, connect.availablePlayerCommands)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_APPLY_EQ) {
                eqProcessor.update(
                    args.getBoolean(EXTRA_EQ_ENABLED, false),
                    args.getIntArray(EXTRA_EQ_GAINS) ?: IntArray(EqSettings.BAND_COUNT),
                )
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_ADVANCE_FOLDER) {
                // The UI asking for the queue-edge folder step explicitly, because media3 masks
                // COMMAND_SEEK_TO_NEXT out at the edge (Advance-List maps to REPEAT_MODE_OFF) and
                // the seek would be dropped before [AdvancePlayer] ever saw it. Still the same
                // single implementation — the two routes are mutually exclusive.
                exoPlayer?.let {
                    advanceFolder(it, args.getBoolean(EXTRA_ADVANCE_FORWARD, true))
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    private inner class EqRenderersFactory(context: Context) : NextRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(eqProcessor))
            .build()
    }

    /**
     * Recover from a track playback can't get through — one that errors out (missing file,
     * unsupported/corrupt container) or wedges at the very end (see [watchForEndStall]) — by
     * moving on to the next one, exactly as if it had finished.
     *
     * Without this the player is simply wedged: a failed track leaves ExoPlayer IDLE holding the
     * error, so it never advances, and the session's play request only re-prepares the SAME broken
     * item — which errors again instantly. To the user the app looks frozen with a dead play
     * button, which is what an unplayable file in the middle of a shuffle used to do.
     *
     * When there is nothing to skip to, the queue is effectively over, so Advance-List gets the
     * same next-folder treatment a clean end would get. That runs whether or not the UI is
     * attached: the UI has no stall detection of its own, so deferring to it here left the player
     * frozen a second short of the end, still displaying "playing", with nothing to recover it.
     */
    private fun skipStuckTrack(player: ExoPlayer) {
        if (player.mediaItemCount == 0) return
        // REPEAT_MODE_ONE would name the broken track as its own successor: step over it instead.
        val repeat = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF
        else player.repeatMode
        val next = player.currentTimeline.getNextWindowIndex(
            player.currentMediaItemIndex, repeat, player.shuffleModeEnabled,
        )
        if (next == C.INDEX_UNSET) {
            // The still-stuck test is just "not playing": a stalled/errored track never reaches
            // STATE_ENDED. The media-id guard inside [advanceFolder] pins it to the same song.
            advanceFolder(player, forward = true) { !player.isPlaying }
            return
        }
        if (errorSkips >= MAX_ERROR_SKIPS.coerceAtMost(player.mediaItemCount)) {
            // Give up, but in a COHERENT state. Returning here left ExoPlayer IDLE holding the
            // error with playWhenReady still set: the notification kept claiming playback, nothing
            // could advance, and errorSkips could never clear (only STATE_READY resets it, which
            // can no longer happen) — the frozen transport this whole method exists to prevent.
            errorSkips = 0
            player.pause()
            return
        }
        errorSkips++
        val resume = player.playWhenReady
        player.seekTo(next, 0L)
        player.prepare()   // clears the error; seekTo alone leaves the player IDLE
        if (resume) player.play()
    }

    /**
     * Watch a buffering spell and, if it never clears at the very end of a track, finish the track
     * instead of hanging there.
     *
     * These are local files, so mid-track buffering is always pathological. The case that actually
     * happens: some rips declare a duration that overruns their real audio, and seeking into that
     * phantom tail lands past the final frame — the renderer then waits forever for samples that
     * don't exist. Playback freezes a second short of the end with the transport still showing
     * "playing", and never rolls into the next song. (Playing the same file straight through is
     * fine: the extractor hits a clean end-of-input, so only a seek can trigger it.)
     *
     * Deliberately narrow — it acts only when the position has stopped moving AND is inside the
     * last [END_STALL_WINDOW_MS] — so an ordinary slow load is left alone.
     */
    private fun watchForEndStall(player: ExoPlayer, playbackState: Int) {
        stallHandler.removeCallbacks(endStallCheck)
        if (playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return
        stallMark = player.currentPosition
        stallHandler.postDelayed(endStallCheck, END_STALL_TIMEOUT_MS)
    }

    private val endStallCheck = object : Runnable {
        override fun run() {
            val player = exoPlayer ?: return
            if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return
            val position = player.currentPosition
            if (position != stallMark) {          // still making progress: keep watching
                stallMark = position
                stallHandler.postDelayed(this, END_STALL_TIMEOUT_MS)
                return
            }
            val duration = player.duration
            if (duration == C.TIME_UNSET || position < duration - END_STALL_WINDOW_MS) return
            skipStuckTrack(player)                // same recovery as a track that errors out
        }
    }

    private fun mediaIds(player: ExoPlayer): List<String> =
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    private fun enqueuedCount(player: ExoPlayer): Int =
        (0 until player.mediaItemCount).count { player.getMediaItemAt(it).isEnqueued }

    /**
     * Old-index → new-index map when [new] is [old] with items inserted, else null (a genuine
     * queue replacement, where nothing about the old session can be carried over). Matching is
     * a greedy subsequence walk, which is enough here: any leftover slot is a newly inserted one.
     */
    private fun insertionShift(old: List<String>, new: List<String>): IntArray? {
        if (old.isEmpty() || new.size <= old.size) return null
        val map = IntArray(old.size)
        var o = 0
        for (n in new.indices) if (o < old.size && new[n] == old[o]) map[o++] = n
        return if (o == old.size) map else null
    }

    /**
     * Fold a "play next" insertion into the running shuffle session: shift the recorded indices
     * through [shift] so the play path and the no-repeat set still point at the right songs, then
     * rebuild the order (which puts the enqueued songs next — see [applyShuffleOrder]).
     *
     * Returns false when this isn't actually a play-next insert, and the caller should reset
     * instead. Two conditions matter: the playing song must be untouched, and the enqueued-item
     * COUNT must have grown by exactly the number of inserted slots. The count is what makes this
     * duplicate-proof — the old check demanded that every slot the id walk left over carry the
     * enqueued flag, but when the enqueued song is a copy of the one already sitting next to it,
     * the walk can't tell the two apart and blamed the wrong one. That rejected a genuine enqueue
     * and reset the session, silently wiping the no-repeat history mid-listen. A queue
     * REPLACEMENT whose old items happen to be a subsequence of the new ones (playing a folder,
     * then All Songs) still fails it, because no enqueued item was added.
     */
    private fun adoptEnqueueInsertion(player: ExoPlayer, shift: IntArray): Boolean {
        if (shufHistory.isEmpty() || shufPos !in shufHistory.indices) return false
        if (shufHistory.any { it !in shift.indices } || shufPlayed.any { it !in shift.indices }) return false
        if (shift[shufHistory[shufPos]] != player.currentMediaItemIndex) return false
        val inserted = player.mediaItemCount - shift.size
        if (enqueuedCount(player) - lastEnqueuedCount != inserted) return false
        for (i in shufHistory.indices) shufHistory[i] = shift[shufHistory[i]]
        val played = shufPlayed.map { shift[it] }
        shufPlayed.clear(); shufPlayed.addAll(played)
        applyShuffleOrder(player)
        return true
    }

    private fun resetShuffleSession(player: ExoPlayer) {
        val count = player.mediaItemCount
        lastIds = mediaIds(player)
        lastEnqueuedCount = enqueuedCount(player)
        // Clear FIRST, so an empty timeline can't leave a stale path behind for the next build.
        shufHistory.clear()
        shufPlayed.clear()
        shufPos = 0
        if (count == 0) return
        val cur = player.currentMediaItemIndex.coerceIn(0, count - 1)
        shufHistory.add(cur)
        shufPlayed.add(cur)
        applyShuffleOrder(player)
    }

    private fun onShuffleAdvance(player: ExoPlayer, cur: Int) {
        if (shufHistory.isEmpty() || player.mediaItemCount == 0) {
            resetShuffleSession(player); return
        }
        val c = cur.coerceIn(0, player.mediaItemCount - 1)
        when {
            c == shufHistory.getOrNull(shufPos) -> return                 // no real change
            c == shufHistory.getOrNull(shufPos - 1) -> shufPos--          // Previous: walk back
            else -> {                                                     // forward (chosen) or a tap
                if (shufPos < shufHistory.size - 1) {
                    shufHistory.subList(shufPos + 1, shufHistory.size).clear()  // drop the old forward path
                }
                // Jumping to an already-played song (queue-screen tap, repeat-list wrap) must
                // MOVE it to the end of the path, not append a second copy — a duplicated
                // history would make the rebuilt shuffle order a non-permutation (wrong length,
                // duplicate indices) and corrupt ExoPlayer navigation.
                shufHistory.removeAll { it == c }
                shufHistory.add(c); shufPos = shufHistory.lastIndex; shufPlayed.add(c)
            }
        }
        applyShuffleOrder(player)
    }

    /**
     * Rebuild ExoPlayer's shuffle order to encode the session:
     * [played path up to current] + [enqueued] + [one chosen unplayed] + [other unplayed] +
     * [discarded played].
     * So Next/auto-advance go to the chosen unplayed, Previous returns the prior path song, and
     * when nothing is unplayed the current song ends up last so Next stops.
     *
     * Manually enqueued songs ("play next") come first, in queue order, and only then does the
     * random walk resume. Under shuffle their TIMELINE position means nothing — this order is
     * what actually plays — so without this they would just be more songs in the random pool.
     *
     * Note there is no special case for "everything has played": the prefix already ends with the
     * current song, so the general form places it last on its own. Re-shuffling here instead
     * threw the recorded play path away, and Previous during the last song of a completed pass
     * then jumped to an arbitrary song and corrupted the history from there on.
     */
    private fun applyShuffleOrder(player: ExoPlayer) {
        val count = player.mediaItemCount
        if (count == 0 || shufHistory.isEmpty()) return
        val prefix = shufHistory.subList(0, shufPos + 1).toList()
        val queued = (0 until count).filter { it !in shufPlayed && player.getMediaItemAt(it).isEnqueued }
        val queuedSet = queued.toHashSet()
        val unplayed = (0 until count).filter { it !in shufPlayed && it !in queuedSet }
        val chosen = if (unplayed.isEmpty()) emptyList() else listOf(unplayed.random())
        val rest = unplayed.filter { it !in chosen }.shuffled()
        // Set, not the prefix List: `it !in prefix` was a linear scan, making this line
        // O(played x prefix) on a path that runs from onMediaItemTransition for EVERY track
        // change — ~25M comparisons once half of a 10k Shuffle-All queue has played.
        val prefixSet = prefix.toHashSet()
        val discarded = shufPlayed.filter { it !in prefixSet }.shuffled()
        val order = (prefix + queued + chosen + rest + discarded).toIntArray()
        // Safety net: the order MUST be a permutation of 0..count-1 or ExoPlayer's timeline
        // navigation corrupts (or crashes). If an invariant was ever violated (stale indices
        // after a shrink, unexpected duplicates), start a fresh session instead of applying it.
        if (order.size != count || order.any { it !in 0 until count } || order.toSet().size != count) {
            resetShuffleSession(player)
            return
        }
        lastIds = mediaIds(player)
        lastEnqueuedCount = enqueuedCount(player)
        player.setShuffleOrder(DefaultShuffleOrder(order, System.nanoTime()))
    }

    private fun advanceFolder(
        player: Player,
        forward: Boolean,
        stillWanted: () -> Boolean = { true },
    ): Boolean {
        if (!FolderAdvance.armed(this)) return false
        val gen = app.session.queueGeneration
        val path = player.currentMediaItem?.pathExtra ?: return false
        if (path.isEmpty()) return false
        serviceScope.launch {
            FolderAdvance.move(this@PlaybackService, player, forward, expectedGen = gen) {
                stillWanted() && player.currentMediaItem?.pathExtra == path
            }
        }
        return true
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = exoPlayer
        if (player == null || (!player.playWhenReady) || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        saveHandler.removeCallbacks(positionSaver)
        stallHandler.removeCallbacks(endStallCheck)
        exoPlayer?.let { store.checkpoint(it) }
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }

    companion object {
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L

        private const val MAX_ERROR_SKIPS = 25

        private const val END_STALL_TIMEOUT_MS = 2_500L

        private const val END_STALL_WINDOW_MS = 5_000L

        const val EXTRA_SAMPLE_RATE = "tb_sample_rate"
        const val EXTRA_BITRATE = "tb_bitrate"

        const val CMD_APPLY_EQ = "com.timbra.EQ_APPLY"
        const val EXTRA_EQ_ENABLED = "tb_eq_enabled"
        const val EXTRA_EQ_GAINS = "tb_eq_gains"

        const val CMD_ADVANCE_FOLDER = "com.timbra.ADVANCE_FOLDER"
        const val EXTRA_ADVANCE_FORWARD = "tb_advance_forward"
    }
}

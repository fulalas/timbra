package com.timbra.player

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.timbra.app
import com.timbra.data.FolderTreeBuilder
import com.timbra.data.SortDefaults
import com.timbra.data.comparatorFor
import com.timbra.repository
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts the ExoPlayer instance and a MediaSession so playback survives the UI and
 * shows system/lock-screen controls. Audio decoding is FFmpeg-backed via nextlib's
 * [NextRenderersFactory], which ships decoders for all ABIs (incl. arm64) — the
 * modern replacement for the original 32-bit-only native engine.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /** 7-band equalizer DSP spliced into ExoPlayer's audio pipeline (see [EqRenderersFactory]). */
    private val eqProcessor = EqualizerAudioProcessor()

    /**
     * Service-side position persistence. The UI's PlayerConnection saves the position on
     * its own lifecycle events, but once the activity is gone the service can play for
     * hours with nobody recording progress — a later cold-start restore would then rewind
     * to wherever the SCREEN was last closed. So the service itself checkpoints the
     * position every few seconds while playing, on every pause, and at shutdown.
     */
    private lateinit var store: PlaybackStateStore

    /** Scope for the background Advance-List folder lookup (folder tree read off the main thread). */
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val saveHandler = Handler(Looper.getMainLooper())
    private val positionSaver = object : Runnable {
        override fun run() {
            val player = mediaSession?.player ?: return
            if (player.mediaItemCount > 0) {
                store.savePosition(player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0))
            }
            if (player.isPlaying) saveHandler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
        }
    }

    // --- Custom shuffle engine ---
    // ExoPlayer's built-in shuffle is a fixed permutation, so Prev/Next just retrace it and can
    // revisit songs. Instead we drive ExoPlayer's shuffle ORDER ourselves so that: Next always
    // goes to a random UNPLAYED song (no repeats), Previous returns the actual song played before,
    // and once everything has played Next stops. State is per shuffle "session" and resets when
    // shuffle is toggled or the queue is replaced.
    private val shufHistory = mutableListOf<Int>()   // timeline indices, actual play path
    private var shufPos = 0                           // index of the current song within shufHistory
    private val shufPlayed = mutableSetOf<Int>()      // every index played this session (no-repeat)
    /** Media-id signature at the last (re)build, to tell a real queue change from our own
     *  setShuffleOrder (which also fires onTimelineChanged) and avoid an infinite loop. */
    private var lastShuffledSig = 0

    override fun onCreate() {
        super.onCreate()
        store = PlaybackStateStore(this)

        // Apply persisted equalizer settings to the DSP before the pipeline starts.
        EqSettings(this).let { eqProcessor.update(it.enabled, it.gains()) }

        // EXTENSION_RENDERER_MODE_ON: prefer the platform MediaCodec decoders (which do
        // true gapless — they read/trim encoder delay+padding) and fall back to the FFmpeg
        // decoders only for formats the device can't handle natively. PREFER routed every
        // track through FFmpeg, which left an audible gap between songs.
        // EqRenderersFactory splices the equalizer DSP into the audio sink.
        val renderers = EqRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Checkpoint now (captures the exact pause point), then keep checkpointing
                // on an interval while playing — the runnable reschedules itself.
                saveHandler.removeCallbacks(positionSaver)
                saveHandler.post(positionSaver)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) resetShuffleSession(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // The queue ended. When the UI is attached it drives the Advance-List advance;
                // when it isn't (backgrounded / Activity gone) nothing else would, so do it here.
                if (playbackState == Player.STATE_ENDED) advanceToNextFolderIfDetached(player)
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                // Reset the session only when the media items actually changed (a real queue
                // swap), not for our own setShuffleOrder (which also fires this with the same
                // items) — otherwise it would loop forever (ANR).
                if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED || !player.shuffleModeEnabled) return
                if (mediaIdSignature(player) != lastShuffledSig) resetShuffleSession(player)
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

        // Publish the DECODED audio format (sample rate / bitrate) to controllers through
        // the session extras — MediaController exposes no other way to read it, and only
        // the service-side ExoPlayer knows the real values.
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

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(eqCallback)
            .build()
    }

    /**
     * Grants the UI a single custom command, [CMD_APPLY_EQ], so the equalizer screen can push
     * live band changes to the DSP. The UI is the source of truth (it persists to [EqSettings]);
     * this just reapplies what it sends.
     */
    private val eqCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val connect = super.onConnect(session, controller)
            val commands = connect.availableSessionCommands.buildUpon()
                .add(SessionCommand(CMD_APPLY_EQ, Bundle.EMPTY))
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
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    /** A [NextRenderersFactory] that splices the equalizer DSP into the audio sink. */
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

    /** A hash of the queue's media ids in order — changes only when the items themselves change. */
    private fun mediaIdSignature(player: ExoPlayer): Int {
        var h = 1
        for (i in 0 until player.mediaItemCount) h = 31 * h + player.getMediaItemAt(i).mediaId.hashCode()
        return h
    }

    /** Start a fresh shuffle session anchored on the currently-playing song. */
    private fun resetShuffleSession(player: ExoPlayer) {
        val count = player.mediaItemCount
        lastShuffledSig = mediaIdSignature(player)
        if (count == 0) return
        val cur = player.currentMediaItemIndex.coerceIn(0, count - 1)
        shufHistory.clear(); shufHistory.add(cur)
        shufPos = 0
        shufPlayed.clear(); shufPlayed.add(cur)
        applyShuffleOrder(player)
    }

    /** React to the current song changing: extend the path forward, or step back on Previous. */
    private fun onShuffleAdvance(player: ExoPlayer, cur: Int) {
        if (shufHistory.isEmpty()) { resetShuffleSession(player); return }
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
     * [played path up to current] + [one chosen unplayed] + [other unplayed] + [discarded played].
     * So Next/auto-advance go to the chosen unplayed, Previous returns the prior path song, and
     * when nothing is unplayed the current song is placed last so Next stops.
     */
    private fun applyShuffleOrder(player: ExoPlayer) {
        val count = player.mediaItemCount
        if (count == 0 || shufHistory.isEmpty()) return
        val cur = shufHistory[shufPos]
        val prefix = shufHistory.subList(0, shufPos + 1).toList()
        val unplayed = (0 until count).filter { it !in shufPlayed }
        val order: IntArray = if (unplayed.isEmpty()) {
            ((0 until count).filter { it != cur }.shuffled() + cur).toIntArray()  // current last -> Next stops
        } else {
            val chosen = unplayed.random()
            val rest = unplayed.filter { it != chosen }.shuffled()
            val discarded = shufPlayed.filter { it !in prefix }.shuffled()
            (prefix + chosen + rest + discarded).toIntArray()
        }
        // Safety net: the order MUST be a permutation of 0..count-1 or ExoPlayer's timeline
        // navigation corrupts (or crashes). If an invariant was ever violated (stale indices
        // after a shrink, unexpected duplicates), start a fresh session instead of applying it.
        if (order.size != count || order.any { it !in 0 until count } || order.toSet().size != count) {
            resetShuffleSession(player)
            return
        }
        lastShuffledSig = mediaIdSignature(player)
        player.setShuffleOrder(DefaultShuffleOrder(order, System.nanoTime()))
    }

    /**
     * Advance-List continuation when the queue ends with no UI attached. Advance-List maps to
     * REPEAT_MODE_OFF, so ExoPlayer just stops at the end; the app-level advance normally lives
     * in the UI (richer deck/phantom/folderContext handling), but the UI's controller is gone
     * once the app is backgrounded or its Activity destroyed — leaving nothing to roll the last
     * song of a folder into the next one with the screen off. This is that fallback.
     *
     * Anchored on the current song's directory (the queue is the folder it was loaded from),
     * matching the UI's own fallback when it has no folderContext. No-op while the UI is attached
     * (it owns the advance then) or when the persisted repeat mode isn't Advance-List.
     */
    private fun advanceToNextFolderIfDetached(player: ExoPlayer) {
        if (app.uiControllerAttached) return
        if (store.loadModes().second != RepeatMode.ADVANCE.ordinal) return
        val path = player.currentMediaItem?.mediaMetadata?.extras?.getString(KEY_PATH) ?: return
        val dir = path.substringBeforeLast('/', "")
        if (dir.isEmpty()) return
        serviceScope.launch {
            val (_, next) = FolderTreeBuilder.neighbourFolders(repository.songFolders(), dir)
            val tracks = next?.tracks?.sortedWith(comparatorFor(SortDefaults.FOLDER_SONGS))
                ?: return@launch  // no next folder: stop here
            if (tracks.isEmpty()) return@launch
            // The folder lookup was async: bail if the user reconnected or the player moved on
            // in the meantime, so we never stomp a fresh queue or a resumed UI's own advance.
            if (app.uiControllerAttached) return@launch
            if (player.playbackState != Player.STATE_ENDED) return@launch
            if (player.currentMediaItem?.mediaMetadata?.extras?.getString(KEY_PATH) != path) return@launch
            player.setMediaItems(tracks.map { it.toMediaItem(this@PlaybackService) }, 0, 0L)
            player.prepare()
            player.play()
            // The UI persists the queue on timeline changes, but it's detached here — so mirror
            // that ourselves. Otherwise a cold start after the process is killed mid-background
            // would restore the STALE previous folder at a now-meaningless index. Also downgrade
            // Shuffle-All to Shuffle-Songs (the pool is now this one folder), matching the UI's
            // own advance, so the restored shuffle icon stays truthful.
            val (shuffleOrdinal, repeatOrdinal) = store.loadModes()
            if (shuffleOrdinal == ShuffleMode.ALL.ordinal) {
                store.saveModes(ShuffleMode.CURRENT.ordinal, repeatOrdinal)
            }
            store.saveQueue(tracks.map { it.id }, emptyList())
            store.savePosition(0, 0)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = mediaSession?.player
        if (player == null || (!player.playWhenReady) || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        saveHandler.removeCallbacks(positionSaver)
        // Final checkpoint before the player goes away, so a cold start resumes exactly here.
        mediaSession?.player?.let { p ->
            if (p.mediaItemCount > 0) {
                store.savePosition(p.currentMediaItemIndex, p.currentPosition.coerceAtLeast(0))
            }
        }
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L

        /** Session-extras keys carrying the decoded audio format to the UI. */
        const val EXTRA_SAMPLE_RATE = "tb_sample_rate"
        const val EXTRA_BITRATE = "tb_bitrate"

        /** Custom command: reapply the equalizer. Args carry [EXTRA_EQ_ENABLED] + [EXTRA_EQ_GAINS]. */
        const val CMD_APPLY_EQ = "com.timbra.EQ_APPLY"
        const val EXTRA_EQ_ENABLED = "tb_eq_enabled"
        const val EXTRA_EQ_GAINS = "tb_eq_gains"
    }
}

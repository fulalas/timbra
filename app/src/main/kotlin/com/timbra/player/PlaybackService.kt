package com.timbra.player

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
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * Hosts the ExoPlayer instance and a MediaSession so playback survives the UI and
 * shows system/lock-screen controls. Audio decoding is FFmpeg-backed via nextlib's
 * [NextRenderersFactory], which ships decoders for all ABIs (incl. arm64) — the
 * modern replacement for the original 32-bit-only native engine.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * Service-side position persistence. The UI's PlayerConnection saves the position on
     * its own lifecycle events, but once the activity is gone the service can play for
     * hours with nobody recording progress — a later cold-start restore would then rewind
     * to wherever the SCREEN was last closed. So the service itself checkpoints the
     * position every few seconds while playing, on every pause, and at shutdown.
     */
    private lateinit var store: PlaybackStateStore
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

        // EXTENSION_RENDERER_MODE_ON: prefer the platform MediaCodec decoders (which do
        // true gapless — they read/trim encoder delay+padding) and fall back to the FFmpeg
        // decoders only for formats the device can't handle natively. PREFER routed every
        // track through FFmpeg, which left an audible gap between songs.
        val renderers = NextRenderersFactory(this)
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

        mediaSession = MediaSession.Builder(this, player).build()
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = mediaSession?.player
        if (player == null || (!player.playWhenReady) || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
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
    }
}

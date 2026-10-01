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

    private var errorSkips = 0

    private val stallHandler = Handler(Looper.getMainLooper())

    private var stallMark = 0L

    private val shufHistory = mutableListOf<Int>()
    private var shufPos = 0
    private val shufPlayed = mutableSetOf<Int>()

    private var lastIds: List<String> = emptyList()

    private var lastEnqueuedCount = 0

    override fun onCreate() {
        super.onCreate()

        val eq = eqSettings
        eqProcessor.update(eq.enabled, eq.gains())

        val renderers = EqRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, TimbraExtractorsFactory()))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exoPlayer = player

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveHandler.removeCallbacks(positionSaver)
                saveHandler.post(positionSaver)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) resetShuffleSession(player) else store.clearShuffleSession()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) errorSkips = 0
                if (playbackState == Player.STATE_ENDED) {
                    advanceFolder(player, forward = true) {
                        player.playbackState == Player.STATE_ENDED
                    }
                }
                watchForEndStall(player, playbackState)
            }

            override fun onPlayerError(error: PlaybackException) = skipStuckTrack(player)

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED ||
                    !player.shuffleModeEnabled
                ) return
                val ids = mediaIds(player)
                if (ids == lastIds) return
                val shift = insertionShift(lastIds, ids)
                if (shift == null || !adoptEnqueueInsertion(player, shift)) resetShuffleSession(player)
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (!player.shuffleModeEnabled) return
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

    private fun skipStuckTrack(player: ExoPlayer) {
        if (player.mediaItemCount == 0) return
        val repeat = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF
        else player.repeatMode
        val next = player.currentTimeline.getNextWindowIndex(
            player.currentMediaItemIndex, repeat, player.shuffleModeEnabled,
        )
        if (next == C.INDEX_UNSET) {
            advanceFolder(player, forward = true) { !player.isPlaying }
            return
        }
        if (errorSkips >= MAX_ERROR_SKIPS.coerceAtMost(player.mediaItemCount)) {
            errorSkips = 0
            player.pause()
            return
        }
        errorSkips++
        val resume = player.playWhenReady
        player.seekTo(next, 0L)
        player.prepare()
        if (resume) player.play()
    }

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
            if (position != stallMark) {
                stallMark = position
                stallHandler.postDelayed(this, END_STALL_TIMEOUT_MS)
                return
            }
            val duration = player.duration
            if (duration == C.TIME_UNSET || position < duration - END_STALL_WINDOW_MS) return
            skipStuckTrack(player)
        }
    }

    private fun mediaIds(player: ExoPlayer): List<String> =
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    private fun enqueuedCount(player: ExoPlayer): Int =
        (0 until player.mediaItemCount).count { player.getMediaItemAt(it).isEnqueued }

    private fun insertionShift(old: List<String>, new: List<String>): IntArray? {
        if (old.isEmpty() || new.size <= old.size) return null
        val map = IntArray(old.size)
        var o = 0
        for (n in new.indices) if (o < old.size && new[n] == old[o]) map[o++] = n
        return if (o == old.size) map else null
    }

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
        shufHistory.clear()
        shufPlayed.clear()
        shufPos = 0
        if (count == 0) return
        if (adoptShuffleRestore(player)) return
        val cur = player.currentMediaItemIndex.coerceIn(0, count - 1)
        shufHistory.add(cur)
        shufPlayed.add(cur)
        applyShuffleOrder(player)
    }

    private fun adoptShuffleRestore(player: ExoPlayer): Boolean {
        val restore = app.session.takeShuffleRestore() ?: return false
        if (restore.fingerprint != queueFingerprint(lastIds)) return false
        val count = player.mediaItemCount
        val history = restore.history.filter { it in 0 until count }.distinct()
        val at = history.indexOf(player.currentMediaItemIndex)
        if (at < 0) return false
        shufHistory.addAll(history)
        shufPos = at
        shufPlayed.addAll(restore.played.filter { it in 0 until count })
        shufPlayed.addAll(history)
        applyShuffleOrder(player)
        return true
    }

    private fun onShuffleAdvance(player: ExoPlayer, cur: Int) {
        if (shufHistory.isEmpty() || player.mediaItemCount == 0) {
            resetShuffleSession(player); return
        }
        val c = cur.coerceIn(0, player.mediaItemCount - 1)
        when {
            c == shufHistory.getOrNull(shufPos) -> return
            c == shufHistory.getOrNull(shufPos - 1) -> { shufPos--; return }
            c == shufHistory.getOrNull(shufPos + 1) -> { shufPos++; return }
            else -> {
                if (shufPos < shufHistory.size - 1) {
                    shufHistory.subList(shufPos + 1, shufHistory.size).clear()
                }
                shufHistory.removeAll { it == c }
                shufHistory.add(c); shufPos = shufHistory.lastIndex; shufPlayed.add(c)
            }
        }
        applyShuffleOrder(player)
    }

    private fun applyShuffleOrder(player: ExoPlayer) {
        val count = player.mediaItemCount
        if (count == 0 || shufHistory.isEmpty()) return
        val queued = (0 until count).filter { it !in shufPlayed && player.getMediaItemAt(it).isEnqueued }
        val queuedSet = queued.toHashSet()
        val unplayed = (0 until count).filter { it !in shufPlayed && it !in queuedSet }
        val chosen = if (unplayed.isEmpty()) emptyList() else listOf(unplayed.random())
        val rest = unplayed.filter { it !in chosen }.shuffled()
        val historySet = shufHistory.toHashSet()
        val discarded = shufPlayed.filter { it !in historySet }.shuffled()
        val order = (shufHistory + queued + chosen + rest + discarded).toIntArray()
        if (order.size != count || order.any { it !in 0 until count } || order.toSet().size != count) {
            resetShuffleSession(player)
            return
        }
        lastIds = mediaIds(player)
        lastEnqueuedCount = enqueuedCount(player)
        store.saveShuffleSession(shufHistory, shufPlayed, queueFingerprint(lastIds))
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

// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.player

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.timbra.app
import com.timbra.data.model.FolderNode
import com.timbra.data.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

data class UiPlayback(
    val hasItem: Boolean = false,
    val mediaId: Long = -1L,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: Long = -1L,
    val filePath: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queueIndex: Int = -1,
    val shuffle: ShuffleMode = ShuffleMode.OFF,
    val repeat: RepeatMode = RepeatMode.OFF,
    val sampleRateHz: Int = 0,
    val bitrateBps: Int = 0,
    val liveTransitionSeq: Int = 0,
) {
    val displayTitle: String get() = title.ifBlank { filePath.substringAfterLast('/') }
}

data class QueueItem(
    val mediaId: Long,
    val albumId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val filePath: String,
    val timelineIndex: Int,
    val enqueued: Boolean,
    val played: Boolean = false,
) {
    val displayTitle: String get() = title.ifBlank { filePath.substringAfterLast('/') }
}

class PlayerConnection(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val store get() = context.app.playbackStore
    private val session get() = context.app.session

    private var appShuffle = ShuffleMode.OFF
    private var appRepeat = RepeatMode.OFF

    private var knownModesRevision = -1

    private data class PreShuffle(val ids: List<Long>, val currentId: Long?, val positionMs: Long)
    private var preShuffle: PreShuffle? = null

    private var enqueueEnd = -1

    private var knownQueueGeneration = 0

    private val _state = MutableStateFlow(UiPlayback())
    val state: StateFlow<UiPlayback> = _state

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            pushState()
            if (controller?.isPlaying == true) handler.postDelayed(this, 500)
        }
    }

    private var lastQueueIdsSig = 0

    private fun queueIdsSignature(p: Player): Int {
        var h = 1
        for (i in 0 until p.mediaItemCount) h = 31 * h + p.getMediaItemAt(i).mediaId.hashCode()
        return h
    }

    private var liveTransitionSeq = 0

    private val queueRefresh = Runnable {
        val c = controller ?: return@Runnable
        val sig = queueIdsSignature(c)
        if (sig == lastQueueIdsSig) return@Runnable
        rebuildQueue(sig)
        saveQueue()
    }

    private fun scheduleQueueRefresh() {
        handler.removeCallbacks(queueRefresh)
        handler.post(queueRefresh)
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                liveTransitionSeq++
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            pushState()
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                adoptExternalModes()
                if (session.queueGeneration != knownQueueGeneration) adoptQueueReplacement()
                scheduleQueueRefresh()
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                markCurrentEnqueuedPlayed()
                if (!player.shuffleModeEnabled && player.currentMediaItemIndex > enqueueEnd) {
                    enqueueEnd = -1
                }
            }
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                )
            ) savePosition()
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.isPlaying) {
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
        }
    }

    private var sampleRateHz = 0
    private var bitrateBps = 0

    private fun readAudioFormat(extras: android.os.Bundle) {
        sampleRateHz = extras.getInt(PlaybackService.EXTRA_SAMPLE_RATE, 0)
        bitrateBps = extras.getInt(PlaybackService.EXTRA_BITRATE, 0)
    }

    fun connect(onReady: () -> Unit = {}) {
        if (controller != null) {
            onReady(); return
        }
        if (controllerFuture != null) return
        buildController(onReady, attempt = 0, epoch = connectEpoch)
    }

    private var connectEpoch = 0

    private fun buildController(onReady: () -> Unit, attempt: Int, epoch: Int) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: android.os.Bundle) {
                    readAudioFormat(extras)
                    pushState()
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            if (future.isCancelled || epoch != connectEpoch) return@addListener
            val built = runCatching { future.get() }.getOrNull()
            if (built == null) {
                controllerFuture = null
                if (attempt < MAX_CONNECT_ATTEMPTS) {
                    handler.postDelayed({
                        if (epoch == connectEpoch && controller == null && controllerFuture == null) {
                            buildController(onReady, attempt + 1, epoch)
                        }
                    }, CONNECT_RETRY_MS)
                } else {
                    onReady()
                }
                return@addListener
            }
            controller = built
            built.addListener(listener)
            readAudioFormat(built.sessionExtras)
            handler.removeCallbacks(ticker)
            handler.post(ticker)
            rebuildQueue()
            knownQueueGeneration = session.queueGeneration
            adoptExternalModes()
            pushState()
            onReady()
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        savePosition()
        handler.removeCallbacks(queueRefresh)
        queueRefresh.run()
        connectEpoch++
        handler.removeCallbacks(ticker)
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
    }

    fun isQueueEmpty(): Boolean = (controller?.mediaItemCount ?: 0) == 0

    fun loadSavedState(): PlaybackStateStore.Saved? = store.load()

    private fun saveQueue() {
        val c = controller ?: return
        val items = _queue.value
        if (items.isEmpty()) return
        store.saveQueue(
            items.map { it.mediaId },
            items.filter { it.enqueued && !it.played }.map { it.timelineIndex },
            c.currentMediaItemIndex,
            c.currentPosition.coerceAtLeast(0),
        )
        saveModes()
    }

    private fun saveModes() {
        store.saveModes(appShuffle, appRepeat)
        knownModesRevision = store.modesRevision()
    }

    private fun savePosition() {
        controller?.let { store.checkpoint(it) }
    }

    private fun applyModes(shuffle: ShuffleMode, repeat: RepeatMode, forceShuffleOrder: Boolean) {
        val c = controller ?: return
        appShuffle = shuffle
        appRepeat = repeat
        knownModesRevision = store.modesRevision()
        c.repeatMode = appRepeat.playerMode
        if (forceShuffleOrder || c.shuffleModeEnabled != appShuffle.playerShuffleEnabled) {
            c.shuffleModeEnabled = appShuffle.playerShuffleEnabled
        }
    }

    fun restore(
        tracks: List<Track>,
        enqueuedFlags: List<Boolean>,
        index: Int,
        positionMs: Long,
        shuffle: ShuffleMode,
        repeat: RepeatMode,
        shufHistory: List<Int>,
        shufPlayed: List<Int>,
    ) {
        val c = controller ?: return
        markQueueReplaced(null)
        if (shuffle != ShuffleMode.OFF && shufHistory.isNotEmpty()) {
            session.offerShuffleRestore(
                PlaybackSession.ShuffleRestore(
                    shufHistory,
                    shufPlayed,
                    queueFingerprint(tracks.map { it.id.toString() }),
                )
            )
        }
        val start = index.coerceIn(0, maxOf(0, tracks.size - 1))
        c.setMediaItems(
            tracks.mapIndexed { i, t -> t.toMediaItem(context, enqueued = enqueuedFlags.getOrElse(i) { false }) },
            start,
            positionMs,
        )
        enqueueEnd = enqueuedFlags.indexOfLast { it }
        applyModes(shuffle, repeat, forceShuffleOrder = true)
        preShuffle = if (appShuffle != ShuffleMode.OFF) {
            PreShuffle(
                tracks.filterIndexed { i, _ -> !enqueuedFlags.getOrElse(i) { false } }.map { it.id },
                tracks.getOrNull(start)?.id,
                positionMs,
            )
        } else null
        c.prepare()
    }

    private fun adoptExternalModes() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (store.modesRevision() == knownModesRevision) return
        val (shuffle, repeat) = store.loadModes()
        applyModes(shuffle, repeat, forceShuffleOrder = false)
        if (appShuffle == ShuffleMode.OFF) preShuffle = null
        else if (preShuffle == null) takeShuffleSnapshot()
        pushState()
    }

    private fun markQueueReplaced(folderContext: String?) {
        session.queueReplaced(folderContext)
        knownQueueGeneration = session.queueGeneration
    }

    private fun adoptQueueReplacement() {
        knownQueueGeneration = session.queueGeneration
        enqueueEnd = -1
        if (appShuffle == ShuffleMode.OFF) preShuffle = null else takeShuffleSnapshot()
    }

    fun clearQueue() {
        val c = controller ?: return
        val cur = c.currentMediaItemIndex
        var i = c.mediaItemCount - 1
        while (i >= 0) {
            if (i == cur || !c.getMediaItemAt(i).isEnqueued) { i--; continue }
            var from = i
            while (from - 1 >= 0 && from - 1 != cur && c.getMediaItemAt(from - 1).isEnqueued) from--
            c.removeMediaItems(from, i + 1)
            i = from - 1
        }
        enqueueEnd = -1
    }

    fun play(
        tracks: List<Track>,
        startIndex: Int,
        play: Boolean = true,
        folderContext: String? = null,
    ): Boolean {
        val c = controller ?: return false
        enqueueEnd = -1
        markQueueReplaced(folderContext)
        val start = startIndex.coerceIn(0, maxOf(0, tracks.size - 1))
        preShuffle = if (appShuffle != ShuffleMode.OFF) {
            PreShuffle(tracks.map { it.id }, tracks.getOrNull(start)?.id, 0)
        } else null
        c.setMediaItems(tracks.map { it.toMediaItem(context) }, start, 0)
        c.prepare()
        if (play) c.play()
        return true
    }

    fun enqueueNext(tracks: List<Track>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        val items = tracks.map { it.toMediaItem(context, enqueued = true) }
        if (c.mediaItemCount == 0) {
            markQueueReplaced(null)
            preShuffle = null
            c.setMediaItems(items, 0, 0)
            enqueueEnd = items.lastIndex
            c.prepare()
            c.play()
            return
        }
        val cur = c.currentMediaItemIndex
        val insertStart = (maxOf(enqueueEnd, cur) + 1).coerceAtMost(c.mediaItemCount)
        c.addMediaItems(insertStart, items)
        enqueueEnd = insertStart + items.size - 1
        when (c.playbackState) {
            Player.STATE_IDLE -> c.prepare()
            Player.STATE_ENDED -> { c.seekTo(insertStart, 0); c.prepare(); c.play() }
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    suspend fun moveFolder(
        context: Context,
        forward: Boolean,
        expectedGen: Int,
        startAt: ((List<Track>) -> Int)? = null,
    ): FolderNode? {
        val c = controller ?: return null
        return if (startAt == null) FolderAdvance.move(context, c, forward, expectedGen)
        else FolderAdvance.move(context, c, forward, expectedGen, startAt)
    }

    fun next() {
        val c = controller ?: return
        if (c.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) c.seekToNext()
        else if (appRepeat == RepeatMode.ADVANCE) requestFolderAdvance(forward = true)
    }

    fun previous() {
        val c = controller ?: return
        if (c.shuffleModeEnabled && !c.hasPreviousMediaItem()) {
            if (c.currentPosition > c.maxSeekToPreviousPosition) c.seekTo(0)
            return
        }
        if (c.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) c.seekToPrevious()
        else if (appRepeat == RepeatMode.ADVANCE) requestFolderAdvance(forward = false)
    }

    private fun requestFolderAdvance(forward: Boolean) {
        val c = controller ?: return
        c.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_ADVANCE_FOLDER, android.os.Bundle.EMPTY),
            android.os.Bundle().apply {
                putBoolean(PlaybackService.EXTRA_ADVANCE_FORWARD, forward)
            },
        )
    }

    fun hasNext(): Boolean = controller?.hasNextMediaItem() == true

    fun nextQueueIndex(): Int = controller?.nextMediaItemIndex ?: -1
    fun prevQueueIndex(): Int = controller?.previousMediaItemIndex ?: -1

    fun previousSong() {
        val c = controller ?: return
        if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem()
    }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }

    fun seekToQueueItem(index: Int, expectedMediaId: Long? = null, play: Boolean = true) {
        val c = controller ?: return
        val target = if (expectedMediaId == null) index else resolveIndex(c, index, expectedMediaId)
        if (target != null && target in 0 until c.mediaItemCount) {
            c.seekTo(target, 0)
            if (play) c.play()
        }
    }

    private fun resolveIndex(c: MediaController, index: Int, expectedMediaId: Long): Int? {
        if (index in 0 until c.mediaItemCount && c.getMediaItemAt(index).trackId == expectedMediaId) {
            return index
        }
        return (0 until c.mediaItemCount).firstOrNull {
            c.getMediaItemAt(it).trackId == expectedMediaId
        }
    }

    fun removeQueueItem(index: Int, expectedMediaId: Long) {
        val c = controller ?: return
        val target = resolveIndex(c, index, expectedMediaId) ?: return
        c.removeMediaItem(target)
        if (target <= enqueueEnd) enqueueEnd--
    }

    fun reorderQueue(orderedMediaIds: List<Long>) {
        val c = controller ?: return
        if (orderedMediaIds.isEmpty()) return
        val cur = c.currentMediaItemIndex
        fun pendingSlots() = ((cur + 1) until c.mediaItemCount).filter { c.getMediaItemAt(it).isEnqueued }
        val pendingIds = pendingSlots().map { c.getMediaItemAt(it).trackId }.toHashSet()
        val wanted = orderedMediaIds.filter { it in pendingIds }
        var searchFrom = cur + 1
        for (id in wanted) {
            val target = (searchFrom until c.mediaItemCount)
                .firstOrNull { c.getMediaItemAt(it).isEnqueued } ?: return
            val from = (target until c.mediaItemCount).firstOrNull {
                c.getMediaItemAt(it).isEnqueued && c.getMediaItemAt(it).trackId == id
            } ?: return
            if (from != target) c.moveMediaItem(from, target)
            searchFrom = target + 1
        }
    }

    fun setShuffle(mode: ShuffleMode) {
        val c = controller ?: return
        if (appShuffle == ShuffleMode.OFF && mode != ShuffleMode.OFF) takeShuffleSnapshot()
        appShuffle = mode
        c.shuffleModeEnabled = mode.playerShuffleEnabled
        saveModes()
        pushState()
    }

    private fun takeShuffleSnapshot() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { preShuffle = null; return }
        val plain = (0 until c.mediaItemCount).filter { !c.getMediaItemAt(it).isEnqueued }
        val ids = plain.mapNotNull { c.getMediaItemAt(it).trackId }
        preShuffle = PreShuffle(ids, c.currentMediaItem?.trackId, c.currentPosition.coerceAtLeast(0))
    }

    private fun markCurrentEnqueuedPlayed() {
        handler.post {
            val c = controller ?: return@post
            val i = c.currentMediaItemIndex
            if (i < 0 || i >= c.mediaItemCount) return@post
            val item = c.getMediaItemAt(i)
            if (!item.isEnqueued || item.isEnqueuedPlayed) return@post
            c.replaceMediaItem(i, item.markEnqueuedPlayed())
            rebuildQueue()
            saveQueue()
        }
    }

    private fun liftEnqueued(c: MediaController): List<MediaItem> =
        (0 until c.mediaItemCount)
            .filter { it != c.currentMediaItemIndex }
            .map { c.getMediaItemAt(it) }
            .filter { it.isEnqueued && !it.isEnqueuedPlayed }

    private fun rebuildAroundCurrent(
        c: MediaController,
        tracks: List<Track>,
        pos: Int,
        carried: List<MediaItem>,
    ) {
        val cur = c.currentMediaItemIndex
        if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
        if (cur > 0) c.removeMediaItems(0, cur)
        val before = tracks.subList(0, pos).map { it.toMediaItem(context) }
        val after = tracks.subList(pos + 1, tracks.size).map { it.toMediaItem(context) }
        if (before.isNotEmpty()) c.addMediaItems(0, before)
        if (after.isNotEmpty()) c.addMediaItems(c.mediaItemCount, after)
        spliceEnqueued(c, carried, pos + 1)
    }

    private fun spliceEnqueued(c: MediaController, items: List<MediaItem>, at: Int) {
        if (items.isEmpty()) { enqueueEnd = -1; return }
        val start = at.coerceIn(0, c.mediaItemCount)
        c.addMediaItems(start, items)
        enqueueEnd = start + items.size - 1
    }

    fun preShuffleQueueIds(): List<Long> = preShuffle?.ids ?: emptyList()

    fun disableShuffleRestoring(tracks: List<Track>) {
        val c = controller ?: return
        val snap = preShuffle
        preShuffle = null
        markQueueReplaced(null)
        appShuffle = ShuffleMode.OFF
        c.shuffleModeEnabled = false
        if (tracks.isEmpty() || snap == null) { saveModes(); pushState(); return }
        val carried = liftEnqueued(c)
        val curId = c.currentMediaItem?.trackId
        val pos = tracks.indexOfFirst { it.id == curId }
        if (pos >= 0) {
            rebuildAroundCurrent(c, tracks, pos, carried)
        } else {
            val at = tracks.indexOfFirst { it.id == snap.currentId }.coerceAtLeast(0)
            c.setMediaItems(tracks.map { it.toMediaItem(context) }, at, snap.positionMs)
            c.prepare()
            spliceEnqueued(c, carried, at + 1)
        }
        saveModes()
        pushState()
    }

    fun playAllShuffled(tracks: List<Track>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        appShuffle = ShuffleMode.ALL
        markQueueReplaced(null)
        val carried = liftEnqueued(c)
        enqueueEnd = -1
        val curId = c.currentMediaItem?.trackId
        val idx = if (curId != null) tracks.indexOfFirst { it.id == curId } else -1
        if (idx >= 0) {
            rebuildAroundCurrent(c, tracks, idx, carried)
        } else {
            val at = Random.nextInt(tracks.size)
            c.setMediaItems(tracks.map { it.toMediaItem(context) }, at, 0)
            c.prepare()
            c.play()
            spliceEnqueued(c, carried, at + 1)
        }
        c.shuffleModeEnabled = true
        saveModes()
        pushState()
    }

    fun setRepeat(mode: RepeatMode) {
        val c = controller ?: return
        appRepeat = mode
        c.repeatMode = mode.playerMode
        saveModes()
        pushState()
    }

    fun applyEq(enabled: Boolean, gainsDb: IntArray) {
        val c = controller ?: return
        val args = android.os.Bundle().apply {
            putBoolean(PlaybackService.EXTRA_EQ_ENABLED, enabled)
            putIntArray(PlaybackService.EXTRA_EQ_GAINS, gainsDb.copyOf())
        }
        c.sendCustomCommand(SessionCommand(PlaybackService.CMD_APPLY_EQ, android.os.Bundle.EMPTY), args)
    }

    private fun rebuildQueue(precomputedSig: Int? = null) {
        val c = controller ?: run { _queue.value = emptyList(); return }
        lastQueueIdsSig = precomputedSig ?: queueIdsSignature(c)
        val items = ArrayList<QueueItem>(c.mediaItemCount)
        for (i in 0 until c.mediaItemCount) {
            val mi = c.getMediaItemAt(i)
            val md = mi.mediaMetadata
            items.add(
                QueueItem(
                    mediaId = mi.trackId ?: -1L,
                    albumId = mi.albumIdExtra,
                    title = md.title?.toString() ?: "",
                    artist = md.artist?.toString() ?: "",
                    album = md.albumTitle?.toString() ?: "",
                    filePath = mi.pathExtra,
                    timelineIndex = i,
                    enqueued = mi.isEnqueued,
                    played = mi.isEnqueuedPlayed,
                )
            )
        }
        _queue.value = items
    }

    private fun pushState() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null) {
            _state.value = UiPlayback(
                liveTransitionSeq = liveTransitionSeq,
                shuffle = appShuffle,
                repeat = appRepeat,
            )
            return
        }
        val md = c.mediaMetadata
        _state.value = UiPlayback(
            hasItem = true,
            mediaId = item.trackId ?: -1L,
            title = md.title?.toString() ?: "",
            artist = md.artist?.toString() ?: "",
            album = md.albumTitle?.toString() ?: "",
            albumId = item.albumIdExtra,
            filePath = item.pathExtra,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.coerceAtLeast(0),
            queueIndex = c.currentMediaItemIndex,
            shuffle = appShuffle,
            repeat = appRepeat,
            sampleRateHz = sampleRateHz,
            bitrateBps = bitrateBps,
            liveTransitionSeq = liveTransitionSeq,
        )
    }

    private companion object {
        const val MAX_CONNECT_ATTEMPTS = 3
        const val CONNECT_RETRY_MS = 400L
    }
}

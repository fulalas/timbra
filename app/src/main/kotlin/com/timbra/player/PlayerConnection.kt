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

/** Snapshot of playback state observed by the UI. */
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
    /** Decoded audio format (from the service via session extras); 0/-1 when unknown. */
    val sampleRateHz: Int = 0,
    val bitrateBps: Int = 0,
    /**
     * Monotonic count of genuine LIVE song transitions (ExoPlayer AUTO end / SEEK next-prev-tap)
     * observed while connected. The player screen animates its card-flip only when this advances,
     * so a song that changed while backgrounded — surfaced by a reconnect state-sync that leaves
     * the count untouched — snaps into place instead of spuriously flipping.
     */
    val liveTransitionSeq: Int = 0,
) {
    /** Title, falling back to the file name when tags are missing — the ONE fallback both the
     *  mini-player and the full player use (they had hand-written, already-divergent copies, so
     *  a blank-titled song read "Nothing playing" on one and "Timbra" on the other). */
    val displayTitle: String get() = title.ifBlank { filePath.substringAfterLast('/') }
}

/** One entry in the play timeline, used to page album art and show the Queue list. */
data class QueueItem(
    val mediaId: Long,
    val albumId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val filePath: String,
    val timelineIndex: Int,
    /** True only for songs the user manually enqueued (play-next), vs. the playing list. */
    val enqueued: Boolean,
    /** True once an [enqueued] song has been played, i.e. the block is consumed up to here.
     *  Carried on the item itself rather than inferred from the timeline index, which says
     *  nothing about progress under shuffle (see [KEY_ENQ_PLAYED]). */
    val played: Boolean = false,
) {
    /** Title, falling back to the file name when tags are missing (mirrors [Track.displayTitle]). */
    val displayTitle: String get() = title.ifBlank { filePath.substringAfterLast('/') }
}

/**
 * UI-side wrapper around a [MediaController] bound to [PlaybackService]. Exposes an
 * observable [state] + [queue] and transport controls. Shuffle/repeat are tracked as
 * app-level modes (broader than ExoPlayer's flags).
 */
class PlayerConnection(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val store get() = context.app.playbackStore
    private val session get() = context.app.session

    private var appShuffle = ShuffleMode.OFF
    private var appRepeat = RepeatMode.OFF

    /**
     * The [PlaybackStateStore.modesRevision] this connection last wrote or read. Anything higher
     * means SOMEONE ELSE changed the modes — the service narrows Shuffle-All to Shuffle-Songs
     * when it advances a folder — and the in-memory copy must be refreshed instead of being
     * written back over theirs (which left the shuffle icon claiming a whole-library pool over a
     * one-folder queue, and made the next tap cycle the wrong way).
     */
    private var knownModesRevision = -1

    /**
     * The queue as it was right before shuffle was turned on, so turning shuffle back off can
     * restore it (Shuffle-All replaces the whole timeline). Track ids + where playback was.
     * In-memory only: killing the app mid-shuffle keeps the shuffled queue.
     */
    /** [currentId] rather than a position: the consumer resolves [ids] against the library with
     *  `mapNotNull`, so a track deleted or rescanned away since shuffle was enabled shortens the
     *  list — and a stored index then pointed at the wrong song, with coerceIn hiding it. */
    private data class PreShuffle(val ids: List<Long>, val currentId: Long?, val positionMs: Long)
    private var preShuffle: PreShuffle? = null

    /** Index of the last "play next" enqueued item, so further enqueues append FIFO. */
    private var enqueueEnd = -1

    /**
     * The [PlaybackSession.queueGeneration] this connection has already accounted for. A higher
     * one means the queue was replaced by someone else — the service, rolling into the next
     * folder — and the per-queue bookkeeping ([adoptQueueReplacement]) still has to happen, or it
     * would describe a queue the user has left.
     */
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

    /** Media-id signature of the last rebuilt queue: the shuffle engine rewrites the shuffle
     *  order on every transition, which also fires EVENT_TIMELINE_CHANGED — but the ITEMS
     *  rarely change, and rebuilding + persisting thousands of ids per song change is
     *  needless main-thread churn. */
    private var lastQueueIdsSig = 0

    private fun queueIdsSignature(p: Player): Int {
        var h = 1
        for (i in 0 until p.mediaItemCount) h = 31 * h + p.getMediaItemAt(i).mediaId.hashCode()
        return h
    }

    /** Bumped on each genuine live song transition (see [UiPlayback.liveTransitionSeq]). */
    private var liveTransitionSeq = 0

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Only actual playback movement observed live counts as a flip-worthy transition:
            // AUTO (a song ended into the next) and SEEK (Next/Prev/tap). REPEAT (repeat-one) and
            // PLAYLIST_CHANGED (queue rebuild, or the reconnect state-sync after backgrounding)
            // must NOT flip — the latter is exactly the spurious foreground animation we avoid.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                liveTransitionSeq++
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            pushState()
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                // A queue replacement may have come from the service (a folder advance), which
                // also narrows the shuffle pool — adopt that before publishing anything else.
                adoptExternalModes()
                if (session.queueGeneration != knownQueueGeneration) adoptQueueReplacement()
                val sig = queueIdsSignature(player)
                if (sig != lastQueueIdsSig) {
                    rebuildQueue(sig)
                    saveQueue()
                }
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                // The play-next block is consumed as it plays: mark the song that just became
                // current so a later queue rebuild carries only what is still PENDING.
                markCurrentEnqueuedPlayed()
                // Retire the block once playback has moved past it, so later enqueues start a
                // new one instead of chaining onto a spent block. Only the sequential case can
                // read that off the timeline index; under shuffle the per-item played mark is
                // what keeps the block honest.
                if (!player.shuffleModeEnabled && player.currentMediaItemIndex > enqueueEnd) {
                    enqueueEnd = -1
                }
            }
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                )
            ) savePosition()
            // The ticker self-perpetuates while playing and is kicked once on connect, so it
            // only ever needs (re)starting when playback actually resumes — kicking it on every
            // event batch would reset its cadence and push a duplicate state emission each time.
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.isPlaying) {
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
        }
    }

    /** Decoded audio format published by the service (see [PlaybackService] extras). */
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
        // A build is already in flight with its own onReady queued; a second one would leak a
        // controller (the field is overwritten, so release() could never reach the first).
        if (controllerFuture != null) return
        buildController(onReady, attempt = 0, epoch = connectEpoch)
    }

    /**
     * Identifies the current connect cycle, so a pending retry from a previous one is abandoned.
     * Without it, a build that failed just before onStop would retry AFTER [release] and bind a
     * controller nobody owns — keeping the service alive with a 500ms state ticker running.
     */
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
            // release() may have cancelled the future before it resolved; get() would throw.
            if (future.isCancelled || epoch != connectEpoch) return@addListener
            val built = runCatching { future.get() }.getOrNull()
            if (built == null) {
                // The session couldn't be bound (service start restriction, a crash loop). Retry
                // shortly instead of going silent until the next onStop/onStart — a swallowed
                // failure left the app with no queue restore, no auto-open and dead transport
                // controls, with nothing to recover it and a stale future still held.
                controllerFuture = null
                if (attempt < MAX_CONNECT_ATTEMPTS) {
                    handler.postDelayed({
                        if (epoch == connectEpoch && controller == null && controllerFuture == null) {
                            buildController(onReady, attempt + 1, epoch)
                        }
                    }, CONNECT_RETRY_MS)
                } else {
                    // Give the caller its turn regardless; every transport call is a no-op
                    // without a controller, so it degrades consistently rather than hanging.
                    onReady()
                }
                return@addListener
            }
            controller = built
            built.addListener(listener)
            readAudioFormat(built.sessionExtras)
            // A fresh controller doesn't replay events, so if a song is already playing the
            // listener won't fire to start the ticker — kick it here (it self-stops when paused).
            handler.removeCallbacks(ticker)
            handler.post(ticker)
            rebuildQueue()
            // Whatever queue is already live is this connection's starting point, not a
            // replacement it has to react to (a config change hands us a session that has been
            // through several) — adoptExternalModes below seeds the shuffle snapshot for it.
            knownQueueGeneration = session.queueGeneration
            // Re-adopt persisted modes onto a surviving queue BEFORE the first state push, so the
            // repeat/shuffle icons don't flash their defaults for a frame.
            adoptExternalModes()
            pushState()
            onReady()
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        savePosition()
        // Abandons any pending connect retry (see [connectEpoch]).
        connectEpoch++
        handler.removeCallbacks(ticker)
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
    }

    // --- Persist / restore ---

    fun isQueueEmpty(): Boolean = (controller?.mediaItemCount ?: 0) == 0

    fun loadSavedState(): PlaybackStateStore.Saved? = store.load()

    /** Persist the queue snapshot [rebuildQueue] just produced (no extra timeline walk). */
    private fun saveQueue() {
        val c = controller ?: return
        val items = _queue.value
        if (items.isEmpty()) return
        // Only the PENDING part of the play-next block is persisted as enqueued: a consumed
        // entry keeps its place in the queue, but restoring it as enqueued would resurrect it
        // as "coming up next" and re-derive the block's end from it.
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

    /**
     * Adopt persisted [shuffle]/[repeat] play modes into the in-memory fields and
     * mirror them onto the player. [forceShuffleOrder] = true (cold [restore], fresh queue)
     * always writes shuffleModeEnabled so the new timeline gets a shuffle order; false (live
     * reconnect) writes it only when it actually drifted, so an already-shuffled session isn't
     * needlessly reshuffled (see PlaybackService.onShuffleModeEnabledChanged / onTimelineChanged).
     */
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

    /**
     * Restore a saved queue paused at [positionMs]; the user presses play to resume.
     * [enqueuedFlags] is aligned with [tracks] and marks which items were in the queue.
     */
    fun restore(
        tracks: List<Track>,
        enqueuedFlags: List<Boolean>,
        index: Int,
        positionMs: Long,
        shuffle: ShuffleMode,
        repeat: RepeatMode,
    ) {
        val c = controller ?: return
        markQueueReplaced(null)
        val start = index.coerceIn(0, maxOf(0, tracks.size - 1))
        c.setMediaItems(
            tracks.mapIndexed { i, t -> t.toMediaItem(context, enqueued = enqueuedFlags.getOrElse(i) { false }) },
            start,
            positionMs,
        )
        // Keep FIFO append working after restore: further enqueues go after the last one.
        enqueueEnd = enqueuedFlags.indexOfLast { it }
        applyModes(shuffle, repeat, forceShuffleOrder = true)
        // If shuffle is being restored as ON, anchor the snapshot on the restored queue so a
        // later shuffle-off keeps this queue (the original pre-shuffle one wasn't persisted).
        preShuffle = if (appShuffle != ShuffleMode.OFF) {
            // Excluding the enqueued items, exactly as [takeShuffleSnapshot] does and for the same
            // reason: the play-next block travels across mode changes on its own, so snapshotting
            // it here too made a later shuffle-off restore those songs TWICE — once as plain list
            // entries and once as the carried block.
            PreShuffle(
                tracks.filterIndexed { i, _ -> !enqueuedFlags.getOrElse(i) { false } }.map { it.id },
                tracks.getOrNull(start)?.id,
                positionMs,
            )
        } else null
        c.prepare()
    }

    /**
     * Adopt play modes written by someone else since this connection last touched them.
     *
     * Covers two cases with one mechanism: a fresh connection to a queue the [PlaybackService]
     * outlived (a config change, or the system reclaiming the Activity but keeping the process),
     * where the modes would otherwise default to OFF and Advance-List would silently stop
     * advancing — its player mode is REPEAT_MODE_OFF, indistinguishable from true OFF; and a LIVE
     * connection whose modes the service changed underneath it (a detached folder advance narrows
     * Shuffle-All). Gated on [PlaybackStateStore.modesRevision], so a connection holding the
     * authoritative modes never re-reads disk and clobbers its own live state.
     */
    private fun adoptExternalModes() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (store.modesRevision() == knownModesRevision) return
        val (shuffle, repeat) = store.loadModes()
        applyModes(shuffle, repeat, forceShuffleOrder = false)
        // Seed the shuffle-off snapshot from the live queue (the original pre-shuffle order
        // wasn't persisted) so a later shuffle-off can rebuild it; never clobber a live one.
        if (appShuffle == ShuffleMode.OFF) preShuffle = null
        else if (preShuffle == null) takeShuffleSnapshot()
        pushState()
    }

    /**
     * Record a queue replacement made by THIS connection, so [adoptQueueReplacement] doesn't
     * then redo the generic bookkeeping over the caller's more specific version.
     */
    private fun markQueueReplaced(folderContext: String?) {
        session.queueReplaced(folderContext)
        knownQueueGeneration = session.queueGeneration
    }

    /**
     * The per-queue bookkeeping that a replacement made ELSEWHERE (the service's folder advance)
     * would otherwise skip, because it drives the player directly rather than through [play].
     *
     * Both halves matter. The play-next insertion cursor is an index into the queue that is gone,
     * so a later "play next" would splice at a meaningless slot (or past the end); and the
     * pre-shuffle snapshot has to follow the NEW queue, or cycling shuffle back to OFF would
     * "restore" a folder the user left two advances ago — teleporting playback backwards.
     */
    private fun adoptQueueReplacement() {
        knownQueueGeneration = session.queueGeneration
        enqueueEnd = -1
        if (appShuffle == ShuffleMode.OFF) preShuffle = null else takeShuffleSnapshot()
    }

    /** Remove every manually-enqueued item from the timeline (Clear Queue). */
    fun clearQueue() {
        val c = controller ?: return
        val cur = c.currentMediaItemIndex
        // Never remove the song being listened to, even when it is itself a play-next entry:
        // dropping the current item makes the player continue with whatever follows, so this
        // used to cut playback off mid-song. Removed in contiguous RUNS (one session
        // transaction each) rather than one item at a time.
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

    // --- Transport ---

    /**
     * Replace the queue with [tracks] starting at [startIndex]. [play] = true starts playback
     * (tapping a song); false leaves the play/pause state untouched — so a folder advance keeps
     * playing if it was playing (playWhenReady survives setMediaItems) and stays paused if paused.
     * [folderContext] anchors folder navigation on the folder this queue came from. Returns false
     * when there is no controller to command (released mid-flight, e.g. the app was
     * backgrounded) — the queue is then untouched.
     */
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
        // The queue is being replaced while shuffle may be on: the pre-shuffle snapshot must
        // follow the NEW queue (turning shuffle off should keep the user here, sequential),
        // not restore whatever list shuffle happened to be enabled in long ago.
        preShuffle = if (appShuffle != ShuffleMode.OFF) {
            PreShuffle(tracks.map { it.id }, tracks.getOrNull(start)?.id, 0)
        } else null
        c.setMediaItems(tracks.map { it.toMediaItem(context) }, start, 0)
        c.prepare()
        if (play) c.play()
        return true
    }

    /** Insert [tracks] to play right after the current one (FIFO across repeated enqueues). */
    fun enqueueNext(tracks: List<Track>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        val items = tracks.map { it.toMediaItem(context, enqueued = true) }
        if (c.mediaItemCount == 0) {
            // Nothing queued yet: these BECOME the queue — but still FLAGGED, so the Queue
            // screen lists them, Clear Queue clears them and they persist as the play-next
            // block. Delegating to play() here built them unflagged, making the enqueue
            // invisible to every one of those.
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
        // ONE session transaction. Inserting per item cost a binder round-trip and an O(n)
        // timeline masking copy each, i.e. O(n²) on the main thread — enqueueing a whole folder
        // subtree (thousands of tracks) froze the UI.
        c.addMediaItems(insertStart, items)
        enqueueEnd = insertStart + items.size - 1
        when (c.playbackState) {
            Player.STATE_IDLE -> c.prepare()
            // Queue had finished: start the just-enqueued track so it actually plays.
            Player.STATE_ENDED -> { c.seekTo(insertStart, 0); c.prepare(); c.play() }
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /**
     * Step to a neighbouring song-folder through the shared [FolderAdvance], on this connection's
     * controller. Only the deck's own gestures come through here — they need to know whether
     * anything actually moved; every other trigger is handled service-side by the same code.
     * Returns the folder moved to, or null on a no-op.
     */
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

    /**
     * Next. Advance-List's "past the last song jumps to the next folder" is NOT implemented here:
     * the service owns it, so this button, the notification, the lock screen and a Bluetooth
     * remote all behave identically — the special case used to be UI-only, and every system
     * transport control silently did nothing at a folder's end.
     *
     * Normally the seek reaches the service's [PlaybackService.AdvancePlayer], which recognises
     * the edge. When media3 has masked the command out (it derives availability from the plain
     * player, where Advance-List is just REPEAT_MODE_OFF) the seek would be dropped locally
     * instead, so ask for the step explicitly. Exactly one of the two routes ever fires.
     */
    fun next() {
        val c = controller ?: return
        if (c.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) c.seekToNext()
        else if (appRepeat == RepeatMode.ADVANCE) requestFolderAdvance(forward = true)
    }

    fun previous() {
        val c = controller ?: return
        // Shuffle: Previous only walks back through songs actually played this session. At the
        // start of that history there is nothing before — restart a song in progress, otherwise
        // do nothing (never wrap, never jump folders; seekToPrevious would restart-loop).
        if (c.shuffleModeEnabled && !c.hasPreviousMediaItem()) {
            if (c.currentPosition > c.maxSeekToPreviousPosition) c.seekTo(0)
            return
        }
        // Advance-List's jump back to the previous folder is likewise the service's; it only
        // triggers at the very start of the first song, and mid-song this restarts as usual.
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

    // --- Shuffle-aware navigation state (used by the album-art deck) ---

    fun hasNext(): Boolean = controller?.hasNextMediaItem() == true

    /** Timeline index of the song that Next / Previous would play (shuffle-order aware);
     *  -1 when there is none. */
    fun nextQueueIndex(): Int = controller?.nextMediaItemIndex ?: -1
    fun prevQueueIndex(): Int = controller?.previousMediaItemIndex ?: -1

    /** Strict back-navigation for the deck swipe: always moves to the previous song in the
     *  play order (never restarts the current one), preserving play/pause. No-op at the start. */
    fun previousSong() {
        val c = controller ?: return
        if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem()
    }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }

    /**
     * Jump to a specific queue position. [play] = true starts playback (tapping a queue row);
     * false only seeks, preserving the current play/pause state (swiping the album-art pager).
     * [expectedMediaId] guards a stale [index] the same way [removeQueueItem] does — the Queue
     * screen's rows carry the index they were bound with, which lags a reorder.
     */
    fun seekToQueueItem(index: Int, expectedMediaId: Long? = null, play: Boolean = true) {
        val c = controller ?: return
        val target = if (expectedMediaId == null) index else resolveIndex(c, index, expectedMediaId)
        if (target != null && target in 0 until c.mediaItemCount) {
            c.seekTo(target, 0)
            if (play) c.play()
        }
    }

    /** [index] when it still holds [expectedMediaId], else the first slot that does; null when
     *  the item is gone from the timeline entirely. */
    private fun resolveIndex(c: MediaController, index: Int, expectedMediaId: Long): Int? {
        if (index in 0 until c.mediaItemCount && c.getMediaItemAt(index).trackId == expectedMediaId) {
            return index
        }
        return (0 until c.mediaItemCount).firstOrNull {
            c.getMediaItemAt(it).trackId == expectedMediaId
        }
    }

    /**
     * Remove a single item from the timeline (Queue screen "Remove"). [expectedMediaId]
     * guards against a stale [index]: the displayed timelineIndex lags behind after an
     * earlier removal, and removing blindly by index would delete the wrong song.
     */
    fun removeQueueItem(index: Int, expectedMediaId: Long) {
        val c = controller ?: return
        val target = resolveIndex(c, index, expectedMediaId) ?: return
        c.removeMediaItem(target)
        if (target <= enqueueEnd) enqueueEnd--
    }

    /**
     * Reorder the pending play-next items to match [orderedMediaIds] (their desired order).
     *
     * Only the enqueued slots AFTER the current one are touched. The block is not necessarily
     * contiguous — it is retired while older entries keep their flag — and assuming it was let a
     * move cross the currently-playing song, which stranded the whole reordered block BEHIND it
     * where it never played. Each item is brought to the next enqueued slot recomputed from the
     * live timeline, so every move runs downward within the pending region and can never cross
     * the current index.
     */
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

    // --- Modes ---

    fun setShuffle(mode: ShuffleMode) {
        val c = controller ?: return
        // Snapshot the queue the first time shuffle is turned on from OFF, so it can be
        // restored when shuffle later returns to OFF (see [disableShuffleRestoring]).
        if (appShuffle == ShuffleMode.OFF && mode != ShuffleMode.OFF) takeShuffleSnapshot()
        appShuffle = mode
        // Toggles ExoPlayer's shuffle over the current queue; enabling regenerates a fresh
        // random order (see PlaybackService).
        c.shuffleModeEnabled = mode.playerShuffleEnabled
        saveModes()
        pushState()
    }

    private fun takeShuffleSnapshot() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { preShuffle = null; return }
        // The playing LIST only: the enqueued block travels across mode changes on its own (see
        // [spliceEnqueued]), so snapshotting it here too would restore those songs twice — once
        // as plain list entries and once as the carried play-next block.
        val plain = (0 until c.mediaItemCount).filter { !c.getMediaItemAt(it).isEnqueued }
        val ids = plain.mapNotNull { c.getMediaItemAt(it).trackId }
        preShuffle = PreShuffle(ids, c.currentMediaItem?.trackId, c.currentPosition.coerceAtLeast(0))
    }

    /**
     * Mark the item that just became current as a consumed play-next entry. Metadata-only (same
     * mediaId and Uri), so media3 applies it in place and playback is not interrupted.
     *
     * Posted rather than run inline: this fires from a [Player.Listener] callback, and mutating
     * the timeline from inside one is best avoided.
     */
    private fun markCurrentEnqueuedPlayed() {
        handler.post {
            val c = controller ?: return@post
            val i = c.currentMediaItemIndex
            if (i < 0 || i >= c.mediaItemCount) return@post
            val item = c.getMediaItemAt(i)
            if (!item.isEnqueued || item.isEnqueuedPlayed) return@post
            c.replaceMediaItem(i, item.markEnqueuedPlayed())
            // The media ids didn't change, so the queue signature won't notice this — refresh
            // explicitly so the Queue screen re-dims and the persisted pending set shrinks.
            rebuildQueue()
            saveQueue()
        }
    }

    /**
     * Lift the PENDING play-next block off the live timeline so a queue rebuild can carry it over
     * (the rebuild sources have no enqueued flags — a verbatim rebuild would drop the block).
     * The playing song is excluded: the seamless rebuild keeps it in place. Songs the block has
     * already played are excluded too, or every mode change would splice them back in right
     * after the current song and replay them.
     */
    private fun liftEnqueued(c: MediaController): List<MediaItem> =
        (0 until c.mediaItemCount)
            .filter { it != c.currentMediaItemIndex }
            .map { c.getMediaItemAt(it) }
            .filter { it.isEnqueued && !it.isEnqueuedPlayed }

    /**
     * Rebuild the timeline as [tracks] around the playing song (at [pos] in [tracks]) WITHOUT
     * touching the currently-playing item, so audio doesn't stall: strip the other items around
     * it, re-add the rest before and after, then splice the [carried] play-next block back in
     * right behind it. (setMediaItems would re-prepare the current item and cause a ~0.5s gap.)
     */
    private fun rebuildAroundCurrent(
        c: MediaController,
        tracks: List<Track>,
        pos: Int,
        carried: List<MediaItem>,
    ) {
        val cur = c.currentMediaItemIndex
        if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
        if (cur > 0) c.removeMediaItems(0, cur)
        // Only the current item remains (index 0). Wrap the rest of [tracks] around it.
        val before = tracks.subList(0, pos).map { it.toMediaItem(context) }
        val after = tracks.subList(pos + 1, tracks.size).map { it.toMediaItem(context) }
        if (before.isNotEmpty()) c.addMediaItems(0, before)
        if (after.isNotEmpty()) c.addMediaItems(c.mediaItemCount, after)
        // The current song sits at `pos` again now that `before` is back in front of it.
        spliceEnqueued(c, carried, pos + 1)
    }

    /**
     * Re-insert a carried-over play-next block at [at], keeping it the enqueued block. The items
     * are the original [MediaItem]s, so they keep their metadata and enqueued flag.
     */
    private fun spliceEnqueued(c: MediaController, items: List<MediaItem>, at: Int) {
        if (items.isEmpty()) { enqueueEnd = -1; return }
        val start = at.coerceIn(0, c.mediaItemCount)
        c.addMediaItems(start, items)
        enqueueEnd = start + items.size - 1
    }

    /** Track ids captured when shuffle was enabled, so the UI can re-resolve them to [Track]s. */
    fun preShuffleQueueIds(): List<Long> = preShuffle?.ids ?: emptyList()

    /**
     * Turn shuffle OFF and restore the pre-shuffle queue from [tracks] (the UI resolves the
     * ids from [preShuffleQueueIds] against the library). If the current song is still in that
     * queue, rebuild around it so audio isn't interrupted; otherwise fall back to the snapshot's
     * saved spot.
     */
    fun disableShuffleRestoring(tracks: List<Track>) {
        val c = controller ?: return
        val snap = preShuffle
        preShuffle = null
        markQueueReplaced(null)
        appShuffle = ShuffleMode.OFF
        c.shuffleModeEnabled = false
        if (tracks.isEmpty() || snap == null) { saveModes(); pushState(); return }
        // The snapshot was taken when shuffle was turned ON, so it predates anything the user
        // queued with "play next" during the shuffle session (see [liftEnqueued]).
        val carried = liftEnqueued(c)
        val curId = c.currentMediaItem?.trackId
        val pos = tracks.indexOfFirst { it.id == curId }
        if (pos >= 0) {
            // Seamless: keep the current song, rebuild the original queue around it.
            rebuildAroundCurrent(c, tracks, pos, carried)
        } else {
            // Current song isn't in the original queue (played into shuffle) — restore as saved.
            val at = tracks.indexOfFirst { it.id == snap.currentId }.coerceAtLeast(0)
            c.setMediaItems(tracks.map { it.toMediaItem(context) }, at, snap.positionMs)
            c.prepare()
            spliceEnqueued(c, carried, at + 1)
        }
        saveModes()
        pushState()
    }

    /**
     * Shuffle-All (the third shuffle mode): make every song the pool. The currently playing
     * song is NOT interrupted — it keeps playing while the queue becomes the whole library; if
     * nothing is playing, start from a random track. Shuffle is enabled last, once the timeline
     * is final, so the fresh random order (see PlaybackService) covers all songs. The pre-shuffle
     * snapshot (taken when shuffle first turned on) is left intact so OFF can restore it.
     */
    fun playAllShuffled(tracks: List<Track>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        appShuffle = ShuffleMode.ALL
        markQueueReplaced(null)
        // Widening the pool to the whole library must not throw away what the user explicitly
        // queued with "play next" (see [liftEnqueued]). Cycling the shuffle button from
        // Shuffle-Songs to OFF passes THROUGH here, so losing the block here loses it for good —
        // disableShuffleRestoring would then have nothing left to carry over.
        val carried = liftEnqueued(c)
        enqueueEnd = -1
        val curId = c.currentMediaItem?.trackId
        val idx = if (curId != null) tracks.indexOfFirst { it.id == curId } else -1
        if (idx >= 0) {
            // Keep the playing song uninterrupted; the timeline stays in library order —
            // shuffleModeEnabled (below) drives the play order.
            rebuildAroundCurrent(c, tracks, idx, carried)
        } else {
            val at = Random.nextInt(tracks.size)
            c.setMediaItems(tracks.map { it.toMediaItem(context) }, at, 0)
            c.prepare()
            c.play()
            spliceEnqueued(c, carried, at + 1)
        }
        // Enable shuffle now that the timeline is the full library, so the regenerated random
        // order (PlaybackService) spans all songs. The new queue's ids are persisted by the
        // resulting EVENT_TIMELINE_CHANGED.
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

    /**
     * Push equalizer state to the service-side effect (see [PlaybackService.CMD_APPLY_EQ]) for
     * live feedback. The equalizer screen persists to [EqSettings] separately; this is the
     * real-time channel. No-op if the controller isn't connected yet.
     */
    fun applyEq(enabled: Boolean, gainsDb: IntArray) {
        val c = controller ?: return
        val args = android.os.Bundle().apply {
            putBoolean(PlaybackService.EXTRA_EQ_ENABLED, enabled)
            // Copied: the equalizer screen keeps mutating its own array as the fader moves.
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
            // Keep the live-transition counter monotonic even through a momentary no-item state
            // (e.g. the reconnect churn on foreground): resetting it to 0 here would make the very
            // next real song look like a fresh transition and spuriously flip the deck. The modes
            // are carried too, so this flow is the single source of truth for them.
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
            // Through the accessors, not the bundle: MediaItems.kt exists precisely to be the
            // single decode point for these keys. (title/artist/album stay on c.mediaMetadata,
            // which also carries in-band metadata updates.)
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
        /** Bounded retry when the session can't be bound (see [buildController]). */
        const val MAX_CONNECT_ATTEMPTS = 3
        const val CONNECT_RETRY_MS = 400L
    }
}

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
)

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
)

/**
 * UI-side wrapper around a [MediaController] bound to [PlaybackService]. Exposes an
 * observable [state] + [queue] and transport controls. Shuffle/repeat are tracked as
 * app-level modes (broader than ExoPlayer's flags).
 */
class PlayerConnection(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val store = PlaybackStateStore(context)

    private var appShuffle = ShuffleMode.OFF
    private var appRepeat = RepeatMode.OFF

    /**
     * The queue as it was right before shuffle was turned on, so turning shuffle back off can
     * restore it (Shuffle-All replaces the whole timeline). Track ids + where playback was.
     * In-memory only: killing the app mid-shuffle keeps the shuffled queue.
     */
    private data class PreShuffle(val ids: List<Long>, val index: Int, val positionMs: Long)
    private var preShuffle: PreShuffle? = null

    /** Index of the last "play next" enqueued item, so further enqueues append FIFO. */
    private var enqueueEnd = -1

    /**
     * Path of the folder the current queue was loaded from by a folder jump/advance;
     * null when the queue came from anywhere else. Set atomically with the queue via
     * [play]'s parameter, cleared by every other queue replacement.
     */
    var folderContext: String? = null
        private set

    /**
     * Bumped on every queue replacement. Folder navigation captures it when a move is
     * requested and aborts if it changed by the time the move runs — so the automatic
     * Advance-List advance and a user swipe aimed at the same transition can't stack
     * into a double jump (see MainActivity.navigateToNeighbourFolder).
     */
    var queueGeneration = 0
        private set

    /** Invoked when the queue ends (or Next past the last song) in Advance-List mode. */
    var onQueueEnded: (() -> Unit)? = null

    /** Invoked when Previous is pressed at the start of the queue in Advance-List mode. */
    var onQueueStart: (() -> Unit)? = null

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
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) &&
                queueIdsSignature(player) != lastQueueIdsSig
            ) {
                rebuildQueue()
                saveQueue()
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                if (player.currentMediaItemIndex > enqueueEnd) enqueueEnd = -1
            }
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                )
            ) savePosition()
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
                player.playbackState == Player.STATE_ENDED && appRepeat == RepeatMode.ADVANCE
            ) {
                onQueueEnded?.invoke()
            }
            if (player.isPlaying) {
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
            if (future.isCancelled) return@addListener
            controller = runCatching { future.get() }.getOrNull()?.also { it.addListener(listener) }
                ?: return@addListener
            // The UI now owns the automatic Advance-List advance; the service defers while attached.
            context.app.uiControllerAttached = true
            readAudioFormat(controller!!.sessionExtras)
            // A fresh controller doesn't replay events, so if a song is already playing the
            // listener won't fire to start the ticker — kick it here (it self-stops when paused).
            handler.removeCallbacks(ticker)
            handler.post(ticker)
            rebuildQueue()
            // Re-adopt persisted modes onto a surviving queue BEFORE the first state push, so the
            // repeat/shuffle icons don't flash their defaults for a frame.
            restoreModesForLiveSession()
            pushState()
            onReady()
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        savePosition()
        // Hand the automatic Advance-List advance back to the service before dropping the listener,
        // so a folder that ends while backgrounded still rolls into the next one.
        context.app.uiControllerAttached = false
        handler.removeCallbacks(ticker)
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
    }

    // --- Persist / restore ---

    fun isQueueEmpty(): Boolean = (controller?.mediaItemCount ?: 0) == 0

    fun loadSavedState(): PlaybackStateStore.Saved? = store.load()

    private fun saveQueue() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        val ids = ArrayList<Long>(c.mediaItemCount)
        val enqueued = ArrayList<Int>()
        for (i in 0 until c.mediaItemCount) {
            val mi = c.getMediaItemAt(i)
            ids.add(mi.mediaId.toLongOrNull() ?: -1L)
            if (mi.mediaMetadata.extras?.getBoolean(KEY_ENQUEUED, false) == true) enqueued.add(i)
        }
        store.saveQueue(ids, enqueued)
        saveModes()
    }

    private fun saveModes() = store.saveModes(appShuffle.ordinal, appRepeat.ordinal)

    private fun savePosition() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        store.savePosition(c.currentMediaItemIndex, c.currentPosition.coerceAtLeast(0))
    }

    /**
     * Adopt persisted [shuffleOrdinal]/[repeatOrdinal] play modes into the in-memory fields and
     * mirror them onto the player. [forceShuffleOrder] = true (cold [restore], fresh queue)
     * always writes shuffleModeEnabled so the new timeline gets a shuffle order; false (live
     * reconnect) writes it only when it actually drifted, so an already-shuffled session isn't
     * needlessly reshuffled (see PlaybackService.onShuffleModeEnabledChanged / onTimelineChanged).
     */
    private fun applyModes(shuffleOrdinal: Int, repeatOrdinal: Int, forceShuffleOrder: Boolean) {
        val c = controller ?: return
        appShuffle = ShuffleMode.entries.getOrElse(shuffleOrdinal) { ShuffleMode.OFF }
        appRepeat = RepeatMode.entries.getOrElse(repeatOrdinal) { RepeatMode.OFF }
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
        shuffleOrdinal: Int,
        repeatOrdinal: Int,
    ) {
        val c = controller ?: return
        queueGeneration++
        c.setMediaItems(
            tracks.mapIndexed { i, t -> t.toMediaItem(context, enqueued = enqueuedFlags.getOrElse(i) { false }) },
            index.coerceIn(0, maxOf(0, tracks.size - 1)),
            positionMs,
        )
        // Keep FIFO append working after restore: further enqueues go after the last one.
        enqueueEnd = enqueuedFlags.indexOfLast { it }
        applyModes(shuffleOrdinal, repeatOrdinal, forceShuffleOrder = true)
        // If shuffle is being restored as ON, anchor the snapshot on the restored queue so a
        // later shuffle-off keeps this queue (the original pre-shuffle one wasn't persisted).
        preShuffle = if (appShuffle != ShuffleMode.OFF) {
            PreShuffle(tracks.map { it.id }, index.coerceIn(0, maxOf(0, tracks.size - 1)), positionMs)
        } else null
        c.prepare()
    }

    /**
     * Re-hydrate the app-level shuffle/repeat modes onto a session whose queue is still live
     * (the [PlaybackService] outlived the Activity — a config change, or the system reclaiming
     * the Activity but keeping the process). [restore] only covers the cold-start path (empty
     * queue); without this a reconnect to a surviving queue rebuilds a fresh [PlayerConnection]
     * with the DEFAULT modes and never reads the saved ones. The player's own repeatMode can't
     * recover it — Advance-List maps to REPEAT_MODE_OFF, indistinguishable from true OFF — so
     * Advance-List would silently stop advancing to the next folder until the user re-selects the
     * mode. Modes are read independently of the saved queue ([PlaybackStateStore.loadModes], they
     * outlive it), and only a FRESH connection adopts them: a retained [PlayerConnection] already
     * holds the authoritative in-memory modes (every setter persists them), so re-reading disk
     * there could clobber live state. No-op on an empty queue (restore handles that).
     */
    private fun restoreModesForLiveSession() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (appShuffle != ShuffleMode.OFF || appRepeat != RepeatMode.OFF) return
        val (shuffleOrdinal, repeatOrdinal) = store.loadModes()
        applyModes(shuffleOrdinal, repeatOrdinal, forceShuffleOrder = false)
        // Seed the shuffle-off snapshot from the live queue (the original pre-shuffle order
        // wasn't persisted), mirroring restore(), so a later shuffle-off can rebuild it.
        if (appShuffle != ShuffleMode.OFF) takeShuffleSnapshot() else preShuffle = null
    }

    /** Remove every manually-enqueued item from the timeline (Clear Queue). */
    fun clearQueue() {
        val c = controller ?: return
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (c.getMediaItemAt(i).mediaMetadata.extras?.getBoolean(KEY_ENQUEUED, false) == true) {
                c.removeMediaItem(i)
            }
        }
        enqueueEnd = -1
    }

    // --- Transport ---

    /**
     * Replace the queue with [tracks] starting at [startIndex]. [play] = true starts playback
     * (tapping a song); false leaves the play/pause state untouched — so a folder advance keeps
     * playing if it was playing (playWhenReady survives setMediaItems) and stays paused if paused.
     * [folderContext] anchors folder navigation on the folder this queue came from (folder
     * jumps/advances only). Returns false when there is no controller to command (released
     * mid-flight, e.g. the app was backgrounded) — the queue is then untouched.
     */
    fun play(
        tracks: List<Track>,
        startIndex: Int,
        play: Boolean = true,
        folderContext: String? = null,
    ): Boolean {
        val c = controller ?: return false
        queueGeneration++
        enqueueEnd = -1
        this.folderContext = folderContext
        val start = startIndex.coerceIn(0, maxOf(0, tracks.size - 1))
        // The queue is being replaced while shuffle may be on: the pre-shuffle snapshot must
        // follow the NEW queue (turning shuffle off should keep the user here, sequential),
        // not restore whatever list shuffle happened to be enabled in long ago.
        preShuffle = if (appShuffle != ShuffleMode.OFF) {
            PreShuffle(tracks.map { it.id }, start, 0)
        } else null
        c.setMediaItems(tracks.map { it.toMediaItem(context) }, start, 0)
        c.prepare()
        if (play) c.play()
        return true
    }

    /** Insert [tracks] to play right after the current one (FIFO across repeated enqueues). */
    fun enqueueNext(tracks: List<Track>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { play(tracks, 0); return }
        val cur = c.currentMediaItemIndex
        val insertStart = ((if (enqueueEnd > cur) enqueueEnd else cur) + 1).coerceAtMost(c.mediaItemCount)
        var at = insertStart
        for (t in tracks) {
            c.addMediaItem(at, t.toMediaItem(context, enqueued = true))
            at++
        }
        enqueueEnd = at - 1
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

    fun next() {
        val c = controller ?: return
        // Advance-List: pressing Next on the last song jumps to the next folder, matching
        // what happens when the queue ends on its own (see [onQueueEnded]).
        if (appRepeat == RepeatMode.ADVANCE && !c.hasNextMediaItem()) onQueueEnded?.invoke()
        else c.seekToNext()
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
        // Advance-List: pressing Previous at the very start (first song, near its beginning)
        // jumps back to the previous folder. Otherwise seekToPrevious restarts the current
        // song or steps back one, exactly as before.
        if (appRepeat == RepeatMode.ADVANCE && !c.hasPreviousMediaItem() &&
            c.currentPosition <= c.maxSeekToPreviousPosition
        ) onQueueStart?.invoke()
        else c.seekToPrevious()
    }

    // --- Shuffle-aware navigation state (used by the album-art deck) ---

    fun hasNext(): Boolean = controller?.hasNextMediaItem() == true
    fun hasPrevious(): Boolean = controller?.hasPreviousMediaItem() == true

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
     */
    fun seekToQueueItem(index: Int, play: Boolean = true) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0)
            if (play) c.play()
        }
    }

    /**
     * Remove a single item from the timeline (Queue screen "Remove"). [expectedMediaId]
     * guards against a stale [index]: the displayed timelineIndex lags behind after an
     * earlier removal, and removing blindly by index would delete the wrong song.
     */
    fun removeQueueItem(index: Int, expectedMediaId: Long) {
        val c = controller ?: return
        val target = if (index in 0 until c.mediaItemCount &&
            c.getMediaItemAt(index).mediaId.toLongOrNull() == expectedMediaId
        ) index
        else (0 until c.mediaItemCount).firstOrNull {
            c.getMediaItemAt(it).mediaId.toLongOrNull() == expectedMediaId
        } ?: return
        c.removeMediaItem(target)
        if (target <= enqueueEnd) enqueueEnd--
    }

    /**
     * Reorder the enqueued block to match [orderedMediaIds] (their desired order). The
     * items keep their timeline slots; only their order within those slots changes.
     */
    fun reorderQueue(orderedMediaIds: List<Long>) {
        val c = controller ?: return
        if (orderedMediaIds.isEmpty()) return
        fun isEnqueued(i: Int) =
            c.getMediaItemAt(i).mediaMetadata.extras?.getBoolean(KEY_ENQUEUED, false) == true
        // The block starts at the first ENQUEUED item (an id-based search could hit a
        // duplicate of the same song in the playing list, outside the block).
        val start = (0 until c.mediaItemCount).firstOrNull { isEnqueued(it) } ?: return
        orderedMediaIds.forEachIndexed { p, id ->
            val target = start + p
            // Search only from the target onward: positions before it are already placed,
            // which also makes duplicate media ids resolve to successive distinct copies.
            val cur = (target until c.mediaItemCount).firstOrNull {
                isEnqueued(it) && c.getMediaItemAt(it).mediaId.toLongOrNull() == id
            } ?: return@forEachIndexed
            if (cur != target) c.moveMediaItem(cur, target)
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
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId.toLongOrNull() ?: -1L }
        preShuffle = PreShuffle(ids, c.currentMediaItemIndex, c.currentPosition.coerceAtLeast(0))
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
        queueGeneration++
        folderContext = null
        appShuffle = ShuffleMode.OFF
        c.shuffleModeEnabled = false
        if (tracks.isEmpty() || snap == null) { saveModes(); pushState(); return }
        val curId = c.currentMediaItem?.mediaId?.toLongOrNull()
        val pos = tracks.indexOfFirst { it.id == curId }
        if (pos >= 0) {
            // Seamless: keep the current song, rebuild the original queue around it.
            val cur = c.currentMediaItemIndex
            if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
            if (cur > 0) c.removeMediaItems(0, cur)
            val before = tracks.subList(0, pos).map { it.toMediaItem(context) }
            val after = tracks.subList(pos + 1, tracks.size).map { it.toMediaItem(context) }
            if (before.isNotEmpty()) c.addMediaItems(0, before)
            if (after.isNotEmpty()) c.addMediaItems(c.mediaItemCount, after)
        } else {
            // Current song isn't in the original queue (played into shuffle) — restore as saved.
            c.setMediaItems(tracks.map { it.toMediaItem(context) }, snap.index.coerceIn(0, tracks.size - 1), snap.positionMs)
            c.prepare()
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
        queueGeneration++
        enqueueEnd = -1
        folderContext = null
        val curId = c.currentMediaItem?.mediaId?.toLongOrNull()
        val idx = if (curId != null) tracks.indexOfFirst { it.id == curId } else -1
        if (idx >= 0) {
            // Rebuild the timeline as "all songs" WITHOUT touching the currently-playing item,
            // so audio doesn't stall: strip the other items around it, then re-add the rest of
            // the library before and after. shuffleModeEnabled drives the play order; the
            // timeline itself stays in library order. (setMediaItems would re-prepare the
            // current item and cause a ~0.5s gap — the bug this avoids.)
            val cur = c.currentMediaItemIndex
            if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
            if (cur > 0) c.removeMediaItems(0, cur)
            // Only the current item remains (index 0). Wrap the rest of the library around it.
            val before = tracks.subList(0, idx).map { it.toMediaItem(context) }
            val after = tracks.subList(idx + 1, tracks.size).map { it.toMediaItem(context) }
            if (before.isNotEmpty()) c.addMediaItems(0, before)
            if (after.isNotEmpty()) c.addMediaItems(c.mediaItemCount, after)
        } else {
            c.setMediaItems(tracks.map { it.toMediaItem(context) }, Random.nextInt(tracks.size), 0)
            c.prepare()
            c.play()
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

    fun currentShuffle(): ShuffleMode = appShuffle
    fun currentRepeat(): RepeatMode = appRepeat

    /**
     * Push equalizer state to the service-side effect (see [PlaybackService.CMD_APPLY_EQ]) for
     * live feedback. The equalizer screen persists to [EqSettings] separately; this is the
     * real-time channel. No-op if the controller isn't connected yet.
     */
    fun applyEq(enabled: Boolean, gainsDb: IntArray) {
        val c = controller ?: return
        val args = android.os.Bundle().apply {
            putBoolean(PlaybackService.EXTRA_EQ_ENABLED, enabled)
            putIntArray(PlaybackService.EXTRA_EQ_GAINS, gainsDb)
        }
        c.sendCustomCommand(SessionCommand(PlaybackService.CMD_APPLY_EQ, android.os.Bundle.EMPTY), args)
    }

    private fun rebuildQueue() {
        val c = controller ?: run { _queue.value = emptyList(); return }
        lastQueueIdsSig = queueIdsSignature(c)
        val items = ArrayList<QueueItem>(c.mediaItemCount)
        for (i in 0 until c.mediaItemCount) {
            val mi = c.getMediaItemAt(i)
            val md = mi.mediaMetadata
            items.add(
                QueueItem(
                    mediaId = mi.mediaId.toLongOrNull() ?: -1L,
                    albumId = md.extras?.getLong(KEY_ALBUM_ID, -1L) ?: -1L,
                    title = md.title?.toString() ?: "",
                    artist = md.artist?.toString() ?: "",
                    album = md.albumTitle?.toString() ?: "",
                    filePath = md.extras?.getString(KEY_PATH) ?: "",
                    timelineIndex = i,
                    enqueued = md.extras?.getBoolean(KEY_ENQUEUED, false) ?: false,
                )
            )
        }
        _queue.value = items
    }

    private fun pushState() {
        val c = controller
        if (c == null || c.currentMediaItem == null) {
            // Keep the live-transition counter monotonic even through a momentary no-item state
            // (e.g. the reconnect churn on foreground): resetting it to 0 here would make the very
            // next real song look like a fresh transition and spuriously flip the deck.
            _state.value = UiPlayback(liveTransitionSeq = liveTransitionSeq)
            return
        }
        val md = c.mediaMetadata
        _state.value = UiPlayback(
            hasItem = true,
            mediaId = c.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L,
            title = md.title?.toString() ?: "",
            artist = md.artist?.toString() ?: "",
            album = md.albumTitle?.toString() ?: "",
            albumId = md.extras?.getLong(KEY_ALBUM_ID, -1L) ?: -1L,
            filePath = md.extras?.getString(KEY_PATH) ?: "",
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

}

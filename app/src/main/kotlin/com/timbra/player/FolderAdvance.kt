// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.player

import android.content.Context
import androidx.media3.common.Player
import com.timbra.app
import com.timbra.data.FolderTreeBuilder
import com.timbra.data.model.FolderNode
import com.timbra.data.model.Track
import com.timbra.data.tracksInPlayOrder
import com.timbra.folderSort
import com.timbra.repository
import kotlinx.coroutines.sync.withLock

/**
 * THE Advance-List folder move, for every trigger and every owner.
 *
 * It used to exist twice — richly in the UI (deck/phantom handling, folderContext, a generation
 * guard and a mutex) and as a cut-down copy in [PlaybackService] for the backgrounded case,
 * coordinated only by "is the UI attached". The copies had drifted on three axes: the service
 * anchored on the playing file's directory instead of the folder a jump had loaded, it always
 * entered at index 0 and force-started playback (so there was no BACKWARD advance at all while
 * detached, and a stuck-track recovery could start audio on a player the user had paused), and
 * it bumped neither the generation nor the mutex the UI added to stop double jumps.
 *
 * Works on any [Player], which is what lets both owners share it: the service passes its
 * ExoPlayer, the UI its MediaController.
 */
object FolderAdvance {

    /**
     * Step to the neighbouring song-folder and load its tracks, entering at [startAt].
     *
     * Serialized by [PlaybackSession.folderNavLock] and abandoned when the queue was already
     * replaced since [expectedGen] was captured — the racing navigation that got there first
     * stands. Preserves play/pause: a queue that ended on its own keeps playing (playWhenReady
     * is still set), a manual Next or swipe from a paused song stays paused.
     *
     * [stillWanted] is re-checked after the (async) traversal lookup, so a caller can abandon the
     * move when the thing it was reacting to no longer holds (the player moved on by itself).
     *
     * Returns the folder moved to, or null on a no-op (library edge, nothing playing, superseded).
     */
    suspend fun move(
        context: Context,
        player: Player,
        forward: Boolean,
        expectedGen: Int = context.app.session.queueGeneration,
        startAt: (List<Track>) -> Int = { tracks -> if (forward) 0 else tracks.lastIndex },
        stillWanted: () -> Boolean = { true },
    ): FolderNode? {
        val session = context.app.session
        return session.folderNavLock.withLock {
            if (expectedGen != session.queueGeneration) return@withLock null
            val current = player.currentMediaItem ?: return@withLock null
            val fallbackAnchor = current.pathExtra.substringBeforeLast('/', "")
            // Prefer the folder a jump/advance last loaded; fall back to the playing file's own
            // directory (always a song-folder entry) when it's absent or stale after a rescan.
            val (prev, next) = FolderTreeBuilder.neighbourFolders(
                context.repository.songFolders(),
                session.folderContext,
                fallbackAnchor,
            )
            // The traversal lookup suspended; re-check that nothing replaced the queue meanwhile
            // and that the caller still wants this.
            if (expectedGen != session.queueGeneration || !stillWanted()) return@withLock null
            val target = (if (forward) next else prev) ?: return@withLock null
            val tracks = target.tracksInPlayOrder(context.folderSort.sortOrder)
            if (tracks.isEmpty()) return@withLock null

            val resume = player.playWhenReady
            val start = startAt(tracks).coerceIn(0, tracks.lastIndex)
            session.queueReplaced(target.path)
            player.setMediaItems(tracks.map { it.toMediaItem(context) }, start, 0L)
            player.prepare()
            if (resume) player.play()

            // Shuffle-All's pool was the whole library; it is now this one folder — which is
            // exactly what Shuffle-Songs means (see [ShuffleMode.narrowedToFolder]). Persisting
            // it bumps the modes revision, which is how a live PlayerConnection notices and
            // re-adopts (its own in-memory copy would otherwise keep claiming ALL and write it
            // back over this).
            val store = context.app.playbackStore
            val (shuffle, repeat) = store.loadModes()
            val narrowed = shuffle.narrowedToFolder()
            if (narrowed != shuffle) store.saveModes(narrowed, repeat)
            // The UI persists the queue on timeline changes, but it may be detached — mirror it
            // here so a process death mid-background doesn't restore the STALE previous folder.
            store.saveQueue(tracks.map { it.id }, emptyList(), start, 0L)
            target
        }
    }

    /** True when the persisted repeat mode is Advance-List, i.e. folder stepping is armed. */
    fun armed(context: Context): Boolean =
        context.app.playbackStore.loadModes().second == RepeatMode.ADVANCE
}

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

object FolderAdvance {

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
            val (prev, next) = FolderTreeBuilder.neighbourFolders(
                context.repository.songFolders(),
                session.folderContext,
                fallbackAnchor,
            )
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

            val store = context.app.playbackStore
            val (shuffle, repeat) = store.loadModes()
            val narrowed = shuffle.narrowedToFolder()
            if (narrowed != shuffle) store.saveModes(narrowed, repeat)
            store.saveQueue(tracks.map { it.id }, emptyList(), start, 0L)
            target
        }
    }

    fun armed(context: Context): Boolean =
        context.app.playbackStore.loadModes().second == RepeatMode.ADVANCE
}

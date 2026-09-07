// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.player

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicReference

/**
 * They live in one process and either side can replace the queue, so private copies let them
 * disagree: the service used to derive the Advance-List anchor from the playing file's own
 * directory while the UI used the folder a jump had loaded, and a queue the service replaced
 * didn't bump the generation the UI's pending moves check — so an advance could stack on top
 * of one that had already happened.
 *
 * Main-thread state (both owners run their queue work there), but the service's stall/error
 * callbacks can observe it from the playback thread — so the whole of it is ONE immutable
 * snapshot swapped atomically. Two independent `@Volatile` fields would give visibility without
 * atomicity: `generation++` could lose an increment (defeating the very guard the counter
 * exists for), and a reader could see the new generation paired with the previous folder.
 */
class PlaybackSession {

    private data class State(val generation: Int, val folderContext: String?)

    private val state = AtomicReference(State(0, null))

    val folderContext: String? get() = state.get().folderContext

    /**
     * Bumped on every queue replacement, by whichever side made it. Folder navigation captures
     * it when a move is requested and aborts if it changed by the time the (async) move runs,
     * so an automatic advance and a user swipe aimed at the same transition can't stack into a
     * double jump.
     */
    val queueGeneration: Int get() = state.get().generation

    val folderNavLock = Mutex()

    /** [fingerprint] is [queueFingerprint] of the queue the restore was remapped ONTO, for the
     *  same reason the persisted session carries one: these are timeline indices, so applying
     *  them to any other queue marks never-played songs played. */
    class ShuffleRestore(val history: List<Int>, val played: List<Int>, val fingerprint: Int)

    private val shuffleRestore = AtomicReference<ShuffleRestore?>(null)

    fun offerShuffleRestore(restore: ShuffleRestore) = shuffleRestore.set(restore)

    fun takeShuffleRestore(): ShuffleRestore? = shuffleRestore.getAndSet(null)

    fun queueReplaced(folderContext: String?) {
        state.updateAndGet { State(it.generation + 1, folderContext) }
    }
}

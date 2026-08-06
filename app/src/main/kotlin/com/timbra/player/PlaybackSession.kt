// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.player

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicReference

/**
 * The queue facts that BOTH the UI's [PlayerConnection] and [PlaybackService] need.
 *
 * They live in one process and either side can replace the queue, so private copies let them
 * disagree: the service used to derive the Advance-List anchor from the playing file's own
 * directory while the UI used the folder a jump had loaded, and a queue the service replaced
 * didn't bump the generation the UI's pending moves check — so an advance could stack on top
 * of one that had already happened.
 *
 * (There is deliberately no "is the UI attached" flag any more. It existed only to arbitrate
 * who performed the folder advance, and that arbitration was itself the bug: the decision was
 * made once, at the instant the queue ended, so backgrounding the app in that window lost the
 * advance entirely. The service now always owns it — see [FolderAdvance].)
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

    /**
     * Path of the folder the current queue was loaded from by a folder tap/jump/advance; null
     * when the queue came from anywhere else. THE Advance-List anchor — when it is null the
     * callers fall back to the playing file's own directory, which is always a song-folder.
     */
    val folderContext: String? get() = state.get().folderContext

    /**
     * Bumped on every queue replacement, by whichever side made it. Folder navigation captures
     * it when a move is requested and aborts if it changed by the time the (async) move runs,
     * so an automatic advance and a user swipe aimed at the same transition can't stack into a
     * double jump.
     */
    val queueGeneration: Int get() = state.get().generation

    /** Serializes folder navigation across both owners, so two moves can't interleave. */
    val folderNavLock = Mutex()

    /** Record a queue replacement; [folderContext] is the folder it came from, or null. */
    fun queueReplaced(folderContext: String?) {
        state.updateAndGet { State(it.generation + 1, folderContext) }
    }
}

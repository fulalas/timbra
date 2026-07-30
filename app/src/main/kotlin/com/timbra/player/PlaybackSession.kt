package com.timbra.player

import kotlinx.coroutines.sync.Mutex

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
 * Main-thread state (both owners run their queue work there); the fields are volatile because
 * the service's stall/error callbacks can observe them from the playback thread.
 */
class PlaybackSession {

    /**
     * Path of the folder the current queue was loaded from by a folder tap/jump/advance; null
     * when the queue came from anywhere else. THE Advance-List anchor — when it is null the
     * callers fall back to the playing file's own directory, which is always a song-folder.
     */
    @Volatile
    var folderContext: String? = null

    /**
     * Bumped on every queue replacement, by whichever side made it. Folder navigation captures
     * it when a move is requested and aborts if it changed by the time the (async) move runs,
     * so an automatic advance and a user swipe aimed at the same transition can't stack into a
     * double jump.
     */
    @Volatile
    var queueGeneration = 0
        private set

    /** Serializes folder navigation across both owners, so two moves can't interleave. */
    val folderNavLock = Mutex()

    /** Record a queue replacement; [folderContext] is the folder it came from, or null. */
    fun queueReplaced(folderContext: String?) {
        queueGeneration++
        this.folderContext = folderContext
    }
}

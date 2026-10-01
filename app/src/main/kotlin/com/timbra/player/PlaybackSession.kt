// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.player

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicReference

class PlaybackSession {

    private data class State(val generation: Int, val folderContext: String?)

    private val state = AtomicReference(State(0, null))

    val folderContext: String? get() = state.get().folderContext

    val queueGeneration: Int get() = state.get().generation

    val folderNavLock = Mutex()

    class ShuffleRestore(val history: List<Int>, val played: List<Int>, val fingerprint: Int)

    private val shuffleRestore = AtomicReference<ShuffleRestore?>(null)

    fun offerShuffleRestore(restore: ShuffleRestore) = shuffleRestore.set(restore)

    fun takeShuffleRestore(): ShuffleRestore? = shuffleRestore.getAndSet(null)

    fun queueReplaced(folderContext: String?) {
        state.updateAndGet { State(it.generation + 1, folderContext) }
    }
}

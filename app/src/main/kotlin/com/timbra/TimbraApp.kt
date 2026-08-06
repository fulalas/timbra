// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra

import android.app.Application
import android.content.Context
import com.timbra.data.FolderSort
import com.timbra.data.MediaRepository
import com.timbra.player.EqSettings
import com.timbra.player.PlaybackSession
import com.timbra.player.PlaybackStateStore
import com.timbra.ui.ArtLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class TimbraApp : Application() {
    val repository: MediaRepository by lazy { MediaRepository(this) }

    /** Persisted equalizer state, shared by the equalizer screen and (indirectly) the service. */
    val eqSettings: EqSettings by lazy { EqSettings(this) }

    /** Persisted queue/position/modes, shared by the UI connection and the service — they use
     *  it as their common channel, so they must not hold separate instances. */
    val playbackStore: PlaybackStateStore by lazy { PlaybackStateStore(this) }

    /** Queue facts shared by the UI connection and the service (see [PlaybackSession]). */
    val session = PlaybackSession()

    /** The folder browser's persisted order, which is also the order folder QUEUES are built
     *  in — see [FolderSort]. */
    val folderSort: FolderSort by lazy { FolderSort(this) }

    /** Bumped whenever the library becomes readable / is rescanned, so screens reload. */
    val libraryEpoch = MutableStateFlow(0)

    /** True once the full player has been auto-opened this process launch. */
    var openedPlayerThisLaunch = false

    fun refreshLibrary() {
        repository.invalidate()
        ArtLoader.invalidate()
        // update {}, not `value += 1`: the latter is a read-modify-write, so two overlapping
        // refreshes (a permission grant and a rescan signal landing together) could collapse into
        // one increment — and since every screen's reload is gated on the epoch CHANGING, the
        // second refresh would be silently dropped and the list left stale.
        libraryEpoch.update { it + 1 }
    }
}

val Context.app: TimbraApp
    get() = applicationContext as TimbraApp

val Context.repository: MediaRepository
    get() = app.repository

val Context.eqSettings: EqSettings
    get() = app.eqSettings

val Context.folderSort: FolderSort
    get() = app.folderSort

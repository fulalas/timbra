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
        libraryEpoch.value += 1
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

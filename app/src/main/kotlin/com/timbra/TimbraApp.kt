package com.timbra

import android.app.Application
import android.content.Context
import com.timbra.data.MediaRepository
import com.timbra.player.EqSettings
import com.timbra.ui.ArtLoader
import kotlinx.coroutines.flow.MutableStateFlow

class TimbraApp : Application() {
    val repository: MediaRepository by lazy { MediaRepository(this) }

    /** Persisted equalizer state, shared by the equalizer screen and (indirectly) the service. */
    val eqSettings: EqSettings by lazy { EqSettings(this) }

    /** Bumped whenever the library becomes readable / is rescanned, so screens reload. */
    val libraryEpoch = MutableStateFlow(0)

    /** True once the full player has been auto-opened this process launch. */
    var openedPlayerThisLaunch = false

    /**
     * True while the UI's [com.timbra.player.PlayerConnection] has a live controller (set on
     * connect, cleared on release). [com.timbra.player.PlaybackService] uses it to decide who
     * owns the automatic Advance-List folder advance at the end of a queue: the UI when it's
     * attached (rich deck/phantom/folderContext handling), the service when it isn't (the app
     * is backgrounded / the Activity was destroyed) — so the last song of a folder still rolls
     * into the next one with the screen off, and the two never double-advance.
     */
    @Volatile
    var uiControllerAttached = false

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

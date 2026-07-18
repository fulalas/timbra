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

// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra

import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import com.timbra.data.FolderSort
import com.timbra.data.MediaRepository
import com.timbra.player.EqSettings
import com.timbra.player.PlaybackSession
import com.timbra.player.PlaybackStateStore
import com.timbra.ui.ArtLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TimbraApp : Application() {
    val repository: MediaRepository by lazy { MediaRepository(this) }

    val eqSettings: EqSettings by lazy { EqSettings(this) }

    val playbackStore: PlaybackStateStore by lazy { PlaybackStateStore(this) }

    val session = PlaybackSession()

    val folderSort: FolderSort by lazy { FolderSort(this) }

    val libraryEpoch = MutableStateFlow(0)

    var openedPlayerThisLaunch = false

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var refreshJob: Job? = null
    private var burstStartedAt = 0L

    private var seenFingerprint: String? = null

    private val fingerprintLock = Mutex()

    private val audioObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = onMediaStoreChanged()
    }

    override fun onCreate() {
        super.onCreate()
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, audioObserver,
        )
    }

    private fun onMediaStoreChanged() {
        val now = SystemClock.uptimeMillis()
        if (refreshJob?.isActive != true) burstStartedAt = now
        val wait = (burstStartedAt + MAX_WAIT_MS - now).coerceAtMost(SETTLE_MS)
        refreshJob?.cancel()
        refreshJob = appScope.launch {
            delay(wait)
            doRefreshLibrary()
        }
    }

    private suspend fun adoptFingerprint(): Boolean = fingerprintLock.withLock {
        val fingerprint = repository.libraryFingerprint()
        val moved = seenFingerprint?.let { it != fingerprint } == true
        seenFingerprint = fingerprint
        moved
    }

    suspend fun refreshLibraryIfChanged() {
        if (adoptFingerprint()) refreshLibrary()
    }

    fun refreshLibrary() {
        refreshJob?.cancel()
        doRefreshLibrary()
    }

    private fun doRefreshLibrary() {
        repository.invalidate()
        ArtLoader.invalidate()
        appScope.launch { adoptFingerprint() }
        libraryEpoch.update { it + 1 }
    }

    private companion object {
        const val SETTLE_MS = 1_200L

        const val MAX_WAIT_MS = 5_000L
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

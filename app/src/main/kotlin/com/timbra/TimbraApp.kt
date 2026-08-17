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

    /**
     * [MediaRepository.libraryFingerprint] as the library caches were last known to match it.
     * Only null before the first measurement of the process, when nothing can be stale yet.
     */
    private var seenFingerprint: String? = null

    /** Serialises the two writers of [seenFingerprint] — the foreground probe and the
     *  post-refresh re-baseline — and makes each measure-compare-adopt one atomic step, so a
     *  slow measurement can never land on top of a newer one. */
    private val fingerprintLock = Mutex()

    private val audioObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = onMediaStoreChanged()
    }

    override fun onCreate() {
        super.onCreate()
        // Watched for the whole process life, never unregistered: songs copied to the device while
        // the app sits in the background must invalidate the caches too, or coming back shows the
        // library from before the copy. Costs nothing while nothing is on screen — invalidate()
        // only DROPS the caches, and the re-query is lazy. notifyForDescendants, because a
        // per-file insert notifies that row's item Uri, not the collection's.
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, audioObserver,
        )
    }

    /**
     * Coalesce a burst of MediaStore notifications into ONE refresh: the scanner signals per file,
     * so copying an album fires dozens, and every refresh throws away the track list, the folder
     * tree, the traversal cache and the whole art cache. Settle for [SETTLE_MS] of quiet — but
     * never hold off longer than [MAX_WAIT_MS] past the first signal, because a few hundred files
     * keep signalling for minutes and the list has to update WHILE the copy runs, not only after.
     */
    private fun onMediaStoreChanged() {
        val now = SystemClock.uptimeMillis()
        // Only a signal arriving with no refresh already pending opens a new burst window.
        if (refreshJob?.isActive != true) burstStartedAt = now
        val wait = (burstStartedAt + MAX_WAIT_MS - now).coerceAtMost(SETTLE_MS)
        refreshJob?.cancel()
        refreshJob = appScope.launch {
            delay(wait) // non-positive once the cap is reached -> refresh straight away
            // NOT refreshLibrary(): that cancels refreshJob, which is this very coroutine.
            doRefreshLibrary()
        }
    }

    /**
     * Measure MediaStore's marker and adopt it as the baseline; true if it MOVED since the last
     * adoption. Never true on the first measurement of the process — there is nothing to compare
     * against, and the caches are cold anyway, so they will be built from this very state.
     */
    private suspend fun adoptFingerprint(): Boolean = fingerprintLock.withLock {
        val fingerprint = repository.libraryFingerprint()
        val moved = seenFingerprint?.let { it != fingerprint } == true
        seenFingerprint = fingerprint
        moved
    }

    /**
     * Refresh only if MediaStore actually moved. The foreground-return backstop for signals
     * [audioObserver] never saw — the process was recreated since, or the provider coalesced them
     * away. Refreshing blindly here instead would re-query the library and re-decode every cover
     * on every app switch, which is exactly what the epoch guard in `reloadOnLibraryChange`
     * exists to avoid.
     */
    suspend fun refreshLibraryIfChanged() {
        if (adoptFingerprint()) refreshLibrary()
    }

    /**
     * An app-initiated refresh. Supersedes any pending debounced one: MediaProvider notifies the
     * ACTING app's observers too, so a delete or a manual rescan would otherwise be followed by a
     * duplicate full refresh — another whole-library re-query and art evictAll — a second later.
     */
    fun refreshLibrary() {
        refreshJob?.cancel()
        doRefreshLibrary()
    }

    private fun doRefreshLibrary() {
        repository.invalidate()
        ArtLoader.invalidate()
        // Re-baseline, because the caches are about to be rebuilt from MediaStore as of NOW and
        // that is what the next probe must compare against. Leaving the marker stale would make
        // that probe refresh a second time for a change already picked up; clearing it to null
        // would be worse — the probe would adopt the NEXT change as its baseline WITHOUT
        // refreshing, and the library would sit stale until the change after that.
        appScope.launch { adoptFingerprint() }
        // update {}, not `value += 1`: the latter is a read-modify-write, so two overlapping
        // refreshes (a permission grant and a rescan signal landing together) could collapse into
        // one increment — and since every screen's reload is gated on the epoch CHANGING, the
        // second refresh would be silently dropped and the list left stale.
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

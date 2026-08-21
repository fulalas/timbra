// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.timbra.R
import com.timbra.app
import com.timbra.folderSort
import com.timbra.repository
import com.timbra.data.FolderTreeBuilder
import com.timbra.data.MediaRepository
import com.timbra.data.model.FolderNode
import com.timbra.data.model.Track
import com.timbra.data.tracksInPlayOrder
import com.timbra.databinding.ActivityMainBinding
import com.timbra.player.FolderAdvance
import com.timbra.player.PlayerConnection
import com.timbra.player.ShuffleMode
import com.timbra.player.UiPlayback
import com.timbra.ui.dialogs.Dialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var player: PlayerConnection
        private set

    private lateinit var miniTransport: TransportBinder

    private val navController: NavController by lazy {
        (supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment).navController
    }
    private val appBarConfig by lazy { AppBarConfiguration(setOf(R.id.libraryFragment)) }

    private val playerNavOptions by lazy {
        navOptions {
            launchSingleTop = true
            popUpTo(R.id.playerFragment) { inclusive = true }
        }
    }

    private val globalMenuTargets = mapOf(
        R.id.action_search to R.id.searchFragment,
        R.id.action_equalizer to R.id.equalizerFragment,
    )

    private var onPlayerScreen = false
    private var lastPlayback: UiPlayback? = null
    private var miniArtMediaId = Long.MIN_VALUE

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[audioPermission()] == true) app.refreshLibrary()
            else showPermissionRationale()
        }

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) app.refreshLibrary()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apps targeting SDK 35 are drawn edge-to-edge and android:statusBarColor /
        // navigationBarColor are IGNORED, so without this the toolbar sits under the status bar and
        // the mini-player under the navigation bar — the controls the user actually taps.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        setupActionBarWithNavController(navController, appBarConfig)
        addGlobalMenu()
        navController.addOnDestinationChangedListener { _, dest, args ->
            onPlayerScreen = dest.id == R.id.playerFragment
            updateMiniVisibility()
            supportActionBar?.subtitle =
                if (dest.id == R.id.folderTreeFragment) breadcrumbFor(args?.getString("folderPath").orEmpty())
                else null
            // Leaving the player: stop any title marquee and return the SHARED toolbar
            // title view to its stock state — end-ellipsis and NOT horizontally scrolling,
            // else long titles on other screens clip with no "…" (the scrolling flag leaks).
            if (dest.id != R.id.playerFragment) {
                toolbarMarquee?.stop()
                binding.toolbar.post {
                    toolbarTitleView()?.apply {
                        setOnClickListener(null)
                        isClickable = false
                    }
                }
            }
        }

        player = PlayerConnection(this)
        setupMiniPlayer()
        ensureAudioPermission(firstCreate = savedInstanceState == null)

        // Launched ONCE here (not in onStart): repeatOnLifecycle already stops/restarts the
        // collection across background/foreground, whereas launching from onStart would add
        // one more never-completing collector per foreground cycle — unbounded growth.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                player.state.collect { bindMiniPlayer(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Backstop for library changes this process didn't witness — see
        // TimbraApp.refreshLibraryIfChanged. It only re-queries the library when MediaStore's own
        // marker has moved, which is what makes it affordable on every foreground return.
        //
        // Gated on the permission, and not only as an optimisation: below API 29 MediaProvider
        // declares android:readPermission, so querying it before the grant throws SecurityException
        // — and onCreate merely QUEUES the request, so on a first run onStart gets here first. The
        // throw would leave this bare launch and take the process down on launch.
        if (hasAudioPermission()) lifecycleScope.launch { app.refreshLibraryIfChanged() }
        player.connect {
            if (!player.isQueueEmpty()) openPlayerOnce() else maybeRestorePlayback()
        }
    }

    private fun maybeRestorePlayback() {
        val saved = player.loadSavedState() ?: return
        lifecycleScope.launch {
            val byId = repository.allTracks().associateBy { it.id }
            val enqSet = saved.enqueuedIndices.toSet()
            val kept = ArrayList<Int>(saved.trackIds.size) // surviving SAVED indices, in order
            val tracks = ArrayList<Track>(saved.trackIds.size)
            val enqueuedFlags = ArrayList<Boolean>(saved.trackIds.size)
            saved.trackIds.forEachIndexed { i, id ->
                val t = byId[id] ?: return@forEachIndexed
                kept.add(i)
                tracks.add(t)
                enqueuedFlags.add(i in enqSet)
            }
            if (tracks.isEmpty()) return@launch
            // Some saved tracks may be gone (deleted/rescanned). Land on the surviving position
            // of the track that was current, or — when that one is itself gone — on its NEAREST
            // surviving neighbour. Falling back to index 0 restarted the queue at the top while
            // still applying the dead track's elapsed time to an unrelated song.
            var index = 0
            var bestDistance = Int.MAX_VALUE
            kept.forEachIndexed { newIndex, savedIndex ->
                val d = abs(savedIndex - saved.index)
                if (d < bestDistance) { bestDistance = d; index = newIndex }
            }
            // The position only means anything for the song it was taken from.
            val positionMs = if (kept.getOrNull(index) == saved.index) saved.positionMs else 0L
            // The shuffle session is recorded as positions in the SAVED queue, so it has to travel
            // through the same surviving-index map as everything else here.
            val newIndexOf = HashMap<Int, Int>(kept.size)
            kept.forEachIndexed { newIndex, savedIndex -> newIndexOf[savedIndex] = newIndex }
            player.restore(
                tracks, enqueuedFlags, index, positionMs, saved.shuffle, saved.repeat,
                saved.shufHistory.mapNotNull { newIndexOf[it] },
                saved.shufPlayed.mapNotNull { newIndexOf[it] },
            )
            openPlayerOnce()
        }
    }

    private fun openPlayerOnce() {
        if (app.openedPlayerThisLaunch) return
        // Reached from a coroutine that resumed after a suspending library read, so the Activity
        // may already be stopped: committing then throws instead of deferring. Don't burn the
        // once-per-launch flag either — the next onStart's connect callback retries.
        if (supportFragmentManager.isStateSaved) return
        if (navController.currentDestination?.id != R.id.libraryFragment) return
        app.openedPlayerThisLaunch = true
        navController.navigate(R.id.playerFragment, null, playerNavOptions)
    }

    private fun addGlobalMenu() = addMenuProvider(object : MenuProvider {
        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
            inflater.inflate(R.menu.menu_global, menu)
        }

        override fun onPrepareMenu(menu: Menu) {
            val onEqScreen = navController.currentDestination?.id == R.id.equalizerFragment
            (globalMenuTargets.keys + R.id.action_rescan).forEach {
                menu.findItem(it)?.isVisible = !onEqScreen
            }
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean {
            if (item.itemId == R.id.action_rescan) { rescanLibrary(); return true }
            val target = globalMenuTargets[item.itemId] ?: return false
            if (navController.currentDestination?.id != target) {
                navController.navigate(target, null, navOptions { launchSingleTop = true })
            }
            return true
        }
    })

    private fun rescanLibrary() {
        // Nothing to read without the permission, and querying anyway would throw below API 29
        // (see onStart) — ask for it instead; the grant callback refreshes.
        if (!hasAudioPermission()) { ensureAudioPermission(firstCreate = false); return }
        app.refreshLibrary()
        lifecycleScope.launch {
            val n = repository.allTracks().size
            Toast.makeText(
                this@MainActivity,
                resources.getQuantityString(R.plurals.rescan_done, n, n),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun openPlayer() = navController.navigate(R.id.playerFragment, null, playerNavOptions)

    fun openFolderChain(targetDir: String) {
        if (targetDir.isBlank()) return
        lifecycleScope.launch {
            val root = repository.folderRoot()
            // The trailing '/' matters: without it a directory whose name merely BEGINS with the
            // root's name matched, and the relative path then started mid-segment — stacking
            // folder entries that don't exist, each silently resolving to the tree root.
            val prefix = "${root.path}/"
            val rel = if (targetDir == root.path) ""
            else if (targetDir.startsWith(prefix)) targetDir.removePrefix(prefix)
            else return@launch
            val segments = rel.split('/').filter { it.isNotEmpty() }
            // The tree read suspended; a stopped Activity can no longer take a transaction.
            if (supportFragmentManager.isStateSaved) return@launch
            navController.navigate(
                R.id.folderTreeFragment,
                bundleOf("folderPath" to "", "folderTitle" to getString(R.string.cat_folders)),
                navOptions { popUpTo(R.id.libraryFragment) { inclusive = false } },
            )
            var path = root.path
            for (seg in segments) {
                path = "$path/$seg"
                navController.navigate(
                    R.id.folderTreeFragment,
                    bundleOf("folderPath" to path, "folderTitle" to seg),
                )
            }
        }
    }

    private fun breadcrumbFor(folderPath: String): String? {
        if (folderPath.isBlank()) return null
        return friendlyPath(folderPath.substringBeforeLast('/', ""))
    }

    fun friendlyPath(path: String): String? = path
        .replaceFirst(STORAGE_EMULATED, "")
        .replaceFirst(STORAGE_VOLUME, "")
        .trim('/')
        .ifBlank { null }

    private var toolbarMarquee: TitleMarquee? = null

    fun setMarqueeTitle(title: String) {
        supportActionBar?.title = title
        binding.toolbar.post {
            // The post may land after the user has left the player, whose title this is — the
            // toolbar's title view is SHARED, so writing it then would clobber the new screen's.
            if (!onPlayerScreen) return@post
            val tv = toolbarTitleView() ?: return@post
            val marquee = toolbarMarquee?.takeIf { it.view === tv }
                ?: TitleMarquee(tv).also { toolbarMarquee = it }
            marquee.set(title)
            tv.setOnClickListener { marquee.scrollOnce() }
        }
    }

    /** The Toolbar's internal title TextView (no public accessor; matched by its text). */
    private fun toolbarTitleView(): TextView? {
        for (i in 0 until binding.toolbar.childCount) {
            val v = binding.toolbar.getChildAt(i)
            if (v is TextView && v.text == binding.toolbar.title) return v
        }
        return null
    }

    suspend fun libraryRelativePath(dir: String): String? {
        if (dir.isBlank()) return null
        val root = repository.folderRoot().path
        return when {
            dir == root -> null
            dir.startsWith("$root/") -> dir.removePrefix("$root/").ifBlank { null }
            else -> friendlyPath(dir)
        }
    }

    suspend fun advanceFolder(
        forward: Boolean,
        expectedGen: Int = app.session.queueGeneration,
    ): Boolean = player.moveFolder(this, forward, expectedGen) != null

    suspend fun jumpToNeighbourFolder(forward: Boolean): String? =
        player.moveFolder(this, forward, app.session.queueGeneration) { tracks ->
            if (player.state.value.shuffle != ShuffleMode.OFF) tracks.indices.random() else 0
        }?.name

    private suspend fun neighbourSongFolders(): Pair<FolderNode?, FolderNode?> {
        val filePath = player.state.value.filePath
        if (filePath.isBlank()) return null to null
        return FolderTreeBuilder.neighbourFolders(
            repository.songFolders(),
            app.session.folderContext,
            filePath.substringBeforeLast('/', ""),
        )
    }

    suspend fun neighbourFolderSongs(): Pair<Track?, Track?> {
        val (prev, next) = neighbourSongFolders()
        val order = folderSort.sortOrder
        return prev?.tracksInPlayOrder(order)?.lastOrNull() to
            next?.tracksInPlayOrder(order)?.firstOrNull()
    }

    fun requestDelete(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pi = MediaStore.createDeleteRequest(contentResolver, uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else {
            // Off the main thread, and the outcome reported: these are synchronous ContentResolver
            // calls, one per file, and discarding the result meant a delete that failed was silent
            // while refreshLibrary() ran and the file reappeared in the list unexplained.
            lifecycleScope.launch {
                val failed = withContext(Dispatchers.IO) {
                    uris.count { runCatching { contentResolver.delete(it, null, null) }.getOrDefault(0) == 0 }
                }
                app.refreshLibrary()
                if (failed > 0) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.delete_failed, failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player.release()
    }

    override fun onSupportNavigateUp(): Boolean {
        // The player's back ARROW is NOT history navigation: it always opens the folder
        // the playing song lives in, with its ancestors stacked beneath — so repeated
        // taps walk UP the tree (album → ... → main folder → Library). Only the arrow:
        // the system back gesture still returns to wherever the player was opened from.
        if (navController.currentDestination?.id == R.id.playerFragment) {
            val dir = player.state.value.filePath.substringBeforeLast('/', "")
            if (dir.isNotEmpty()) {
                openFolderChain(dir)
                return true
            }
        }
        return navController.navigateUp(appBarConfig) || super.onSupportNavigateUp()
    }

    private fun setupMiniPlayer() = with(binding.miniPlayer) {
        root.setOnClickListener { openPlayer() }
        miniPlay.setOnClickListener { player.togglePlayPause() }
        miniNext.setOnClickListener { player.next() }
        miniPrev.setOnClickListener { player.previous() }
        miniTransport = TransportBinder(
            seek = miniSeek,
            position = miniPosition,
            duration = miniDuration,
            play = miniPlay,
        ) { player.seekTo(it) }
    }

    private fun updateMiniVisibility() {
        binding.miniPlayer.root.isVisible = lastPlayback?.hasItem == true && !onPlayerScreen
    }

    private fun bindMiniPlayer(s: UiPlayback) = with(binding.miniPlayer) {
        val prev = lastPlayback
        lastPlayback = s
        updateMiniVisibility()
        if (!s.hasItem) { miniArtMediaId = Long.MIN_VALUE; return }
        // Most emissions are 500ms position ticks; touch only the views whose source changed.
        // A bind after the no-item state re-sets everything.
        val fresh = prev == null || !prev.hasItem
        if (fresh || s.title != prev.title || s.filePath != prev.filePath || s.artist != prev.artist) {
            miniTitle.text = s.displayTitle
            miniSubtitle.text = s.artist
        }
        miniTransport.bind(s, prev?.takeIf { it.hasItem })
        // Only (re)load the cover when the track actually changes, otherwise it flickers
        // on every 500ms position tick. No art → no thumbnail (no generic placeholder).
        if (s.mediaId != miniArtMediaId) {
            miniArtMediaId = s.mediaId
            // Load via the track's content Uri so embedded-only covers are found too (and keyed
            // per TRACK — same album, different files, different embedded art is possible).
            val uri = if (s.mediaId >= 0) MediaRepository.trackUri(s.mediaId) else null
            ArtLoader.load(miniArt, this@MainActivity, uri, s.albumId) { miniArt.isVisible = it }
        }
    }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun ensureAudioPermission(firstCreate: Boolean) {
        val wanted = buildList {
            add(audioPermission())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val needed = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            // Only on a genuine first create. This activity declares no android:configChanges, so
            // onCreate runs again on every rotation, dark-mode switch and multi-window resize — and
            // refreshLibrary() discards the track list, folder tree, traversal cache AND the whole
            // art LruCache, so a rotation was re-querying MediaStore and re-decoding every cover.
            if (firstCreate) app.refreshLibrary()
        } else {
            permLauncher.launch(needed.toTypedArray())
        }
    }

    /**
     * A refused audio permission used to be a dead end: the launcher callback did nothing,
     * [ensureAudioPermission] is only reachable from onCreate, and the browse screens' empty state
     * reads "no results" — so the library just looked empty, with no explanation and no way to
     * re-ask short of force-stopping the app.
     */
    private fun showPermissionRationale() {
        Dialogs.confirm(
            this,
            titleRes = R.string.perm_needed_title,
            message = getString(R.string.perm_needed_body),
            confirmRes = R.string.perm_open_settings,
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }
    }

    private companion object {
        /** Compiled once: [friendlyPath] runs on every navigation and every song change. */
        val STORAGE_EMULATED = Regex("^/storage/emulated/\\d+/?")
        val STORAGE_VOLUME = Regex("^/storage/[^/]+/?")
    }
}

package com.timbra.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.launch
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

    /** The player is a transient overlay: opening it never stacks a second copy, and
     *  backing out of it returns to the browse screen beneath — never to another player. */
    private val playerNavOptions by lazy {
        navOptions {
            launchSingleTop = true
            popUpTo(R.id.playerFragment) { inclusive = true }
        }
    }

    /** Overflow items that just open a screen, single-top. */
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
        }

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) app.refreshLibrary()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        setupActionBarWithNavController(navController, appBarConfig)
        addGlobalMenu()
        navController.addOnDestinationChangedListener { _, dest, args ->
            // The full player screen already shows the deck, so hide the mini-player there.
            onPlayerScreen = dest.id == R.id.playerFragment
            updateMiniVisibility()
            // Under the folder name, show the path down to (but not including) this folder.
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
        ensureAudioPermission()

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
        player.connect {
            if (!player.isQueueEmpty()) openPlayerOnce() else maybeRestorePlayback()
        }
    }

    /** After the app is reopened with no live playback, reload the last saved queue (paused). */
    private fun maybeRestorePlayback() {
        val saved = player.loadSavedState() ?: return
        lifecycleScope.launch {
            val byId = repository.allTracks().associateBy { it.id }
            // Keep tracks + enqueued flags aligned while dropping any tracks that no longer exist.
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
            player.restore(tracks, enqueuedFlags, index, positionMs,
                saved.shuffleOrdinal, saved.repeatOrdinal)
            openPlayerOnce()
        }
    }

    /** On a cold launch, open the full player once if there is a current song. */
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

    /** Search + Equalizer are available from every screen's overflow menu (incl. the player). */
    private fun addGlobalMenu() = addMenuProvider(object : MenuProvider {
        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
            inflater.inflate(R.menu.menu_global, menu)
        }

        override fun onPrepareMenu(menu: Menu) {
            // On the equalizer screen the overflow shows only its own Reset action.
            val onEqScreen = navController.currentDestination?.id == R.id.equalizerFragment
            globalMenuTargets.keys.forEach { menu.findItem(it)?.isVisible = !onEqScreen }
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean {
            val target = globalMenuTargets[item.itemId] ?: return false
            if (navController.currentDestination?.id != target) {
                navController.navigate(target, null, navOptions { launchSingleTop = true })
            }
            return true
        }
    })

    /** Open the full player (single top, never stacked). */
    fun openPlayer() = navController.navigate(R.id.playerFragment, null, playerNavOptions)

    /**
     * Open [targetDir] as if the user had drilled into it: rebuilds the back stack with
     * the folder's ancestors (Library → Folders root → … → target) so Back walks up the
     * folder tree instead of jumping straight back to the Library.
     */
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
            // Reset to just the Library, then push the Folders root and each ancestor.
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

    /** Friendly ancestor path (storage prefix stripped) shown under the folder name. */
    private fun breadcrumbFor(folderPath: String): String? {
        if (folderPath.isBlank()) return null
        return friendlyPath(folderPath.substringBeforeLast('/', ""))
    }

    /** [path] with the storage prefix stripped, for display in toolbars; null when empty. */
    fun friendlyPath(path: String): String? = path
        .replaceFirst(STORAGE_EMULATED, "")
        .replaceFirst(STORAGE_VOLUME, "")
        .trim('/')
        .ifBlank { null }

    /** Drives the toolbar title marquee; bound lazily to the Toolbar's (shared) title view. */
    private var toolbarMarquee: TitleMarquee? = null

    /**
     * Set the toolbar title and marquee-scroll it ONCE when it doesn't fit (long folder
     * paths on the player); tapping the title scrolls it once more. The Toolbar owns the
     * title text (via the action bar) but reuses one internal TextView, so the marquee
     * controller is rebound only if that view instance actually changes.
     */
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

    /**
     * [dir] relative to the folder-tree root, for the player toolbar. The root folder's
     * own name is omitted — every song lives under it, so it says nothing. Null when
     * [dir] IS the root (or blank); paths outside the tree fall back to [friendlyPath].
     */
    suspend fun libraryRelativePath(dir: String): String? {
        if (dir.isBlank()) return null
        val root = repository.folderRoot().path
        return when {
            dir == root -> null
            dir.startsWith("$root/") -> dir.removePrefix("$root/").ifBlank { null }
            else -> friendlyPath(dir)
        }
    }

    /**
     * Advance-List walk to the next ([forward] = true) or previous song-folder — next from
     * its first song, previous from its last (timeline walking). Used by the deck's swipe onto
     * a phantom card, which needs to know whether anything actually moved; every other trigger
     * (a queue that ends, Next/Previous from anywhere) is handled service-side by the same
     * [FolderAdvance]. Returns false (a no-op) at the library's edges or when nothing is playing.
     */
    suspend fun advanceFolder(
        forward: Boolean,
        expectedGen: Int = app.session.queueGeneration,
    ): Boolean = player.moveFolder(this, forward, expectedGen) != null

    /**
     * Vertical-swipe folder jump: the next/previous song-folder in the flat traversal
     * order. Unlike [advanceFolder] this is a direct jump, not timeline walking, so BOTH
     * directions enter at the folder's FIRST song — or a random one when shuffle is on
     * (the folder is the new pool). Works in any repeat mode and preserves play/pause.
     * Returns the folder's name, or null on a no-op.
     */
    suspend fun jumpToNeighbourFolder(forward: Boolean): String? =
        player.moveFolder(this, forward, app.session.queueGeneration) { tracks ->
            if (player.state.value.shuffle != ShuffleMode.OFF) tracks.indices.random() else 0
        }?.name

    /**
     * The (previous, next) song-folders around the one being played, in the flat traversal
     * order ([MediaRepository.songFolders]); nulls at the library's edges or when nothing
     * is playing. Anchored on the folder a jump/advance last loaded
     * ([com.timbra.player.PlaybackSession.folderContext]); when that is absent — or STALE, i.e.
     * no longer in the rebuilt tree after a rescan — it falls back to the playing file's own
     * directory, which always directly contains that file and so is itself a song-folder entry.
     */
    private suspend fun neighbourSongFolders(): Pair<FolderNode?, FolderNode?> {
        val filePath = player.state.value.filePath
        if (filePath.isBlank()) return null to null
        return FolderTreeBuilder.neighbourFolders(
            repository.songFolders(),
            app.session.folderContext,
            filePath.substringBeforeLast('/', ""),
        )
    }

    /**
     * The neighbour-folder songs used for Advance-List phantom art, from one traversal
     * lookup: (previous folder's last song, next folder's first song), either null if absent.
     *
     * Taken from the ends of the SAME list the advance itself builds. minWith/maxWith over the
     * unsorted folder returned the FIRST of a comparator tie while the advance takes the LAST
     * (stable sort) — and naturalCompare reports 0 for names differing only by leading zeros or
     * case — so the previewed card could show one file's art and the swipe play another's.
     */
    suspend fun neighbourFolderSongs(): Pair<Track?, Track?> {
        val (prev, next) = neighbourSongFolders()
        val order = folderSort.sortOrder
        return prev?.tracksInPlayOrder(order)?.lastOrNull() to
            next?.tracksInPlayOrder(order)?.firstOrNull()
    }

    /** Delete files from storage (system shows its own confirmation on API 30+). */
    fun requestDelete(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pi = MediaStore.createDeleteRequest(contentResolver, uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else {
            uris.forEach { runCatching { contentResolver.delete(it, null, null) } }
            app.refreshLibrary()
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

    // --- Mini player ---

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

    // --- Permissions ---

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun ensureAudioPermission() {
        val wanted = buildList {
            add(audioPermission())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val needed = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) app.refreshLibrary() else permLauncher.launch(needed.toTypedArray())
    }

    private companion object {
        /** Compiled once: [friendlyPath] runs on every navigation and every song change. */
        val STORAGE_EMULATED = Regex("^/storage/emulated/\\d+/?")
        val STORAGE_VOLUME = Regex("^/storage/[^/]+/?")
    }
}

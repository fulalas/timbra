package com.timbra.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.core.view.doOnLayout
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
import com.timbra.repository
import com.timbra.data.SortDefaults
import com.timbra.data.comparatorFor
import com.timbra.data.model.FolderNode
import com.timbra.data.model.Track
import com.timbra.data.sortedBy
import com.timbra.databinding.ActivityMainBinding
import com.timbra.player.PlayerConnection
import com.timbra.player.ShuffleMode
import com.timbra.player.UiPlayback
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var player: PlayerConnection
        private set

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

    private var onPlayerScreen = false
    private var lastPlayback = UiPlayback()
    private var miniUserSeeking = false
    private var miniArtAlbumId = Long.MIN_VALUE

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
                titleScroll = null
                binding.toolbar.post {
                    toolbarTitleView()?.apply {
                        setHorizontallyScrolling(false)
                        ellipsize = TextUtils.TruncateAt.END
                        scrollTo(0, 0)
                        setOnClickListener(null)
                        isClickable = false
                    }
                }
            }
        }

        player = PlayerConnection(this)
        player.onQueueEnded = { advanceToNextFolder() }
        player.onQueueStart = { advanceToPrevFolder() }
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
            val tracks = ArrayList<com.timbra.data.model.Track>()
            val enqueuedFlags = ArrayList<Boolean>()
            saved.trackIds.forEachIndexed { i, id ->
                val t = byId[id] ?: return@forEachIndexed
                tracks.add(t)
                enqueuedFlags.add(i in enqSet)
            }
            if (tracks.isNotEmpty()) {
                // Some saved tracks may be gone (deleted/rescanned); remap the index to the
                // surviving position of the track that was actually current.
                val currentId = saved.trackIds.getOrNull(saved.index)
                val index = tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
                player.restore(tracks, enqueuedFlags, index, saved.positionMs,
                    saved.shuffleOrdinal, saved.repeatOrdinal)
                openPlayerOnce()
            }
        }
    }

    /** On a cold launch, open the full player once if there is a current song. */
    private fun openPlayerOnce() {
        if (app.openedPlayerThisLaunch) return
        if (navController.currentDestination?.id != R.id.libraryFragment) return
        app.openedPlayerThisLaunch = true
        navController.navigate(R.id.playerFragment, null, playerNavOptions)
    }

    /** Search is available from every screen's overflow menu (incl. the player). */
    private fun addGlobalMenu() = addMenuProvider(object : MenuProvider {
        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
            inflater.inflate(R.menu.menu_global, menu)
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
            R.id.action_search -> {
                if (navController.currentDestination?.id != R.id.searchFragment) {
                    navController.navigate(R.id.searchFragment, null, navOptions { launchSingleTop = true })
                }
                true
            }
            else -> false
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
            val rel = if (targetDir.startsWith(root.path)) targetDir.removePrefix(root.path) else ""
            val segments = rel.split('/').filter { it.isNotEmpty() }
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
        .replaceFirst(Regex("^/storage/emulated/\\d+/?"), "")
        .replaceFirst(Regex("^/storage/[^/]+/?"), "")
        .trim('/')
        .ifBlank { null }

    /** The in-flight title marquee run; replacing it cancels the old one. */
    private var titleScroll: Runnable? = null

    /** The clean (single) title, so a tap or a re-run never doubles an already-doubled
     *  string, and leaving the screen can restore it (see [toolbarTitleView] users). */
    private var titleText: String = ""

    /**
     * Set the toolbar title and marquee-scroll it ONCE when it doesn't fit (long folder
     * paths on the player); tapping the title scrolls it once more. Hand-rolled on
     * postOnAnimation rather than the stock TextView marquee: the stock speed is a fixed
     * private constant (we want it faster), and frame-stepped scrolling also ignores the
     * dev device's zeroed animator scales.
     */
    fun setMarqueeTitle(title: String) {
        titleText = title
        supportActionBar?.title = title
        binding.toolbar.post {
            val tv = toolbarTitleView() ?: return@post
            tv.ellipsize = null // ellipsizing would shrink the text layout; we scroll it
            tv.setHorizontallyScrolling(true)
            scrollTitleOnce(tv)
            tv.setOnClickListener { scrollTitleOnce(tv) }
        }
    }

    /**
     * Marquee the title ONE full loop, then stop — the beginning scrolls off the left and
     * wraps around from the right, landing back at rest (no jarring snap-to-start).
     *
     * A plain TextView can't draw a wrap-around ghost, so we give it a doubled string
     * ("title <gap> title") and scroll by exactly one copy+gap: at the end the SECOND copy
     * sits precisely where the first began, so restoring the single title is invisible.
     *
     * [doOnLayout] is the readiness signal — setting the title invalidates the text layout
     * and schedules a re-layout; measuring before it runs (the screen-entry case) would
     * read a stale width as "fits" and never start. It fires immediately when already laid
     * out (the tap case).
     */
    private fun scrollTitleOnce(tv: TextView) {
        titleScroll = null
        tv.text = titleText // reset in case a prior interrupted run left it doubled
        tv.scrollTo(0, 0)
        tv.doOnLayout {
            val viewport = tv.width - tv.paddingLeft - tv.paddingRight
            val lineWidth = tv.paint.measureText(titleText)
            if (lineWidth <= viewport) { titleScroll = null; return@doOnLayout } // fits — no scroll

            // Ghost copy separated by a gap, so the wrap reads as one continuous loop.
            val gapPx = TITLE_MARQUEE_GAP_DP * resources.displayMetrics.density
            val spaceW = tv.paint.measureText(" ").coerceAtLeast(1f)
            val nSpaces = (gapPx / spaceW).toInt().coerceAtLeast(1)
            val doubled = titleText + " ".repeat(nSpaces) + titleText
            tv.text = doubled
            // The wrap point: distance to bring the second copy's start to the left edge.
            val distance = lineWidth + nSpaces * spaceW
            val outMs = distance / (TITLE_MARQUEE_DP_S * resources.displayMetrics.density / 1000f)
            val t0 = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
            val run = object : Runnable {
                override fun run() {
                    // Die out when replaced, or when navigation swapped the title underneath.
                    if (titleScroll !== this || tv.text !== doubled) return
                    val t = android.view.animation.AnimationUtils.currentAnimationTimeMillis() - t0
                    if (t < TITLE_MARQUEE_START_HOLD_MS) { // brief readable pause on the start
                        tv.postOnAnimation(this); return
                    }
                    val p = (t - TITLE_MARQUEE_START_HOLD_MS) / outMs
                    if (p < 1f) {
                        tv.scrollTo((distance * p).toInt(), 0)
                        tv.postOnAnimation(this)
                    } else {
                        // Landed on the second copy's start = identical to the first at rest;
                        // restore the single title and snap to 0, seamless.
                        tv.text = titleText
                        tv.scrollTo(0, 0)
                        titleScroll = null
                    }
                }
            }
            titleScroll = run
            tv.postOnAnimation(run)
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

    /** Serializes all folder navigation, so two moves can't interleave mid-computation. */
    private val folderNavMutex = Mutex()

    /**
     * Advance-List repeat: continue with the next song-folder. Triggered when the queue
     * ends on its own or when Next is pressed on the last song (fire-and-forget). The
     * generation is captured HERE, at the moment of the trigger — if another navigation
     * (e.g. a vertical swipe racing the natural queue end) replaces the queue first, this
     * advance is superseded and must not stack a second move on top.
     */
    fun advanceToNextFolder() {
        val gen = player.queueGeneration
        lifecycleScope.launch { advanceFolder(forward = true, expectedGen = gen) }
    }

    /** Advance-List: continue with the previous song-folder (fire-and-forget). */
    fun advanceToPrevFolder() {
        val gen = player.queueGeneration
        lifecycleScope.launch { advanceFolder(forward = false, expectedGen = gen) }
    }

    /**
     * Advance-List walk to the next ([forward] = true) or previous song-folder — next from
     * its first song, previous from its last (timeline walking). Returns false (a no-op) at
     * the library's edges or when nothing is playing, so a caller tracking a pending advance
     * can recover. Preserves play/pause: a queue that ended on its own keeps playing
     * (playWhenReady is still set), a manual Next/swipe advance from a paused song stays paused.
     */
    suspend fun advanceFolder(
        forward: Boolean,
        expectedGen: Int = player.queueGeneration,
    ): Boolean = navigateToNeighbourFolder(forward, expectedGen) { tracks ->
        if (forward) 0 else tracks.lastIndex
    } != null

    /**
     * Vertical-swipe folder jump: the next/previous song-folder in the flat traversal
     * order. Unlike [advanceFolder] this is a direct jump, not timeline walking, so BOTH
     * directions enter at the folder's FIRST song — or a random one when shuffle is on
     * (the folder is the new pool). Works in any repeat mode and preserves play/pause
     * (see [advanceFolder]). Returns the folder's name, or null on a no-op.
     */
    suspend fun jumpToNeighbourFolder(forward: Boolean): String? =
        navigateToNeighbourFolder(forward, player.queueGeneration) { tracks ->
            if (player.currentShuffle() != ShuffleMode.OFF) tracks.indices.random() else 0
        }?.name

    /**
     * The one folder-navigation path: move to the neighbouring song-folder and load its
     * tracks starting at [startOf]. Serialized by [folderNavMutex], and aborted when the
     * queue was already replaced since [expectedGen] was captured — the racing navigation
     * (auto-advance vs. swipe) that got there first stands; this one is superseded.
     * Returns the folder navigated to, or null on a no-op.
     */
    private suspend fun navigateToNeighbourFolder(
        forward: Boolean,
        expectedGen: Int,
        startOf: (List<Track>) -> Int,
    ): FolderNode? = folderNavMutex.withLock {
        if (expectedGen != player.queueGeneration) return null
        val (prev, next) = neighbourSongFolders()
        val target = (if (forward) next else prev) ?: return null
        val tracks = target.tracks.sortedBy(SortDefaults.FOLDER_SONGS)
        if (tracks.isEmpty()) return null
        // play() can refuse (controller released mid-flight, e.g. backgrounded): nothing
        // changed, so report the no-op instead of advancing the anchor/toast past reality.
        if (!player.play(tracks, startOf(tracks), play = false, folderContext = target.path)) {
            return null
        }
        // Shuffle-All's pool was the whole library; it is now this folder — which is what
        // Shuffle-Songs means. Keeps the mode icon truthful about the actual pool.
        if (player.currentShuffle() == ShuffleMode.ALL) player.setShuffle(ShuffleMode.CURRENT)
        target
    }

    /**
     * The (previous, next) song-folders around the one being played, in the flat traversal
     * order ([MediaRepository.songFolders]); nulls at the library's edges or when nothing
     * is playing. Anchored on the folder a jump/advance last loaded
     * ([PlayerConnection.folderContext]); when that is absent — or STALE, i.e. no longer in
     * the rebuilt tree after a rescan — it falls back to the playing file's own directory,
     * which always directly contains that file and so is itself a song-folder entry.
     */
    private suspend fun neighbourSongFolders(): Pair<FolderNode?, FolderNode?> {
        val filePath = player.state.value.filePath
        if (filePath.isBlank()) return null to null
        val folders = repository.songFolders()
        var idx = player.folderContext
            ?.let { ctx -> folders.indexOfFirst { it.path == ctx } } ?: -1
        if (idx < 0) {
            val dir = filePath.substringBeforeLast('/', "")
            idx = folders.indexOfFirst { it.path == dir }
        }
        if (idx < 0) return null to null
        return folders.getOrNull(idx - 1) to folders.getOrNull(idx + 1)
    }

    /**
     * The neighbour-folder songs used for Advance-List phantom art, from one traversal
     * lookup: (previous folder's last song, next folder's first song), either null if
     * absent. Same traversal as the advances themselves, so the phantom cards preview the
     * songs those swipes actually lead to. Uses minWith/maxWith rather than sorting each
     * whole folder just to read one end.
     */
    suspend fun neighbourFolderSongs(): Pair<Track?, Track?> {
        val cmp = comparatorFor(SortDefaults.FOLDER_SONGS)
        val (prev, next) = neighbourSongFolders()
        return prev?.tracks?.maxWithOrNull(cmp) to next?.tracks?.minWithOrNull(cmp)
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
        // Drag the mini timeline to seek.
        miniSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) miniPosition.text = Format.clock(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar) { miniUserSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                miniUserSeeking = false
                player.seekTo(sb.progress.toLong())
            }
        })
    }

    private fun updateMiniVisibility() {
        binding.miniPlayer.root.isVisible = lastPlayback.hasItem && !onPlayerScreen
    }

    private fun bindMiniPlayer(s: UiPlayback) = with(binding.miniPlayer) {
        lastPlayback = s
        updateMiniVisibility()
        if (!s.hasItem) { miniArtAlbumId = Long.MIN_VALUE; return }
        miniTitle.text = s.title.ifBlank { getString(R.string.nothing_playing) }
        miniSubtitle.text = s.artist
        miniPlay.setImageResource(if (s.isPlaying) R.drawable.deck_pause else R.drawable.deck_play)
        miniSeek.max = s.durationMs.toInt().coerceAtLeast(1)
        miniDuration.text = Format.clock(s.durationMs)
        if (!miniUserSeeking) {
            miniSeek.progress = s.positionMs.toInt().coerceIn(0, miniSeek.max)
            miniPosition.text = Format.clock(s.positionMs)
        }
        // Only (re)load the cover when the track actually changes, otherwise it flickers
        // on every 500ms position tick. No art → no thumbnail (no generic placeholder).
        if (s.albumId != miniArtAlbumId) {
            miniArtAlbumId = s.albumId
            ArtLoader.load(miniArt, this@MainActivity, null, s.albumId) { miniArt.isVisible = it }
        }
    }

    // --- Permissions ---

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun ensureAudioPermission() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, audioPermission())
                != PackageManager.PERMISSION_GRANTED
            ) add(audioPermission())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) app.refreshLibrary() else permLauncher.launch(needed.toTypedArray())
    }

    private companion object {
        /** Title marquee speed (dp/s). Stock TextView marquee is 30dp/s; this is faster. */
        const val TITLE_MARQUEE_DP_S = 53.3f

        /** Gap between the end of the title and its wrapped-around start, during the loop. */
        const val TITLE_MARQUEE_GAP_DP = 48f

        /** Brief readable pause on the start before the loop begins. */
        const val TITLE_MARQUEE_START_HOLD_MS = 500L
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.timbra.R
import com.timbra.app
import com.timbra.data.model.Track
import com.timbra.databinding.FragmentPlayerBinding
import com.timbra.player.QueueItem
import com.timbra.player.RepeatMode
import com.timbra.player.ShuffleMode
import com.timbra.player.UiPlayback
import com.timbra.player.cycleNext
import com.timbra.repository
import com.timbra.ui.Format
import com.timbra.ui.MainActivity
import com.timbra.ui.Popup
import com.timbra.ui.TitleMarquee
import com.timbra.ui.TransportBinder
import com.timbra.ui.player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

class PlayerFragment : Fragment() {

    private var _b: FragmentPlayerBinding? = null
    private val b get() = _b!!

    private val c get() = b.controls

    private lateinit var artAdapter: ArtPagerAdapter
    private lateinit var transport: TransportBinder

    private var currentFilePath = ""

    private val main get() = requireActivity() as MainActivity

    private var titleMarquee: TitleMarquee? = null
    private var boundTitle: String? = null

    private val playerIndex get() = player.state.value.queueIndex

    private var pagerSynced = false

    private var lastLiveSeq = 0

    private var pagerIdle = true

    private var deckRv: RecyclerView? = null

    /**
     * True once the user has physically dragged since the pager last settled. onPageSelected
     * fires for programmatic moves and data-change clamps too (e.g. when a folder advance
     * shrinks the queue and ViewPager2 clamps onto the new phantom) — those must NOT seek or
     * trigger another advance, so page changes only act when they follow a real drag.
     */
    private var sawDrag = false

    private val queueItems: List<QueueItem> get() = player.queue.value

    private var phantomPrev: QueueItem? = null
    private var phantomNext: QueueItem? = null
    private var phantomKey: String? = null

    private val leadOffset get() = if (phantomPrev != null) 1 else 0

    /**
     * True from the moment a swipe onto a phantom card triggers a folder advance until it is
     * finalized. While set, page selections are ignored so a fling can't cascade through
     * several folders as the queue swaps underneath it.
     */
    private var advancing = false

    private var advanceReady = false

    private var lastBoundMediaId = -1L

    /** The state [bind] last applied. Most emissions are 500ms position ticks, so the static
     *  views (icons, subtitle, duration) are re-set only when their source fields changed —
     *  setImageResource in particular reloads + invalidates even for an unchanged res id.
     *  Null forces a full re-apply (fresh view, foreground return). */
    private var lastBound: UiPlayback? = null

    /**
     * A deck rebuild requested while the pager was mid-gesture. Mutating the pages during a
     * drag/fling shifts positions under the finger and fires spurious onPageSelected events
     * (which once seeked playback backwards) — so rebuilds wait for the pager to settle.
     */
    private var pendingRebuild = false

    /**
     * The folder advance to run once the swipe settles on the phantom. Deferred (not run at
     * the moment of the page selection) so the queue swap doesn't happen mid-fling — otherwise
     * the freshly-added pages let the fling sail past the phantom onto the wrong song.
     */
    private var pendingAdvance: (() -> Unit)? = null

    private var folderJumping = false

    /** True while [runAdvance]'s advanceFolder call is actually suspended. Distinguishes a
     *  LIVE advance (pendingAdvance already consumed, queue swap still coming — must not be
     *  disturbed) from a STRANDED `advancing` latch that the self-heal may safely clear. */
    private var advanceInFlight = false

    private var vDragging = false

    /** True while [cycleShuffle]'s library load is still suspended, so a second tap can't be
     *  computed from the mode the first one hasn't applied yet. */
    private var shuffleCycleInFlight = false

    private var deckGlide: Runnable? = null

    private var onDeckCommitted: (() -> Unit)? = null

    /** Physical flick speed (px/s) that commits a folder jump — density-scaled so the same
     *  physical gesture commits on every screen (a raw px/s constant would be ~3x more
     *  sensitive on xxhdpi than mdpi). */
    private val commitFlingPxS by lazy { COMMIT_FLING_DP_S * resources.displayMetrics.density }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentPlayerBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        main.supportActionBar?.title = ""
        currentFilePath = ""

        // The fragment instance survives on the back stack but the pager view is fresh
        // (position 0), so reset all transient pager/advance state — otherwise stale flags
        // (e.g. advancing==true, a matching phantomKey) carry into the recreated view and
        // freeze it or suppress the phantom rebuild. The first alignment then snaps.
        pagerSynced = false
        pagerIdle = true
        advancing = false
        advanceReady = false
        advanceInFlight = false
        pendingAdvance = null
        folderJumping = false
        vDragging = false
        shuffleCycleInFlight = false
        deckGlide = null
        onDeckCommitted = null
        sawDrag = false
        lastBoundMediaId = -1L
        lastBound = null
        pendingRebuild = false
        phantomPrev = null
        phantomNext = null
        phantomKey = null
        titleMarquee = TitleMarquee(c.title)
        boundTitle = null

        artAdapter = ArtPagerAdapter(viewLifecycleOwner)
        b.artPager.adapter = artAdapter
        b.artPager.offscreenPageLimit = 1
        // The art deck sits flush against a screen edge (the left edge in the landscape
        // two-pane layout, both edges in portrait) — the system back-gesture zone. Claim a
        // gesture-exclusion rect so a swipe that starts near the edge reaches the pager
        // instead of being stolen by edge-navigation. The OS silently grants at most 200dp
        // per edge (taken bottom-up from the requested rects), so a full-deck rect would
        // only protect the deck's LOWEST 200dp — request a 200dp band centred on the deck
        // instead, covering where card swipes actually start. API 29+ (back gesture = Q).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.artPager.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val h = bottom - top
                val band = (200 * resources.displayMetrics.density).toInt().coerceAtMost(h)
                val bandTop = (h - band) / 2
                v.systemGestureExclusionRects = listOf(Rect(0, bandTop, right - left, bandTop + band))
            }
        }
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        val vDragListener = object : RecyclerView.OnItemTouchListener {
            private var downRawX = 0f
            private var anchorY = 0f
            private var lastY = 0f
            private var lastT = 0L
            private var vel = 0f
            private var activePointerId = MotionEvent.INVALID_POINTER_ID

            override fun onInterceptTouchEvent(v: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        activePointerId = e.getPointerId(0)
                        downRawX = e.rawX
                        anchorY = e.rawY
                        lastY = e.rawY
                        lastT = e.eventTime
                        vel = 0f
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!advancing && !folderJumping &&
                            v.scrollState == RecyclerView.SCROLL_STATE_IDLE
                        ) {
                            val dx = e.rawX - downRawX
                            val dy = e.rawY - anchorY
                            if (abs(dy) > touchSlop && abs(dy) > 2 * abs(dx)) {
                                vDragging = true
                                deckGlide = null
                                // Re-anchor at the claim point so the deck doesn't hop by
                                // the slop distance, but keep any caught glide offset.
                                anchorY = e.rawY - b.artPager.translationY
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                return true
                            }
                        }
                    }
                }
                return false
            }

            override fun onTouchEvent(v: RecyclerView, e: MotionEvent) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> if (vDragging) {
                        val dt = (e.eventTime - lastT).toFloat()
                        if (dt > 0) vel = 0.6f * ((e.rawY - lastY) / dt) + 0.4f * vel
                        lastY = e.rawY
                        lastT = e.eventTime
                        val h = b.artPager.height.toFloat()
                        b.artPager.translationY = (e.rawY - anchorY).coerceIn(-h, h)
                    }
                    // The finger that owns the drag lifted while another is still down:
                    // settle NOW and ignore the stream's remainder. Pointer indices compact
                    // on a lift, so from the next MOVE e.rawY would silently be the OTHER
                    // finger — teleporting the deck and spiking the velocity into a false
                    // fling-commit.
                    MotionEvent.ACTION_POINTER_UP ->
                        if (vDragging && e.getPointerId(e.actionIndex) == activePointerId) {
                            vDragging = false
                            settleVerticalDrag(vel)
                        }
                    MotionEvent.ACTION_UP -> if (vDragging) {
                        vDragging = false
                        settleVerticalDrag(vel)
                    }
                    MotionEvent.ACTION_CANCEL -> if (vDragging) {
                        vDragging = false
                        glideDeckTo(0f) { afterVerticalDrag() }
                    }
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        }
        // Kill the default item-change animation on the pager's RecyclerView. When a folder
        // advance swaps the queue, its ~230ms remove/insert animation renders the changing
        // pages empty (black) mid-transition — the flicker seen at the end of the swipe.
        for (i in 0 until b.artPager.childCount) {
            val rv = b.artPager.getChildAt(i) as? RecyclerView ?: continue
            deckRv = rv
            rv.itemAnimator = null
            rv.addOnItemTouchListener(vDragListener)
        }
        b.artPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // Only a real user drag may change the track; programmatic moves and the clamp
                // ViewPager2 does when the queue shrinks also land here and must be ignored.
                // Once an advance is pending, ignore everything until the pager settles: a fling
                // stays in SETTLING across the queue swap and would otherwise carry onto the new
                // folder's phantom and advance again (and again), cascading through folders.
                if (!sawDrag || advancing) return
                // Consume the drag, so follow-up selection events from the deck rebuild are
                // never mistaken for another user action.
                if (player.state.value.shuffle != ShuffleMode.OFF) {
                    when {
                        position < leadOffset -> { sawDrag = false; player.previousSong() }
                        position > leadOffset -> {
                            sawDrag = false
                            if (player.hasNext()) player.next()
                            else armAdvance(forward = true)
                        }
                    }
                    return
                }
                if (phantomPrev != null && position == 0) {
                    armAdvance(forward = false); return
                }
                if (phantomNext != null && position == leadOffset + queueItems.size) {
                    armAdvance(forward = true); return
                }
                // Anything but a one-page move is not a user swipe (programmatic moves and
                // clamps land here too) and must not touch playback.
                when ((position - leadOffset) - playerIndex) {
                    +1 -> player.next()
                    -1 -> player.previousSong()
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    sawDrag = true
                    // Self-heal: `advancing` still set when a NEW user drag starts — with no
                    // advance armed (pendingAdvance == null) AND none actually running
                    // (advanceInFlight == false; clearing a LIVE advance would let its queue
                    // swap land mid-drag and corrupt the gesture) — is a stranded latch from
                    // an advance that never finalized. Clear it so this gesture works instead
                    // of the deck staying dead until the screen is recreated. folderJumping
                    // needs no heal: jumpFolder's finally guarantees its release.
                    if (pendingAdvance == null && !advanceInFlight && !folderJumping && advancing) {
                        advancing = false; advanceReady = false
                    }
                }
                pagerIdle = state == ViewPager2.SCROLL_STATE_IDLE
                if (pagerIdle) {
                    _b?.artPager?.removeCallbacks(pagerIdleHeal)
                    sawDrag = false
                    when {
                        pendingAdvance != null -> { val go = pendingAdvance!!; pendingAdvance = null; go() }
                        advancing -> finalizeAdvanceIfReady()
                        else -> afterVerticalDrag()
                    }
                }
            }
        })

        c.play.setOnClickListener { player.togglePlayPause() }
        c.next.setOnClickListener { player.next() }
        c.prev.setOnClickListener { player.previous() }
        c.repeat.setOnClickListener { cycleRepeat() }
        c.shuffle.setOnClickListener { cycleShuffle() }

        c.info.setOnClickListener {
            val dir = currentFilePath.substringBeforeLast('/', "")
            if (dir.isNotEmpty()) main.openFolderChain(dir)
        }

        transport = TransportBinder(
            seek = c.seek,
            position = c.position,
            duration = c.duration,
            play = c.play,
        ) { player.seekTo(it) }

        viewLifecycleOwner.lifecycleScope.launch {
            // viewLifecycleOwner-qualified: unqualified this binds to the FRAGMENT's lifecycle,
            // while the block below deliberately resets VIEW-scoped state on each restart.
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Returning from the background re-runs this block and re-emits both
                // StateFlows, but the view (and pager) weren't recreated, so onViewCreated
                // didn't reset the flag. Snap the first realignment after every foreground
                // entry too, otherwise the re-emit animates a card flip for no reason.
                pagerSynced = false
                currentFilePath = ""
                boundTitle = null
                lastBound = null
                launch { player.queue.collect { bindQueue() } }
                launch { player.state.collect { bind(it) } }
            }
        }
    }

    private fun cycleRepeat() {
        val next = player.state.value.repeat.cycleNext()
        player.setRepeat(next)
        showModePopup(next.titleRes, next.subtitleRes)
    }

    private fun cycleShuffle() {
        if (shuffleCycleInFlight) return
        val next = player.state.value.shuffle.cycleNext()
        when (next) {
            ShuffleMode.CURRENT -> player.setShuffle(ShuffleMode.CURRENT)
            ShuffleMode.ALL -> launchShuffleChange {
                player.playAllShuffled(requireContext().repository.allTracks())
            }
            ShuffleMode.OFF -> launchShuffleChange {
                val byId = requireContext().repository.allTracks().associateBy { it.id }
                val tracks = player.preShuffleQueueIds().mapNotNull { byId[it] }
                player.disableShuffleRestoring(tracks)
            }
        }
        showModePopup(next.titleRes, next.subtitleRes)
    }

    private fun launchShuffleChange(block: suspend () -> Unit) {
        shuffleCycleInFlight = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                block()
            } finally {
                shuffleCycleInFlight = false
            }
        }
    }

    /**
     * The queue generation is captured HERE, at the moment of the gesture — not when the deferred
     * block finally runs. If the last track ends by itself during the fling, the service's own
     * advance lands first; reading the generation late would see its NEW value, pass the staleness
     * check and step a SECOND folder.
     */
    private fun armAdvance(forward: Boolean) {
        advancing = true
        advanceReady = false
        val gen = requireContext().app.session.queueGeneration
        pendingAdvance = { runAdvance(forward, gen) }
    }

    /**
     * Announce a mode change in the deck's own overlay, NOT a Toast: the system queues toasts and
     * plays each one for its full duration, so cycling the shuffle/repeat button quickly replayed
     * the backlog and the message on screen lagged several taps behind the actual mode.
     */
    private fun showModePopup(msg: String) {
        Popup.show(_b?.modePopup ?: return, msg)
    }

    private fun showModePopup(titleRes: Int, subRes: Int?) = showModePopup(buildString {
        append(getString(titleRes))
        if (subRes != null) { append('\n'); append(getString(subRes)) }
    })

    private fun pagePosOf(index: Int): Int =
        if (player.state.value.shuffle != ShuffleMode.OFF) leadOffset else index + leadOffset

    private fun syncPager(index: Int, animate: Boolean, force: Boolean = false) {
        if (index < 0) return
        if (!force) {
            // During a folder advance the pager position is owned by the advance (it stays on the
            // phantom until finalizeAdvanceIfReady jumps it). A state-driven sync here would run
            // before the queue rebuild and land on the OLD queue's index — a wrong-folder flash.
            if (advancing) return
            if (!pagerIdle || vDragging) return
        }
        val pos = pagePosOf(index)
        if (pos !in 0 until artAdapter.itemCount) return
        // During a vertical folder jump the deck is off-screen: reposition by snapping — an
        // animated flip could still be mid-scroll when the deck slides back into view.
        if (b.artPager.currentItem != pos) {
            b.artPager.setCurrentItem(pos, animate && !force && !folderJumping)
        }
        pagerSynced = true
    }

    /**
     * Both steps are needed. setCurrentItem keeps ViewPager2's own position honest, but it NO-OPS
     * when that position is stale-equal to the target: it is a plain page index, and a restructured
     * list (the timeline deck swapped for the 3-card shuffle one, or back) moves the pages under it.
     * And a restructured deck has no surviving anchor child, so RecyclerView falls back to laying
     * out from page 0 — a card nothing has decoded yet, which paints blank + brand for a frame or
     * two: the flicker on the shuffle/repeat buttons. scrollToPosition on the LayoutManager is what
     * actually pins the layout, and is a visual no-op when the deck is already resting there.
     */
    private fun alignDeck() {
        if (advancing || !pagerIdle || vDragging) return
        if (playerIndex < 0) return
        val pos = pagePosOf(playerIndex)
        if (pos !in 0 until artAdapter.itemCount) return
        if (b.artPager.currentItem != pos) b.artPager.setCurrentItem(pos, false)
        deckRv?.layoutManager?.scrollToPosition(pos)
        pagerSynced = true
    }

    private fun bindQueue() {
        val landingAdvance = advancing
        phantomKey = null
        updatePhantom(player.state.value)
        rebuildPages(landingAdvance)
    }

    /**
     * submitList commits the diff asynchronously, so itemCount only reflects the new list once
     * the commit callback fires: align the pager there, otherwise the first load (empty -> N)
     * would read a stale count.
     */
    private fun rebuildPages(landingAdvance: Boolean = false) {
        // Never mutate the deck under a live gesture — horizontal (positions shift beneath
        // the finger and fire spurious selections) or a held vertical drag; the settle
        // handlers apply the deferred rebuild ([afterVerticalDrag]). A landing folder
        // advance is the exception — that swap is what the settle is waiting on.
        if ((!pagerIdle || vDragging) && !landingAdvance) { pendingRebuild = true; return }
        val items = queueItems
        val current = items.getOrNull(playerIndex)
        val pages = ArrayList<QueueItem>(items.size + 2)
        if (player.state.value.shuffle != ShuffleMode.OFF && current != null) {
            phantomPrev?.let { pages.add(it) }
            pages.add(current.copy(timelineIndex = SHUFFLE_CARD_INDEX))
            phantomNext?.let { pages.add(it) }
        } else {
            phantomPrev?.let { pages.add(it) }
            pages.addAll(items)
            phantomNext?.let { pages.add(it) }
        }
        // Mark the advance landing at SUBMIT time, not in the commit callback: AsyncListDiffer
        // CANCELS a still-running diff — callback included — when a newer list is submitted,
        // and a phantom recompute racing in right behind the landing rebuild did exactly that.
        // With the flag set only in the cancelled callback, `advancing` stayed latched forever:
        // every pager sync blocked, deck parked on a neighbour's card (the wrong/blank cover).
        if (landingAdvance) advanceReady = true
        artAdapter.submitList(pages) {
            // submitList commits asynchronously; the view may already be torn down by the time
            // this runs (rapid nav / rotation), so bail before touching b/requireActivity().
            if (_b == null) return@submitList
            when {
                advancing -> finalizeAdvanceIfReady()
                // Aligned INSIDE the commit, not from a post: the scroll is then still pending
                // when the layout this list change already scheduled runs, so the deck lays out
                // straight onto the current song's page instead of painting a stray card first
                // (see [alignDeck]).
                else -> alignDeck()
            }
            onDeckCommitted?.invoke()
        }
    }

    private fun jumpFolder(forward: Boolean) {
        // The settle callback matters on this path too: settleVerticalDrag's COMMIT branch calls
        // us INSTEAD of glideDeckTo(0f) { afterVerticalDrag() }, relying on the finally block below
        // to apply what the held finger deferred — and returning here launches no coroutine, so
        // there is no finally. Without it a pendingRebuild stays stranded and the deck keeps a
        // stale page list whose leadOffset no longer matches, mis-decoding the next swipe.
        if (advancing || folderJumping) { glideDeckTo(0f) { afterVerticalDrag() }; return }
        folderJumping = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val oldId = player.state.value.mediaId
                val out = if (forward) -b.artPager.height.toFloat() else b.artPager.height.toFloat()
                val slideOut = CompletableDeferred<Unit>()
                // The outgoing animation runs on a bitmap SNAPSHOT of the deck; the live pager is
                // hidden for the whole swap. The jump's queue replacement rebinds the visible page
                // mid-glide (the new folder's cover used to paint as a band on the outgoing deck —
                // the flash), and view-recycling could briefly show a previously-visited cover.
                // With only a frozen snapshot on screen, no rebind can ever flash. Snapshot failure
                // (e.g. an unsnapshotable hardware bitmap) falls back to gliding the live pager.
                val overlay = runCatching {
                    // RGB_565: the snapshot is opaque, on screen for ~180ms and then dropped, so
                    // ARGB_8888 was allocating 4-8 MB per jump on the main thread against a heap
                    // the art cache has already claimed an eighth of.
                    val bmp = Bitmap.createBitmap(b.artPager.width, b.artPager.height, Bitmap.Config.RGB_565)
                    b.artPager.draw(Canvas(bmp))
                    ImageView(requireContext()).apply {
                        id = R.id.deck_snapshot
                        setImageBitmap(bmp)
                    }
                }.getOrNull()
                if (overlay != null) {
                    // Added UNDER the deck's own children (the pager is hidden below, so the
                    // snapshot is what shows): appended on top it covered the mode popup, hiding
                    // the folder-name announcement this jump makes for the whole glide.
                    b.deckWindow.addView(
                        overlay,
                        0,
                        FrameLayout.LayoutParams(b.artPager.width, b.artPager.height),
                    )
                    // draw() captures content untranslated — carry the finger's offset over so the
                    // hand-off is seamless, then park the hidden pager.
                    overlay.translationY = b.artPager.translationY
                    b.artPager.visibility = View.INVISIBLE
                    b.artPager.translationY = 0f
                    glideViewTo(overlay, out) { slideOut.complete(Unit) }
                } else {
                    glideDeckTo(out) { slideOut.complete(Unit) }
                }
                val folder = main.jumpToNeighbourFolder(forward)
                if (folder == null) {
                    overlay?.let { b.artPager.translationY = it.translationY }
                    glideDeckTo(0f)
                    return@launch
                }
                withTimeoutOrNull(LANDING_TIMEOUT_MS) {
                    player.state.first { it.mediaId != oldId }
                    // Deck committed = the adapter's list has the new current song on the page
                    // the pager will rest on. (Phantom recomputes may take one more commit.)
                    // Check first, THEN await the next commit: state and commit order isn't
                    // guaranteed, and a commit that already landed will never re-signal.
                    while (!deckShowsCurrent()) awaitDeckCommit()
                }
                showModePopup(folder)
                // Bounded: the glide's onEnd never fires if another glide replaced it, and an
                // unbounded await here would strand [folderJumping] and freeze the deck.
                withTimeoutOrNull(SLIDE_MS * 3) { slideOut.await() }
                overlay?.let { b.deckWindow.removeView(it) }
                // Land the hidden pager on the current song's page before revealing it, so the
                // slide-in can only ever show the new folder's own art.
                syncPager(playerIndex, animate = false, force = true)
                b.artPager.translationY = -out
                b.artPager.visibility = View.VISIBLE
                glideDeckTo(0f)
            } finally {
                // ALWAYS release the latch — a jumpToNeighbourFolder that throws, a detached
                // activity, or a cancelled coroutine would otherwise leave [folderJumping] set
                // forever, deadening every deck gesture until the screen is recreated. And never
                // leave the live pager hidden or a snapshot overlay behind (idempotent).
                folderJumping = false
                _b?.let { bb ->
                    bb.artPager.visibility = View.VISIBLE
                    // By id, not by index: the deck window also holds the mode popup, and an
                    // index-based sweep ("everything after the pager") deleted it for good.
                    for (i in bb.deckWindow.childCount - 1 downTo 0) {
                        if (bb.deckWindow.getChildAt(i).id == R.id.deck_snapshot) {
                            bb.deckWindow.removeViewAt(i)
                        }
                    }
                    // Apply anything the held finger deferred. The COMMIT branch of
                    // settleVerticalDrag doesn't run [afterVerticalDrag] (only the spring-back
                    // does), so a rebuild deferred during the drag was stranded whenever the
                    // jump turned out to be a no-op — leaving the deck on a stale page list
                    // whose leadOffset no longer matched, which mis-decodes the next swipe.
                    afterVerticalDrag()
                }
            }
        }
    }

    private fun deckShowsCurrent(): Boolean =
        artAdapter.currentList.getOrNull(pagePosOf(playerIndex))?.mediaId ==
            player.state.value.mediaId

    private suspend fun awaitDeckCommit() = suspendCancellableCoroutine { cont ->
        onDeckCommitted = { onDeckCommitted = null; cont.resume(Unit) }
        cont.invokeOnCancellation { onDeckCommitted = null }
    }

    private fun settleVerticalDrag(velPxPerMs: Float) {
        val ty = b.artPager.translationY
        val flung = abs(velPxPerMs) * 1000f >= commitFlingPxS && (velPxPerMs < 0f) == (ty < 0f)
        if (ty != 0f && (abs(ty) >= b.artPager.height / 4f || flung)) jumpFolder(forward = ty < 0f)
        else glideDeckTo(0f) { afterVerticalDrag() }
    }

    private fun afterVerticalDrag() {
        if (pendingRebuild) { pendingRebuild = false; rebuildPages() }
        else syncPager(playerIndex, animate = false)
    }

    /**
     * The matching SCROLL_STATE_IDLE only arrives while the pager keeps getting animation
     * frames, and a smooth scroll begun as the window stops drawing (screen off, a dialog over
     * the player) never delivers one. Every deck mutation is gated on [pagerIdle], so the deck
     * would sit frozen on a neighbour's cover — rebuilds piling into [pendingRebuild] — until the
     * next touch happened to reset it.
     */
    private fun markPagerBusy() {
        pagerIdle = false
        b.artPager.removeCallbacks(pagerIdleHeal)
        b.artPager.postDelayed(pagerIdleHeal, PAGER_SETTLE_TIMEOUT_MS)
    }

    private val pagerIdleHeal = Runnable {
        val bb = _b ?: return@Runnable
        if (pagerIdle || bb.artPager.scrollState != ViewPager2.SCROLL_STATE_IDLE) return@Runnable
        pagerIdle = true
        afterVerticalDrag()
    }

    private fun glideDeckTo(target: Float, onEnd: (() -> Unit)? = null) =
        glideViewTo(b.artPager, target, onEnd)

    private fun glideViewTo(view: View, target: Float, onEnd: (() -> Unit)? = null) {
        val start = view.translationY
        if (start == target) { deckGlide = null; onEnd?.invoke(); return }
        val t0 = AnimationUtils.currentAnimationTimeMillis()
        val glide = object : Runnable {
            override fun run() {
                if (_b == null || deckGlide !== this) return
                val f = ((AnimationUtils.currentAnimationTimeMillis() - t0).toFloat() / SLIDE_MS)
                    .coerceIn(0f, 1f)
                view.translationY = start + (target - start) * GLIDE_EASE.getInterpolation(f)
                if (f < 1f) view.postOnAnimation(this)
                else { deckGlide = null; onEnd?.invoke() }
            }
        }
        deckGlide = glide
        view.postOnAnimation(glide)
    }

    /**
     * Run the deferred folder advance. If it turns out to be a no-op (no neighbour folder, or
     * nothing playing), no queue change arrives to finalize it — so abandon it here, otherwise
     * [advancing] would stay set forever and freeze the pager.
     */
    private fun runAdvance(forward: Boolean, expectedGen: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            // A throwing advanceFolder (detached activity, repository error) would otherwise
            // leave [advancing] set forever and freeze the deck — treat failure as a no-op.
            advanceInFlight = true
            val advanced = try {
                main.advanceFolder(forward, expectedGen)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                false
            } finally {
                advanceInFlight = false
            }
            if (!advanced && advancing) abandonAdvance()
        }
    }

    private fun abandonAdvance() {
        advancing = false
        advanceReady = false
        pendingAdvance = null
        syncPager(playerIndex, animate = false)
    }

    private fun finalizeAdvanceIfReady() {
        if (!advancing || !advanceReady || !pagerIdle) return
        advancing = false
        advanceReady = false
        syncPager(playerIndex, animate = false, force = true)
    }

    private fun phantomOf(t: Track, sentinelIndex: Int) = QueueItem(
        mediaId = t.id, albumId = t.albumId, title = "", artist = "", album = "",
        filePath = "", timelineIndex = sentinelIndex, enqueued = false,
    )

    private fun updatePhantom(s: UiPlayback) {
        val dir = s.filePath.substringBeforeLast('/', "")
        if (s.shuffle != ShuffleMode.OFF && s.hasItem) {
            // Repeat-Song makes next/previousMediaItemIndex return the CURRENT index — that's
            // not a neighbour to preview (a card with the same cover whose swipe would just
            // restart the song); treat it as "none".
            val nextIdx = player.nextQueueIndex().takeIf { it != s.queueIndex } ?: -1
            val prevIdx = player.prevQueueIndex().takeIf { it != s.queueIndex } ?: -1
            val key = "shuf:$nextIdx:$prevIdx:${s.mediaId}:${queueItems.size}"
            if (key == phantomKey) return
            phantomKey = key
            phantomNext = queueItems.getOrNull(nextIdx)?.copy(timelineIndex = SHUFFLE_CARD_INDEX)
            phantomPrev = queueItems.getOrNull(prevIdx)?.copy(timelineIndex = SHUFFLE_CARD_INDEX)
            rebuildPages()
            if (phantomNext == null && s.repeat == RepeatMode.ADVANCE && dir.isNotEmpty()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val (_, next) = main.neighbourFolderSongs()
                    if (phantomKey != key) return@launch
                    phantomNext = next?.let { phantomOf(it, PHANTOM_NEXT_INDEX) }
                    rebuildPages()
                }
            }
            return
        }
        val key = if (s.repeat == RepeatMode.ADVANCE && dir.isNotEmpty()) dir else null
        if (key == phantomKey) return
        phantomKey = key
        if (key == null) {
            if (phantomPrev != null || phantomNext != null) {
                phantomPrev = null; phantomNext = null; rebuildPages()
            }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val (prev, next) = main.neighbourFolderSongs()
            // A newer state may have changed the target folder while we were computing.
            if (phantomKey != key) return@launch
            phantomNext = next?.let { phantomOf(it, PHANTOM_NEXT_INDEX) }
            phantomPrev = prev?.let { phantomOf(it, PHANTOM_PREV_INDEX) }
            rebuildPages()
        }
    }

    private fun bind(s: UiPlayback) {
        if (s.filePath != currentFilePath) {
            currentFilePath = s.filePath
            val dir = s.filePath.substringBeforeLast('/', "")
            viewLifecycleOwner.lifecycleScope.launch {
                val title = main.libraryRelativePath(dir) ?: ""
                // A newer song may have bound while the root was being looked up.
                if (_b != null && currentFilePath == s.filePath) {
                    main.setMarqueeTitle(title)
                }
            }
        }

        // Animate the card-flip only on a real track change (the playing SONG changed), keyed
        // on mediaId not queueIndex: repeat/shuffle toggles, phantom add/remove, and queue
        // rebuilds (Shuffle-All / restore-on-off) keep the same song but change its index —
        // those repositions are snapped by rebuildPages, so animating here would be spurious.
        // This runs BEFORE updatePhantom so the shuffle deck can flip onto the OLD edge card.
        val songChanged = s.mediaId != lastBoundMediaId
        if (songChanged) {
            lastBoundMediaId = s.mediaId
            // Animate the flip only for a LIVE transition — one ExoPlayer reported (AUTO end /
            // SEEK next-prev-tap) while we were connected, which advances liveTransitionSeq. A song
            // that changed while backgrounded arrives via a reconnect state-sync that does NOT
            // advance the sequence, so it snaps into place instead of spuriously flipping.
            val animate = pagerSynced && s.liveTransitionSeq != lastLiveSeq
            if (s.shuffle != ShuffleMode.OFF) {
                if (pagerIdle && !advancing && !vDragging) {
                    val target = when (s.mediaId) {
                        phantomNext?.mediaId -> leadOffset + 1
                        phantomPrev?.mediaId -> 0
                        else -> -1
                    }
                    if (target in 0 until artAdapter.itemCount && b.artPager.currentItem != target) {
                        b.artPager.setCurrentItem(target, animate)
                        // The SETTLING event may arrive after updatePhantom below would run —
                        // mark the pager busy NOW so the deck rebuild defers to the settle.
                        if (animate) markPagerBusy()
                    }
                    pagerSynced = true
                }
            } else {
                syncPager(s.queueIndex, animate = animate)
            }
        }
        // The queue flow lags the state flow by a message-loop turn ([PlayerConnection] posts its
        // queue rebuild), so a state pushed by a timeline REPLACEMENT — cycling shuffle to OFF
        // restores the pre-shuffle queue — carries an index into a queue nobody has published
        // yet. Every deck decision below would then read the OLD queue's song at the NEW index:
        // the deck was rebuilt around, and aligned onto, an unrelated card whose art isn't
        // cached, so the cover blanked until the real queue landed. Leaving it alone is safe —
        // a mismatch means the ids changed, so a queue emission is already posted, and
        // [bindQueue] does all of this from the pair that agrees.
        val queueLags = s.hasItem && queueItems.getOrNull(s.queueIndex)?.mediaId != s.mediaId
        if (!queueLags) {
            // Leaving shuffle swaps the 3-card shuffle deck back for the timeline deck, and
            // NOTHING ELSE guarantees that rebuild: updatePhantom only rebuilds when a phantom
            // card actually changed, and with repeat-Song both shuffle phantoms are already
            // "none" (next/previous MediaItemIndex return the current index under
            // REPEAT_MODE_ONE) — while the restored timeline can carry the very same ids in the
            // same order, so no queue emission arrives either. The deck then stayed a ONE-card
            // shuffle deck: swipes dead, art frozen.
            val leftShuffle = lastBound?.shuffle?.let { it != ShuffleMode.OFF } == true &&
                s.shuffle == ShuffleMode.OFF
            updatePhantom(s)
            if (leftShuffle) rebuildPages()

            // Belt-and-braces (runs every position tick): a phantom recompute can swap the
            // leading card underneath a RESTING pager — e.g. right after a folder jump,
            // replacing the previous-folder card can leave ViewPager2 parked on it, showing the
            // neighbour folder's cover while a different song plays — and nothing else
            // re-aligns a pager whose song did NOT change. syncPager's own gates (mid-gesture,
            // advancing, jumping, out-of-range) make this a strict no-op except when the deck
            // is at rest off the current page. Skipped on the song-change tick itself: that
            // bind may have just started an intentional flip onto an edge card (shuffle) that
            // must not be undone.
            if (s.hasItem && !songChanged && s.shuffle == ShuffleMode.OFF) {
                syncPager(s.queueIndex, animate = false)
            }
        }

        // Marquee the title only when it actually changes (bind runs every position tick, and
        // re-setting would restart the scroll from the top each time).
        val titleText = if (s.hasItem) s.displayTitle else getString(R.string.nothing_playing)
        if (titleText != boundTitle) {
            boundTitle = titleText
            titleMarquee?.set(titleText)
        }
        val prev = lastBound
        if (prev == null || s.hasItem != prev.hasItem || s.artist != prev.artist || s.album != prev.album) {
            c.subtitle.text = if (s.hasItem) Format.subtitle(s.artist, s.album) else ""
        }
        if (prev == null || s.shuffle != prev.shuffle) c.shuffle.setImageResource(s.shuffle.iconRes)
        if (prev == null || s.repeat != prev.repeat) c.repeat.setImageResource(s.repeat.iconRes)
        if (prev == null || s.hasItem != prev.hasItem || s.sampleRateHz != prev.sampleRateHz ||
            s.bitrateBps != prev.bitrateBps || s.filePath != prev.filePath
        ) {
            c.audioInfo.text = if (s.hasItem) Format.audioInfo(s.sampleRateHz, s.bitrateBps, s.filePath) else ""
        }
        transport.bind(s, prev)
        lastBound = s

        lastLiveSeq = s.liveTransitionSeq
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b?.artPager?.removeCallbacks(pagerIdleHeal)
        titleMarquee?.stop()
        titleMarquee = null
        deckRv = null
        _b = null
    }

    companion object {
        private const val SLIDE_MS = 180L

        private const val PAGER_SETTLE_TIMEOUT_MS = 1_000L

        private const val COMMIT_FLING_DP_S = 300f

        private const val LANDING_TIMEOUT_MS = 2_000L

        private val GLIDE_EASE = DecelerateInterpolator()

        private const val PHANTOM_NEXT_INDEX = -2
        private const val PHANTOM_PREV_INDEX = -3

        /**
         * Shared sentinel for ALL cards of the shuffle deck (prev/current/next). With one
         * sentinel, a card's diff identity is just its song — so after a swipe the rebuilt
         * deck ANCHORS on the card the pager is resting on (it reappears as the new center)
         * and no repositioning is needed. A repositioning setCurrentItem issued from the
         * commit callback can be deferred to the next layout frame, which never comes while
         * paused — it then fires on the next touch and swallows that gesture.
         */
        private const val SHUFFLE_CARD_INDEX = -4
    }
}

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
import android.widget.Toast
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

    /** The control column, shared between the portrait and landscape layouts via an
     *  `<include>` (see res/layout/player_controls.xml) — so the deck lives on [b] and every
     *  transport/info view on this one binding. */
    private val c get() = b.controls

    private lateinit var artAdapter: ArtPagerAdapter
    private lateinit var transport: TransportBinder

    private var currentFilePath = ""

    /** The activity, cast once instead of at every call site. */
    private val main get() = requireActivity() as MainActivity

    /** Marquees the song title when it overflows — same one-loop scroll as the toolbar path,
     *  but auto only: a tap on the title opens the song's folder ([c.info]), so there's no
     *  tap-to-replay here. Rebuilt per view; [boundTitle] guards it against restarting on
     *  every position tick. */
    private var titleMarquee: TitleMarquee? = null
    private var boundTitle: String? = null

    /**
     * The player's current queue index, read straight from the state flow so it's always
     * authoritative — the queue and state flows are collected on separate coroutines that
     * resume in any order, so a cached field would go stale mid-advance and snap the pager
     * to the wrong (or out-of-range) page. Used both to align the pager and to let the page
     * callback tell a real swipe apart from a programmatic sync.
     */
    private val playerIndex get() = player.state.value.queueIndex

    /** False until the pager has been aligned once; the first alignment snaps (no animation). */
    private var pagerSynced = false

    /**
     * The [UiPlayback.liveTransitionSeq] value last handled. The card flip animates only when the
     * sequence has advanced since the previous bind — i.e. ExoPlayer reported a genuine live
     * AUTO/SEEK song transition. A song that changed while we were backgrounded arrives via a
     * reconnect state-sync (which doesn't advance the sequence), so it snaps into place instead.
     */
    private var lastLiveSeq = 0

    /** True only when the pager is at rest, so ticks don't yank it mid-swipe. */
    private var pagerIdle = true

    /**
     * True once the user has physically dragged since the pager last settled. onPageSelected
     * fires for programmatic moves and data-change clamps too (e.g. when a folder advance
     * shrinks the queue and ViewPager2 clamps onto the new phantom) — those must NOT seek or
     * trigger another advance, so page changes only act when they follow a real drag.
     */
    private var sawDrag = false

    /**
     * The live queue (without phantom pages); the pager shows this plus maybe phantom cards.
     * Read straight from the flow — a mirrored field was a second source of truth that
     * onViewCreated reset to empty while the flow still held the real queue.
     */
    private val queueItems: List<QueueItem> get() = player.queue.value

    /**
     * Advance-List phantom cards: [phantomPrev] leads the queue (previous folder's last song),
     * [phantomNext] trails it (next folder's first song). Null when that neighbour doesn't
     * exist. [phantomKey] is the song folder they were computed for, so they aren't recomputed
     * on every position tick.
     */
    private var phantomPrev: QueueItem? = null
    private var phantomNext: QueueItem? = null
    private var phantomKey: String? = null

    /** Pager positions are shifted by 1 when a leading (previous-folder) card is present. */
    private val leadOffset get() = if (phantomPrev != null) 1 else 0

    /**
     * True from the moment a swipe onto a phantom card triggers a folder advance until it is
     * finalized. While set, page selections are ignored so a fling can't cascade through
     * several folders as the queue swaps underneath it.
     */
    private var advancing = false

    /** Set once the advance's new queue has committed; the pager repositions only after this
     *  AND the pager has settled, so the swipe's deceleration onto the phantom isn't cut short. */
    private var advanceReady = false

    /** mediaId of the last bound track, so [bind] animates only on a real song change (not a
     *  re-index from a queue rebuild). */
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

    /** True while a vertical-swipe folder jump is in flight, so a repeated gesture can't
     *  fire a second jump from stale state. */
    private var folderJumping = false

    /** True while [runAdvance]'s advanceFolder call is actually suspended. Distinguishes a
     *  LIVE advance (pendingAdvance already consumed, queue swap still coming — must not be
     *  disturbed) from a STRANDED `advancing` latch that the self-heal may safely clear. */
    private var advanceInFlight = false

    /** True while a finger holds the deck in a vertical drag. Gates deck mutations:
     *  [syncPager] and [rebuildPages] must not move/rebuild pages under the held finger. */
    private var vDragging = false

    /** True while [cycleShuffle]'s library load is still suspended, so a second tap can't be
     *  computed from the mode the first one hasn't applied yet. */
    private var shuffleCycleInFlight = false

    /** The in-flight deck glide (see [glideDeckTo]); replacing it cancels the old one. */
    private var deckGlide: Runnable? = null

    /** One-shot hook invoked from [rebuildPages]' submitList commit callback, so
     *  [jumpFolder] can await deck commits instead of polling (see [awaitDeckCommit]). */
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
        // Next to the back arrow the player shows the playing song's folder path ([bind]
        // keeps it current); blank until the first state arrives. Reset the cached path so
        // a recreated view re-applies the title even when the song didn't change.
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
        // Vertical swipes on the art deck jump to a sibling folder — up = next, down =
        // previous, in filename order. A direct jump (NOT history navigation). Like the
        // pager's own horizontal swipe, the deck FOLLOWS the finger: a dominantly-vertical
        // move past the touch slop claims the gesture from the RecyclerView, drags the deck
        // by translationY, and the release either commits the jump (enough travel or a
        // matching flick) or springs back.
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        val vDragListener = object : RecyclerView.OnItemTouchListener {
            // Gesture-scoped state lives here, not on the fragment; only [vDragging] is
            // shared (other code must not mutate the deck under the finger). Anchors use
            // RAW screen coordinates — the local ones shift as the deck translates under
            // the finger. `vel` is a smoothed px/ms velocity for the release fling test.
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
                            // Dominantly vertical, past the slop, and no horizontal page
                            // scroll under way — claim the stream (a spring-back glide may
                            // be caught mid-flight and re-owned by the finger).
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
        // Scan children for the RecyclerView rather than assuming it's index 0.
        for (i in 0 until b.artPager.childCount) {
            val rv = b.artPager.getChildAt(i) as? RecyclerView ?: continue
            rv.itemAnimator = null
            rv.addOnItemTouchListener(vDragListener)
        }
        // Swiping the art pager (deck-style card flip) changes the track.
        b.artPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // Only a real user drag may change the track; programmatic moves and the clamp
                // ViewPager2 does when the queue shrinks also land here and must be ignored.
                // Once an advance is pending, ignore everything until the pager settles: a fling
                // stays in SETTLING across the queue swap and would otherwise carry onto the new
                // folder's phantom and advance again (and again), cascading through folders.
                if (!sawDrag || advancing) return
                // Shuffle: the deck is just [previous-played?, current, shuffle-next?]. A swipe
                // can only land on an edge card; a missing neighbour means that swipe was never
                // possible (no card to swipe onto — e.g. no going back at the history start, no
                // forward once everything has played). Consume the drag so follow-up selection
                // events from the deck rebuild are never mistaken for another user action.
                if (player.state.value.shuffle != ShuffleMode.OFF) {
                    when {
                        position < leadOffset -> { sawDrag = false; player.previousSong() }
                        position > leadOffset -> {
                            sawDrag = false
                            if (player.hasNext()) player.next()
                            // Trailing card with nothing unplayed left = the Advance-List
                            // next-folder fallback: defer the folder advance to the settle.
                            else armAdvance(forward = true)
                        }
                    }
                    return
                }
                // Shuffle off: pages mirror the timeline, edges are Advance-List folder cards.
                // Defer the folder advance until the swipe settles (see the settle handler) so
                // the queue doesn't swap mid-fling.
                if (phantomPrev != null && position == 0) {
                    armAdvance(forward = false); return
                }
                if (phantomNext != null && position == leadOffset + queueItems.size) {
                    armAdvance(forward = true); return
                }
                // A swipe moves through songs with the SAME transport calls as the
                // previous/next buttons — one code path, so the two can never drift apart.
                // The deck only decides the direction. Neither call starts a paused player.
                // A backward flip is always a real song change (the landed card IS the
                // previous song's), so it takes the strict previous-song move — the
                // restart-current-song step belongs to the button, whose press carries no
                // such visual target. Anything but a one-page move is not a user swipe
                // (programmatic moves/clamps land here too) and must not touch playback.
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
                        // Swipe just settled on the phantom: now run the deferred advance. The
                        // deceleration has played out fully (identical to an in-folder swipe);
                        // the queue swap happens from rest.
                        pendingAdvance != null -> { val go = pendingAdvance!!; pendingAdvance = null; go() }
                        // New queue already landed: reposition onto the real (identical) page.
                        advancing -> finalizeAdvanceIfReady()
                        // A deck rebuild waited for the gesture to finish, or the pager simply
                        // needs to be back on the playing track — the same "settle the deck"
                        // policy the vertical drag applies.
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

        // Tap the song info to open the folder the current track lives in — with its
        // ancestors on the back stack so Back walks up the folder tree.
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
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Returning from the background re-runs this block and re-emits both
                // StateFlows, but the view (and pager) weren't recreated, so onViewCreated
                // didn't reset the flag. Snap the first realignment after every foreground
                // entry too, otherwise the re-emit animates a card flip for no reason.
                pagerSynced = false
                // Forget the applied titles so the re-emit re-sets them — both the toolbar
                // path and the song title should marquee-scroll on EVERY entry to this
                // screen, foreground returns included.
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

    /**
     * Advance the shuffle mode one step.
     *
     * The ALL and OFF branches only apply the new mode AFTER a suspending library read, and the
     * next mode is derived from the current one — so a second tap inside that window (the read is
     * a full MediaStore scan whenever the track cache is cold, e.g. right after an Activity
     * recreation) recomputed the same transition, toasted twice and ran the whole-library rebuild
     * again. [shuffleCycleInFlight] makes the button ignore taps until the mode has landed.
     */
    private fun cycleShuffle() {
        if (shuffleCycleInFlight) return
        val next = player.state.value.shuffle.cycleNext()
        when (next) {
            ShuffleMode.CURRENT -> player.setShuffle(ShuffleMode.CURRENT)
            ShuffleMode.ALL -> launchShuffleChange {
                player.playAllShuffled(requireContext().repository.allTracks())
            }
            // Back to OFF: restore the queue we had before shuffling (resolve the snapshot's
            // track ids against the library, since Shuffle-All replaced the whole timeline).
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
     * Arm a folder advance to run once the swipe settles (see the settle handler), so the queue
     * doesn't swap mid-fling. One place, so the three-flag latch can't be half-set.
     *
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

    /** Same lightweight message system as enqueue (a Toast). */
    private fun showModePopup(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun showModePopup(titleRes: Int, subRes: Int) = showModePopup(buildString {
        append(getString(titleRes))
        if (subRes != 0) { append('\n'); append(getString(subRes)) }
    })

    /** Page position of queue index [index]: with shuffle on the deck is [prev?, current,
     *  next?], so the only real page is the current one at [leadOffset]; otherwise pages
     *  mirror the timeline shifted past any leading phantom. */
    private fun pagePosOf(index: Int): Int =
        if (player.state.value.shuffle != ShuffleMode.OFF) leadOffset else index + leadOffset

    /**
     * Align the pager to the real queue [index] (shifted past any leading phantom). [animate]
     * is false for structural moves (first open, resume, queue/phantom rebuilds) and true only
     * for genuine track transitions, which get the card-flip animation.
     *
     * [force] skips the gesture/advance gates, to land a folder advance by interrupting any
     * residual fling. (It used to be a second near-copy of this function, which had already
     * drifted — it omitted one of the two gates.)
     */
    private fun syncPager(index: Int, animate: Boolean, force: Boolean = false) {
        if (index < 0) return
        if (!force) {
            // During a folder advance the pager position is owned by the advance (it stays on the
            // phantom until finalizeAdvanceIfReady jumps it). A state-driven sync here would run
            // before the queue rebuild and land on the OLD queue's index — a wrong-folder flash.
            if (advancing) return
            // Don't fight a gesture in progress — neither a horizontal drag/fling nor a held
            // vertical drag; the settle handlers re-sync once at rest ([afterVerticalDrag]).
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

    private fun bindQueue() {
        // If this queue change is the swipe-driven folder advance landing, take the pager
        // straight from the phantom onto the new song in one move (see [rebuildPages]).
        val landingAdvance = advancing
        // The shuffle phantoms are built FROM the queue, so a queue change invalidates them.
        phantomKey = null
        updatePhantom(player.state.value)
        rebuildPages(landingAdvance)
    }

    /**
     * Push the queue (plus any phantom cards) to the pager. A structural change like this
     * always snaps — only in-place track transitions animate. submitList commits the diff
     * asynchronously, so itemCount only reflects the new list once the commit callback fires:
     * align the pager there, otherwise the first load (empty -> N) would read a stale count.
     *
     * [landingAdvance] means the queue just changed because a swiped folder advance completed:
     * force the pager onto the new song right here (interrupting the fling, before the frame
     * draws so there's no clamp flash), then end the advance. A phantom recompute that lands
     * here mid-advance leaves the pager alone — the pending advance will position it.
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
            // Shuffle: a 3-card deck — the song actually played before (if any), the current
            // song, and the upcoming shuffle pick (or the Advance-List folder fallback). A
            // missing neighbour means that swipe is impossible: no card, no gesture. All cards
            // share SHUFFLE_CARD_INDEX so rebuilds anchor on the resting card (see companion).
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
                // ANY commit that lands while an advance is waiting finalizes it — this may be
                // the landing rebuild itself or the phantom rebuild that superseded it; either
                // way the committed list carries the new queue, so the reposition is correct.
                advancing -> finalizeAdvanceIfReady()
                // Post the alignment out of the RecyclerView update pass: a setCurrentItem
                // issued inside it gets deferred to the next layout frame, which may never
                // come while paused — the stale reposition then swallows the next gesture.
                // (Usually a no-op anyway: shuffle-deck rebuilds anchor on the resting card.)
                else -> b.artPager.post { if (_b != null) syncPager(playerIndex, animate = false) }
            }
            // Signal a waiting vertical jump that a commit landed (see [awaitDeckCommit]).
            onDeckCommitted?.invoke()
        }
    }

    /**
     * Vertical-swipe folder jump (see [MainActivity.jumpToNeighbourFolder]), with the same
     * slide transition as the horizontal deck — just vertical: the deck glides out in the
     * swipe direction while the neighbour folder loads, then the new song's art glides in
     * from the opposite edge. The slide-in waits (bounded) until the new song is current
     * AND the rebuilt deck has committed (submitList is async), otherwise it would show
     * the OLD folder's art for a frame; the folder-name popup is shown at that same moment
     * so the announcement and the landing can't contradict each other. A no-op jump (edge
     * of the library) glides back to rest.
     */
    private fun jumpFolder(forward: Boolean) {
        if (advancing || folderJumping) { glideDeckTo(0f); return }
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
                    val bmp = Bitmap.createBitmap(b.artPager.width, b.artPager.height, Bitmap.Config.ARGB_8888)
                    b.artPager.draw(Canvas(bmp))
                    ImageView(requireContext()).apply { setImageBitmap(bmp) }
                }.getOrNull()
                if (overlay != null) {
                    b.deckWindow.addView(
                        overlay,
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
                    // No neighbour folder (or nothing playing): spring back from wherever the
                    // outgoing leg is; the finally below reveals the pager and drops the overlay.
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
                // Enter from the opposite edge with the new folder's art.
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
                    while (bb.deckWindow.childCount > 1) {
                        bb.deckWindow.removeViewAt(bb.deckWindow.childCount - 1)
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

    /** True when the adapter's committed list shows the current song on the page the
     *  pager will rest on. */
    private fun deckShowsCurrent(): Boolean =
        artAdapter.currentList.getOrNull(pagePosOf(playerIndex))?.mediaId ==
            player.state.value.mediaId

    /** Suspend until [rebuildPages]' next submitList commit. Everything runs on the main
     *  thread, so installing the hook right after a [deckShowsCurrent] miss cannot lose a
     *  commit in between. */
    private suspend fun awaitDeckCommit() = suspendCancellableCoroutine { cont ->
        onDeckCommitted = { onDeckCommitted = null; cont.resume(Unit) }
        cont.invokeOnCancellation { onDeckCommitted = null }
    }

    /**
     * Decide a released vertical drag's fate: enough travel (a quarter of the deck) or a
     * flick in the drag's direction commits the folder jump; anything else springs back.
     * [velPxPerMs] is the drag's smoothed release velocity.
     */
    private fun settleVerticalDrag(velPxPerMs: Float) {
        val ty = b.artPager.translationY
        val flung = abs(velPxPerMs) * 1000f >= commitFlingPxS && (velPxPerMs < 0f) == (ty < 0f)
        if (ty != 0f && (abs(ty) >= b.artPager.height / 4f || flung)) jumpFolder(forward = ty < 0f)
        else glideDeckTo(0f) { afterVerticalDrag() }
    }

    /** Apply deck work that was deferred while a gesture was in progress — the ONE
     *  "settle the deck" policy, shared by the pager's idle handler and the drag release. */
    private fun afterVerticalDrag() {
        if (pendingRebuild) { pendingRebuild = false; rebuildPages() }
        else syncPager(playerIndex, animate = false)
    }

    /**
     * Latch the pager busy for a programmatic smooth scroll, with a bounded recovery.
     *
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

    /**
     * Frame-stepped glide of the deck's translationY to [target], invoking [onEnd] when it
     * lands. Hand-rolled on postOnAnimation rather than any Animator: the dev device runs
     * with ALL animator scales at 0 (animations off), which snaps Animator-driven motion
     * straight to its end state — while direct per-frame property writes are untouched.
     * Starting a new glide (or the finger re-claiming the deck) cancels the previous one,
     * whose onEnd then never fires.
     */
    private fun glideDeckTo(target: Float, onEnd: (() -> Unit)? = null) =
        glideViewTo(b.artPager, target, onEnd)

    /** Same frame-stepped glide, but on an arbitrary view — the folder jump animates a bitmap
     *  snapshot overlay instead of the live pager (see [jumpFolder]). */
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

    /** Give up an advance that changed nothing and put the pager back on the current song. */
    private fun abandonAdvance() {
        advancing = false
        advanceReady = false
        pendingAdvance = null
        syncPager(playerIndex, animate = false)
    }

    /**
     * Complete a swipe-driven folder advance: once the new queue has committed [advanceReady]
     * and the pager has settled on the phantom [pagerIdle], jump onto the real current song.
     * Both show the same art, so with item animations off this reposition is invisible.
     */
    private fun finalizeAdvanceIfReady() {
        if (!advancing || !advanceReady || !pagerIdle) return
        advancing = false
        advanceReady = false
        syncPager(playerIndex, animate = false, force = true)
    }

    /** Synthetic phantom page carrying a neighbour folder song's art + id (id keeps it unique). */
    private fun phantomOf(t: Track, sentinelIndex: Int) = QueueItem(
        mediaId = t.id, albumId = t.albumId, title = "", artist = "", album = "",
        filePath = "", timelineIndex = sentinelIndex, enqueued = false,
    )

    /**
     * Recompute the leading/trailing phantom cards.
     *
     * Shuffle ON: the cards preview the shuffle-order neighbours (next = the upcoming random
     * unplayed song, prev = the song actually played before) so the deck can be swiped past the
     * timeline edges. A missing neighbour means that swipe is disallowed — no card, nothing to
     * swipe onto (e.g. no going back at the start of the shuffle history). If everything has
     * played and Advance-List is on, the trailing card falls back to the next folder.
     *
     * Shuffle OFF: Advance-List folder cards (previous folder's last song / next folder's
     * first), keyed on the song's folder so they aren't recomputed on every position tick.
     */
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
            // Shuffle exhausted: with Advance-List the deck still ends on the next-folder card.
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
            // The toolbar shows the playing song's folder path, relative to the library
            // root (common to every song, so it says nothing) and without the file name
            // (already told by the song info below the deck).
            val dir = s.filePath.substringBeforeLast('/', "")
            viewLifecycleOwner.lifecycleScope.launch {
                val title = main.libraryRelativePath(dir) ?: ""
                // A newer song may have bound while the root was being looked up.
                if (_b != null && currentFilePath == s.filePath) {
                    // Long paths marquee once (and again on tap) instead of ellipsizing.
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
                // Button-press transitions (pager at rest): flip onto the edge card that
                // previews this song; the deferred rebuild then re-centers invisibly on the
                // same art. Swipe transitions are already mid-gesture and settle on their
                // own. Never while a finger holds the deck in a vertical drag.
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
        updatePhantom(s)

        // Belt-and-braces (runs every position tick): a phantom recompute can swap the leading
        // card underneath a RESTING pager — e.g. right after a folder jump, replacing the
        // previous-folder card can leave ViewPager2 parked on it, showing the neighbour
        // folder's cover while a different song plays — and nothing else re-aligns a pager
        // whose song did NOT change. syncPager's own gates (mid-gesture, advancing, jumping,
        // out-of-range) make this a strict no-op except when the deck is at rest off the
        // current page. Skipped on the song-change tick itself: that bind may have just
        // started an intentional flip onto an edge card (shuffle) that must not be undone.
        if (s.hasItem && !songChanged && s.shuffle == ShuffleMode.OFF) {
            syncPager(s.queueIndex, animate = false)
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

        // Track the live-transition sequence so the NEXT bind can tell a genuine transition
        // (sequence advanced) from a re-sync of the same state (sequence unchanged).
        lastLiveSeq = s.liveTransitionSeq
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b?.artPager?.removeCallbacks(pagerIdleHeal)
        titleMarquee?.stop()
        titleMarquee = null
        _b = null
    }

    companion object {
        /** Duration of each half of the vertical folder-jump slide (out, then in). */
        private const val SLIDE_MS = 180L

        /** Upper bound on waiting for a programmatic smooth scroll to report IDLE (see
         *  [markPagerBusy]) before the deck un-gates itself anyway. */
        private const val PAGER_SETTLE_TIMEOUT_MS = 1_000L

        /** Release velocity (dp/s) that commits a folder jump even with little travel. */
        private const val COMMIT_FLING_DP_S = 300f

        /** Upper bound on waiting for a jump's new queue/deck to land before the slide-in
         *  recovers anyway (slow rescans, huge folders). */
        private const val LANDING_TIMEOUT_MS = 2_000L

        private val GLIDE_EASE = DecelerateInterpolator()

        /** Sentinel timelineIndexes marking the phantom pages (never real queue slots). */
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

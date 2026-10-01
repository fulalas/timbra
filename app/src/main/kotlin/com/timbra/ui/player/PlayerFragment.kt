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

    private var sawDrag = false

    private val queueItems: List<QueueItem> get() = player.queue.value

    private var phantomPrev: QueueItem? = null
    private var phantomNext: QueueItem? = null
    private var phantomKey: String? = null

    private val leadOffset get() = if (phantomPrev != null) 1 else 0

    private var advancing = false

    private var advanceReady = false

    private var lastBoundMediaId = -1L

    private var lastBound: UiPlayback? = null

    private var pendingRebuild = false

    private var pendingAdvance: (() -> Unit)? = null

    private var folderJumping = false

    private var advanceInFlight = false

    private var vDragging = false

    private var shuffleCycleInFlight = false

    private var deckGlide: Runnable? = null

    private var onDeckCommitted: (() -> Unit)? = null

    private val commitFlingPxS by lazy { COMMIT_FLING_DP_S * resources.displayMetrics.density }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentPlayerBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        main.supportActionBar?.title = ""
        currentFilePath = ""

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
        for (i in 0 until b.artPager.childCount) {
            val rv = b.artPager.getChildAt(i) as? RecyclerView ?: continue
            deckRv = rv
            rv.itemAnimator = null
            rv.addOnItemTouchListener(vDragListener)
        }
        b.artPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (!sawDrag || advancing) return
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
                when ((position - leadOffset) - playerIndex) {
                    +1 -> player.next()
                    -1 -> player.previousSong()
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    sawDrag = true
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
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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

    private fun armAdvance(forward: Boolean) {
        advancing = true
        advanceReady = false
        val gen = requireContext().app.session.queueGeneration
        pendingAdvance = { runAdvance(forward, gen) }
    }

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
            if (advancing) return
            if (!pagerIdle || vDragging) return
        }
        val pos = pagePosOf(index)
        if (pos !in 0 until artAdapter.itemCount) return
        if (b.artPager.currentItem != pos) {
            b.artPager.setCurrentItem(pos, animate && !force && !folderJumping)
        }
        pagerSynced = true
    }

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

    private fun rebuildPages(landingAdvance: Boolean = false) {
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
        if (landingAdvance) advanceReady = true
        artAdapter.submitList(pages) {
            if (_b == null) return@submitList
            when {
                advancing -> finalizeAdvanceIfReady()
                else -> alignDeck()
            }
            onDeckCommitted?.invoke()
        }
    }

    private fun jumpFolder(forward: Boolean) {
        if (advancing || folderJumping) { glideDeckTo(0f) { afterVerticalDrag() }; return }
        folderJumping = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val oldId = player.state.value.mediaId
                val out = if (forward) -b.artPager.height.toFloat() else b.artPager.height.toFloat()
                val slideOut = CompletableDeferred<Unit>()
                val overlay = runCatching {
                    val bmp = Bitmap.createBitmap(b.artPager.width, b.artPager.height, Bitmap.Config.RGB_565)
                    b.artPager.draw(Canvas(bmp))
                    ImageView(requireContext()).apply {
                        id = R.id.deck_snapshot
                        setImageBitmap(bmp)
                    }
                }.getOrNull()
                if (overlay != null) {
                    b.deckWindow.addView(
                        overlay,
                        0,
                        FrameLayout.LayoutParams(b.artPager.width, b.artPager.height),
                    )
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
                    while (!deckShowsCurrent()) awaitDeckCommit()
                }
                showModePopup(folder)
                withTimeoutOrNull(SLIDE_MS * 3) { slideOut.await() }
                overlay?.let { b.deckWindow.removeView(it) }
                syncPager(playerIndex, animate = false, force = true)
                b.artPager.translationY = -out
                b.artPager.visibility = View.VISIBLE
                glideDeckTo(0f)
            } finally {
                folderJumping = false
                _b?.let { bb ->
                    bb.artPager.visibility = View.VISIBLE
                    for (i in bb.deckWindow.childCount - 1 downTo 0) {
                        if (bb.deckWindow.getChildAt(i).id == R.id.deck_snapshot) {
                            bb.deckWindow.removeViewAt(i)
                        }
                    }
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

    private fun runAdvance(forward: Boolean, expectedGen: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
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
                if (_b != null && currentFilePath == s.filePath) {
                    main.setMarqueeTitle(title)
                }
            }
        }

        val songChanged = s.mediaId != lastBoundMediaId
        if (songChanged) {
            lastBoundMediaId = s.mediaId
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
                        if (animate) markPagerBusy()
                    }
                    pagerSynced = true
                }
            } else {
                syncPager(s.queueIndex, animate = animate)
            }
        }
        val queueLags = s.hasItem && queueItems.getOrNull(s.queueIndex)?.mediaId != s.mediaId
        if (!queueLags) {
            val leftShuffle = lastBound?.shuffle?.let { it != ShuffleMode.OFF } == true &&
                s.shuffle == ShuffleMode.OFF
            updatePhantom(s)
            if (leftShuffle) rebuildPages()

            if (s.hasItem && !songChanged && s.shuffle == ShuffleMode.OFF) {
                syncPager(s.queueIndex, animate = false)
            }
        }

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

        private const val SHUFFLE_CARD_INDEX = -4
    }
}

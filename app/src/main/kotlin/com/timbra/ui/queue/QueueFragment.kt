// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui.queue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.timbra.R
import com.timbra.databinding.FragmentListBinding
import com.timbra.player.QueueItem
import com.timbra.ui.ItemActions
import com.timbra.ui.dialogs.Dialogs
import com.timbra.ui.player
import com.timbra.ui.linearWithDivider
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Shows the manually-enqueued (play-next) songs — already-played ones stay dimmed. Tap
 * to jump; long-press for Info/Share/Remove; drag the right-side handle to reorder.
 */
class QueueFragment : Fragment(), MenuProvider {

    private var _b: FragmentListBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: QueueAdapter
    private lateinit var touchHelper: ItemTouchHelper

    private var fullQueue: List<QueueItem> = emptyList()
    private var displayed: List<QueueItem> = emptyList()
    private var dragging = false

    /** A queue emission arrived while a drag held the list; re-apply it on the drop. */
    private var missedQueueUpdate = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = QueueAdapter(
            owner = viewLifecycleOwner,
            // The row carries the timeline index it was BOUND with, which lags a reorder until
            // the queue flow re-emits — so the id is the authority, exactly as for Remove.
            onClick = { item -> player.seekToQueueItem(item.timelineIndex, item.mediaId) },
            onLong = { item ->
                ItemActions.showForQueue(this, item) {
                    player.removeQueueItem(item.timelineIndex, item.mediaId)
                }
            },
            onDragStart = { touchHelper.startDrag(it) },
        )
        b.recycler.linearWithDivider()
        b.recycler.adapter = adapter
        b.empty.setText(R.string.queue_empty)

        touchHelper = ItemTouchHelper(dragCallback())
        touchHelper.attachToRecyclerView(b.recycler)

        requireActivity().addMenuProvider(this, viewLifecycleOwner)

        viewLifecycleOwner.lifecycleScope.launch {
            // viewLifecycleOwner-qualified: unqualified this resolves against the FRAGMENT, mixing
            // two owners inside a view-scoped coroutine (see Ext.kt for the correct form).
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // The list membership only changes when the queue changes...
                launch { player.queue.collect { fullQueue = it; refreshList() } }
                // ...while the highlight/dim only needs the current index.
                launch {
                    player.state.map { it.queueIndex }.distinctUntilChanged().collect {
                        if (!dragging) adapter.currentIndex = it
                    }
                }
            }
        }
    }

    private fun refreshList() {
        if (dragging) {
            // Don't reset the list under the finger — but remember that something arrived, so
            // the drop re-applies it. Swallowing it outright left the rows bound to timeline
            // indices from a queue that had since been replaced (a folder advance, Shuffle-All),
            // and nothing ever re-emitted to correct them.
            missedQueueUpdate = true
            return
        }
        missedQueueUpdate = false
        // Show all enqueued songs; already-played ones remain (dimmed by the adapter).
        val next = fullQueue.filter { it.enqueued }
        if (next != displayed) {
            displayed = next
            adapter.submit(next)
        }
        _b?.empty?.isVisible = next.isEmpty()
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_queue, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_clear_queue -> {
            Dialogs.confirm(
                requireContext(),
                titleRes = R.string.menu_clear_queue,
                message = getString(R.string.clear_queue_confirm),
                confirmRes = R.string.menu_clear_queue,
            ) { player.clearQueue() }
            true
        }
        else -> false
    }

    private fun dragCallback() = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0,
    ) {
        // Drag is started manually from the handle, not by long-press (that opens the menu).
        override fun isLongPressDragEnabled() = false

        override fun onMove(
            rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            adapter.moveItem(from, to)
            return true
        }

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) dragging = true
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            dragging = false
            // Commit the new order to the player; the queue flow then refreshes cleanly.
            player.reorderQueue(adapter.currentItems().map { it.mediaId })
            // A reorder that reached the player re-emits and refreshes the list on its own, but
            // one that netted no move (or whose ids are gone) doesn't — so apply anything that
            // arrived during the drag, and re-sync the highlight the drag also suppressed.
            if (missedQueueUpdate) refreshList()
            adapter.currentIndex = player.state.value.queueIndex
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}

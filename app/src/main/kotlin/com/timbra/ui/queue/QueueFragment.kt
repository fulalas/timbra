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

class QueueFragment : Fragment(), MenuProvider {

    private var _b: FragmentListBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: QueueAdapter
    private lateinit var touchHelper: ItemTouchHelper

    private var fullQueue: List<QueueItem> = emptyList()
    private var displayed: List<QueueItem> = emptyList()
    private var dragging = false

    private var missedQueueUpdate = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = QueueAdapter(
            owner = viewLifecycleOwner,
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
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { player.queue.collect { fullQueue = it; refreshList() } }
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
            missedQueueUpdate = true
            return
        }
        missedQueueUpdate = false
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
            player.reorderQueue(adapter.currentItems().map { it.mediaId })
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

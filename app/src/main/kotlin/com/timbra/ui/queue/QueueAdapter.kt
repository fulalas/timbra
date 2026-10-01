// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui.queue

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.timbra.R
import com.timbra.data.MediaRepository
import com.timbra.databinding.RowQueueBinding
import com.timbra.player.QueueItem
import com.timbra.ui.ArtLoader
import com.timbra.ui.Format
import com.timbra.ui.applyNowPlaying

class QueueAdapter(
    private val owner: LifecycleOwner,
    private val onClick: (QueueItem) -> Unit,
    private val onLong: (QueueItem) -> Unit,
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<QueueAdapter.VH>() {

    private var items: List<QueueItem> = emptyList()

    var currentIndex: Int = -1
        set(value) {
            if (field == value) return
            val old = field
            field = value
            notifyRowFor(old)
            notifyRowFor(value)
        }

    private fun notifyRowFor(timelineIndex: Int) {
        if (timelineIndex < 0) return
        val pos = items.indexOfFirst { it.timelineIndex == timelineIndex }
        if (pos >= 0) notifyItemChanged(pos)
    }

    fun submit(list: List<QueueItem>) {
        val old = items
        items = list
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) =
                old[o].mediaId == list[n].mediaId && old[o].timelineIndex == list[n].timelineIndex
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n]
        }).dispatchUpdatesTo(this)
    }

    fun currentItems(): List<QueueItem> = items

    fun moveItem(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        val list = items.toMutableList()
        list.add(to, list.removeAt(from))
        items = list
        notifyItemMoved(from, to)
    }

    inner class VH(val b: RowQueueBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(RowQueueBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val playing = item.timelineIndex == currentIndex
        val played = item.played && !playing
        holder.b.title.text = item.displayTitle
        holder.b.subtitle.text = Format.subtitle(item.artist, item.album)
        holder.b.root.alpha = if (played) 0.4f else 1f
        applyNowPlaying(holder.b.root, holder.b.title, playing)
        ArtLoader.load(holder.b.thumb, owner, MediaRepository.trackUri(item.mediaId), item.albumId) {
            holder.b.thumb.isInvisible = !it
        }
        holder.b.root.setOnClickListener { onClick(item) }
        holder.b.root.setOnLongClickListener { onLong(item); true }
        holder.b.dragHandle.isInvisible = played
        holder.b.dragHandle.setOnTouchListener { _, e ->
            if (!played && e.actionMasked == MotionEvent.ACTION_DOWN) onDragStart(holder)
            false
        }
    }
}

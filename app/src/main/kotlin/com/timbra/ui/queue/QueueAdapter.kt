package com.timbra.ui.queue

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.timbra.R
import com.timbra.databinding.RowQueueBinding
import com.timbra.player.QueueItem
import com.timbra.ui.ArtLoader
import com.timbra.ui.Format
import com.timbra.ui.applyNowPlaying

class QueueAdapter(
    private val owner: LifecycleOwner,
    /** Receives the tapped item's timeline index. */
    private val onClick: (Int) -> Unit,
    private val onLong: (QueueItem) -> Unit,
    /** Called when the drag handle is touched, to start a reorder drag. */
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<QueueAdapter.VH>() {

    private var items: List<QueueItem> = emptyList()

    /** Timeline index of the currently-playing item (for highlighting). */
    var currentIndex: Int = -1
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    fun submit(list: List<QueueItem>) {
        items = list
        notifyDataSetChanged()
    }

    fun currentItems(): List<QueueItem> = items

    /** Visually move a row during a drag (does not touch the player). */
    fun moveItem(from: Int, to: Int) {
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
        val played = item.timelineIndex < currentIndex
        holder.b.title.text = item.title
        holder.b.subtitle.text = Format.subtitle(item.artist, item.album)
        // Already-played queue items stay in the list but dimmed.
        holder.b.root.alpha = if (played) 0.4f else 1f
        applyNowPlaying(holder.b.root, holder.b.title, playing)
        // No generic placeholder for art-less rows — but keep the slot (INVISIBLE, not
        // GONE) so every row's text stays aligned in a mixed queue.
        ArtLoader.load(holder.b.thumb, owner, null, item.albumId) { holder.b.thumb.isInvisible = !it }
        holder.b.root.setOnClickListener { onClick(item.timelineIndex) }
        holder.b.root.setOnLongClickListener { onLong(item); true }
        holder.b.dragHandle.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onDragStart(holder)
            false
        }
    }
}

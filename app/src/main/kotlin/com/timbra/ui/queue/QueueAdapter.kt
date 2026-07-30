package com.timbra.ui.queue

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.lifecycle.LifecycleOwner
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
    /** Called when the drag handle is touched, to start a reorder drag. */
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<QueueAdapter.VH>() {

    private var items: List<QueueItem> = emptyList()

    /** Timeline index of the currently-playing item (for highlighting). */
    var currentIndex: Int = -1
        set(value) {
            if (field == value) return
            val old = field
            field = value
            // Only rows whose visuals depend on the index that moved change: the highlight on
            // the two endpoints, and the played-dim on everything the index stepped across.
            // Rebinding the whole list would reload every visible row's art per transition.
            val lo = minOf(old, value)
            val hi = maxOf(old, value)
            items.forEachIndexed { pos, item ->
                if (item.timelineIndex in lo..hi) notifyItemChanged(pos)
            }
        }

    fun submit(list: List<QueueItem>) {
        items = list
        notifyDataSetChanged()
    }

    fun currentItems(): List<QueueItem> = items

    /** Visually move a row during a drag (does not touch the player). */
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
        // The item's own consumed mark, not a timeline-index comparison: under shuffle the index
        // says nothing about what has been played.
        val played = item.played && !playing
        holder.b.title.text = item.displayTitle
        holder.b.subtitle.text = Format.subtitle(item.artist, item.album)
        // Already-played queue items stay in the list but dimmed.
        holder.b.root.alpha = if (played) 0.4f else 1f
        applyNowPlaying(holder.b.root, holder.b.title, playing)
        // No generic placeholder for art-less rows — but keep the slot (INVISIBLE, not
        // GONE) so every row's text stays aligned in a mixed queue.
        // Load via the track's content Uri so embedded-only covers are found here too.
        ArtLoader.load(holder.b.thumb, owner, MediaRepository.trackUri(item.mediaId), item.albumId) {
            holder.b.thumb.isInvisible = !it
        }
        holder.b.root.setOnClickListener { onClick(item) }
        holder.b.root.setOnLongClickListener { onLong(item); true }
        // Only PENDING entries can be reordered — a consumed one has nothing left to reorder,
        // and dragging it would ask the player to move an item it can no longer place.
        holder.b.dragHandle.isInvisible = played
        holder.b.dragHandle.setOnTouchListener { _, e ->
            if (!played && e.actionMasked == MotionEvent.ACTION_DOWN) onDragStart(holder)
            false
        }
    }
}

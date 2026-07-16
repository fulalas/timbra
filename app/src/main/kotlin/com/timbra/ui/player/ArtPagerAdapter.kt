package com.timbra.ui.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.timbra.R
import com.timbra.databinding.ItemArtBinding
import com.timbra.player.QueueItem
import com.timbra.ui.ArtLoader

/**
 * Pages the play queue's album art so it can be swiped like a deck of cards.
 *
 * Backed by [ListAdapter]/[DiffUtil] rather than `notifyDataSetChanged()`: an
 * identical re-emit of the queue (e.g. when the fragment returns to the foreground)
 * diffs to zero changes, so the ViewPager2 keeps its current page instead of being
 * reset to 0 — which is what used to trigger a phantom card-flip on resume.
 */
class ArtPagerAdapter(
    private val owner: LifecycleOwner,
) : ListAdapter<QueueItem, ArtPagerAdapter.VH>(DIFF) {

    inner class VH(val b: ItemArtBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemArtBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        // Pages without art show the glowing app mark instead of a generic placeholder.
        ArtLoader.load(holder.b.pageArt, owner, null, item.albumId) { has ->
            holder.b.pageBrand.isVisible = !has
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<QueueItem>() {
            // Identity = the track AND its slot. Keying on slot alone would make a full queue
            // swap (a folder advance) rebind the visible page in place — e.g. the next-folder
            // phantom card reloading a different cover mid-transition. Requiring the mediaId to
            // match too means a replaced queue diffs to insert/remove, so no visible page is
            // mutated under the user and the incoming page paints its (cached) art immediately.
            override fun areItemsTheSame(a: QueueItem, b: QueueItem) =
                a.timelineIndex == b.timelineIndex && a.mediaId == b.mediaId

            // Only the album art is drawn per page, but full equality is cheap and correct.
            override fun areContentsTheSame(a: QueueItem, b: QueueItem) = a == b
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.timbra.R
import com.timbra.data.MediaRepository
import com.timbra.databinding.ItemArtBinding
import com.timbra.player.QueueItem
import com.timbra.ui.ArtLoader

class ArtPagerAdapter(
    private val owner: LifecycleOwner,
) : ListAdapter<QueueItem, ArtPagerAdapter.VH>(DIFF) {

    inner class VH(val b: ItemArtBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemArtBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        ArtLoader.load(holder.b.pageArt, owner, MediaRepository.trackUri(item.mediaId), item.albumId) { has ->
            holder.b.pageBrand.isVisible = !has
        }
    }

    override fun onViewRecycled(holder: VH) {
        ArtLoader.clear(holder.b.pageArt)
        holder.b.pageBrand.isVisible = false
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<QueueItem>() {
            override fun areItemsTheSame(a: QueueItem, b: QueueItem) =
                a.timelineIndex == b.timelineIndex && a.mediaId == b.mediaId

            override fun areContentsTheSame(a: QueueItem, b: QueueItem) = a == b
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui.list

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.timbra.R
import com.timbra.data.model.FolderNode
import com.timbra.data.model.Track
import com.timbra.databinding.RowFolderBinding
import com.timbra.databinding.RowTrackBinding
import com.timbra.ui.ArtLoader
import com.timbra.ui.Format
import com.timbra.ui.applyNowPlaying

/** One entry in a browse list: a folder, a playable track, or a navigable index item. */
sealed interface ListItem {
    data class FolderRow(val node: FolderNode) : ListItem
    data class TrackRow(val track: Track, val indexInList: Int) : ListItem
    data class NavRow(
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val kind: String,
        val id: Long,
        val navTitle: String,
    ) : ListItem
}

/**
 * The shared browse-list adapter.
 *
 * Backed by [ListAdapter]/[DiffUtil] rather than `notifyDataSetChanged()`: submit() is called on
 * every debounced keystroke in Search and on every sort/View-As change and rescan elsewhere, and a
 * blanket invalidation rebound every visible row from scratch — three setText, a duration format,
 * a subtitle join, an art load and two fresh listeners each — and dropped the scroll position even
 * when most of the list was unchanged. [ListItem] is a data-class hierarchy, so equality is free.
 */
class LibraryListAdapter(
    private val owner: LifecycleOwner,
    private val onTrack: (Int) -> Unit,
    private val onFolder: (FolderNode) -> Unit = {},
    private val onNav: (ListItem.NavRow) -> Unit = {},
    private val onLongItem: (ListItem) -> Unit = {},
) : ListAdapter<ListItem, RecyclerView.ViewHolder>(DIFF) {

    var currentMediaId: Long = -1L
        set(value) {
            if (field == value) return
            val old = field
            field = value
            // Only the previously- and newly-highlighted rows need rebinding.
            notifyTrackChanged(old)
            notifyTrackChanged(value)
        }

    private fun notifyTrackChanged(id: Long) {
        if (id < 0) return
        val pos = currentList.indexOfFirst { it is ListItem.TrackRow && it.track.id == id }
        if (pos >= 0) notifyItemChanged(pos)
    }

    fun submit(list: List<ListItem>) = submitList(list)

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ListItem.TrackRow -> TYPE_TRACK
        else -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_TRACK)
            TrackVH(RowTrackBinding.inflate(inflater, parent, false))
        else
            RowVH(RowFolderBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ListItem.TrackRow -> (holder as TrackVH).bind(item)
            is ListItem.FolderRow -> (holder as RowVH).bindFolder(item)
            is ListItem.NavRow -> (holder as RowVH).bindNav(item)
        }
    }

    private inner class TrackVH(val b: RowTrackBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ListItem.TrackRow) {
            val t = item.track
            val playing = t.id == currentMediaId
            b.title.text = t.displayTitle
            b.subtitle.text = subtitleFor(b, t)
            b.duration.text = Format.clock(t.durationMs)
            applyNowPlaying(b.root, b.title, playing)
            // isInvisible, not isVisible: GONE removed the bar's 2dp from the layout, so the
            // playing row was taller than its neighbours and the whole list shifted by 2dp on
            // every track change. Keeping the slot reserved is the same trick row_queue uses to
            // keep its art column aligned.
            b.nowPlayingBar.isInvisible = !playing
            // No generic placeholder for art-less rows — but keep the slot (INVISIBLE, not
            // GONE) so every row's text stays aligned in a mixed list.
            ArtLoader.load(b.thumb, owner, t.uri, t.albumId) { b.thumb.isInvisible = !it }
            b.root.setOnClickListener { onTrack(item.indexInList) }
            b.root.setOnLongClickListener { onLongItem(item); true }
        }
    }

    private inner class RowVH(val b: RowFolderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bindFolder(item: ListItem.FolderRow) {
            val n = item.node
            b.thumb.setImageResource(R.drawable.matte_folder_96)
            b.title.text = n.name
            b.subtitle.text = b.root.resources.getQuantityString(
                R.plurals.song_count, n.totalTrackCount, n.totalTrackCount,
            )
            b.root.setOnClickListener { onFolder(n) }
            b.root.setOnLongClickListener { onLongItem(item); true }
        }

        fun bindNav(item: ListItem.NavRow) {
            b.thumb.setImageResource(item.iconRes)
            b.title.text = item.title
            b.subtitle.text = item.subtitle
            b.root.setOnClickListener { onNav(item) }
            // RowVH is shared with FolderRow, which DOES install one — a holder recycled from a
            // folder row would otherwise keep the previous folder's handler and open its action
            // menu on a long press here.
            b.root.setOnLongClickListener(null)
        }
    }

    private fun subtitleFor(b: RowTrackBinding, t: Track): String {
        val ctx = b.root.context
        val artist = t.artist.ifBlank { ctx.getString(R.string.unknown_artist) }
        val album = t.album.ifBlank { ctx.getString(R.string.unknown_album) }
        return Format.subtitle(artist, album)
    }

    companion object {
        private const val TYPE_TRACK = 0
        private const val TYPE_ROW = 1

        private val DIFF = object : DiffUtil.ItemCallback<ListItem>() {
            override fun areItemsTheSame(old: ListItem, new: ListItem): Boolean = when {
                old is ListItem.TrackRow && new is ListItem.TrackRow ->
                    old.track.id == new.track.id
                old is ListItem.FolderRow && new is ListItem.FolderRow ->
                    old.node.path == new.node.path
                old is ListItem.NavRow && new is ListItem.NavRow ->
                    old.kind == new.kind && old.id == new.id
                else -> false
            }

            override fun areContentsTheSame(old: ListItem, new: ListItem): Boolean = old == new
        }
    }
}

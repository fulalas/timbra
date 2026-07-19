package com.timbra.ui.folders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.timbra.R
import com.timbra.app
import com.timbra.data.FolderTreeBuilder
import com.timbra.data.SortDefaults
import com.timbra.data.SortOrder
import com.timbra.data.ViewAs
import com.timbra.data.model.FolderNode
import com.timbra.data.model.Track
import com.timbra.data.sortedBy
import com.timbra.databinding.FragmentListBinding
import com.timbra.repository
import com.timbra.ui.ItemActions
import com.timbra.ui.MainActivity
import com.timbra.ui.dialogs.Dialogs
import com.timbra.ui.linearWithDivider
import com.timbra.ui.list.LibraryListAdapter
import com.timbra.ui.list.ListItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Folder browser. Defaults (per the product spec): folders shown as a HIERARCHY and
 * the songs inside a folder sorted BY FILENAME.
 */
class FolderTreeFragment : Fragment(), MenuProvider {

    private var _b: FragmentListBinding? = null
    private val b get() = _b!!

    private lateinit var folderPath: String
    private lateinit var folderTitle: String

    private lateinit var adapter: LibraryListAdapter
    private var playable: List<Track> = emptyList()

    // On the first load after arriving here (e.g. the player's song-info tap opens the
    // playing track's folder), center that track in the list. Consumed once, so later
    // reloads (a rescan) don't yank the user's scroll position around.
    private var centerOnPlaying = true

    private var viewAs: ViewAs = SortDefaults.FOLDER_VIEW
    private var sortOrder: SortOrder = SortDefaults.FOLDER_SONGS

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        folderPath = requireArguments().getString("folderPath", "")
        folderTitle = requireArguments().getString("folderTitle", getString(R.string.cat_folders))
        (requireActivity() as AppCompatActivity).supportActionBar?.title = folderTitle

        adapter = LibraryListAdapter(
            owner = viewLifecycleOwner,
            onTrack = { index -> player().play(playable, index) },
            onFolder = { node -> openFolder(node) },
            onNav = { },
            onLongItem = { onLong(it) },
        )
        b.recycler.linearWithDivider(divider = false)
        b.recycler.adapter = adapter

        requireActivity().addMenuProvider(this, viewLifecycleOwner)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireContext().app.libraryEpoch.collect { load() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                player().state.map { it.mediaId }.distinctUntilChanged()
                    .collect { adapter.currentMediaId = it }
            }
        }
    }

    private fun player() = (requireActivity() as MainActivity).player

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val root = requireContext().repository.folderRoot()
            val node = FolderTreeBuilder.find(root, folderPath) ?: root
            val items = ArrayList<ListItem>()

            if (viewAs == ViewAs.HIERARCHY) {
                FolderTreeBuilder.sortedChildren(node)
                    .forEach { items.add(ListItem.FolderRow(it)) }
                playable = node.tracks.sortedBy(sortOrder)
            } else {
                playable = FolderTreeBuilder.flatten(node).sortedBy(sortOrder)
            }
            playable.forEachIndexed { i, t -> items.add(ListItem.TrackRow(t, i)) }

            _b ?: return@launch
            adapter.submit(items)
            b.empty.isVisible = items.isEmpty()
            if (centerOnPlaying) {
                centerOnPlaying = false
                centerPlaying(items)
            }
        }
    }

    /**
     * Scroll so the currently-playing track sits in the middle of the viewport. A no-op when
     * the track isn't in this list, or when the whole list already fits on screen (nothing to
     * scroll). Two-step: bring the row into view first, then measure its real height so the
     * centering offset is exact regardless of row type.
     */
    private fun centerPlaying(items: List<ListItem>) {
        val playingId = player().state.value.mediaId
        val pos = items.indexOfFirst { it is ListItem.TrackRow && it.track.id == playingId }
        if (pos < 0) return
        b.recycler.post {
            val rv = _b?.recycler ?: return@post
            val lm = rv.layoutManager as? LinearLayoutManager ?: return@post
            if (lm.findFirstCompletelyVisibleItemPosition() == 0 &&
                lm.findLastCompletelyVisibleItemPosition() == items.lastIndex
            ) return@post // the whole list fits — leave the scroll alone
            lm.scrollToPosition(pos) // make the target's view exist so it can be measured
            rv.post {
                val rv2 = _b?.recycler ?: return@post
                val rowH = rv2.findViewHolderForAdapterPosition(pos)?.itemView?.height
                    ?: rv2.getChildAt(0)?.height ?: 0
                val offset = ((rv2.height - rowH) / 2).coerceAtLeast(0)
                (rv2.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, offset)
            }
        }
    }

    private fun onLong(item: ListItem) {
        val (label, ts) = when (item) {
            is ListItem.TrackRow -> item.track.displayTitle to listOf(item.track)
            is ListItem.FolderRow ->
                item.node.name to FolderTreeBuilder.flatten(item.node).sortedBy(sortOrder)
            else -> return
        }
        ItemActions.show(this, label, ts)
    }

    private fun openFolder(node: FolderNode) {
        findNavController().navigate(
            R.id.folderTreeFragment,
            bundleOf("folderPath" to node.path, "folderTitle" to node.name),
        )
    }

    // --- Menu (View As + Sort) ---

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_list, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_view_as -> {
            Dialogs.showViewAs(requireContext(), viewAs) { viewAs = it; load() }
            true
        }
        R.id.action_sort -> {
            Dialogs.showSort(requireContext(), SortOrder.entries, sortOrder) {
                sortOrder = it; load()
            }
            true
        }
        else -> false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}

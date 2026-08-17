// SPDX-License-Identifier: GPL-3.0-or-later
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.timbra.R
import com.timbra.data.FolderTreeBuilder
import com.timbra.data.ViewAs
import com.timbra.data.model.FolderNode
import com.timbra.data.model.Track
import com.timbra.data.sortedBy
import com.timbra.data.tracksInPlayOrder
import com.timbra.databinding.FragmentListBinding
import com.timbra.folderSort
import com.timbra.repository
import com.timbra.ui.ItemActions
import com.timbra.ui.dialogs.Dialogs
import com.timbra.ui.linearWithDivider
import com.timbra.ui.list.LibraryListAdapter
import com.timbra.ui.list.ListItem
import com.timbra.ui.player
import com.timbra.ui.reloadOnLibraryChange
import com.timbra.ui.trackNowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FolderTreeFragment : Fragment(), MenuProvider {

    private var _b: FragmentListBinding? = null
    private val b get() = _b!!

    private lateinit var folderPath: String
    private lateinit var folderTitle: String

    private lateinit var adapter: LibraryListAdapter

    /**
     * What a row tap plays, committed on the main thread TOGETHER with the rows it belongs to.
     * The playable list used to be assigned from inside the background sort block, so during a
     * re-sort (or a View-As switch) it no longer matched the rows still on screen — a tap in that
     * window carried an index from the old list into the new one and played a different song.
     */
    private class Loaded(
        val items: List<ListItem>,
        val playable: List<Track>,
        /** The Advance-List anchor for a queue built from these rows: the browsed folder in
         *  hierarchy view, and nothing in flat view (the queue spans a whole subtree that no
         *  single directory names, so the playing file's own folder is the better anchor). */
        val folderContext: String?,
    )

    private var loaded = Loaded(emptyList(), emptyList(), null)

    /** The in-flight [load]; replacing it cancels the old one, so only the newest build commits. */
    private var loadJob: Job? = null

    // On the first load after arriving here (e.g. the player's song-info tap opens the
    // playing track's folder), center that track in the list. Consumed once, so later
    // reloads (a rescan) don't yank the user's scroll position around.
    private var centerOnPlaying = true

    /** Persisted app-wide, because it also decides the order folder QUEUES are built in
     *  (see [com.timbra.data.FolderSort]). */
    private val folderSort get() = requireContext().folderSort

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        folderPath = requireArguments().getString("folderPath", "")
        // ifBlank, not just a getString default: the nav-graph default is deliberately empty
        // (NavInflater rejects a @string/... default), so the localised label is applied here.
        folderTitle = requireArguments().getString("folderTitle", "")
            .ifBlank { getString(R.string.cat_folders) }
        (requireActivity() as AppCompatActivity).supportActionBar?.title = folderTitle

        adapter = LibraryListAdapter(
            owner = viewLifecycleOwner,
            // folderContext anchors the Advance-List walk on the folder actually being browsed;
            // omitting it left the anchor to the playing file's own directory, which in flat view
            // is a subfolder whose neighbours are already inside this queue.
            onTrack = { index ->
                player.play(loaded.playable, index, folderContext = loaded.folderContext)
            },
            onFolder = { node -> openFolder(node) },
            onLongItem = { onLong(it) },
        )
        b.recycler.linearWithDivider(divider = false)
        b.recycler.adapter = adapter

        requireActivity().addMenuProvider(this, viewLifecycleOwner)

        reloadOnLibraryChange { load() }
        trackNowPlaying(adapter)
    }

    private fun load() {
        // Cancel the build already running: View-As, Sort and the library-epoch reload all call
        // this, and while each commit is atomic the ORDERING was not — on a large folder the
        // slower earlier build could finish last and overwrite the newer rows together with its
        // playable list and folderContext, which is the mismatch [Loaded] exists to prevent.
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val root = requireContext().repository.folderRoot()
            val viewAs = folderSort.viewAs
            val order = folderSort.sortOrder
            // The natural sort over a big folder isn't free — keep it off the main thread.
            val result = withContext(Dispatchers.Default) {
                val node = FolderTreeBuilder.find(root, folderPath) ?: root
                val items = ArrayList<ListItem>()
                val playable: List<Track>
                var context: String? = null
                if (viewAs == ViewAs.HIERARCHY) {
                    FolderTreeBuilder.sortedChildren(node)
                        .forEach { items.add(ListItem.FolderRow(it)) }
                    playable = node.tracksInPlayOrder(order)
                    context = node.path.takeIf { it.isNotEmpty() }
                } else {
                    playable = FolderTreeBuilder.flatten(node).sortedBy(order)
                }
                playable.forEachIndexed { i, t -> items.add(ListItem.TrackRow(t, i)) }
                Loaded(items, playable, context)
            }

            _b ?: return@launch
            // Commit the rows and what they play in one step, on the main thread.
            loaded = result
            adapter.submit(result.items)
            b.empty.isVisible = result.items.isEmpty()
            if (centerOnPlaying) {
                centerOnPlaying = false
                centerPlaying(result.items)
            }
        }
    }

    private fun centerPlaying(items: List<ListItem>) {
        val playingId = player.state.value.mediaId
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
        when (item) {
            is ListItem.TrackRow ->
                ItemActions.show(this, item.track.displayTitle, listOf(item.track))
            is ListItem.FolderRow -> {
                // Flattening a whole subtree and natural-sorting it is exactly the work load()
                // takes care to keep off the main thread — doing it inline in the click callback
                // froze the UI for a large folder before the dialog even appeared.
                val order = folderSort.sortOrder
                viewLifecycleOwner.lifecycleScope.launch {
                    val ts = withContext(Dispatchers.Default) {
                        FolderTreeBuilder.flatten(item.node).sortedBy(order)
                    }
                    if (_b == null) return@launch
                    ItemActions.show(this@FolderTreeFragment, item.node.name, ts)
                }
            }
            else -> return
        }
    }

    private fun openFolder(node: FolderNode) {
        findNavController().navigate(
            R.id.folderTreeFragment,
            bundleOf("folderPath" to node.path, "folderTitle" to node.name),
        )
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_list, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_view_as -> {
            Dialogs.showViewAs(requireContext(), folderSort.viewAs) {
                folderSort.viewAs = it; load()
            }
            true
        }
        R.id.action_sort -> {
            Dialogs.showSort(requireContext(), folderSort.sortOrder) {
                folderSort.sortOrder = it; load()
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

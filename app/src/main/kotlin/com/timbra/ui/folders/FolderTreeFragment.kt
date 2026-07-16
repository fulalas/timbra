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

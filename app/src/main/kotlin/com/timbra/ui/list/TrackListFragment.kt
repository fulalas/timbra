package com.timbra.ui.list

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
import com.timbra.data.SortDefaults
import com.timbra.data.SortOrder
import com.timbra.data.sortedBy
import com.timbra.data.model.Track
import com.timbra.databinding.FragmentListBinding
import com.timbra.repository
import com.timbra.ui.ItemActions
import com.timbra.ui.MainActivity
import com.timbra.ui.dialogs.Dialogs
import com.timbra.ui.linearWithDivider
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A browse list that renders either an index of albums/artists/genres/playlists
 * (navigable rows) or a flat list of playable tracks, depending on [listKind].
 */
class TrackListFragment : Fragment(), MenuProvider {

    private var _b: FragmentListBinding? = null
    private val b get() = _b!!

    private lateinit var kind: String
    private var listId: Long = -1L
    private lateinit var listTitle: String

    private lateinit var adapter: LibraryListAdapter
    private var tracks: List<Track> = emptyList()
    private lateinit var sortOrder: SortOrder

    private val isTrackMode: Boolean get() = kind in TRACK_KINDS

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        kind = requireArguments().getString("listKind", KIND_SONGS)
        listId = requireArguments().getLong("listId", -1L)
        listTitle = requireArguments().getString("listTitle", getString(R.string.library))
        sortOrder = defaultSortFor(kind)

        (requireActivity() as AppCompatActivity).supportActionBar?.title = listTitle

        adapter = LibraryListAdapter(
            owner = viewLifecycleOwner,
            onTrack = { index -> player().play(tracks, index) },
            onFolder = { /* not used here */ },
            onNav = { nav -> openIndexTarget(nav) },
            onLongItem = { item ->
                if (item is ListItem.TrackRow) {
                    ItemActions.show(this, item.track.displayTitle, listOf(item.track))
                }
            },
        )
        b.recycler.linearWithDivider()
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
            val repo = requireContext().repository
            val items: List<ListItem> = when (kind) {
                KIND_SONGS -> trackRows(repo.allTracks().sortedBy(sortOrder))
                KIND_ALBUM -> trackRows(repo.tracksForAlbum(listId).sortedBy(sortOrder))
                KIND_ARTIST -> trackRows(repo.tracksForArtist(listTitle).sortedBy(sortOrder))
                KIND_GENRE -> trackRows(repo.tracksForGenre(listId).sortedBy(sortOrder))
                KIND_PLAYLIST -> trackRows(repo.tracksForPlaylist(listId))
                KIND_ALBUMS -> repo.albums().map {
                    ListItem.NavRow(it.title, countLabel(it.trackCount), R.drawable.matte_albums,
                        KIND_ALBUM, it.id, it.title)
                }
                KIND_ARTISTS -> repo.artists().map {
                    ListItem.NavRow(it.name, countLabel(it.trackCount), R.drawable.matte_artists,
                        KIND_ARTIST, it.id, it.name)
                }
                KIND_GENRES -> repo.genres().map {
                    ListItem.NavRow(it.name, countLabel(it.trackCount), R.drawable.matte_genres,
                        KIND_GENRE, it.id, it.name)
                }
                KIND_PLAYLISTS -> repo.playlists().map {
                    ListItem.NavRow(it.name, countLabel(it.trackCount), R.drawable.matte_playlists,
                        KIND_PLAYLIST, it.id, it.name)
                }
                else -> emptyList()
            }
            _b ?: return@launch
            adapter.submit(items)
            b.empty.isVisible = items.isEmpty()
        }
    }

    private fun trackRows(sorted: List<Track>): List<ListItem> {
        tracks = sorted
        return sorted.mapIndexed { i, t -> ListItem.TrackRow(t, i) }
    }

    private fun countLabel(n: Int) = getString(R.string.song_count, n)

    private fun openIndexTarget(nav: ListItem.NavRow) {
        findNavController().navigate(
            R.id.trackListFragment,
            bundleOf("listKind" to nav.kind, "listId" to nav.id, "listTitle" to nav.navTitle),
        )
    }

    // --- Menu (Sort) ---

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_list, menu)
        menu.findItem(R.id.action_view_as)?.isVisible = false
        menu.findItem(R.id.action_sort)?.isVisible = isTrackMode
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_sort -> {
            Dialogs.showSort(requireContext(), SortOrder.entries, sortOrder) {
                sortOrder = it
                load()
            }
            true
        }
        else -> false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        private val TRACK_KINDS = setOf(KIND_SONGS, KIND_ALBUM, KIND_ARTIST, KIND_GENRE, KIND_PLAYLIST)

        const val KIND_SONGS = "songs"
        const val KIND_ALBUMS = "albums"
        const val KIND_ALBUM = "album"
        const val KIND_ARTISTS = "artists"
        const val KIND_ARTIST = "artist"
        const val KIND_GENRES = "genres"
        const val KIND_GENRE = "genre"
        const val KIND_PLAYLISTS = "playlists"
        const val KIND_PLAYLIST = "playlist"

        fun defaultSortFor(kind: String): SortOrder = when (kind) {
            KIND_ALBUM -> SortDefaults.ALBUM_TRACKS
            else -> SortDefaults.LIBRARY_SONGS
        }
    }
}

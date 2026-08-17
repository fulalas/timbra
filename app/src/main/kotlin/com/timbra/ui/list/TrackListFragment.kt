// SPDX-License-Identifier: GPL-3.0-or-later
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.timbra.R
import com.timbra.data.SortDefaults
import com.timbra.data.SortOrder
import com.timbra.data.sortedBy
import com.timbra.data.model.Track
import com.timbra.player.enumByName
import com.timbra.databinding.FragmentListBinding
import com.timbra.repository
import com.timbra.ui.ItemActions
import com.timbra.ui.dialogs.Dialogs
import com.timbra.ui.linearWithDivider
import com.timbra.ui.player
import com.timbra.ui.reloadOnLibraryChange
import com.timbra.ui.trackNowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrackListFragment : Fragment(), MenuProvider {

    private var _b: FragmentListBinding? = null
    private val b get() = _b!!

    private lateinit var kind: String
    private var listId: Long = -1L
    private lateinit var listTitle: String

    private lateinit var adapter: LibraryListAdapter
    private lateinit var sortOrder: SortOrder

    /**
     * What a row tap plays, committed on the main thread TOGETHER with the rows it belongs to.
     * Assigning it from inside the background sort block meant that during a re-sort it no longer
     * matched the rows on screen, so a tap carried an index from the old order into the new one
     * and played a different song.
     */
    private var playable: List<Track> = emptyList()

    /** The in-flight [load]; replacing it cancels the old one, so only the newest build commits. */
    private var loadJob: Job? = null

    private val isTrackMode: Boolean get() = kind in TRACK_KINDS

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        kind = requireArguments().getString("listKind", KIND_SONGS)
        listId = requireArguments().getLong("listId", -1L)
        listTitle = requireArguments().getString("listTitle", getString(R.string.library))
        // Restored across rotation and back-stack returns; FolderSort's doc calls out losing a
        // sort choice that way as a bug, and this screen had it too.
        sortOrder = savedInstanceState?.getString(STATE_SORT)
            ?.let { enumByName(it, defaultSortFor(kind)) }
            ?: defaultSortFor(kind)

        (requireActivity() as AppCompatActivity).supportActionBar?.title = listTitle

        adapter = LibraryListAdapter(
            owner = viewLifecycleOwner,
            onTrack = { index -> player.play(playable, index) },
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

        reloadOnLibraryChange { load() }
        trackNowPlaying(adapter)
    }

    private fun load() {
        // Cancel the build already running: the sort dialog and the library-epoch reload both call
        // this, and an older, slower whole-library sort could otherwise commit last and pair stale
        // rows with a stale `playable` list — a tap then plays a different song.
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val repo = requireContext().repository
            // Resolved on the main thread while the fragment is definitely attached: the row
            // mapping below runs on Dispatchers.Default, and Fragment.getString() there would
            // throw once the user navigated away mid-load.
            val res = requireContext().resources
            // The queries are IO-dispatched, but the whole-library natural sort isn't free —
            // keep it (and the row mapping) off the main thread too.
            val built = withContext(Dispatchers.Default) { when (kind) {
                KIND_SONGS -> trackRows(repo.allTracks().sortedBy(sortOrder))
                KIND_ALBUM -> trackRows(repo.tracksForAlbum(listId).sortedBy(sortOrder))
                KIND_ARTIST -> trackRows(repo.tracksForArtist(listTitle).sortedBy(sortOrder))
                KIND_GENRE -> trackRows(repo.tracksForGenre(listId).sortedBy(sortOrder))
                KIND_PLAYLIST -> trackRows(repo.tracksForPlaylist(listId))
                KIND_ALBUMS -> navRows(res, repo.albums(), R.drawable.matte_albums, KIND_ALBUM) {
                    Triple(it.id, it.title, it.trackCount)
                }
                KIND_ARTISTS -> navRows(res, repo.artists(), R.drawable.matte_artists, KIND_ARTIST) {
                    Triple(it.id, it.name, it.trackCount)
                }
                KIND_GENRES -> navRows(res, repo.genres(), R.drawable.matte_genres, KIND_GENRE) {
                    Triple(it.id, it.name, it.trackCount)
                }
                KIND_PLAYLISTS -> navRows(res, repo.playlists(), R.drawable.matte_playlists, KIND_PLAYLIST) {
                    Triple(it.id, it.name, it.trackCount)
                }
                else -> Built(emptyList(), emptyList())
            } }
            _b ?: return@launch
            // Commit the rows and what they play in one step, on the main thread.
            playable = built.playable
            adapter.submit(built.items)
            b.empty.isVisible = built.items.isEmpty()
        }
    }

    private class Built(val items: List<ListItem>, val playable: List<Track>)

    private fun trackRows(sorted: List<Track>): Built =
        Built(sorted.mapIndexed { i, t -> ListItem.TrackRow(t, i) }, sorted)

    private fun <T> navRows(
        res: android.content.res.Resources,
        entries: List<T>,
        iconRes: Int,
        kind: String,
        info: (T) -> Triple<Long, String, Int>,
    ): Built = Built(
        entries.map { entry ->
            val (id, label, count) = info(entry)
            ListItem.NavRow(
                label, res.getQuantityString(R.plurals.song_count, count, count),
                iconRes, kind, id, label,
            )
        },
        emptyList(),
    )

    private fun openIndexTarget(nav: ListItem.NavRow) {
        findNavController().navigate(
            R.id.trackListFragment,
            bundleOf("listKind" to nav.kind, "listId" to nav.id, "listTitle" to nav.navTitle),
        )
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_list, menu)
        menu.findItem(R.id.action_view_as)?.isVisible = false
        menu.findItem(R.id.action_sort)?.isVisible = isTrackMode
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_sort -> {
            Dialogs.showSort(requireContext(), sortOrder) {
                sortOrder = it
                load()
            }
            true
        }
        else -> false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::sortOrder.isInitialized) outState.putString(STATE_SORT, sortOrder.name)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        private const val STATE_SORT = "sortOrder"

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

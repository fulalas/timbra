// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.timbra.R
import com.timbra.data.model.Track
import com.timbra.data.sortedBy
import com.timbra.data.tracksInPlayOrder
import com.timbra.databinding.FragmentSearchBinding
import com.timbra.folderSort
import com.timbra.repository
import com.timbra.ui.ItemActions
import com.timbra.ui.dialogs.Dialogs
import com.timbra.ui.linearWithDivider
import com.timbra.ui.player
import com.timbra.ui.trackNowPlaying
import com.timbra.ui.list.LibraryListAdapter
import com.timbra.ui.list.ListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchFragment : Fragment() {

    private var _b: FragmentSearchBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: LibraryListAdapter

    private var results: List<Track> = emptyList()
    private var searchJob: Job? = null

    private var imeFocusObserver: ViewTreeObserver? = null
    private var imeFocusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentSearchBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = LibraryListAdapter(
            owner = viewLifecycleOwner,
            onTrack = { index -> promptPlay(index) },
            onLongItem = { item ->
                if (item is ListItem.TrackRow) {
                    ItemActions.show(this, item.track.displayTitle, listOf(item.track))
                }
            },
        )
        b.recycler.linearWithDivider(divider = false)
        b.recycler.adapter = adapter

        b.searchInput.addTextChangedListener { onQuery(it?.toString().orEmpty()) }
        b.searchInput.setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
        b.searchInput.post {
            val bb = _b ?: return@post
            if (bb.searchInput.hasWindowFocus()) {
                showKeyboard()
            } else {
                val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
                    override fun onWindowFocusChanged(hasFocus: Boolean) {
                        if (!hasFocus) return
                        removeImeFocusListener()
                        showKeyboard()
                    }
                }
                imeFocusListener = listener
                imeFocusObserver = bb.searchInput.viewTreeObserver
                    .also { it.addOnWindowFocusChangeListener(listener) }
            }
        }

        trackNowPlaying(adapter)
    }

    private fun onQuery(raw: String) {
        searchJob?.cancel()
        val query = raw.trim()
        if (query.isEmpty()) {
            results = emptyList()
            adapter.submit(emptyList())
            b.empty.setText(R.string.search_prompt)
            b.empty.isVisible = true
            return
        }
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(200)
            val all = requireContext().repository.allTracks()
            val filtered = withContext(Dispatchers.Default) {
                all.filter {
                    it.title.contains(query, true) || it.artist.contains(query, true) ||
                        it.album.contains(query, true) || it.fileName.contains(query, true)
                }
            }
            if (_b == null) return@launch
            results = filtered
            adapter.submit(filtered.mapIndexed { i, t -> ListItem.TrackRow(t, i) })
            b.empty.isVisible = filtered.isEmpty()
            if (filtered.isEmpty()) b.empty.setText(R.string.search_no_results)
        }
    }

    private fun promptPlay(index: Int) {
        val snapshot = results
        val track = snapshot.getOrNull(index) ?: return
        Dialogs.actions(
            requireContext(),
            getString(R.string.search_play_title, track.displayTitle),
            arrayOf(
                getString(R.string.search_play_all_results),
                getString(R.string.search_play_folder),
            ),
        ) { which ->
            when (which) {
                0 -> playAllResults(snapshot, index)
                1 -> playFolder(track)
            }
        }
    }

    private fun playAllResults(tracks: List<Track>, index: Int) {
        val ordered = ArrayList<Track>(tracks.size)
        ordered += tracks[index]
        tracks.forEachIndexed { i, t -> if (i != index) ordered += t }
        player.play(ordered, 0)
    }

    private fun playFolder(track: Track) {
        val dir = track.path.substringBeforeLast('/', "")
        viewLifecycleOwner.lifecycleScope.launch {
            val repo = requireContext().repository
            val order = requireContext().folderSort.sortOrder
            val node = repo.songFolders().firstOrNull { it.path == dir }
            val all = if (node == null) repo.allTracks() else emptyList()
            val folderTracks = withContext(Dispatchers.Default) {
                node?.tracksInPlayOrder(order)
                    ?: all.filter { it.path.substringBeforeLast('/', "") == dir }.sortedBy(order)
            }
            val start = folderTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            if (_b == null) return@launch
            player.play(folderTracks, start, folderContext = dir)
        }
    }

    private fun showKeyboard() {
        val et = _b?.searchInput ?: return
        et.requestFocus()
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(et, 0)
    }

    private fun hideKeyboard() {
        val et = _b?.searchInput ?: return
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(et.windowToken, 0)
    }

    private fun removeImeFocusListener() {
        val listener = imeFocusListener ?: return
        imeFocusObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener)
        imeFocusListener = null
        imeFocusObserver = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        removeImeFocusListener()
        _b = null
    }
}

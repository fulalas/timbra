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

/** Searches every song in the library (title / artist / album / filename). */
class SearchFragment : Fragment() {

    private var _b: FragmentSearchBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: LibraryListAdapter

    private var results: List<Track> = emptyList()
    private var searchJob: Job? = null

    // One-shot listener that waits for window focus before showing the keyboard; tracked
    // (with the exact observer it was added to) so it can be removed even if the view is
    // destroyed before it ever fires — otherwise it leaks the fragment on the window observer.
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
        // The layout declares imeOptions=actionSearch, which puts a Search key on the keyboard.
        // Results are already live-debounced, so the useful thing for it to do is get the keyboard
        // out of the way of the results this screen went to some trouble to raise.
        b.searchInput.setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
        // Pop the keyboard as soon as Search opens so the user can type straight away. Posted so
        // it runs after the view is attached. Search is reached from the overflow menu, whose
        // popup holds window focus; until the activity window gets it back, showSoftInput is
        // silently dropped — so show now if we already have window focus, else wait for it.
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
        // NOT lowercased: the matching below is already case-insensitive, so folding here was
        // pure waste (and could only ever change which characters the user's own input matched).
        val query = raw.trim()
        if (query.isEmpty()) {
            results = emptyList()
            adapter.submit(emptyList())
            b.empty.setText(R.string.search_prompt)
            b.empty.isVisible = true
            return
        }
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(200) // debounce rapid keystrokes
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

    /**
     * A tapped search result can start playback two ways, so ask which: play all found
     * songs (the tapped one first), or play the tapped song and continue through its folder
     * (Advance-List then flows on into neighbouring folders).
     */
    private fun promptPlay(index: Int) {
        // Snapshot the tapped list: a debounced search can replace `results` while this dialog
        // is open, so both actions must act on what the user actually tapped, not the live list.
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

    /** Play every result in [tracks], with the tapped song (at [index]) pulled to the front. */
    private fun playAllResults(tracks: List<Track>, index: Int) {
        val ordered = ArrayList<Track>(tracks.size)
        ordered += tracks[index]
        tracks.forEachIndexed { i, t -> if (i != index) ordered += t }
        player.play(ordered, 0)
    }

    /** Play the tapped song within its folder, so playback continues down the folder list. */
    private fun playFolder(track: Track) {
        val dir = track.path.substringBeforeLast('/', "")
        viewLifecycleOwner.lifecycleScope.launch {
            val repo = requireContext().repository
            val order = requireContext().folderSort.sortOrder
            val node = repo.songFolders().firstOrNull { it.path == dir }
            val all = if (node == null) repo.allTracks() else emptyList()
            // The filter + natural sort are whole-library work in the fallback case; the repo
            // calls hop back to Main, so do them on Default rather than on the UI thread.
            val folderTracks = withContext(Dispatchers.Default) {
                // If the folder node isn't found, rebuild it by directory from all tracks rather
                // than degrading to a one-song queue — "Its folder" should queue the whole folder.
                node?.tracksInPlayOrder(order)
                    ?: all.filter { it.path.substringBeforeLast('/', "") == dir }.sortedBy(order)
            }
            val start = folderTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            if (_b == null) return@launch
            player.play(folderTracks, start, folderContext = dir)
        }
    }

    /**
     * Focus the field and force the on-screen keyboard up. Explicit show (flag 0), NOT
     * SHOW_IMPLICIT: the framework drops an implicit request when it thinks a hardware
     * keyboard is present (e.g. a GSI's virtual input device), so the keyboard never appears.
     */
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

    /** Detach the pending window-focus listener from the exact observer it was added to. */
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

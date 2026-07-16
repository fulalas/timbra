package com.timbra.ui.search

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.timbra.R
import com.timbra.data.model.Track
import com.timbra.databinding.FragmentSearchBinding
import com.timbra.repository
import com.timbra.ui.ItemActions
import com.timbra.ui.MainActivity
import com.timbra.ui.linearWithDivider
import com.timbra.ui.list.LibraryListAdapter
import com.timbra.ui.list.ListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Searches every song in the library (title / artist / album / filename). */
class SearchFragment : Fragment() {

    private var _b: FragmentSearchBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: LibraryListAdapter
    private val player get() = (requireActivity() as MainActivity).player

    private var results: List<Track> = emptyList()
    private var searchJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentSearchBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = LibraryListAdapter(
            owner = viewLifecycleOwner,
            onTrack = { index -> player.play(results, index) },
            onFolder = { },
            onNav = { },
            onLongItem = { item ->
                if (item is ListItem.TrackRow) {
                    ItemActions.show(this, item.track.displayTitle, listOf(item.track))
                }
            },
        )
        b.recycler.linearWithDivider(divider = false)
        b.recycler.adapter = adapter

        b.searchInput.addTextChangedListener { onQuery(it?.toString().orEmpty()) }
        b.searchInput.requestFocus()
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(b.searchInput, InputMethodManager.SHOW_IMPLICIT)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                player.state.map { it.mediaId }.distinctUntilChanged()
                    .collect { adapter.currentMediaId = it }
            }
        }
    }

    private fun onQuery(raw: String) {
        searchJob?.cancel()
        val query = raw.trim().lowercase()
        if (query.isEmpty()) {
            results = emptyList()
            adapter.submit(emptyList())
            showEmpty(R.string.search_prompt)
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
            if (filtered.isEmpty()) showEmpty(R.string.search_no_results) else b.empty.visibility = View.GONE
        }
    }

    private fun showEmpty(textRes: Int) {
        b.empty.setText(textRes)
        b.empty.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}

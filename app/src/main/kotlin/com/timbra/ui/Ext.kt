// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.timbra.R
import com.timbra.app
import com.timbra.player.PlayerConnection
import com.timbra.ui.list.LibraryListAdapter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val Fragment.player: PlayerConnection
    get() = (requireActivity() as MainActivity).player

fun Fragment.trackNowPlaying(adapter: LibraryListAdapter) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            player.state.map { it.mediaId }.distinctUntilChanged()
                .collect { adapter.currentMediaId = it }
        }
    }
}

fun Fragment.reloadOnLibraryChange(onChange: () -> Unit) {
    var loadedEpoch = -1
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            requireContext().app.libraryEpoch.collect { epoch ->
                if (epoch != loadedEpoch) {
                    loadedEpoch = epoch
                    onChange()
                }
            }
        }
    }
}

fun RecyclerView.linearWithDivider(divider: Boolean = true) {
    layoutManager = LinearLayoutManager(context)
    if (divider) addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
}

fun applyNowPlaying(root: View, title: TextView, playing: Boolean) {
    root.setBackgroundResource(if (playing) R.drawable.row_bg_playing else R.drawable.row_bg)
    title.setTextColor(
        ContextCompat.getColor(root.context, if (playing) R.color.pa_accent else R.color.pa_text_primary),
    )
}

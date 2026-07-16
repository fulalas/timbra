package com.timbra.ui

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.timbra.R

/** Standard vertical list wiring used by every browse screen. */
fun RecyclerView.linearWithDivider(divider: Boolean = true) {
    layoutManager = LinearLayoutManager(context)
    if (divider) addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
}

/** Apply the now-playing highlight (accent background + accent title) to a list row. */
fun applyNowPlaying(root: View, title: TextView, playing: Boolean) {
    root.setBackgroundResource(if (playing) R.drawable.row_bg_playing else R.drawable.row_bg)
    title.setTextColor(
        ContextCompat.getColor(root.context, if (playing) R.color.pa_accent else R.color.pa_text_primary),
    )
}

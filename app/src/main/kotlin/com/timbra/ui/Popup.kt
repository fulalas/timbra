// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui

import android.view.View
import android.widget.TextView

object Popup {

    const val SHORT_MS = 1_500L
    const val LONG_MS = 2_800L

    private const val FADE_MS = 150L

    fun show(view: TextView, msg: String, durationMs: Long = SHORT_MS) {
        // Cancel BEFORE re-showing: ViewPropertyAnimator runs withEndAction on cancel too, so
        // the outgoing fade's hide would land right after this show and blank the popup.
        view.animate().cancel()
        view.text = msg
        view.alpha = 1f
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(0f)
            .setStartDelay(durationMs)
            .setDuration(FADE_MS)
            .withEndAction { view.visibility = View.GONE }
    }
}

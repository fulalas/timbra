// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui.eq

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.roundToInt

class VerticalFader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var max = 100
    var onValue: ((Int) -> Unit)? = null

    var onRelease: (() -> Unit)? = null

    // Steal touches from the child SeekBar so the entire column is the hit target.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = isEnabled

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        // CANCEL is not a release: the gesture was taken away (an ancestor claimed it, the window
        // lost focus, a dialog opened over the screen). Treating it like ACTION_UP applied the
        // cancel coordinate as a value AND fired onRelease, which the equalizer screen wires
        // straight to setGains — persisting a band gain the user never chose.
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
            return true
        }
        val released = when (event.action) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); false }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                true
            }
            else -> false
        }
        // Map the event's Y first, THEN persist: the release coordinate is itself a value, and
        // notifying afterwards meant the last step of a drag reached the live DSP but never
        // SharedPreferences (screen and sound showed one gain, prefs held the previous one).
        if (height > 0) {
            val frac = 1f - (event.y / height).coerceIn(0f, 1f)
            onValue?.invoke((frac * max).roundToInt())
        }
        if (released) onRelease?.invoke()
        return true
    }
}

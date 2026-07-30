package com.timbra.ui.eq

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Column container for one equalizer band. Holds the (rotated) fader SeekBar for visuals but
 * grabs all touches itself, so the user can drag anywhere in the whole column — not just on the
 * thin thumb — to change the band. Touch Y is mapped to [0, max] (top = max).
 */
class VerticalFader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var max = 100
    var onValue: ((Int) -> Unit)? = null

    /** Fires when the finger lifts (or the gesture is cancelled) — the moment to persist. */
    var onRelease: (() -> Unit)? = null

    // Steal touches from the child SeekBar so the entire column is the hit target.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = isEnabled

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val released = when (event.action) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); false }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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

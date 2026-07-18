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

    // Steal touches from the child SeekBar so the entire column is the hit target.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = isEnabled

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (height > 0) {
            val frac = 1f - (event.y / height).coerceIn(0f, 1f)
            onValue?.invoke((frac * max).roundToInt())
        }
        return true
    }
}

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

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = isEnabled

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
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
        if (height > 0) {
            val frac = 1f - (event.y / height).coerceIn(0f, 1f)
            onValue?.invoke((frac * max).roundToInt())
        }
        if (released) onRelease?.invoke()
        return true
    }
}

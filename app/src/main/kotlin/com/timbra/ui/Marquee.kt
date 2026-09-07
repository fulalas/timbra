// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui

import android.text.TextUtils
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.core.view.doOnLayout

class TitleMarquee(private val tv: TextView) {

    val view: TextView get() = tv

    private var scroll: Runnable? = null

    private var text: String = ""

    /**
     * Bumped by every [set]/[scrollOnce]/[stop]. [scrollOnce] defers all its work into a
     * `doOnLayout` block that cannot be un-registered, so without this a run started for one
     * screen fired AFTER [stop] and overwrote the (SHARED, in the toolbar's case) TextView with
     * the previous screen's doubled title — which then kept scrolling, since the runnable's own
     * guards were both satisfied.
     */
    private var epoch = 0

    fun set(value: String) {
        text = value
        scrollOnce()
    }

    fun stop() {
        epoch++
        scroll = null
        tv.setHorizontallyScrolling(false)
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.scrollTo(0, 0)
    }

    fun scrollOnce() {
        scroll = null
        val startedAt = ++epoch
        // Re-apply the state a scroll needs rather than assuming [set] left it in place: [stop]
        // deliberately undoes both, and this is the tap-to-replay entry point — MainActivity wires
        // it to a persistent click listener on the SHARED toolbar title view, so a tap arriving
        // after a stop was scrolling a layout clipped to the viewport and end-ellipsized, and
        // nothing visibly moved.
        tv.ellipsize = null
        tv.setHorizontallyScrolling(true)
        tv.text = text // reset in case a prior interrupted run left it doubled
        tv.scrollTo(0, 0)
        tv.doOnLayout {
            if (epoch != startedAt) return@doOnLayout
            val viewport = tv.width - tv.paddingLeft - tv.paddingRight
            val lineWidth = tv.paint.measureText(text)
            if (lineWidth <= viewport) { scroll = null; return@doOnLayout }

            val density = tv.resources.displayMetrics.density
            val gapPx = GAP_DP * density
            val spaceW = tv.paint.measureText(" ").coerceAtLeast(1f)
            val nSpaces = (gapPx / spaceW).toInt().coerceAtLeast(1)
            val doubled = text + " ".repeat(nSpaces) + text
            tv.text = doubled
            val distance = lineWidth + nSpaces * spaceW
            val outMs = distance / (DP_S * density / 1000f)
            val t0 = AnimationUtils.currentAnimationTimeMillis()
            val run = object : Runnable {
                override fun run() {
                    if (scroll !== this || tv.text !== doubled) return
                    val t = AnimationUtils.currentAnimationTimeMillis() - t0
                    if (t < START_HOLD_MS) {
                        tv.postOnAnimation(this); return
                    }
                    val p = (t - START_HOLD_MS) / outMs
                    if (p < 1f) {
                        tv.scrollTo((distance * p).toInt(), 0)
                        tv.postOnAnimation(this)
                    } else {
                        tv.text = text
                        tv.scrollTo(0, 0)
                        scroll = null
                    }
                }
            }
            scroll = run
            tv.postOnAnimation(run)
        }
    }

    private companion object {
        const val DP_S = 53.3f

        const val GAP_DP = 48f

        const val START_HOLD_MS = 500L
    }
}

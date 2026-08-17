// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui

import android.text.TextUtils
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.core.view.doOnLayout

class TitleMarquee(private val tv: TextView) {

    /** The [TextView] this instance drives, so an owner can tell whether a re-fetched (shared)
     *  view is still the same instance and reuse this controller rather than double-driving it. */
    val view: TextView get() = tv

    /** The in-flight run; replacing it (or [stop]) cancels the old one, checked by identity. */
    private var scroll: Runnable? = null

    /** The clean (single) string, so a re-run never doubles an already-doubled text. */
    private var text: String = ""

    /**
     * Bumped by every [set]/[scrollOnce]/[stop]. [scrollOnce] defers all its work into a
     * `doOnLayout` block that cannot be un-registered, so without this a run started for one
     * screen fired AFTER [stop] and overwrote the (SHARED, in the toolbar's case) TextView with
     * the previous screen's doubled title — which then kept scrolling, since the runnable's own
     * guards were both satisfied.
     */
    private var epoch = 0

    /** Set the text and marquee it once when it doesn't fit; ellipsizing is dropped (it would
     *  shrink the layout we scroll). Safe to call every frame only if the text is unchanged —
     *  a genuinely new string restarts the loop, so callers should guard on change. */
    fun set(value: String) {
        text = value
        scrollOnce()
    }

    /**
     * Return the view to its stock, non-scrolling, end-ellipsized state and cancel any run.
     * Leaves the text alone: a SHARED toolbar view has its title replaced by navigation right
     * after, and a dedicated view is either about to get a fresh [set] or is being destroyed —
     * so restoring the single string here would only risk clobbering the next screen's title.
     */
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
            if (lineWidth <= viewport) { scroll = null; return@doOnLayout } // fits — no scroll

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
                    if (t < START_HOLD_MS) { // brief readable pause on the start
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

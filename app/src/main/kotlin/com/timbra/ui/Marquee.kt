package com.timbra.ui

import android.text.TextUtils
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.core.view.doOnLayout

/**
 * One-shot horizontal marquee for a single [TextView]: when the text is wider than the view
 * it scrolls ONE full loop — the start slides off the left and wraps back in from the right,
 * landing exactly at rest — then stops. Text that fits is left untouched.
 *
 * Hand-rolled on postOnAnimation rather than the stock TextView marquee: the stock speed is a
 * fixed private constant (we want it faster), stock marquee needs focus/selection (here the
 * view is either shared across screens or already claims taps for another action), and
 * frame-stepped scrolling also ignores the dev device's zeroed animator scales.
 *
 * Used for the toolbar folder-path title (tap replays it, see [scrollOnce]) and the player's
 * song title (auto only — a tap there opens the song's folder instead).
 */
class TitleMarquee(private val tv: TextView) {

    /** The [TextView] this instance drives, so an owner can tell whether a re-fetched (shared)
     *  view is still the same instance and reuse this controller rather than double-driving it. */
    val view: TextView get() = tv

    /** The in-flight run; replacing it (or [stop]) cancels the old one, checked by identity. */
    private var scroll: Runnable? = null

    /** The clean (single) string, so a re-run never doubles an already-doubled text. */
    private var text: String = ""

    /** Set the text and marquee it once when it doesn't fit; ellipsizing is dropped (it would
     *  shrink the layout we scroll). Safe to call every frame only if the text is unchanged —
     *  a genuinely new string restarts the loop, so callers should guard on change. */
    fun set(value: String) {
        text = value
        tv.text = value
        tv.ellipsize = null
        tv.setHorizontallyScrolling(true)
        scrollOnce()
    }

    /**
     * Return the view to its stock, non-scrolling, end-ellipsized state and cancel any run.
     * Leaves the text alone: a SHARED toolbar view has its title replaced by navigation right
     * after, and a dedicated view is either about to get a fresh [set] or is being destroyed —
     * so restoring the single string here would only risk clobbering the next screen's title.
     */
    fun stop() {
        scroll = null
        tv.setHorizontallyScrolling(false)
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.scrollTo(0, 0)
    }

    /**
     * Marquee the current text ONE full loop, then stop — the beginning scrolls off the left
     * and wraps around from the right, landing back at rest (no jarring snap-to-start).
     *
     * A plain TextView can't draw a wrap-around ghost, so we give it a doubled string
     * ("title <gap> title") and scroll by exactly one copy+gap: at the end the SECOND copy
     * sits precisely where the first began, so restoring the single title is invisible.
     *
     * [doOnLayout] is the readiness signal — setting the title invalidates the text layout
     * and schedules a re-layout; measuring before it runs (the screen-entry case) would read
     * a stale width as "fits" and never start. It fires immediately when already laid out
     * (the tap case).
     */
    fun scrollOnce() {
        scroll = null
        tv.text = text // reset in case a prior interrupted run left it doubled
        tv.scrollTo(0, 0)
        tv.doOnLayout {
            val viewport = tv.width - tv.paddingLeft - tv.paddingRight
            val lineWidth = tv.paint.measureText(text)
            if (lineWidth <= viewport) { scroll = null; return@doOnLayout } // fits — no scroll

            // Ghost copy separated by a gap, so the wrap reads as one continuous loop.
            val density = tv.resources.displayMetrics.density
            val gapPx = GAP_DP * density
            val spaceW = tv.paint.measureText(" ").coerceAtLeast(1f)
            val nSpaces = (gapPx / spaceW).toInt().coerceAtLeast(1)
            val doubled = text + " ".repeat(nSpaces) + text
            tv.text = doubled
            // The wrap point: distance to bring the second copy's start to the left edge.
            val distance = lineWidth + nSpaces * spaceW
            val outMs = distance / (DP_S * density / 1000f)
            val t0 = AnimationUtils.currentAnimationTimeMillis()
            val run = object : Runnable {
                override fun run() {
                    // Die out when replaced, or when the text was swapped underneath us.
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
                        // Landed on the second copy's start = identical to the first at rest;
                        // restore the single title and snap to 0, seamless.
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
        /** Marquee speed (dp/s). Stock TextView marquee is 30dp/s; this is faster. */
        const val DP_S = 53.3f

        /** Gap between the end of the title and its wrapped-around start, during the loop. */
        const val GAP_DP = 48f

        /** Brief readable pause on the start before the loop begins. */
        const val START_HOLD_MS = 500L
    }
}

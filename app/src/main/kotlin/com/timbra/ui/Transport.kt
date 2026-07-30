package com.timbra.ui

import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.timbra.R
import com.timbra.player.UiPlayback

/**
 * The transport wiring shared by the mini-player and the full player: a seek bar that reports
 * drags, the position/duration clocks, and the play/pause icon.
 *
 * Both screens render the same [UiPlayback], and their hand-written copies of this had already
 * drifted — so a fix to the seek behaviour, the duration clamp or the icon states landed on one
 * screen and not the other.
 */
class TransportBinder(
    private val seek: SeekBar,
    private val position: TextView,
    private val duration: TextView,
    private val play: ImageView,
    private val onSeek: (Long) -> Unit,
) {

    /** True while a finger is on the bar, so position ticks don't fight the drag. */
    private var userSeeking = false

    init {
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) position.text = Format.clock(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                onSeek(sb.progress.toLong())
            }
        })
    }

    /**
     * Apply [s]. [prev] is the state last applied (null re-applies everything), so the 500ms
     * position ticks only touch the views whose source actually changed — setImageResource in
     * particular reloads and invalidates even for an unchanged resource id.
     */
    fun bind(s: UiPlayback, prev: UiPlayback?) {
        if (prev == null || s.isPlaying != prev.isPlaying) {
            play.setImageResource(if (s.isPlaying) R.drawable.deck_pause else R.drawable.deck_play)
        }
        if (prev == null || s.durationMs != prev.durationMs) {
            seek.max = s.durationMs.toInt().coerceAtLeast(1)
            duration.text = Format.clock(s.durationMs)
        }
        if (!userSeeking) {
            seek.progress = s.positionMs.toInt().coerceIn(0, seek.max)
            position.text = Format.clock(s.positionMs)
        }
    }
}

package com.timbra.player

import androidx.media3.common.Player
import com.timbra.R

/**
 * App-level shuffle modes.
 * OFF -> CURRENT (shuffle songs in the current list) -> ALL (shuffle every song) -> OFF.
 * Returning to OFF restores the pre-shuffle queue (see PlayerConnection), so cycling through
 * the modes and back is non-destructive. Enabling shuffle regenerates a fresh random order.
 */
enum class ShuffleMode(val iconRes: Int, val titleRes: Int, val subtitleRes: Int) {
    OFF(R.drawable.matte_shuffle_none, R.string.shuffle_off, 0),
    CURRENT(R.drawable.matte_shuffle_songs, R.string.shuffle_songs, R.string.shuffle_songs_sub),
    ALL(R.drawable.matte_shuffle_all, R.string.shuffle_all, 0);

    fun next(): ShuffleMode = entries[(ordinal + 1) % entries.size]

    val playerShuffleEnabled: Boolean get() = this != OFF
}

/**
 * App-level repeat modes.
 * OFF -> LIST (loop list) -> ADVANCE (play next list at end) -> SONG (loop song) -> OFF.
 */
enum class RepeatMode(
    val iconRes: Int,
    val titleRes: Int,
    val subtitleRes: Int,
    val playerMode: Int,
) {
    OFF(R.drawable.matte_repeat_none, R.string.repeat_off, 0, Player.REPEAT_MODE_OFF),
    LIST(R.drawable.matte_repeat, R.string.repeat_list, R.string.repeat_list_sub, Player.REPEAT_MODE_ALL),
    ADVANCE(R.drawable.matte_repeat_advance, R.string.repeat_advance, R.string.repeat_advance_sub, Player.REPEAT_MODE_OFF),
    SONG(R.drawable.matte_repeat_song, R.string.repeat_song, 0, Player.REPEAT_MODE_ONE);

    fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]
}

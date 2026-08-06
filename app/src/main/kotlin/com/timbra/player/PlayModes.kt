// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.player

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.media3.common.Player
import com.timbra.R

/** Cycle to the next entry, wrapping — the ONE cycling rule both mode buttons use. */
inline fun <reified T : Enum<T>> T.cycleNext(): T {
    val all = enumValues<T>()
    return all[(ordinal + 1) % all.size]
}

/**
 * Resolve a PERSISTED enum by name, falling back to [default] for a missing/unknown one.
 *
 * Names, not ordinals: an ordinal is positional, so inserting or reordering an entry silently
 * reinterprets an already-stored value as a different mode — and an `entries.getOrElse` guard
 * cannot notice, because the stale ordinal is still in range.
 */
inline fun <reified T : Enum<T>> enumByName(name: String?, default: T): T =
    if (name == null) default else enumValues<T>().firstOrNull { it.name == name } ?: default

/**
 * App-level shuffle modes.
 * OFF -> CURRENT (shuffle songs in the current list) -> ALL (shuffle every song) -> OFF.
 * Returning to OFF restores the pre-shuffle queue (see PlayerConnection), so cycling through
 * the modes and back is non-destructive. Enabling shuffle regenerates a fresh random order.
 */
enum class ShuffleMode(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    /** Null when the mode needs no explanatory second line — absence is modelled in the type,
     *  so a caller cannot hand 0 to getString and get a NotFoundException. */
    @StringRes val subtitleRes: Int?,
) {
    OFF(R.drawable.matte_shuffle_none, R.string.shuffle_off, null),
    CURRENT(R.drawable.matte_shuffle_songs, R.string.shuffle_songs, R.string.shuffle_songs_sub),
    ALL(R.drawable.matte_shuffle_all, R.string.shuffle_all, null);

    val playerShuffleEnabled: Boolean get() = this != OFF

    /**
     * The mode a folder advance leaves behind: Shuffle-All's pool was the whole library, but
     * after the advance it is this ONE folder — which is exactly what Shuffle-Songs means, so
     * the icon would otherwise lie about the pool. Owned here because both the attached advance
     * (MainActivity) and the detached one (PlaybackService) have to apply the same rule.
     */
    fun narrowedToFolder(): ShuffleMode = if (this == ALL) CURRENT else this
}

/**
 * App-level repeat modes.
 * OFF -> LIST (loop list) -> ADVANCE (play next list at end) -> SONG (loop song) -> OFF.
 */
enum class RepeatMode(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    /** Null when the mode needs no explanatory second line (see [ShuffleMode.subtitleRes]). */
    @StringRes val subtitleRes: Int?,
    val playerMode: Int,
) {
    OFF(R.drawable.matte_repeat_none, R.string.repeat_off, null, Player.REPEAT_MODE_OFF),
    LIST(R.drawable.matte_repeat, R.string.repeat_list, R.string.repeat_list_sub, Player.REPEAT_MODE_ALL),
    ADVANCE(R.drawable.matte_repeat_advance, R.string.repeat_advance, R.string.repeat_advance_sub, Player.REPEAT_MODE_OFF),
    SONG(R.drawable.matte_repeat_song, R.string.repeat_song, null, Player.REPEAT_MODE_ONE);
}

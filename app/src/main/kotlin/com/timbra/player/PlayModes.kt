// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.player

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.media3.common.Player
import com.timbra.R

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

enum class ShuffleMode(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
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

enum class RepeatMode(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int?,
    val playerMode: Int,
) {
    OFF(R.drawable.matte_repeat_none, R.string.repeat_off, null, Player.REPEAT_MODE_OFF),
    LIST(R.drawable.matte_repeat, R.string.repeat_list, R.string.repeat_list_sub, Player.REPEAT_MODE_ALL),
    ADVANCE(R.drawable.matte_repeat_advance, R.string.repeat_advance, R.string.repeat_advance_sub, Player.REPEAT_MODE_OFF),
    SONG(R.drawable.matte_repeat_song, R.string.repeat_song, null, Player.REPEAT_MODE_ONE);
}

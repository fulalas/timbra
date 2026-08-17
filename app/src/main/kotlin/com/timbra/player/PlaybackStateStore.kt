// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.player

import android.content.Context
import androidx.media3.common.Player

class PlaybackStateStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("playback_state", Context.MODE_PRIVATE)

    data class Saved(
        val trackIds: List<Long>,
        val enqueuedIndices: List<Int>,
        val index: Int,
        val positionMs: Long,
        val shuffle: ShuffleMode,
        val repeat: RepeatMode,
    )

    /**
     * The queue only changes when the timeline changes — write the (potentially large) id list
     * rarely.
     *
     * The index and position go in the SAME transaction, because they only mean anything
     * relative to this id list: they used to be written by a different event, so a timeline
     * rebuild that fired only EVENT_TIMELINE_CHANGED (turning shuffle off, say) left the saved
     * index pointing into the previous queue, and a process death in that window restored the
     * wrong song at a meaningless position.
     */
    fun saveQueue(trackIds: List<Long>, enqueuedIndices: List<Int>, index: Int, positionMs: Long) {
        prefs.edit()
            .putString(KEY_IDS, joinLongs(trackIds))
            .putString(KEY_ENQ, joinInts(enqueuedIndices))
            .putInt(KEY_INDEX, index)
            .putLong(KEY_POS, positionMs)
            .apply()
    }

    /**
     * Bumps [modesRevision] so a reader can tell "nobody has touched these since I last wrote
     * them" from "someone else did" — the detached service narrows Shuffle-All when it advances
     * a folder, and a retained PlayerConnection has to notice.
     *
     * Synchronized because the bump is a read-modify-write and there are two writers by design:
     * [PlayerConnection] on the main thread and the service's detached folder advance. Two
     * interleaved calls would otherwise both read N and write N+1, losing an increment — and a
     * lost increment makes the connection conclude nobody changed the modes.
     */
    @Synchronized
    fun saveModes(shuffle: ShuffleMode, repeat: RepeatMode) {
        prefs.edit()
            .putString(KEY_SHUFFLE, shuffle.name)
            .putString(KEY_REPEAT, repeat.name)
            .putInt(KEY_MODES_REV, prefs.getInt(KEY_MODES_REV, 0) + 1)
            .apply()
    }

    fun modesRevision(): Int = prefs.getInt(KEY_MODES_REV, 0)

    // Pre-sized joins: these run on the main thread (from a Player.Listener callback), and a
    // 10k-track Shuffle-All queue grows an unsized StringBuilder through ~12 array copies.
    private fun joinLongs(values: List<Long>): String {
        val sb = StringBuilder(values.size * 8)
        for (i in values.indices) {
            if (i > 0) sb.append(',')
            sb.append(values[i])
        }
        return sb.toString()
    }

    private fun joinInts(values: List<Int>): String {
        val sb = StringBuilder(values.size * 5)
        for (i in values.indices) {
            if (i > 0) sb.append(',')
            sb.append(values[i])
        }
        return sb.toString()
    }

    private fun savePosition(index: Int, positionMs: Long) {
        prefs.edit().putInt(KEY_INDEX, index).putLong(KEY_POS, positionMs).apply()
    }

    fun checkpoint(player: Player) {
        if (player.mediaItemCount == 0) return
        savePosition(player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0))
    }

    fun load(): Saved? {
        val tokens = prefs.getString(KEY_IDS, null)
            ?.split(",")
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val ids = tokens.map { it.toLongOrNull() ?: return null }
        val enqueued = prefs.getString(KEY_ENQ, null)
            ?.split(",")
            ?.filter { it.isNotEmpty() }
            ?.map { it.toIntOrNull() ?: return null }
            ?.filter { it in ids.indices }
            ?: emptyList()
        val (shuffle, repeat) = loadModes()
        return Saved(
            trackIds = ids,
            enqueuedIndices = enqueued,
            index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, ids.lastIndex),
            positionMs = prefs.getLong(KEY_POS, 0).coerceAtLeast(0),
            shuffle = shuffle,
            repeat = repeat,
        )
    }

    /**
     * The persisted play modes — read independently of the saved queue, so a live-session
     * reconnect can re-adopt them even when the queue itself isn't (re)loaded from disk.
     *
     * Stored by NAME (see [enumByName]). The legacy ordinal keys are still read when no name is
     * present, so an in-place update keeps the user's modes instead of silently resetting
     * Advance-List to OFF. Defaults to OFF for both.
     */
    fun loadModes(): Pair<ShuffleMode, RepeatMode> {
        val shuffle = prefs.getString(KEY_SHUFFLE, null)
            ?.let { enumByName(it, ShuffleMode.OFF) }
            ?: ShuffleMode.entries.getOrElse(prefs.getInt(KEY_SHUFFLE_LEGACY, 0)) { ShuffleMode.OFF }
        val repeat = prefs.getString(KEY_REPEAT, null)
            ?.let { enumByName(it, RepeatMode.OFF) }
            ?: RepeatMode.entries.getOrElse(prefs.getInt(KEY_REPEAT_LEGACY, 0)) { RepeatMode.OFF }
        return shuffle to repeat
    }

    private companion object {
        const val KEY_IDS = "queue_ids"
        const val KEY_ENQ = "enqueued_indices"
        const val KEY_INDEX = "index"
        const val KEY_POS = "position"
        const val KEY_MODES_REV = "modes_revision"

        // Names, written since 0.8.0.
        const val KEY_SHUFFLE = "shuffle_name"
        const val KEY_REPEAT = "repeat_name"

        // Ordinals, written before 0.8.0 — read once, to migrate an existing install.
        const val KEY_SHUFFLE_LEGACY = "shuffle"
        const val KEY_REPEAT_LEGACY = "repeat"
    }
}

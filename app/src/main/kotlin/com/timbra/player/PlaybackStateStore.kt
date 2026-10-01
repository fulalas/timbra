// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.player

import android.content.Context
import androidx.media3.common.Player

fun queueFingerprint(mediaIds: List<String>): Int {
    var h = 1
    for (id in mediaIds) h = 31 * h + id.hashCode()
    return h
}

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
        val shufHistory: List<Int>,
        val shufPlayed: List<Int>,
    )

    fun saveQueue(trackIds: List<Long>, enqueuedIndices: List<Int>, index: Int, positionMs: Long) {
        prefs.edit()
            .putString(KEY_IDS, joinLongs(trackIds))
            .putString(KEY_ENQ, joinInts(enqueuedIndices))
            .putInt(KEY_INDEX, index)
            .putLong(KEY_POS, positionMs)
            .apply()
    }

    @Synchronized
    fun saveModes(shuffle: ShuffleMode, repeat: RepeatMode) {
        prefs.edit()
            .putString(KEY_SHUFFLE, shuffle.name)
            .putString(KEY_REPEAT, repeat.name)
            .putInt(KEY_MODES_REV, prefs.getInt(KEY_MODES_REV, 0) + 1)
            .apply()
    }

    fun modesRevision(): Int = prefs.getInt(KEY_MODES_REV, 0)

    fun saveShuffleSession(history: List<Int>, played: Set<Int>, fingerprint: Int) {
        prefs.edit()
            .putString(KEY_SHUF_HIST, joinInts(history))
            .putString(KEY_SHUF_PLAYED, joinInts(played.toList()))
            .putInt(KEY_SHUF_FP, fingerprint)
            .apply()
    }

    fun clearShuffleSession() {
        prefs.edit()
            .remove(KEY_SHUF_HIST)
            .remove(KEY_SHUF_PLAYED)
            .remove(KEY_SHUF_FP)
            .apply()
    }

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
        val sessionOk = prefs.contains(KEY_SHUF_HIST) &&
            prefs.getInt(KEY_SHUF_FP, 0) == queueFingerprint(tokens)
        return Saved(
            trackIds = ids,
            enqueuedIndices = enqueued,
            index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, ids.lastIndex),
            positionMs = prefs.getLong(KEY_POS, 0).coerceAtLeast(0),
            shuffle = shuffle,
            repeat = repeat,
            shufHistory = if (sessionOk) loadInts(KEY_SHUF_HIST, ids.indices) else emptyList(),
            shufPlayed = if (sessionOk) loadInts(KEY_SHUF_PLAYED, ids.indices) else emptyList(),
        )
    }

    private fun loadInts(key: String, valid: IntRange): List<Int> =
        prefs.getString(key, null)
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            ?.filter { it in valid }
            ?: emptyList()

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
        const val KEY_SHUF_HIST = "shuffle_history"
        const val KEY_SHUF_PLAYED = "shuffle_played"
        const val KEY_SHUF_FP = "shuffle_queue_fp"

        const val KEY_SHUFFLE = "shuffle_name"
        const val KEY_REPEAT = "repeat_name"

        const val KEY_SHUFFLE_LEGACY = "shuffle"
        const val KEY_REPEAT_LEGACY = "repeat"
    }
}

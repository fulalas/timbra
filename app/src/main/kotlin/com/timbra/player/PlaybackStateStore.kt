package com.timbra.player

import android.content.Context

/**
 * Persists the current queue (as track ids), the playing index, position and play
 * modes to SharedPreferences so playback can be restored after the app is closed.
 */
class PlaybackStateStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("playback_state", Context.MODE_PRIVATE)

    data class Saved(
        val trackIds: List<Long>,
        val enqueuedIndices: List<Int>,
        val index: Int,
        val positionMs: Long,
        val shuffleOrdinal: Int,
        val repeatOrdinal: Int,
    )

    /** The queue only changes when the timeline changes — write the (potentially large) id list rarely. */
    fun saveQueue(trackIds: List<Long>, enqueuedIndices: List<Int>) {
        prefs.edit()
            .putString(KEY_IDS, trackIds.joinToString(","))
            .putString(KEY_ENQ, enqueuedIndices.joinToString(","))
            .apply()
    }

    fun saveModes(shuffleOrdinal: Int, repeatOrdinal: Int) {
        prefs.edit().putInt(KEY_SHUFFLE, shuffleOrdinal).putInt(KEY_REPEAT, repeatOrdinal).apply()
    }

    /** Cheap, frequent write on transitions / play-pause / stop. */
    fun savePosition(index: Int, positionMs: Long) {
        prefs.edit().putInt(KEY_INDEX, index).putLong(KEY_POS, positionMs).apply()
    }

    fun load(): Saved? {
        val ids = prefs.getString(KEY_IDS, null)
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val enqueued = prefs.getString(KEY_ENQ, null)
            ?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        return Saved(
            trackIds = ids,
            enqueuedIndices = enqueued,
            index = prefs.getInt(KEY_INDEX, 0),
            positionMs = prefs.getLong(KEY_POS, 0),
            shuffleOrdinal = prefs.getInt(KEY_SHUFFLE, 0),
            repeatOrdinal = prefs.getInt(KEY_REPEAT, 0),
        )
    }

    private companion object {
        const val KEY_IDS = "queue_ids"
        const val KEY_ENQ = "enqueued_indices"
        const val KEY_INDEX = "index"
        const val KEY_POS = "position"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
    }
}

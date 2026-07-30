package com.timbra.player

import android.content.Context
import androidx.media3.common.Player

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
     */
    fun saveModes(shuffleOrdinal: Int, repeatOrdinal: Int) {
        prefs.edit()
            .putInt(KEY_SHUFFLE, shuffleOrdinal)
            .putInt(KEY_REPEAT, repeatOrdinal)
            .putInt(KEY_MODES_REV, prefs.getInt(KEY_MODES_REV, 0) + 1)
            .apply()
    }

    /** Monotonic counter of mode writes (see [saveModes]). */
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

    /** Cheap, frequent write on transitions / play-pause / stop. */
    private fun savePosition(index: Int, positionMs: Long) {
        prefs.edit().putInt(KEY_INDEX, index).putLong(KEY_POS, positionMs).apply()
    }

    /** Checkpoint [player]'s current index + position; a no-op on an empty timeline. */
    fun checkpoint(player: Player) {
        if (player.mediaItemCount == 0) return
        savePosition(player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0))
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

    /**
     * The persisted play modes (shuffle, repeat) as ordinals — read independently of the saved
     * queue, so a live-session reconnect can re-adopt them even when the queue itself isn't
     * (re)loaded from disk. Defaults to OFF (ordinal 0) for both.
     */
    fun loadModes(): Pair<Int, Int> =
        prefs.getInt(KEY_SHUFFLE, 0) to prefs.getInt(KEY_REPEAT, 0)

    private companion object {
        const val KEY_IDS = "queue_ids"
        const val KEY_ENQ = "enqueued_indices"
        const val KEY_INDEX = "index"
        const val KEY_POS = "position"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
        const val KEY_MODES_REV = "modes_revision"
    }
}

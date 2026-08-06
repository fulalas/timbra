// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.player

import android.content.Context

/**
 * Persists the 7-band equalizer state (on/off + per-band gains) to SharedPreferences so it
 * survives app restarts. Same idiom as [PlaybackStateStore]: its own private file, gains
 * stored as a comma-joined string. The service reapplies these to the DSP on cold start (see
 * [EqualizerAudioProcessor]); the equalizer screen reads/writes them live.
 */
class EqSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("eq_settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** The 7 band gains in dB, always length [BAND_COUNT], each clamped to the valid range. */
    fun gains(): IntArray {
        // map, NOT mapNotNull: the list is POSITIONAL (index = band), so dropping an unparseable
        // token shortened it and shifted every later band onto a gain that belonged to a
        // different frequency — "3,x,5" gave band 1 the 5 dB meant for band 2. Substituting per
        // slot keeps a corrupt token confined to its own band.
        val stored = prefs.getString(KEY_GAINS, null)
            ?.split(",")
            ?.map { it.toIntOrNull() ?: 0 }
            ?: emptyList()
        return IntArray(BAND_COUNT) { i -> (stored.getOrNull(i) ?: 0).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) }
    }

    fun setGains(gains: IntArray) {
        val clamped = IntArray(BAND_COUNT) { i -> (gains.getOrNull(i) ?: 0).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) }
        prefs.edit().putString(KEY_GAINS, clamped.joinToString(",")).apply()
    }

    /** Reset every band to 0 dB (flat). Leaves the on/off state untouched. */
    fun reset() = setGains(IntArray(BAND_COUNT))

    companion object {
        const val BAND_COUNT = 7
        const val MIN_GAIN_DB = -15
        const val MAX_GAIN_DB = 15

        /** Center frequencies (Hz) for the 7 bands — standard graphic-EQ spacing.
         *  An immutable List, not an IntArray: an array reads like a constant but is writable,
         *  and this one is shared process-wide by the DSP and the equalizer screen. */
        val BAND_FREQS: List<Int> = listOf(60, 150, 400, 1000, 2400, 6000, 15000)

        private const val KEY_ENABLED = "eq_enabled"
        private const val KEY_GAINS = "eq_gains"
    }
}

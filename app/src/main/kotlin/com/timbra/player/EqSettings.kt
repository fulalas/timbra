package com.timbra.player

import android.content.Context

/**
 * Persists the 7-band equalizer state (on/off + per-band gains) to SharedPreferences so it
 * survives app restarts. Same idiom as [PlaybackStateStore]: its own private file, gains
 * stored as a comma-joined string. The service reapplies these on cold start (see
 * [EqController]); the equalizer screen reads/writes them live.
 */
class EqSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("eq_settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** The 7 band gains in dB, always length [BAND_COUNT], each clamped to the valid range. */
    fun gains(): IntArray {
        val stored = prefs.getString(KEY_GAINS, null)
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()
        return IntArray(BAND_COUNT) { i -> (stored.getOrNull(i) ?: 0).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) }
    }

    fun setGains(gains: IntArray) {
        val clamped = IntArray(BAND_COUNT) { i -> (gains.getOrNull(i) ?: 0).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) }
        prefs.edit().putString(KEY_GAINS, clamped.joinToString(",")).apply()
    }

    fun setBand(index: Int, db: Int) {
        if (index !in 0 until BAND_COUNT) return
        val g = gains()
        g[index] = db.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        setGains(g)
    }

    /** Reset every band to 0 dB (flat). Leaves the on/off state untouched. */
    fun reset() = setGains(IntArray(BAND_COUNT))

    companion object {
        const val BAND_COUNT = 7
        const val MIN_GAIN_DB = -15
        const val MAX_GAIN_DB = 15

        /** Center frequencies (Hz) for the 7 bands — standard graphic-EQ spacing. */
        val BAND_FREQS = intArrayOf(60, 150, 400, 1000, 2400, 6000, 15000)

        private const val KEY_ENABLED = "eq_enabled"
        private const val KEY_GAINS = "eq_gains"
    }
}

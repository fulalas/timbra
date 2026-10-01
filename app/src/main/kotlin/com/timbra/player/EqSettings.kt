// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.player

import android.content.Context

class EqSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("eq_settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun gains(): IntArray {
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

    fun reset() = setGains(IntArray(BAND_COUNT))

    companion object {
        const val BAND_COUNT = 7
        const val MIN_GAIN_DB = -15
        const val MAX_GAIN_DB = 15

        val BAND_FREQS: List<Int> = listOf(60, 150, 400, 1000, 2400, 6000, 15000)

        private const val KEY_ENABLED = "eq_enabled"
        private const val KEY_GAINS = "eq_gains"
    }
}

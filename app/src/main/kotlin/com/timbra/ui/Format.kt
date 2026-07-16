package com.timbra.ui

import java.util.Locale

object Format {
    /** Milliseconds -> "m:ss" or "h:mm:ss". */
    fun clock(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** "artist  •  album", dropping blank parts. */
    fun subtitle(artist: String, album: String): String =
        listOf(artist, album).filter { it.isNotBlank() }.joinToString("  •  ")

    /** "44.1KHz  320Kbps  mp3" (Poweramp-style), dropping unknown parts. */
    fun audioInfo(sampleRateHz: Int, bitrateBps: Int, filePath: String): String {
        val parts = ArrayList<String>(3)
        if (sampleRateHz > 0) {
            parts.add(
                if (sampleRateHz % 1000 == 0) "${sampleRateHz / 1000}KHz"
                else String.format(Locale.US, "%.1fKHz", sampleRateHz / 1000f)
            )
        }
        if (bitrateBps > 0) parts.add("${bitrateBps / 1000}Kbps")
        filePath.substringAfterLast('.', "").lowercase(Locale.US)
            .takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return parts.joinToString("  ")
    }
}

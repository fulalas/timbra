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
}

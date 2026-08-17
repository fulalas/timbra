// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui

import java.util.Locale

object Format {
    /**
     * Milliseconds -> "m:ss" or "h:mm:ss". Built by hand rather than with String.format: this
     * runs on every position tick of both players, on every seek-bar drag callback, and once
     * per row bind while a list is flung — and String.format re-parses its pattern and
     * allocates a Formatter on each call.
     */
    fun clock(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return buildString(8) {
            if (h > 0) {
                append(h).append(':')
                if (m < 10) append('0')
            }
            append(m).append(':')
            if (s < 10) append('0')
            append(s)
        }
    }

    fun subtitle(artist: String, album: String): String =
        listOf(artist, album).filter { it.isNotBlank() }.joinToString("  •  ")

    fun audioInfo(sampleRateHz: Int, bitrateBps: Int, filePath: String): String {
        val parts = ArrayList<String>(3)
        if (sampleRateHz > 0) {
            parts.add(
                if (sampleRateHz % 1000 == 0) "${sampleRateHz / 1000}KHz"
                else String.format(Locale.US, "%.1fKHz", sampleRateHz / 1000f)
            )
        }
        if (bitrateBps > 0) parts.add("${bitrateBps / 1000}Kbps")
        // Extension of the FILE NAME, not of the whole path: applied to the path, an
        // extension-less file under a dotted directory (".../Vol.2/track01") yielded a
        // "container" containing path separators ("2/track01").
        filePath.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.US)
            .takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return parts.joinToString("  ")
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.data

import com.timbra.R
import com.timbra.data.model.FolderNode
import com.timbra.data.model.Track

enum class SortOrder(val labelRes: Int) {
    FILENAME(R.string.sort_filename),
    TITLE(R.string.sort_title),
    TRACK_NO(R.string.sort_track),
    ALBUM(R.string.sort_album),
    ARTIST(R.string.sort_artist),
    DATE(R.string.sort_date),
    DURATION(R.string.sort_duration),
}

enum class ViewAs(val labelRes: Int) {
    HIERARCHY(R.string.view_as_hierarchy),
    FLAT(R.string.view_as_flat),
}

object SortDefaults {
    val FOLDER_SONGS: SortOrder = SortOrder.FILENAME
    val FOLDER_VIEW: ViewAs = ViewAs.HIERARCHY
    val LIBRARY_SONGS: SortOrder = SortOrder.TITLE
    val ALBUM_TRACKS: SortOrder = SortOrder.TRACK_NO
}

fun comparatorFor(order: SortOrder): Comparator<Track> {
    val primary: Comparator<Track> = when (order) {
        SortOrder.FILENAME -> compareBy(NATURAL) { it.fileName }
        SortOrder.TITLE -> compareBy(NATURAL) { it.displayTitle }
        SortOrder.TRACK_NO -> compareBy<Track> { it.discOrFirst }
            .thenBy { it.trackNo }.thenBy(NATURAL) { it.displayTitle }
        SortOrder.ALBUM -> compareBy<Track, String>(NATURAL) { it.album }
            .thenBy { it.discOrFirst }.thenBy { it.trackNo }
        SortOrder.ARTIST -> compareBy<Track, String>(NATURAL) { it.artist }
            .thenBy(NATURAL) { it.displayTitle }
        SortOrder.DATE -> compareByDescending { it.dateAddedSec }
        SortOrder.DURATION -> compareBy { it.durationMs }
    }
    return primary.thenBy(NATURAL) { it.fileName }.thenBy { it.id }
}

private val Track.discOrFirst: Int get() = if (discNo <= 0) 1 else discNo

fun List<Track>.sortedBy(order: SortOrder): List<Track> = sortedWith(comparatorFor(order))

fun FolderNode.tracksInPlayOrder(order: SortOrder): List<Track> = tracks.sortedBy(order)

val NATURAL: Comparator<String> = Comparator { a, b -> naturalCompare(a, b) }

private fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            while (i < a.length && a[i] == '0') i++
            while (j < b.length && b[j] == '0') j++
            var endA = i
            while (endA < a.length && a[endA].isDigit()) endA++
            var endB = j
            while (endB < b.length && b[endB].isDigit()) endB++
            if (endA - i != endB - j) return (endA - i) - (endB - j)
            while (i < endA) {
                val c = a[i].compareTo(b[j])
                if (c != 0) return c
                i++
                j++
            }
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

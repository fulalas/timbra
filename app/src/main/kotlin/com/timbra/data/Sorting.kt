package com.timbra.data

import com.timbra.R
import com.timbra.data.model.Track

/** How a track list is ordered. */
enum class SortOrder(val labelRes: Int) {
    FILENAME(R.string.sort_filename),
    TITLE(R.string.sort_title),
    TRACK_NO(R.string.sort_track),
    ALBUM(R.string.sort_album),
    ARTIST(R.string.sort_artist),
    DATE(R.string.sort_date),
    DURATION(R.string.sort_duration),
}

/** How folder contents are presented. */
enum class ViewAs(val labelRes: Int) {
    HIERARCHY(R.string.view_as_hierarchy),
    FLAT(R.string.view_as_flat),
}

object SortDefaults {
    /** Folder Songs default to "by filename" and folders default to a hierarchy view. */
    val FOLDER_SONGS: SortOrder = SortOrder.FILENAME
    val FOLDER_VIEW: ViewAs = ViewAs.HIERARCHY
    val LIBRARY_SONGS: SortOrder = SortOrder.TITLE
    val ALBUM_TRACKS: SortOrder = SortOrder.TRACK_NO
}

/** The ordering used by [sortedBy]; exposed so callers that only need the first/last track
 *  can use minWith/maxWith instead of sorting the whole list. */
fun comparatorFor(order: SortOrder): Comparator<Track> = when (order) {
    SortOrder.FILENAME -> compareBy(NATURAL) { it.fileName }
    SortOrder.TITLE -> compareBy(NATURAL) { it.title }
    SortOrder.TRACK_NO -> compareBy<Track> { it.trackNo }.thenBy(NATURAL) { it.title }
    SortOrder.ALBUM -> Comparator<Track> { x, y -> NATURAL.compare(x.album, y.album) }.thenBy { it.trackNo }
    SortOrder.ARTIST -> Comparator<Track> { x, y -> NATURAL.compare(x.artist, y.artist) }.thenBy(NATURAL) { it.title }
    SortOrder.DATE -> compareByDescending<Track> { it.dateAddedSec }
    SortOrder.DURATION -> compareBy<Track> { it.durationMs }
}

fun List<Track>.sortedBy(order: SortOrder): List<Track> = sortedWith(comparatorFor(order))

/**
 * Natural (alphanumeric) string order: digit runs compare as numbers, so "2" sorts
 * before "10", and text compares case-insensitively.
 */
val NATURAL: Comparator<String> = Comparator { a, b -> naturalCompare(a, b) }

private fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val startA = i
            val startB = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val numA = a.substring(startA, i).trimStart('0').ifEmpty { "0" }
            val numB = b.substring(startB, j).trimStart('0').ifEmpty { "0" }
            if (numA.length != numB.length) return numA.length - numB.length
            val c = numA.compareTo(numB)
            if (c != 0) return c
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

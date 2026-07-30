package com.timbra.data

import com.timbra.R
import com.timbra.data.model.FolderNode
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
    // Disc first: MediaStore encodes disc*1000 + track, so without it a 2-disc album
    // interleaves (disc1/t1, disc2/t1, disc1/t2, ...) once the disc is split off.
    SortOrder.TRACK_NO -> compareBy<Track> { it.discNo }.thenBy { it.trackNo }.thenBy(NATURAL) { it.title }
    SortOrder.ALBUM -> compareBy<Track, String>(NATURAL) { it.album }
        .thenBy { it.discNo }.thenBy { it.trackNo }
    SortOrder.ARTIST -> compareBy<Track, String>(NATURAL) { it.artist }.thenBy(NATURAL) { it.title }
    SortOrder.DATE -> compareByDescending<Track> { it.dateAddedSec }
    SortOrder.DURATION -> compareBy<Track> { it.durationMs }
}

fun List<Track>.sortedBy(order: SortOrder): List<Track> = sortedWith(comparatorFor(order))

/**
 * A folder's songs in canonical play order — the ONE order every folder entry point uses
 * (folder taps, Advance-List advances from the UI or the detached service, search's
 * "its folder"), so they can never land on differently-ordered queues. [order] comes from the
 * user's persisted folder-sort choice ([FolderSort]), which is what the browse list shows.
 */
fun FolderNode.tracksInPlayOrder(order: SortOrder): List<Track> = tracks.sortedBy(order)

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
            // Compare the digit runs IN PLACE — skip leading zeros, then order by run length
            // and, failing that, digit by digit. Filenames are overwhelmingly "01 Title", so
            // this branch is taken by nearly every comparison; the substring+trimStart form
            // allocated two to four short-lived strings each time, on a comparator that runs
            // on the main thread during folder advances.
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

// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra

import com.timbra.TestTracks.track
import com.timbra.data.SortOrder
import com.timbra.data.sortedBy
import org.junit.Assert.assertEquals
import org.junit.Test

/** The per-order comparators, including the three defects the review found in them. */
class SortingTest {

    @Test fun `title order uses the DISPLAYED title, not the raw tag`() {
        // An untagged track shows its filename, so it must sort as that filename — ordering on the
        // raw (blank) tag clumped every untagged track at the top under the empty string.
        val tagged = track(id = 1, title = "Berlin", path = "/m/zz.mp3")
        val untagged = track(id = 2, title = "", path = "/m/alpha.mp3")
        assertEquals(
            listOf(untagged, tagged),
            listOf(tagged, untagged).sortedBy(SortOrder.TITLE),
        )
    }

    @Test fun `date order is deterministic when the timestamps tie`() {
        // A folder copied in one operation shares dateAddedSec to the second; without a tiebreak
        // the result silently depended on the order of the input list.
        val a = track(id = 1, dateAddedSec = 100, path = "/m/a.mp3")
        val b = track(id = 2, dateAddedSec = 100, path = "/m/b.mp3")
        val c = track(id = 3, dateAddedSec = 100, path = "/m/c.mp3")
        val one = listOf(c, a, b).sortedBy(SortOrder.DATE)
        val two = listOf(b, c, a).sortedBy(SortOrder.DATE)
        assertEquals(one, two)
        assertEquals(listOf(a, b, c), one)
    }

    @Test fun `duration order is deterministic when the durations tie`() {
        val a = track(id = 1, durationMs = 200_000, path = "/m/a.mp3")
        val b = track(id = 2, durationMs = 200_000, path = "/m/b.mp3")
        assertEquals(
            listOf(b, a).sortedBy(SortOrder.DURATION),
            listOf(a, b).sortedBy(SortOrder.DURATION),
        )
    }

    @Test fun `newest first for date order`() {
        val old = track(id = 1, dateAddedSec = 10)
        val new = track(id = 2, dateAddedSec = 20)
        assertEquals(listOf(new, old), listOf(old, new).sortedBy(SortOrder.DATE))
    }

    @Test fun `an untagged disc number falls in with the first disc`() {
        // discNo 0 means "no disc tag", not "disc zero" — as a raw key it sorted untagged files
        // ahead of disc 1 on a partially-tagged album.
        val untagged = track(id = 1, discNo = 0, trackNo = 5, title = "e")
        val disc1 = track(id = 2, discNo = 1, trackNo = 1, title = "a")
        val disc2 = track(id = 3, discNo = 2, trackNo = 1, title = "b")
        assertEquals(
            listOf(disc1, untagged, disc2),
            listOf(disc2, untagged, disc1).sortedBy(SortOrder.TRACK_NO),
        )
    }

    @Test fun `multi-disc albums do not interleave`() {
        val d1t1 = track(id = 1, discNo = 1, trackNo = 1)
        val d1t2 = track(id = 2, discNo = 1, trackNo = 2)
        val d2t1 = track(id = 3, discNo = 2, trackNo = 1)
        val d2t2 = track(id = 4, discNo = 2, trackNo = 2)
        assertEquals(
            listOf(d1t1, d1t2, d2t1, d2t2),
            listOf(d2t1, d1t2, d2t2, d1t1).sortedBy(SortOrder.TRACK_NO),
        )
    }

    @Test fun `filename order is natural`() {
        val t1 = track(id = 1, path = "/m/1 a.mp3")
        val t2 = track(id = 2, path = "/m/2 a.mp3")
        val t10 = track(id = 3, path = "/m/10 a.mp3")
        assertEquals(listOf(t1, t2, t10), listOf(t10, t2, t1).sortedBy(SortOrder.FILENAME))
    }

    @Test fun `every order is stable and total on a mixed list`() {
        val tracks = (1L..25L).map {
            track(
                id = it,
                title = if (it % 4 == 0L) "" else "T${it % 7}",
                artist = "A${it % 3}",
                album = "Al${it % 5}",
                durationMs = (it % 6) * 1000,
                trackNo = (it % 5).toInt(),
                discNo = (it % 3).toInt(),
                dateAddedSec = it % 4,
                path = "/m/${it % 9} f$it.mp3",
            )
        }
        for (order in SortOrder.entries) {
            val once = tracks.sortedBy(order)
            assertEquals("$order must be idempotent", once, once.sortedBy(order))
            assertEquals("$order must not drop or add tracks", tracks.size, once.size)
            assertEquals("$order must be a permutation", tracks.toSet(), once.toSet())
            // Deterministic regardless of input order — the "ONE canonical order" contract.
            assertEquals("$order must not depend on input order", once, tracks.reversed().sortedBy(order))
        }
    }
}

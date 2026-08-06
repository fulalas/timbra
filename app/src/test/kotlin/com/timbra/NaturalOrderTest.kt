// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra

import com.timbra.data.NATURAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-rolled natural comparator. It backs every filename and title ordering in the app and
 * runs on the main thread during folder advances, so its edge cases are worth pinning down.
 */
class NaturalOrderTest {

    private fun cmp(a: String, b: String) = NATURAL.compare(a, b)

    @Test fun `digit runs compare numerically, not lexicographically`() {
        assertTrue(cmp("track2.mp3", "track10.mp3") < 0)
        assertTrue(cmp("2", "10") < 0)
        assertTrue(cmp("Disc 2", "Disc 10") < 0)
    }

    @Test fun `leading zeros do not change the numeric value`() {
        assertEquals(0, cmp("01 Title", "1 Title"))
        assertEquals(0, cmp("007", "7"))
        assertTrue(cmp("01 A", "02 A") < 0)
        assertTrue(cmp("0", "1") < 0)
        assertEquals(0, cmp("00", "0"))
    }

    @Test fun `text compares case-insensitively`() {
        assertEquals(0, cmp("Album", "album"))
        assertTrue(cmp("apple", "Banana") < 0)
    }

    @Test fun `a prefix sorts before the longer string`() {
        assertTrue(cmp("Song", "Song 2") < 0)
        assertTrue(cmp("", "a") < 0)
        assertEquals(0, cmp("", ""))
    }

    @Test fun `mixed digit and text segments`() {
        assertTrue(cmp("a01b", "a1c") < 0)
        assertTrue(cmp("0x", "00y") < 0)
        assertTrue(cmp("a0", "a0x") < 0)
    }

    @Test fun `is a consistent total order over a realistic file list`() {
        val names = listOf(
            "10 Ten.mp3", "2 Two.mp3", "01 One.mp3", "1 one again.mp3",
            "Intro.flac", "intro (alt).flac", "100.mp3", "20.mp3", "", "3",
        )
        // Antisymmetry and reflexivity on every pair — a comparator that violates these makes
        // sortedWith throw "Comparison method violates its general contract".
        for (a in names) {
            assertEquals(0, cmp(a, a))
            for (b in names) {
                val ab = cmp(a, b)
                val ba = cmp(b, a)
                assertEquals("antisymmetry for '$a' vs '$b'", ab.compareTo(0), -ba.compareTo(0))
            }
        }
        // Transitivity, which is what sortedWith actually relies on.
        for (a in names) for (b in names) for (c in names) {
            if (cmp(a, b) <= 0 && cmp(b, c) <= 0) {
                assertTrue("transitivity for '$a' <= '$b' <= '$c'", cmp(a, c) <= 0)
            }
        }
    }

    @Test fun `sorting a track listing lands in human order`() {
        val sorted = listOf("10 Ten", "2 Two", "1 One", "20 Twenty").sortedWith(NATURAL)
        assertEquals(listOf("1 One", "2 Two", "10 Ten", "20 Twenty"), sorted)
    }
}

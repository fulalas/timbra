// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra

import com.timbra.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test fun `clock renders m ss below an hour`() {
        assertEquals("0:00", Format.clock(0))
        assertEquals("0:00", Format.clock(999))
        assertEquals("0:01", Format.clock(1_000))
        assertEquals("0:59", Format.clock(59_999))
        assertEquals("1:00", Format.clock(60_000))
        assertEquals("5:03", Format.clock(303_000))
        assertEquals("59:59", Format.clock(3_599_000))
    }

    @Test fun `clock renders h mm ss from an hour up, zero-padding the minutes`() {
        assertEquals("1:00:00", Format.clock(3_600_000))
        assertEquals("1:02:03", Format.clock(3_723_000))
        assertEquals("1:15:03", Format.clock(4_503_000))
        assertEquals("10:00:00", Format.clock(36_000_000))
    }

    @Test fun `clock treats a negative or unknown duration as zero`() {
        assertEquals("0:00", Format.clock(-1))
        assertEquals("0:00", Format.clock(Long.MIN_VALUE))
    }

    @Test fun `subtitle joins the known parts only`() {
        assertEquals("Artist  •  Album", Format.subtitle("Artist", "Album"))
        assertEquals("Artist", Format.subtitle("Artist", ""))
        assertEquals("Album", Format.subtitle("   ", "Album"))
        assertEquals("", Format.subtitle("", ""))
    }

    @Test fun `audioInfo drops unknown parts and formats whole kHz without a decimal`() {
        assertEquals("44.1KHz  320Kbps  mp3", Format.audioInfo(44_100, 320_000, "/m/a.mp3"))
        assertEquals("48KHz  flac", Format.audioInfo(48_000, 0, "/m/a.flac"))
        assertEquals("192Kbps", Format.audioInfo(0, 192_000, "/m/noext"))
        assertEquals("", Format.audioInfo(0, 0, "/m/noext"))
    }

    @Test fun `audioInfo takes the extension from the FILENAME, not the path`() {
        // A dotted directory with an extension-less file used to yield a "container" containing
        // path separators.
        assertEquals("44.1KHz", Format.audioInfo(44_100, 0, "/m/Vol.2/track01"))
        assertEquals("44.1KHz  ogg", Format.audioInfo(44_100, 0, "/m/Vol.2/track01.OGG"))
    }
}

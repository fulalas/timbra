// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra

import android.net.Uri
import com.timbra.data.model.Track
import org.mockito.Mockito

/**
 * Track factory for the pure-logic tests.
 *
 * The only reason a mock appears here is [Track.uri]: the framework stub on the unit-test
 * classpath cannot produce a real [Uri], and none of the logic under test reads it.
 */
object TestTracks {

    private val FAKE_URI: Uri = Mockito.mock(Uri::class.java)

    fun track(
        id: Long = 1L,
        title: String = "",
        artist: String = "",
        album: String = "",
        albumId: Long = 1L,
        durationMs: Long = 0L,
        trackNo: Int = 0,
        discNo: Int = 0,
        dateAddedSec: Long = 0L,
        path: String = "/m/$id.mp3",
    ): Track = Track(
        id = id, uri = FAKE_URI, title = title, artist = artist, album = album,
        albumId = albumId, durationMs = durationMs, trackNo = trackNo, discNo = discNo,
        dateAddedSec = dateAddedSec, path = path,
    )
}

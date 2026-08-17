// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.player

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.timbra.R
import com.timbra.data.MediaRepository
import com.timbra.data.model.Track

const val KEY_ALBUM_ID = "pa_album_id"
const val KEY_PATH = "pa_path"
const val KEY_ENQUEUED = "pa_enqueued"

const val KEY_ENQ_PLAYED = "pa_enqueued_played"

val MediaItem.isEnqueued: Boolean
    get() = mediaMetadata.extras?.getBoolean(KEY_ENQUEUED, false) == true

val MediaItem.isEnqueuedPlayed: Boolean
    get() = mediaMetadata.extras?.getBoolean(KEY_ENQ_PLAYED, false) == true

val MediaItem.albumIdExtra: Long
    get() = mediaMetadata.extras?.getLong(KEY_ALBUM_ID, -1L) ?: -1L

val MediaItem.pathExtra: String
    get() = mediaMetadata.extras?.getString(KEY_PATH) ?: ""

val MediaItem.trackId: Long?
    get() = mediaId.toLongOrNull()

/**
 * The same item marked as a consumed play-next entry. Metadata-only change with the mediaId and
 * Uri untouched, which media3 applies in place — so replacing the currently-playing item with
 * this does NOT re-prepare it or interrupt audio.
 */
fun MediaItem.markEnqueuedPlayed(): MediaItem {
    val md = mediaMetadata
    val extras = Bundle(md.extras ?: Bundle()).apply { putBoolean(KEY_ENQ_PLAYED, true) }
    return buildUpon()
        .setMediaMetadata(md.buildUpon().setExtras(extras).build())
        .build()
}

fun Track.toMediaItem(context: Context, enqueued: Boolean = false): MediaItem {
    val extras = Bundle().apply {
        putLong(KEY_ALBUM_ID, albumId)
        putString(KEY_PATH, path)
        if (enqueued) putBoolean(KEY_ENQUEUED, true)
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(displayTitle)
        .setArtist(artist.ifBlank { context.getString(R.string.unknown_artist) })
        .setAlbumTitle(album.ifBlank { context.getString(R.string.unknown_album) })
        .setArtworkUri(MediaRepository.albumArtUri(albumId))
        .setExtras(extras)
        .build()
    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(metadata)
        .build()
}

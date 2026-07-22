package com.timbra.player

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.timbra.R
import com.timbra.data.MediaRepository
import com.timbra.data.model.Track

/**
 * Metadata-extras keys carried on every queue [MediaItem]. Shared by the UI (which builds the
 * queue and reads it back) and [PlaybackService] (which reads the current song's path and
 * rebuilds the queue for a background Advance-List folder advance).
 */
const val KEY_ALBUM_ID = "pa_album_id"
const val KEY_PATH = "pa_path"
const val KEY_ENQUEUED = "pa_enqueued"

/** Build the ExoPlayer [MediaItem] for a [Track], carrying the extras the app relies on. */
fun Track.toMediaItem(context: Context, enqueued: Boolean = false): MediaItem {
    val extras = Bundle().apply {
        putLong(KEY_ALBUM_ID, albumId)
        putString(KEY_PATH, path)
        if (enqueued) putBoolean(KEY_ENQUEUED, true)
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(title.ifBlank { fileName })
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

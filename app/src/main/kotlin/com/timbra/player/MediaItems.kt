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
 *
 * Every read goes through the accessors below rather than poking at the bundle: the keys had
 * grown six independent decode sites with differing defaults, so renaming one meant finding
 * them all (and missing the service pair silently stopped the background folder advance).
 */
const val KEY_ALBUM_ID = "pa_album_id"
const val KEY_PATH = "pa_path"
const val KEY_ENQUEUED = "pa_enqueued"

/**
 * Set once an enqueued ("play next") item has actually started playing, i.e. the block has been
 * consumed up to here.
 *
 * Without it the enqueued flag had no notion of being used up: the only "has it played" test was
 * the timeline index, which means nothing under shuffle, so every queue rebuild (a shuffle mode
 * change, Shuffle-All) lifted the WHOLE block — songs the user had already heard included — and
 * spliced them back in right after the current song, replaying them.
 */
const val KEY_ENQ_PLAYED = "pa_enqueued_played"

/**
 * True when this queue item was manually enqueued ("play next"). The single reader of
 * [KEY_ENQUEUED] — both layers (UI block bookkeeping, service shuffle grouping) go through it.
 */
val MediaItem.isEnqueued: Boolean
    get() = mediaMetadata.extras?.getBoolean(KEY_ENQUEUED, false) == true

/** True when an enqueued item has already been played (see [KEY_ENQ_PLAYED]). */
val MediaItem.isEnqueuedPlayed: Boolean
    get() = mediaMetadata.extras?.getBoolean(KEY_ENQ_PLAYED, false) == true

/** The MediaStore album id this item's art belongs to; -1 when absent. */
val MediaItem.albumIdExtra: Long
    get() = mediaMetadata.extras?.getLong(KEY_ALBUM_ID, -1L) ?: -1L

/** The item's absolute file path; "" when absent. */
val MediaItem.pathExtra: String
    get() = mediaMetadata.extras?.getString(KEY_PATH) ?: ""

/** The MediaStore track id ([MediaItem.mediaId] is that id as a string); null when unparseable. */
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

/** Build the ExoPlayer [MediaItem] for a [Track], carrying the extras the app relies on. */
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

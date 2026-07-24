package com.timbra.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.timbra.R
import com.timbra.data.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal album-art loader (no third-party image lib). Decodes a thumbnail off the
 * main thread and caches per TRACK when a track Uri is available (art can be embedded
 * per-file: two tracks sharing an albumId may carry different covers), per album
 * otherwise. Guards against RecyclerView view reuse via a tag.
 */
object ArtLoader {

    /** Target edge for decoded art; matches the loadThumbnail cap so every decode path is
     *  bounded the same way. */
    private const val MAX_EDGE = 512

    // Budget the cache in KB (~1/8 of the heap); sizeOf returns KB. A single 512² bitmap
    // is ~1 MB, so a fixed tiny ceiling would evict everything immediately. Keys are the
    // namespaced strings built in [load] ("t<trackId>" / "a<albumId>").
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4096),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** albumIds already known to have no artwork, so we don't retry the I/O on every bind. */
    private val misses = java.util.Collections.synchronizedSet(HashSet<Long>())

    /** Per-TRACK misses (keyed by the track's MediaStore id). Art can be embedded per-file,
     *  so an album-level miss must not block a sibling track whose own tags carry a cover. */
    private val trackMisses = java.util.Collections.synchronizedSet(HashSet<Long>())

    /**
     * Drop the negative-result set and cached bitmaps. Called on library rescans — without
     * this, an album whose art was added after a miss would show the placeholder until the
     * process died (and stale art would survive re-tagging).
     */
    fun invalidate() {
        misses.clear()
        trackMisses.clear()
        cache.evictAll()
    }

    /**
     * Load the track's art into [view]. There is deliberately NO placeholder image
     * (Poweramp-style): [onArt] reports whether art exists so the caller can hide the
     * view or show a brand mark instead. It may fire twice — false immediately (view
     * cleared, nothing decoded yet), then true if the async decode lands (tag-guarded
     * against view reuse).
     */
    fun load(
        view: ImageView,
        owner: LifecycleOwner,
        trackUri: Uri?,
        albumId: Long,
        onArt: (Boolean) -> Unit = {},
    ) {
        val trackId = trackUri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() }
        // Cache/reuse key: per-track when the Uri identifies one (embedded art differs between
        // files of the same album), album-level otherwise. Namespaced — track and album ids
        // live in different MediaStore tables and would collide as raw longs.
        val key = if (trackId != null) "t$trackId" else "a$albumId"
        view.setTag(R.id.art_tag, key)
        cache.get(key)?.let { view.setImageBitmap(it); onArt(true); return }
        view.setImageDrawable(null)
        onArt(false)
        if (trackUri == null && albumId < 0) return
        // Negative cache, split the same way: an album miss must not veto a Uri-bearing sibling.
        if (trackId != null) { if (trackId in trackMisses) return }
        else if (albumId >= 0 && albumId in misses) return

        owner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decode(view, trackUri, albumId) }
            if (bmp != null) {
                cache.put(key, bmp)
                if (view.getTag(R.id.art_tag) == key) {
                    view.setImageBitmap(bmp)
                    onArt(true)
                }
            } else if (trackId != null) {
                trackMisses.add(trackId)
            } else if (albumId >= 0) {
                misses.add(albumId)
            }
        }
    }

    /**
     * Drop a view's art and disown any in-flight decode (retag so a late callback's tag-guard
     * fails). Call from `onViewRecycled` so a pooled ImageView never carries a previous song's
     * cover into its next attachment.
     */
    fun clear(view: ImageView) {
        view.setTag(R.id.art_tag, null)
        view.setImageDrawable(null)
    }

    private fun decode(view: ImageView, trackUri: Uri?, albumId: Long): Bitmap? {
        val resolver = view.context.contentResolver
        // API 29+: reliable thumbnail from the track content Uri.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && trackUri != null) {
            runCatching {
                return resolver.loadThumbnail(trackUri, Size(MAX_EDGE, MAX_EDGE), null)
            }
        }
        // Fallback: legacy album-art Uri stream.
        if (albumId >= 0) {
            runCatching {
                resolver.openInputStream(MediaRepository.albumArtUri(albumId))?.use {
                    return BitmapFactory.decodeStream(it)
                }
            }
        }
        // Last resort: read the picture embedded in the file's own tags. MediaStore's thumbnail
        // and album-art table both miss covers on plenty of files (it simply never indexed them),
        // so a track the user knows has art still showed the blank brand — this reads it straight
        // from the file, independent of MediaStore. Runs off the main thread (decode is on IO).
        if (trackUri != null) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(view.context, trackUri)
                mmr.embeddedPicture?.let { return decodeSampled(it) }
            } catch (_: Throwable) {
                // Unreadable file / no picture — fall through to no-art.
            } finally {
                mmr.release()
            }
        }
        return null
    }

    /** Decode an embedded picture bounded to ~[MAX_EDGE], matching the loadThumbnail cap.
     *  Ripped libraries embed 3000² covers; decoding those full-size (~36 MB ARGB) would
     *  blow past the whole cache budget and thrash every visible page out of it. */
    private fun decodeSampled(pic: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(pic, 0, pic.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_EDGE || bounds.outHeight / (sample * 2) >= MAX_EDGE) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(pic, 0, pic.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

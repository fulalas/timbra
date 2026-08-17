// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import java.util.concurrent.atomic.AtomicInteger
import android.widget.ImageView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.timbra.R
import com.timbra.data.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ArtLoader {

    private const val MAX_EDGE = 512

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
     * Bumped by [invalidate]. A decode that started before a rescan must publish NOTHING — it
     * cannot be cancelled, and its trailing `cache.put` / miss-record used to land after
     * evictAll() and reinstate the pre-rescan cover (or permanently blacklist art that had just
     * been added), which is exactly what invalidate() exists to prevent.
     */
    private val generation = AtomicInteger(0)

    /**
     * Drop the negative-result set and cached bitmaps. Called on library rescans — without
     * this, an album whose art was added after a miss would show the placeholder until the
     * process died (and stale art would survive re-tagging).
     */
    fun invalidate() {
        // AtomicInteger: `generation++` is a read-modify-write, and a lost increment is what lets
        // an in-flight decode pass the guard in load() and put a pre-rescan bitmap back into the
        // just-evicted cache (or blacklist art the rescan had only now added).
        generation.incrementAndGet()
        misses.clear()
        trackMisses.clear()
        cache.evictAll()
    }

    fun load(
        view: ImageView,
        owner: LifecycleOwner,
        trackUri: Uri?,
        albumId: Long,
        targetEdgePx: Int = autoTarget(view),
        onArt: (Boolean) -> Unit = {},
    ) {
        val target = targetEdgePx.coerceIn(1, MAX_EDGE)
        val trackId = trackUri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() }
        // Cache/reuse key: per-track when the Uri identifies one (embedded art differs between
        // files of the same album), album-level otherwise. Namespaced — track and album ids
        // live in different MediaStore tables and would collide as raw longs — and suffixed with
        // the decode size, so the deck's 512px and a list's 144px coexist instead of one
        // evicting the other.
        val key = (if (trackId != null) "t$trackId" else "a$albumId") + "@$target"
        view.setTag(R.id.art_tag, key)
        cache.get(key)?.let { view.setImageBitmap(it); onArt(true); return }
        view.setImageDrawable(null)
        onArt(false)
        if (trackUri == null && albumId < 0) return
        // Negative cache, split the same way: an album miss must not veto a Uri-bearing sibling.
        if (trackId != null) { if (trackId in trackMisses) return }
        else if (albumId >= 0 && albumId in misses) return

        // Read the Context here, on the main thread — the decode ran on Dispatchers.IO and
        // dereferenced the View to get it, which is View state accessed off the main thread.
        val context = view.context.applicationContext
        val startedAt = generation.get()
        owner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decode(context, trackUri, albumId, target) }
            // A rescan happened while we were decoding: this result describes the old library.
            if (generation.get() != startedAt) return@launch
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

    private fun autoTarget(view: ImageView): Int {
        val lp = view.layoutParams
        val edge = maxOf(lp?.width ?: 0, lp?.height ?: 0)
        return if (edge > 0) edge else MAX_EDGE
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

    private fun decode(context: Context, trackUri: Uri?, albumId: Long, target: Int): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && trackUri != null) {
            runCatching {
                // Size is a REQUEST, not a bound — loadThumbnail returns whatever MediaStore has,
                // commonly a 512px cover. Uncapped it broke this file's central invariant (the
                // cache key is suffixed with the decode size and the budget assumes a 48dp row
                // caches a 48dp bitmap), reintroducing on the primary API 29+ path the very waste
                // the size-keying was added to remove.
                return resolver.loadThumbnail(trackUri, Size(target, target), null).cappedTo(target)
            }
        }
        // Fallback: legacy album-art Uri stream. This is the ONLY path on API 24-28, and it is
        // also where an API 29+ loadThumbnail failure lands (common — MediaStore never
        // thumbnailed plenty of files), so it must be sampled: decoding straight from the stream
        // produced multi-megabyte bitmaps for a 48dp row and risked OOM on large covers.
        if (albumId >= 0) {
            runCatching {
                val bytes = resolver.openInputStream(MediaRepository.albumArtUri(albumId))
                    ?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) return decodeSampled(bytes, target)
            }
        }
        // Last resort: read the picture embedded in the file's own tags. MediaStore's thumbnail
        // and album-art table both miss covers on plenty of files (it simply never indexed them),
        // so a track the user knows has art still showed the blank brand — this reads it straight
        // from the file, independent of MediaStore. Runs off the main thread (decode is on IO).
        if (trackUri != null) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, trackUri)
                mmr.embeddedPicture?.let { return decodeSampled(it, target) }
            } catch (_: Throwable) {
            } finally {
                mmr.release()
            }
        }
        return null
    }

    private fun Bitmap.cappedTo(target: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= target || longest == 0) return this
        val scale = target.toDouble() / longest
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, w, h, true).also { if (it !== this) recycle() }
    }

    /**
     * Decode [pic] with neither edge above [target].
     *
     * The loop tests the UN-halved dimension, so the result is bounded BY the target rather than
     * merely above it: testing the already-halved one stopped a step early, letting a 1023² cover
     * decode full-size (~4.2 MB) against a budget sized for ~1 MB — enough for one bitmap to
     * trim the whole cache on put.
     */
    private fun decodeSampled(pic: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(pic, 0, pic.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(
            pic, 0, pic.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}

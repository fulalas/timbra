package com.timbra.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 * main thread and caches by albumId. Guards against RecyclerView view reuse via a tag.
 */
object ArtLoader {

    // Budget the cache in KB (~1/8 of the heap); sizeOf returns KB. A single 512² bitmap
    // is ~1 MB, so a fixed tiny ceiling would evict everything immediately.
    private val cache = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4096),
    ) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024
    }

    /** albumIds already known to have no artwork, so we don't retry the I/O on every bind. */
    private val misses = java.util.Collections.synchronizedSet(HashSet<Long>())

    /**
     * Drop the negative-result set and cached bitmaps. Called on library rescans — without
     * this, an album whose art was added after a miss would show the placeholder until the
     * process died (and stale art would survive re-tagging).
     */
    fun invalidate() {
        misses.clear()
        cache.evictAll()
    }

    /**
     * Load [albumId]'s art into [view]. There is deliberately NO placeholder image
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
        view.setTag(R.id.art_tag, albumId)
        cache.get(albumId)?.let { view.setImageBitmap(it); onArt(true); return }
        view.setImageDrawable(null)
        onArt(false)
        if (trackUri == null && albumId < 0) return
        if (albumId >= 0 && albumId in misses) return

        owner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decode(view, trackUri, albumId) }
            if (bmp != null) {
                cache.put(albumId, bmp)
                if (view.getTag(R.id.art_tag) == albumId) {
                    view.setImageBitmap(bmp)
                    onArt(true)
                }
            } else if (albumId >= 0) {
                misses.add(albumId)
            }
        }
    }

    private fun decode(view: ImageView, trackUri: Uri?, albumId: Long): Bitmap? {
        val resolver = view.context.contentResolver
        // API 29+: reliable thumbnail from the track content Uri.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && trackUri != null) {
            runCatching {
                return resolver.loadThumbnail(trackUri, Size(512, 512), null)
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
        return null
    }
}

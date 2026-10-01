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

    private val misses = java.util.Collections.synchronizedSet(HashSet<Long>())

    private val trackMisses = java.util.Collections.synchronizedSet(HashSet<Long>())

    private val generation = AtomicInteger(0)

    fun invalidate() {
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
        val key = (if (trackId != null) "t$trackId" else "a$albumId") + "@$target"
        view.setTag(R.id.art_tag, key)
        cache.get(key)?.let { view.setImageBitmap(it); onArt(true); return }
        view.setImageDrawable(null)
        onArt(false)
        if (trackUri == null && albumId < 0) return
        if (trackId != null) { if (trackId in trackMisses) return }
        else if (albumId >= 0 && albumId in misses) return

        val context = view.context.applicationContext
        val startedAt = generation.get()
        owner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decode(context, trackUri, albumId, target) }
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

    fun clear(view: ImageView) {
        view.setTag(R.id.art_tag, null)
        view.setImageDrawable(null)
    }

    private fun decode(context: Context, trackUri: Uri?, albumId: Long, target: Int): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && trackUri != null) {
            runCatching {
                return resolver.loadThumbnail(trackUri, Size(target, target), null).cappedTo(target)
            }
        }
        if (albumId >= 0) {
            runCatching {
                val bytes = resolver.openInputStream(MediaRepository.albumArtUri(albumId))
                    ?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) return decodeSampled(bytes, target)
            }
        }
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

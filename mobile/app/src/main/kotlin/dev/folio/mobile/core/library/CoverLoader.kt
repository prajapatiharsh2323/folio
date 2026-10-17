package dev.folio.mobile.core.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache

/**
 * Cover thumbnail loader for library cards.
 *
 * [sampleSize] is pure math (JVM-tested); [decode] is the Android side:
 * bounds pass → power-of-two inSampleSize → decode, with a small bitmap LRU
 * so lazy-list scrolling doesn't re-decode on every rebind. Covers are
 * display-only thumbnails (the full-size file stays in the doc dir).
 */
object CoverLoader {

    /** Card thumb is 44x58dp; decode at ~3x to stay crisp on any density. */
    const val REQ_W = 132
    const val REQ_H = 174

    /** Thumbnail cache budget: a few dozen covers at most. */
    private const val CACHE_BYTES = 8 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Smallest power of two such that the decoded image still covers
     * (reqW, reqH) — i.e. halving once more would drop below either request.
     * Never upscales (result is always >= 1). Non-positive inputs → 1.
     */
    fun sampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || reqW <= 0 || reqH <= 0) return 1
        var sample = 1
        var w = srcW
        var h = srcH
        while (w / 2 >= reqW && h / 2 >= reqH) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample
    }

    /**
     * Decodes `store`-relative [path] (as carried on
     * [LibraryUi.LibraryItem.cover] or `files.cover`) to a bitmap sampled for
     * [reqW]×[reqH] px, or null when the file is missing or not decodable.
     * Defaults match the library-card thumb; the reader splash asks for a
     * bigger size. Blocking — call from a background dispatcher.
     */
    fun decode(
        store: dev.folio.mobile.core.store.AppFileStore,
        path: String,
        reqW: Int = REQ_W,
        reqH: Int = REQ_H
    ): Bitmap? {
        val file = runCatching { store.resolve(path) }.getOrNull() ?: return null
        if (!file.isFile) return null
        val cacheKey = "$path@${reqW}x$reqH"
        cache.get(cacheKey)?.let { return it }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        cache.put(cacheKey, bitmap)
        return bitmap
    }
}

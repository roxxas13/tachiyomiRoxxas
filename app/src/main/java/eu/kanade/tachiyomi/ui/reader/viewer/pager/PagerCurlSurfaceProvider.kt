package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.system.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

/**
 * Small, pager-scoped cache of curl-only bitmap surfaces.
 *
 * ReaderPage owns the streams; this class owns and recycles only the decoded bitmap copies.
 */
internal class PagerCurlSurfaceProvider {
    private val cache = LinkedHashMap<ReaderPage, Bitmap>(MAX_CACHED_SURFACES, 0.75f, true)
    private var closed = false

    suspend fun load(page: ReaderPage): Bitmap? =
        withContext(Dispatchers.IO) {
            synchronized(cache) {
                if (closed) return@withContext null
                cache[page]
            }?.let { return@withContext it }
            if (page.status != Page.State.READY || page.firstHalf != null || page.stream == null) {
                return@withContext null
            }
            val decoded = decodeStaticPage(page) ?: return@withContext null
            synchronized(cache) {
                if (closed) {
                    decoded.recycle()
                    null
                } else {
                    cache[page]?.also { decoded.recycle() } ?: decoded.also { cache[page] = it }
                }
            }
        }

    fun trimTo(activePages: Set<ReaderPage>) {
        synchronized(cache) {
            val iterator = cache.entries.iterator()
            while (cache.size > MAX_CACHED_SURFACES && iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in activePages) {
                    iterator.remove()
                    entry.value.recycle()
                }
            }
        }
    }

    fun close() {
        synchronized(cache) {
            closed = true
            cache.values.forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            cache.clear()
        }
    }

    private fun decodeStaticPage(page: ReaderPage): Bitmap? =
        runCatching {
            page.stream?.invoke()?.buffered()?.use { stream ->
                if (ImageUtil.isAnimatedAndSupported(stream)) return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            page.stream?.invoke()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_SURFACE_DIMENSION) {
                sampleSize *= 2
            }
            val options =
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            page.stream?.invoke()?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()

    companion object {
        private const val MAX_CACHED_SURFACES = 8
        private const val MAX_SURFACE_DIMENSION = 2560
    }
}

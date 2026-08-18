package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import kotlin.math.max

/**
 * Retained implementation of the original clipped-band prototype for visual comparison.
 * It is intentionally not used by the production-facing [PageCurlView].
 */
internal class PageCurlCanvasReferenceRenderer {
    private val geometry = PageCurlGeometry()
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backsidePaint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.argb(150, 238, 232, 218), PorterDuff.Mode.SRC_ATOP)
        }
    private val destination = RectF()
    private val matrix = Matrix()

    fun draw(
        canvas: Canvas,
        current: Bitmap,
        next: Bitmap,
        progress: Float,
        direction: PageCurlDirection,
    ) {
        destination.set(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
        canvas.drawBitmap(next, null, destination, pagePaint)
        geometry.update(canvas.width.toFloat(), canvas.height.toFloat(), progress, direction)
        canvas.save()
        canvas.clipPath(geometry.visiblePage)
        canvas.drawBitmap(current, null, destination, pagePaint)
        canvas.restore()
        if (geometry.foldWidth <= 0f) return

        val scale = max(geometry.foldWidth / canvas.width * 2f, 0.03f)
        matrix.reset()
        matrix.setScale(canvas.width.toFloat() / current.width, canvas.height.toFloat() / current.height)
        matrix.postScale(-scale, 1f)
        val translation =
            if (direction == PageCurlDirection.LEFT_TO_RIGHT) {
                geometry.foldX + geometry.foldWidth
            } else {
                canvas.width * (1f + scale) - geometry.foldX - geometry.foldWidth
            }
        matrix.postTranslate(translation, 0f)
        canvas.save()
        canvas.clipPath(geometry.foldedPage)
        canvas.drawBitmap(current, matrix, backsidePaint)
        canvas.restore()
    }
}

package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Path
import kotlin.math.PI
import kotlin.math.sin

internal class PageCurlGeometry {
    val visiblePage = Path()
    val foldedPage = Path()
    val shadow = Path()

    var foldX = 0f
        private set
    var foldWidth = 0f
        private set

    fun update(
        width: Float,
        height: Float,
        progress: Float,
        direction: PageCurlDirection,
    ) {
        val clampedProgress = progress.coerceIn(0f, 1f)
        foldX = width * (1f - clampedProgress)
        foldWidth = width * 0.24f * sin(PI.toFloat() * clampedProgress).coerceAtLeast(0f)

        val bow = foldWidth * 0.32f
        val outerX = (foldX + foldWidth).coerceAtMost(width)
        val shadowX = (foldX - width * 0.035f).coerceAtLeast(0f)

        visiblePage.reset()
        visiblePage.moveTo(x(0f, width, direction), 0f)
        visiblePage.lineTo(x(foldX, width, direction), 0f)
        addCurve(visiblePage, foldX, bow, width, height, direction)
        visiblePage.lineTo(x(0f, width, direction), height)
        visiblePage.close()

        foldedPage.reset()
        foldedPage.moveTo(x(foldX, width, direction), 0f)
        foldedPage.lineTo(x(outerX, width, direction), 0f)
        addCurve(foldedPage, outerX, -bow, width, height, direction)
        foldedPage.lineTo(x(foldX, width, direction), height)
        addCurveReverse(foldedPage, foldX, bow, width, height, direction)
        foldedPage.close()

        shadow.reset()
        shadow.moveTo(x(shadowX, width, direction), 0f)
        shadow.lineTo(x(foldX, width, direction), 0f)
        addCurve(shadow, foldX, bow, width, height, direction)
        shadow.lineTo(x(shadowX, width, direction), height)
        addCurveReverse(shadow, shadowX, bow, width, height, direction)
        shadow.close()
    }

    private fun addCurve(
        path: Path,
        baseX: Float,
        bow: Float,
        width: Float,
        height: Float,
        direction: PageCurlDirection,
    ) {
        path.cubicTo(
            x(baseX + bow, width, direction),
            height * 0.28f,
            x(baseX - bow, width, direction),
            height * 0.72f,
            x(baseX, width, direction),
            height,
        )
    }

    private fun addCurveReverse(
        path: Path,
        baseX: Float,
        bow: Float,
        width: Float,
        height: Float,
        direction: PageCurlDirection,
    ) {
        path.cubicTo(
            x(baseX - bow, width, direction),
            height * 0.72f,
            x(baseX + bow, width, direction),
            height * 0.28f,
            x(baseX, width, direction),
            0f,
        )
    }

    private fun x(
        canonicalX: Float,
        width: Float,
        direction: PageCurlDirection,
    ): Float = if (direction == PageCurlDirection.LEFT_TO_RIGHT) canonicalX else width - canonicalX
}

package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

internal class PageCurlMeshRenderer {
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val destination = RectF()
    private val shadowMatrix = Matrix()
    private val shadowShader = LinearGradient(0f, 0f, 1f, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
    private val highlightShader = LinearGradient(0f, 0f, 1f, 0f, Color.TRANSPARENT, Color.WHITE, Shader.TileMode.CLAMP)
    private var shaderBitmap: Bitmap? = null

    fun draw(
        canvas: Canvas,
        current: Bitmap,
        next: Bitmap,
        mesh: PageCurlMesh,
        shadowStrength: Float,
        direction: PageCurlDirection,
    ) {
        destination.set(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
        canvas.drawBitmap(next, null, destination, backgroundPaint)
        ensureShader(current)
        drawRegion(canvas, mesh, mesh.flatIndices, mesh.flatIndexCount)
        drawRegion(canvas, mesh, mesh.frontIndices, mesh.frontIndexCount)
        drawRegion(canvas, mesh, mesh.backIndices, mesh.backIndexCount)
        drawContactShadow(canvas, mesh.contactX, shadowStrength, direction)
        drawHighlight(canvas, mesh.contactX, shadowStrength, direction)
    }

    private fun ensureShader(bitmap: Bitmap) {
        if (shaderBitmap === bitmap) return
        shaderBitmap = bitmap
        pagePaint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    @Suppress("DEPRECATION")
    private fun drawRegion(
        canvas: Canvas,
        mesh: PageCurlMesh,
        indices: ShortArray,
        indexCount: Int,
    ) {
        if (indexCount == 0) return
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES,
            mesh.vertices.size,
            mesh.vertices,
            0,
            mesh.textureCoordinates,
            0,
            mesh.colors,
            0,
            indices,
            0,
            indexCount,
            pagePaint,
        )
    }

    private fun drawContactShadow(
        canvas: Canvas,
        contactX: Float,
        strength: Float,
        direction: PageCurlDirection,
    ) {
        val width = canvas.width * 0.055f
        val left = if (direction == PageCurlDirection.LEFT_TO_RIGHT) contactX - width else contactX
        shadowMatrix.reset()
        shadowMatrix.setScale(if (direction == PageCurlDirection.LEFT_TO_RIGHT) width else -width, 1f)
        shadowMatrix.postTranslate(if (direction == PageCurlDirection.LEFT_TO_RIGHT) left else contactX + width, 0f)
        shadowShader.setLocalMatrix(shadowMatrix)
        shadowPaint.shader = shadowShader
        shadowPaint.alpha = (150 * strength.coerceIn(0f, 1f)).toInt()
        canvas.drawRect(left, 0f, left + width, canvas.height.toFloat(), shadowPaint)
    }

    private fun drawHighlight(
        canvas: Canvas,
        contactX: Float,
        strength: Float,
        direction: PageCurlDirection,
    ) {
        val width = canvas.width * 0.018f
        val left = if (direction == PageCurlDirection.LEFT_TO_RIGHT) contactX else contactX - width
        highlightMatrix(width, left, direction)
        highlightPaint.shader = highlightShader
        highlightPaint.alpha = (42 * strength.coerceIn(0f, 1f)).toInt()
        canvas.drawRect(left, 0f, left + width, canvas.height.toFloat(), highlightPaint)
    }

    private fun highlightMatrix(
        width: Float,
        left: Float,
        direction: PageCurlDirection,
    ) {
        shadowMatrix.reset()
        shadowMatrix.setScale(if (direction == PageCurlDirection.LEFT_TO_RIGHT) width else -width, 1f)
        shadowMatrix.postTranslate(if (direction == PageCurlDirection.LEFT_TO_RIGHT) left else left + width, 0f)
        highlightShader.setLocalMatrix(shadowMatrix)
    }
}

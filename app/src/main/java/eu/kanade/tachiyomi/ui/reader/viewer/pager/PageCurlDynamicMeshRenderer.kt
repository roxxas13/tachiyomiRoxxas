package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

internal class PageCurlDynamicMeshRenderer {
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val debugPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
    private val destination = RectF()
    private val gradientMatrix = Matrix()
    private val axisPath = Path()
    private val shadowShader = LinearGradient(0f, 0f, 1f, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
    private var shaderBitmap: Bitmap? = null

    fun draw(
        canvas: Canvas,
        current: Bitmap,
        next: Bitmap,
        mesh: PageCurlDynamicMesh,
        shadowStrength: Float,
        debugOverlay: Boolean,
        renderWidth: Float = canvas.width.toFloat(),
        renderHeight: Float = canvas.height.toFloat(),
        drawBackground: Boolean = true,
        exitTranslationX: Float = 0f,
    ) {
        if (drawBackground) {
            destination.set(0f, 0f, renderWidth, renderHeight)
            canvas.drawBitmap(next, null, destination, backgroundPaint)
        }
        ensureShader(current)
        canvas.save()
        canvas.translate(exitTranslationX, 0f)
        drawRegion(canvas, mesh, mesh.flatIndices, mesh.flatIndexCount)
        drawRegion(canvas, mesh, mesh.frontIndices, mesh.frontIndexCount)
        drawRegion(canvas, mesh, mesh.backIndices, mesh.backIndexCount)
        drawAxisShadow(canvas, mesh, shadowStrength, renderWidth, renderHeight)
        drawCrestHighlight(canvas, mesh, shadowStrength, renderWidth, renderHeight)
        if (debugOverlay) drawDebugOverlay(canvas, mesh)
        canvas.restore()
    }

    private fun ensureShader(bitmap: Bitmap) {
        if (shaderBitmap === bitmap) return
        shaderBitmap = bitmap
        pagePaint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    @Suppress("DEPRECATION")
    private fun drawRegion(
        canvas: Canvas,
        mesh: PageCurlDynamicMesh,
        indices: ShortArray,
        count: Int,
    ) {
        if (count == 0) return
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
            count,
            pagePaint,
        )
    }

    private fun drawAxisShadow(
        canvas: Canvas,
        mesh: PageCurlDynamicMesh,
        strength: Float,
        renderWidth: Float,
        renderHeight: Float,
    ) {
        val shadowWidth = renderWidth * 0.055f
        val angleDegrees = Math.toDegrees(kotlin.math.atan2(mesh.axisY.toDouble(), mesh.axisX.toDouble())).toFloat()
        gradientMatrix.reset()
        gradientMatrix.setScale(shadowWidth, 1f)
        gradientMatrix.postRotate(angleDegrees - 90f)
        gradientMatrix.postTranslate(mesh.foldX, mesh.foldY)
        shadowShader.setLocalMatrix(gradientMatrix)
        shadowPaint.shader = shadowShader
        shadowPaint.alpha = (135 * strength.coerceIn(0f, 1f)).toInt()
        canvas.save()
        canvas.rotate(angleDegrees - 90f, mesh.foldX, mesh.foldY)
        canvas.drawRect(
            mesh.foldX - shadowWidth,
            mesh.foldY - renderHeight * 1.5f,
            mesh.foldX,
            mesh.foldY + renderHeight * 1.5f,
            shadowPaint,
        )
        canvas.restore()
    }

    private fun drawCrestHighlight(
        canvas: Canvas,
        mesh: PageCurlDynamicMesh,
        strength: Float,
        renderWidth: Float,
        renderHeight: Float,
    ) {
        val crestX = mesh.foldX + mesh.normalX * mesh.curlBoundaryDistance * 0.5f
        val crestY = mesh.foldY + mesh.normalY * mesh.curlBoundaryDistance * 0.5f
        val extent = renderWidth + renderHeight
        shadowPaint.shader = null
        shadowPaint.color = Color.WHITE
        shadowPaint.alpha = (32 * strength.coerceIn(0f, 1f)).toInt()
        shadowPaint.strokeWidth = renderWidth * 0.012f
        canvas.drawLine(
            crestX - mesh.axisX * extent,
            crestY - mesh.axisY * extent,
            crestX + mesh.axisX * extent,
            crestY + mesh.axisY * extent,
            shadowPaint,
        )
    }

    private fun drawDebugOverlay(
        canvas: Canvas,
        mesh: PageCurlDynamicMesh,
    ) {
        debugPaint.color = Color.argb(135, 0, 255, 255)
        for (row in 0..mesh.vertical) {
            axisPath.reset()
            for (column in 0..mesh.horizontal) {
                val offset = (row * (mesh.horizontal + 1) + column) * 2
                if (column ==
                    0
                ) {
                    axisPath.moveTo(mesh.vertices[offset], mesh.vertices[offset + 1])
                } else {
                    axisPath.lineTo(
                        mesh.vertices[offset],
                        mesh.vertices[
                            offset +
                                1,
                        ],
                    )
                }
            }
            canvas.drawPath(axisPath, debugPaint)
        }
        for (column in 0..mesh.horizontal) {
            axisPath.reset()
            for (row in 0..mesh.vertical) {
                val offset = (row * (mesh.horizontal + 1) + column) * 2
                if (row ==
                    0
                ) {
                    axisPath.moveTo(mesh.vertices[offset], mesh.vertices[offset + 1])
                } else {
                    axisPath.lineTo(
                        mesh.vertices[offset],
                        mesh.vertices[
                            offset +
                                1,
                        ],
                    )
                }
            }
            canvas.drawPath(axisPath, debugPaint)
        }
        debugPaint.color = Color.YELLOW
        val extent = canvas.width + canvas.height.toFloat()
        canvas.drawLine(
            mesh.foldX - mesh.axisX * extent,
            mesh.foldY - mesh.axisY * extent,
            mesh.foldX + mesh.axisX * extent,
            mesh.foldY + mesh.axisY * extent,
            debugPaint,
        )
        debugPaint.color = Color.MAGENTA
        canvas.drawCircle(mesh.touchScreenX, mesh.touchScreenY, 14f, debugPaint)
        debugPaint.color = Color.GREEN
        val boundaryX = mesh.foldX + mesh.normalX * mesh.curlBoundaryDistance
        val boundaryY = mesh.foldY + mesh.normalY * mesh.curlBoundaryDistance
        canvas.drawLine(
            boundaryX - mesh.axisX * extent,
            boundaryY - mesh.axisY * extent,
            boundaryX + mesh.axisX * extent,
            boundaryY + mesh.axisY * extent,
            debugPaint,
        )
    }
}

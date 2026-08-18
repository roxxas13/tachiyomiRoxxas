package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal class PageCurlDynamicMesh(
    horizontal: Int,
    vertical: Int,
) {
    var horizontal = horizontal
        private set
    var vertical = vertical
        private set

    var vertices = FloatArray((horizontal + 1) * (vertical + 1) * 2)
        private set
    var textureCoordinates = FloatArray(vertices.size)
        private set
    var colors = IntArray((horizontal + 1) * (vertical + 1))
        private set
    var flatIndices = ShortArray(horizontal * vertical * 6)
        private set
    var frontIndices = ShortArray(horizontal * vertical * 6)
        private set
    var backIndices = ShortArray(horizontal * vertical * 6)
        private set
    var flatIndexCount = 0
        private set
    var frontIndexCount = 0
        private set
    var backIndexCount = 0
        private set

    var foldX = 0f
        private set
    var foldY = 0f
        private set
    var axisX = 0f
        private set
    var axisY = 1f
        private set
    var normalX = 1f
        private set
    var normalY = 0f
        private set
    var touchScreenX = 0f
        private set
    var touchScreenY = 0f
        private set
    var curlBoundaryDistance = 0f
        private set
    var physicalPullDistance = 0f
        private set

    fun resize(
        horizontal: Int,
        vertical: Int,
    ) {
        if (this.horizontal == horizontal && this.vertical == vertical) return
        this.horizontal = horizontal
        this.vertical = vertical
        vertices = FloatArray((horizontal + 1) * (vertical + 1) * 2)
        textureCoordinates = FloatArray(vertices.size)
        colors = IntArray((horizontal + 1) * (vertical + 1))
        flatIndices = ShortArray(horizontal * vertical * 6)
        frontIndices = ShortArray(horizontal * vertical * 6)
        backIndices = ShortArray(horizontal * vertical * 6)
    }

    @Suppress("LongParameterList")
    fun update(
        width: Float,
        height: Float,
        bitmapWidth: Float,
        bitmapHeight: Float,
        touchX: Float,
        touchY: Float,
        requestedRadius: Float,
        direction: PageCurlDirection,
        spineAttachment: Float = 0f,
        backsideBrightness: Float = 0.8f,
        debugChecks: Boolean = false,
    ) {
        flatIndexCount = 0
        frontIndexCount = 0
        backIndexCount = 0
        val requestedTouchX = if (direction == PageCurlDirection.LEFT_TO_RIGHT) touchX else width - touchX
        val canonicalTouchX = requestedTouchX.coerceIn(-width * MAX_PULL_BEYOND_PAGE, width * 1.1f)
        val canonicalTouchY = touchY.coerceIn(height * 0.04f, height * 0.96f)
        touchScreenX = touchX
        touchScreenY = canonicalTouchY
        val anchorX = width
        val anchorY = height * 0.5f
        var pullX = anchorX - canonicalTouchX
        var pullY = (anchorY - canonicalTouchY).coerceIn(-height * 0.42f, height * 0.42f)
        val pullLength = hypot(pullX, pullY)
        if (pullLength < 1f) {
            pullX = 1f
            pullY = 0f
        }
        val safeLength = hypot(pullX, pullY).coerceAtLeast(1f)
        physicalPullDistance = safeLength
        normalX = pullX / safeLength
        normalY = pullY / safeLength
        axisX = -normalY
        axisY = normalX
        foldX = (anchorX + canonicalTouchX) * 0.5f
        foldY = (anchorY + canonicalTouchY) * 0.5f
        val anchorDistance = safeLength * 0.5f
        val radius = requestedRadius.coerceAtMost(anchorDistance / (PI.toFloat() * 2.15f)).coerceAtLeast(2f)
        curlBoundaryDistance = PI.toFloat() * radius
        val tailLength = (anchorDistance - curlBoundaryDistance).coerceAtLeast(1f)
        val tailScale = anchorDistance / tailLength
        val cameraDistance = width * 8f

        for (row in 0..vertical) {
            val sourceY = height * row / vertical
            for (column in 0..horizontal) {
                val sourceX = width * column / horizontal
                val dx = sourceX - foldX
                val dy = sourceY - foldY
                val signedDistance = dx * normalX + dy * normalY
                val tangentDistance = dx * axisX + dy * axisY
                val angle: Float
                val deformedNormal: Float
                val deformedTangent: Float
                val depth: Float
                val region: Int
                when {
                    signedDistance <= 0f -> {
                        angle = 0f
                        deformedNormal = signedDistance
                        deformedTangent = tangentDistance
                        depth = 0f
                        region = REGION_FLAT
                    }
                    signedDistance < curlBoundaryDistance -> {
                        angle = signedDistance / radius
                        deformedNormal = radius * sin(angle)
                        deformedTangent = tangentDistance
                        depth = radius * (1f - cos(angle))
                        region = if (angle < PI.toFloat() / 2f) REGION_FRONT else REGION_BACK
                    }
                    else -> {
                        val tailDistance = signedDistance - curlBoundaryDistance
                        val tailProgress = ((signedDistance - curlBoundaryDistance) / tailLength).coerceIn(0f, 1f)
                        angle = PI.toFloat() + tailProgress * PI.toFloat() * 0.38f
                        deformedNormal = radius * sin(angle) - tailDistance * tailScale * 0.58f
                        deformedTangent = tangentDistance * (1f - tailProgress * 0.1f)
                        depth = radius * (1f - cos(angle)) * (1f - tailProgress * 0.25f)
                        region = REGION_BACK
                    }
                }
                val worldX = foldX + normalX * deformedNormal + axisX * deformedTangent
                val worldY = foldY + normalY * deformedNormal + axisY * deformedTangent
                val perspective = cameraDistance / (cameraDistance - depth).coerceAtLeast(cameraDistance * 0.8f)
                var projectedX = width * 0.5f + (worldX - width * 0.5f) * perspective
                var projectedY = height * 0.5f + (worldY - height * 0.5f) * perspective
                val spineWeight =
                    (1f - sourceX / (width * SPINE_CONSTRAINT_WIDTH)).coerceIn(0f, 1f) *
                        spineAttachment.coerceIn(0f, 1f)
                projectedX += (sourceX - projectedX) * spineWeight
                projectedY += (sourceY - projectedY) * spineWeight
                if (debugChecks) {
                    check(projectedX.isFinite() && projectedY.isFinite() && perspective.isFinite()) {
                        "Non-finite page-curl vertex at ($row, $column)"
                    }
                }
                val screenX = if (direction == PageCurlDirection.LEFT_TO_RIGHT) projectedX else width - projectedX
                val textureX =
                    if (direction == PageCurlDirection.LEFT_TO_RIGHT) sourceX else width - sourceX
                val vertex = row * (horizontal + 1) + column
                val offset = vertex * 2
                vertices[offset] = screenX
                vertices[offset + 1] = projectedY
                textureCoordinates[offset] = textureX / width * bitmapWidth
                textureCoordinates[offset + 1] = sourceY / height * bitmapHeight
                colors[vertex] = lightColor(angle, region, backsideBrightness)
            }
        }

        for (row in 0 until vertical) {
            for (column in 0 until horizontal) {
                val centerX = width * (column + 0.5f) / horizontal
                val centerY = height * (row + 0.5f) / vertical
                val distance = (centerX - foldX) * normalX + (centerY - foldY) * normalY
                val region =
                    when {
                        distance <= 0f -> REGION_FLAT
                        distance < radius * PI.toFloat() / 2f -> REGION_FRONT
                        else -> REGION_BACK
                    }
                appendCell(row, column, region)
            }
        }

        if (direction == PageCurlDirection.RIGHT_TO_LEFT) {
            foldX = width - foldX
            normalX = -normalX
            axisX = -axisX
        }
    }

    private fun appendCell(
        row: Int,
        column: Int,
        region: Int,
    ) {
        val indices: ShortArray
        var offset: Int
        when (region) {
            REGION_FLAT -> {
                indices = flatIndices
                offset = flatIndexCount
                flatIndexCount += 6
            }
            REGION_FRONT -> {
                indices = frontIndices
                offset = frontIndexCount
                frontIndexCount += 6
            }
            else -> {
                indices = backIndices
                offset = backIndexCount
                backIndexCount += 6
            }
        }
        val topLeft = (row * (horizontal + 1) + column).toShort()
        val topRight = (topLeft + 1).toShort()
        val bottomLeft = (topLeft + horizontal + 1).toShort()
        val bottomRight = (bottomLeft + 1).toShort()
        indices[offset++] = topLeft
        indices[offset++] = bottomLeft
        indices[offset++] = topRight
        indices[offset++] = topRight
        indices[offset++] = bottomLeft
        indices[offset] = bottomRight
    }

    private fun lightColor(
        angle: Float,
        region: Int,
        backsideBrightness: Float,
    ): Int =
        when (region) {
            REGION_FLAT -> Color.WHITE
            REGION_FRONT -> {
                val light = (0.7f + 0.3f * cos(angle)).coerceIn(0.55f, 1f)
                val value = (255 * light).toInt()
                Color.rgb(value, value, value)
            }
            else -> {
                val light =
                    (backsideBrightness.coerceIn(0.5f, 1f) * (0.88f + 0.12f * abs(cos(angle))))
                        .coerceIn(0.5f, 1f)
                Color.rgb((255 * light).toInt(), (248 * light).toInt(), (232 * light).toInt())
            }
        }

    private companion object {
        const val MAX_PULL_BEYOND_PAGE = 0.35f
        const val SPINE_CONSTRAINT_WIDTH = 0.24f
        const val REGION_FLAT = 0
        const val REGION_FRONT = 1
        const val REGION_BACK = 2
    }
}

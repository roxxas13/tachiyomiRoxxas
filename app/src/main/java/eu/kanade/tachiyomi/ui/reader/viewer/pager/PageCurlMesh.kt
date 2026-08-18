package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal class PageCurlMesh(
    subdivisions: Int,
) {
    var subdivisions = subdivisions
        private set

    var vertices = FloatArray((subdivisions + 1) * 4)
        private set
    var textureCoordinates = FloatArray((subdivisions + 1) * 4)
        private set
    var colors = IntArray((subdivisions + 1) * 2)
        private set
    var flatIndices = ShortArray(subdivisions * 6)
        private set
    var frontIndices = ShortArray(subdivisions * 6)
        private set
    var backIndices = ShortArray(subdivisions * 6)
        private set

    var flatIndexCount = 0
        private set
    var frontIndexCount = 0
        private set
    var backIndexCount = 0
        private set
    var contactX = 0f
        private set

    fun resize(subdivisions: Int) {
        if (this.subdivisions == subdivisions) return
        this.subdivisions = subdivisions
        vertices = FloatArray((subdivisions + 1) * 4)
        textureCoordinates = FloatArray((subdivisions + 1) * 4)
        colors = IntArray((subdivisions + 1) * 2)
        flatIndices = ShortArray(subdivisions * 6)
        frontIndices = ShortArray(subdivisions * 6)
        backIndices = ShortArray(subdivisions * 6)
    }

    fun update(
        viewWidth: Float,
        viewHeight: Float,
        bitmapWidth: Float,
        bitmapHeight: Float,
        progress: Float,
        radius: Float,
        direction: PageCurlDirection,
    ) {
        flatIndexCount = 0
        frontIndexCount = 0
        backIndexCount = 0
        val safeRadius = radius.coerceAtLeast(1f)
        val halfTurnLength = PI.toFloat() * safeRadius
        val fold = viewWidth - progress.coerceIn(0f, 1f) * (viewWidth + halfTurnLength)
        contactX = mirrorX(fold, viewWidth, direction)
        val cameraDistance = viewWidth * 7f

        for (column in 0..subdivisions) {
            val sourceX = viewWidth * column / subdivisions
            val distance = sourceX - fold
            val angle: Float
            val transformedX: Float
            val depth: Float
            val region: MeshRegion
            when {
                distance <= 0f -> {
                    angle = 0f
                    transformedX = sourceX
                    depth = 0f
                    region = MeshRegion.FLAT
                }
                distance < halfTurnLength -> {
                    angle = distance / safeRadius
                    transformedX = fold + safeRadius * sin(angle)
                    depth = safeRadius * (1f - cos(angle))
                    region = if (angle < PI.toFloat() / 2f) MeshRegion.FRONT else MeshRegion.BACK
                }
                else -> {
                    angle = PI.toFloat()
                    transformedX = fold - (distance - halfTurnLength)
                    depth = safeRadius * 2f
                    region = MeshRegion.BACK
                }
            }
            val perspective = cameraDistance / (cameraDistance - depth).coerceAtLeast(cameraDistance * 0.75f)
            val projectedX = viewWidth / 2f + (transformedX - viewWidth / 2f) * perspective
            val screenX = mirrorX(projectedX, viewWidth, direction)
            val textureX =
                if (direction == PageCurlDirection.LEFT_TO_RIGHT) {
                    sourceX / viewWidth * bitmapWidth
                } else {
                    (viewWidth - sourceX) / viewWidth * bitmapWidth
                }
            val color =
                when (region) {
                    MeshRegion.FLAT -> Color.WHITE
                    MeshRegion.FRONT -> {
                        val light = (0.72f + 0.28f * cos(angle)).coerceIn(0.58f, 1f)
                        val value = (255 * light).toInt()
                        Color.rgb(value, value, value)
                    }
                    MeshRegion.BACK -> {
                        val light = (0.56f + 0.16f * -cos(angle)).coerceIn(0.5f, 0.72f)
                        Color.rgb((245 * light).toInt(), (232 * light).toInt(), (210 * light).toInt())
                    }
                }
            val offset = column * 4
            vertices[offset] = screenX
            vertices[offset + 1] = viewHeight / 2f + (0f - viewHeight / 2f) * perspective
            vertices[offset + 2] = screenX
            vertices[offset + 3] = viewHeight / 2f + (viewHeight - viewHeight / 2f) * perspective
            textureCoordinates[offset] = textureX
            textureCoordinates[offset + 1] = 0f
            textureCoordinates[offset + 2] = textureX
            textureCoordinates[offset + 3] = bitmapHeight
            colors[column * 2] = color
            colors[column * 2 + 1] = color
        }

        for (column in 0 until subdivisions) {
            val midpoint = viewWidth * (column + 0.5f) / subdivisions
            val distance = midpoint - fold
            val target =
                when {
                    distance <= 0f -> MeshRegion.FLAT
                    distance / safeRadius < PI.toFloat() / 2f -> MeshRegion.FRONT
                    else -> MeshRegion.BACK
                }
            appendStrip(column, target)
        }
    }

    private fun appendStrip(
        column: Int,
        region: MeshRegion,
    ) {
        val target: ShortArray
        var offset: Int
        when (region) {
            MeshRegion.FLAT -> {
                target = flatIndices
                offset = flatIndexCount
                flatIndexCount += 6
            }
            MeshRegion.FRONT -> {
                target = frontIndices
                offset = frontIndexCount
                frontIndexCount += 6
            }
            MeshRegion.BACK -> {
                target = backIndices
                offset = backIndexCount
                backIndexCount += 6
            }
        }
        val topLeft = (column * 2).toShort()
        val bottomLeft = (column * 2 + 1).toShort()
        val topRight = (column * 2 + 2).toShort()
        val bottomRight = (column * 2 + 3).toShort()
        target[offset++] = topLeft
        target[offset++] = bottomLeft
        target[offset++] = topRight
        target[offset++] = topRight
        target[offset++] = bottomLeft
        target[offset] = bottomRight
    }

    private fun mirrorX(
        x: Float,
        width: Float,
        direction: PageCurlDirection,
    ) = if (direction == PageCurlDirection.LEFT_TO_RIGHT) x else width - x

    private enum class MeshRegion {
        FLAT,
        FRONT,
        BACK,
    }
}

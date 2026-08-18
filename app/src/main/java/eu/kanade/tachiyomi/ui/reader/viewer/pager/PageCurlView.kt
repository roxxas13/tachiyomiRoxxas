package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.core.animation.doOnEnd
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Standalone interactive horizontal page curl. Supplied bitmaps remain owned by the caller. */
class PageCurlView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val mesh = PageCurlMesh(DEFAULT_SUBDIVISIONS)
        private val renderer = PageCurlMeshRenderer()
        private val dynamicMesh = PageCurlDynamicMesh(DEFAULT_HORIZONTAL_SUBDIVISIONS, DEFAULT_VERTICAL_SUBDIVISIONS)
        private val dynamicRenderer = PageCurlDynamicMeshRenderer()
        private val canvasRenderer = PageCurlCanvasReferenceRenderer()
        private var currentPage: Bitmap? = null
        private var backPage: Bitmap? = null
        private var nextPage: Bitmap? = null
        private var spreadPages: SpreadPages? = null
        private val spreadDestination = RectF()
        private val turningDestination = RectF()
        private val spreadPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val spinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var spineGradient: LinearGradient? = null
        private var direction = PageCurlDirection.LEFT_TO_RIGHT
        private var isDragging = false
        private var curlStarted = false
        private var downX = 0f
        private var downY = 0f
        private var settleAnimator: ValueAnimator? = null
        private var velocityTracker: VelocityTracker? = null
        private var touchX = 0f
        private var touchY = 0f
        private var settleStartX = 0f
        private var settleStartY = 0f
        private var isCurlCompleted = false
        private var interactionPhase = PageCurlInteractionPhase.IDLE
        private var exitTranslationX = 0f

        var onCurlCompleted: (() -> Unit)? = null
        var onCurlCancelled: (() -> Unit)? = null
        var onDebugStateChanged: ((PageCurlDebugState) -> Unit)? = null

        var curlRadiusFraction: Float = DEFAULT_RADIUS_FRACTION
            set(value) {
                field = value.coerceIn(0.04f, 0.3f)
                invalidate()
            }

        var meshSubdivisionCount: Int = DEFAULT_SUBDIVISIONS
            set(value) {
                field = value.coerceIn(MIN_SUBDIVISIONS, MAX_SUBDIVISIONS)
                mesh.resize(field)
                invalidate()
            }

        var shadowStrength: Float = DEFAULT_SHADOW_STRENGTH
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        var backsideBrightness: Float = DEFAULT_BACKSIDE_BRIGHTNESS
            set(value) {
                field = value.coerceIn(0.5f, 1f)
                invalidate()
            }

        var exitCaptureProgress: Float = DEFAULT_EXIT_CAPTURE_PROGRESS
            set(value) {
                field = value.coerceIn(0.75f, 0.9f)
            }

        var rendererMode = PageCurlRendererMode.DYNAMIC_MESH
            set(value) {
                field = value
                invalidate()
            }

        var debugOverlay = false
            set(value) {
                field = value
                invalidate()
            }

        var debugFiniteChecks = false

        /** Fraction of the full view width accepted at the active edge. */
        var activationZoneFraction = EDGE_FRACTION
            set(value) {
                field = value.coerceIn(EDGE_FRACTION, 1f)
            }

        var horizontalSubdivisionCount = DEFAULT_HORIZONTAL_SUBDIVISIONS
            set(value) {
                field = value.coerceIn(20, 72)
                dynamicMesh.resize(field, verticalSubdivisionCount)
                invalidate()
            }

        var verticalSubdivisionCount = DEFAULT_VERTICAL_SUBDIVISIONS
            set(value) {
                field = value.coerceIn(8, 40)
                dynamicMesh.resize(horizontalSubdivisionCount, field)
                invalidate()
            }

        var curlProgress: Float = 0f
            private set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        fun setPages(
            current: Bitmap,
            next: Bitmap,
            underlying: Bitmap = next,
        ) {
            spreadPages = null
            currentPage = current
            backPage = next
            nextPage = underlying
            reset()
        }

        /** Debug/prototype spread API. Supplied bitmaps remain owned by the caller. */
        fun setSpreadPages(
            fixedCurrentPage: Bitmap?,
            turningCurrentPage: Bitmap?,
            fixedNextPage: Bitmap?,
            incomingNextPage: Bitmap?,
        ) {
            spreadPages = SpreadPages(fixedCurrentPage, turningCurrentPage, fixedNextPage, incomingNextPage)
            currentPage = turningCurrentPage
            backPage = fixedNextPage
            nextPage = incomingNextPage ?: fixedNextPage
            rendererMode = PageCurlRendererMode.DYNAMIC_MESH
            reset()
        }

        fun setDirection(direction: PageCurlDirection) {
            if (this.direction != direction) {
                this.direction = direction
                reset()
            }
        }

        fun reset() {
            cancelSettleAnimation()
            recycleVelocityTracker()
            isDragging = false
            curlStarted = false
            isCurlCompleted = false
            interactionPhase = PageCurlInteractionPhase.IDLE
            exitTranslationX = 0f
            curlProgress = 0f
            touchX = if (direction == PageCurlDirection.LEFT_TO_RIGHT) width.toFloat() else 0f
            touchY = height * 0.5f
            dispatchDebugState()
        }

        /**
         * Starts a gesture already validated and claimed by an external owner such as PagerViewer.
         * Unlike [onTouchEvent], this intentionally skips the standalone edge-activation test.
         */
        fun beginExternalGesture(
            downX: Float,
            downY: Float,
            @Suppress("UNUSED_PARAMETER") eventTime: Long,
        ): Boolean = beginGesture(downX, downY, requireActiveEdge = false)

        fun updateExternalGesture(
            x: Float,
            y: Float,
            @Suppress("UNUSED_PARAMETER") eventTime: Long,
        ): Boolean = updateGesture(x, y, skipTouchSlop = true)

        fun endExternalGesture(
            x: Float,
            y: Float,
            eventTime: Long,
        ): Boolean {
            if (!isDragging) return false
            updateExternalGesture(x, y, eventTime)
            if (curlStarted) finishDrag(true) else reset()
            return true
        }

        fun cancelExternalGesture(): Boolean {
            if (!isDragging) return false
            if (curlStarted) finishDrag(false) else reset()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            spreadPages?.let {
                drawSpread(canvas, it)
                return
            }
            val current = currentPage ?: return
            val back = backPage ?: current
            val next = nextPage ?: return
            if (width == 0 || height == 0) return
            if (isCurlCompleted) {
                spreadDestination.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawBitmap(next, null, spreadDestination, spreadPaint)
                return
            }
            when (rendererMode) {
                PageCurlRendererMode.CANVAS -> canvasRenderer.draw(canvas, current, next, curlProgress, direction)
                PageCurlRendererMode.VERTICAL_MESH -> {
                    mesh.update(
                        width.toFloat(),
                        height.toFloat(),
                        current.width.toFloat(),
                        current.height.toFloat(),
                        curlProgress,
                        width * curlRadiusFraction,
                        direction,
                    )
                    renderer.draw(canvas, current, next, mesh, shadowStrength, direction)
                }
                PageCurlRendererMode.DYNAMIC_MESH -> {
                    dynamicMesh.update(
                        width.toFloat(),
                        height.toFloat(),
                        current.width.toFloat(),
                        current.height.toFloat(),
                        back.width.toFloat(),
                        back.height.toFloat(),
                        touchX,
                        touchY,
                        width * curlRadiusFraction,
                        direction,
                        backsideBrightness = backsideBrightness,
                        debugChecks = debugFiniteChecks,
                    )
                    dynamicRenderer.draw(
                        canvas,
                        current,
                        back,
                        next,
                        dynamicMesh,
                        shadowStrength,
                        debugOverlay,
                        exitTranslationX = exitTranslationX,
                    )
                }
            }
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            val spineX = w * 0.5f
            val halfWidth = w * 0.018f
            spineGradient =
                LinearGradient(
                    spineX - halfWidth,
                    0f,
                    spineX + halfWidth,
                    0f,
                    intArrayOf(Color.TRANSPARENT, Color.argb(80, 0, 0, 0), Color.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP,
                )
            if (!isDragging && settleAnimator == null && curlProgress == 0f) {
                touchX = if (direction == PageCurlDirection.LEFT_TO_RIGHT) w.toFloat() else 0f
                touchY = h * 0.5f
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (currentPage == null || nextPage == null || width == 0 || isCurlCompleted) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!beginGesture(event.x, event.y, requireActiveEdge = true)) return false
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging) return false
                    velocityTracker?.addMovement(event)
                    return updateGesture(event.x, event.y, skipTouchSlop = false)
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) return false
                    velocityTracker?.addMovement(event)
                    touchX = event.x
                    touchY = event.y
                    updateProgress(event.x)
                    if (curlStarted) {
                        finishDrag(true)
                    } else {
                        reset()
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) return false
                    if (curlStarted) finishDrag(false) else reset()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onDetachedFromWindow() {
            cancelSettleAnimation()
            recycleVelocityTracker()
            super.onDetachedFromWindow()
        }

        private fun finishDrag(complete: Boolean) {
            isDragging = false
            parent?.requestDisallowInterceptTouchEvent(false)
            recycleVelocityTracker()
            settle(complete)
        }

        private fun beginGesture(
            x: Float,
            y: Float,
            requireActiveEdge: Boolean,
        ): Boolean {
            if (currentPage == null || nextPage == null || width == 0 || height == 0 || isCurlCompleted) return false
            if (requireActiveEdge && !isNearActiveEdge(x)) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            cancelSettleAnimation()
            recycleVelocityTracker()
            isDragging = true
            curlStarted = false
            downX = x
            downY = y
            interactionPhase = PageCurlInteractionPhase.TOUCH_PENDING
            touchX = x
            touchY = y
            updateProgress(x)
            dispatchDebugState()
            return true
        }

        private fun updateGesture(
            x: Float,
            y: Float,
            skipTouchSlop: Boolean,
        ): Boolean {
            if (!isDragging) return false
            if (!curlStarted) {
                if (!skipTouchSlop && hypot(x - downX, y - downY) < ViewConfiguration.get(context).scaledTouchSlop) {
                    return true
                }
                curlStarted = true
                interactionPhase = PageCurlInteractionPhase.DRAGGING
            }
            touchX = x
            touchY = y
            updateProgress(x)
            dispatchDebugState()
            return true
        }

        private fun isNearActiveEdge(x: Float): Boolean {
            val edgeSize = max(48f * resources.displayMetrics.density, width * activationZoneFraction)
            return if (direction == PageCurlDirection.LEFT_TO_RIGHT) x >= width - edgeSize else x <= edgeSize
        }

        private fun updateProgress(x: Float) {
            val travel = if (spreadPages == null) width.toFloat() else width * 0.5f
            curlProgress = if (direction == PageCurlDirection.LEFT_TO_RIGHT) (width - x) / travel else x / travel
        }

        private fun settle(complete: Boolean) {
            val startProgress = curlProgress
            settleStartX = touchX
            settleStartY = touchY
            interactionPhase = PageCurlInteractionPhase.SETTLING
            val pageWidth = if (spreadPages == null) width.toFloat() else width * 0.5f
            val turningLeft = if (spreadPages != null && direction == PageCurlDirection.LEFT_TO_RIGHT) pageWidth else 0f
            val physicalTargetX =
                if (complete) {
                    val pullBeyondPage = (exitCaptureProgress - 0.7f).coerceIn(0.05f, 0.2f)
                    if (direction == PageCurlDirection.LEFT_TO_RIGHT) {
                        turningLeft - pageWidth * pullBeyondPage
                    } else {
                        turningLeft + pageWidth * (1f + pullBeyondPage)
                    }
                } else {
                    if (direction == PageCurlDirection.LEFT_TO_RIGHT) width.toFloat() else 0f
                }
            settleAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration =
                        if (complete) {
                            (
                                MIN_COMPLETE_DURATION_MS +
                                    (MAX_COMPLETE_DURATION_MS - MIN_COMPLETE_DURATION_MS) * (1f - startProgress)
                            ).toLong()
                        } else {
                            (SETTLE_DURATION_MS * abs(startProgress)).toLong().coerceAtLeast(80L)
                        }
                    addUpdateListener {
                        val fraction = it.animatedFraction
                        if (complete) {
                            val physicalFraction = (fraction / exitCaptureProgress).coerceAtMost(1f)
                            curlProgress = startProgress + (1f - startProgress) * physicalFraction
                            touchX = settleStartX + (physicalTargetX - settleStartX) * physicalFraction
                            touchY = settleStartY + (height * 0.5f - settleStartY) * physicalFraction * 0.35f
                            if (fraction >= exitCaptureProgress) {
                                interactionPhase = PageCurlInteractionPhase.EXITING
                                val exitFraction = (fraction - exitCaptureProgress) / (1f - exitCaptureProgress)
                                val exitDirection = if (direction == PageCurlDirection.LEFT_TO_RIGHT) -1f else 1f
                                exitTranslationX = exitDirection * width * EXIT_DISTANCE_PAGES * exitFraction
                            }
                        } else {
                            curlProgress = startProgress * (1f - fraction)
                            touchX = settleStartX + (physicalTargetX - settleStartX) * fraction
                            touchY = settleStartY + (height * 0.5f - settleStartY) * fraction * 0.35f
                        }
                        dispatchDebugState()
                    }
                    doOnEnd {
                        settleAnimator = null
                        if (complete) {
                            if (!isCurlCompleted) {
                                isCurlCompleted = true
                                interactionPhase = PageCurlInteractionPhase.COMPLETED
                                invalidate()
                                dispatchDebugState()
                                onCurlCompleted?.invoke()
                            }
                        } else {
                            interactionPhase = PageCurlInteractionPhase.IDLE
                            exitTranslationX = 0f
                            dispatchDebugState()
                            onCurlCancelled?.invoke()
                        }
                    }
                    start()
                }
        }

        private fun cancelSettleAnimation() {
            settleAnimator?.removeAllListeners()
            settleAnimator?.cancel()
            settleAnimator = null
        }

        private fun recycleVelocityTracker() {
            velocityTracker?.recycle()
            velocityTracker = null
        }

        private fun drawSpread(
            canvas: Canvas,
            pages: SpreadPages,
        ) {
            if (width == 0 || height == 0) return
            canvas.drawColor(Color.rgb(24, 24, 24))
            val pageWidth = width * 0.5f
            val turningLeft = if (direction == PageCurlDirection.LEFT_TO_RIGHT) pageWidth else 0f
            val fixedLeft = if (turningLeft == 0f) pageWidth else 0f
            val fixed = if (isCurlCompleted) pages.fixedNext else pages.fixedCurrent
            fixed?.let { drawPage(canvas, it, fixedLeft, pageWidth) }
            pages.incomingNext?.let { drawPage(canvas, it, turningLeft, pageWidth) }
            drawSpine(canvas, pageWidth)
            if (isCurlCompleted) return

            val turningCurrent = pages.turningCurrent ?: return
            val turningBack = pages.fixedNext ?: turningCurrent
            val rendererNext = pages.incomingNext ?: pages.fixedNext ?: return
            fitPage(turningCurrent, turningLeft, pageWidth, turningDestination)
            canvas.save()
            canvas.translate(turningDestination.left, turningDestination.top)
            dynamicMesh.update(
                turningDestination.width(),
                turningDestination.height(),
                turningCurrent.width.toFloat(),
                turningCurrent.height.toFloat(),
                turningBack.width.toFloat(),
                turningBack.height.toFloat(),
                touchX - turningDestination.left,
                touchY - turningDestination.top,
                turningDestination.width() * curlRadiusFraction,
                direction,
                spineAttachment = (1f - ((curlProgress - 0.68f) / 0.3f)).coerceIn(0f, 1f),
                backsideBrightness = backsideBrightness,
                debugChecks = debugFiniteChecks,
            )
            dynamicRenderer.draw(
                canvas,
                turningCurrent,
                turningBack,
                rendererNext,
                dynamicMesh,
                shadowStrength,
                debugOverlay,
                turningDestination.width(),
                turningDestination.height(),
                drawBackground = false,
                exitTranslationX = exitTranslationX,
            )
            canvas.restore()
        }

        private fun drawPage(
            canvas: Canvas,
            bitmap: Bitmap,
            left: Float,
            pageWidth: Float,
        ) {
            fitPage(bitmap, left, pageWidth, spreadDestination)
            canvas.drawBitmap(bitmap, null, spreadDestination, spreadPaint)
        }

        private fun fitPage(
            bitmap: Bitmap,
            left: Float,
            pageWidth: Float,
            destination: RectF,
        ) {
            val scale = minOf(pageWidth / bitmap.width, height.toFloat() / bitmap.height)
            val fittedWidth = bitmap.width * scale
            val fittedHeight = bitmap.height * scale
            val fittedLeft = left + (pageWidth - fittedWidth) * 0.5f
            val fittedTop = (height - fittedHeight) * 0.5f
            destination.set(fittedLeft, fittedTop, fittedLeft + fittedWidth, fittedTop + fittedHeight)
        }

        private fun drawSpine(
            canvas: Canvas,
            spineX: Float,
        ) {
            val halfWidth = width * 0.018f
            spinePaint.shader = spineGradient
            canvas.drawRect(spineX - halfWidth, 0f, spineX + halfWidth, height.toFloat(), spinePaint)
            spinePaint.shader = null
        }

        private data class SpreadPages(
            val fixedCurrent: Bitmap?,
            val turningCurrent: Bitmap?,
            val fixedNext: Bitmap?,
            val incomingNext: Bitmap?,
        )

        fun debugState() =
            PageCurlDebugState(
                interactionPhase,
                curlProgress,
                dynamicMesh.physicalPullDistance,
                exitTranslationX,
                touchX,
                touchY,
            )

        private fun dispatchDebugState() {
            onDebugStateChanged?.invoke(debugState())
        }

        private companion object {
            const val DEFAULT_SUBDIVISIONS = 64
            const val DEFAULT_HORIZONTAL_SUBDIVISIONS = 48
            const val DEFAULT_VERTICAL_SUBDIVISIONS = 20
            const val MIN_SUBDIVISIONS = 24
            const val MAX_SUBDIVISIONS = 120
            const val DEFAULT_RADIUS_FRACTION = 0.11f
            const val DEFAULT_SHADOW_STRENGTH = 0.7f
            const val EDGE_FRACTION = 0.15f
            const val SETTLE_DURATION_MS = 320L
            const val MIN_COMPLETE_DURATION_MS = 300L
            const val MAX_COMPLETE_DURATION_MS = 650L
            const val EXIT_DISTANCE_PAGES = 1.7f
            const val DEFAULT_EXIT_CAPTURE_PROGRESS = 0.82f
            const val DEFAULT_BACKSIDE_BRIGHTNESS = 0.9f
        }
    }

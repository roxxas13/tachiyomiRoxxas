package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.os.Parcelable
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.viewpager.widget.DirectionalViewPager
import eu.kanade.tachiyomi.ui.reader.viewer.GestureDetectorWithLongTap

/**
 * Pager implementation that listens for tap and long tap and allows temporarily disabling touch
 * events in order to work with child views that need to disable touch events on this parent. The
 * pager can also be declared to be vertical by creating it with [isHorizontal] to false.
 */
open class Pager(
    context: Context,
    isHorizontal: Boolean = true,
) : DirectionalViewPager(context, isHorizontal) {
    /**
     * Tap listener function to execute when a tap is detected.
     */
    var tapListener: ((MotionEvent) -> Unit)? = null

    /**
     * Long tap listener function to execute when a long tap is detected.
     */
    var longTapListener: ((MotionEvent) -> Boolean)? = null

    /** Gives an optional pager-only renderer first refusal of a complete touch sequence. */
    var pageTransitionTouchHandler: ((MotionEvent) -> PageTransitionTouchResult)? = null

    private var pageTransitionTouchState = PageTransitionTouchResult.REJECTED

    var isRestoring = false

    override fun onRestoreInstanceState(state: Parcelable?) {
        isRestoring = true
        val currentItem = currentItem
        super.onRestoreInstanceState(state)
        setCurrentItem(currentItem, false)
        isRestoring = false
    }

    /**
     * Gesture listener that implements tap and long tap events.
     */
    private val gestureListener =
        object : GestureDetectorWithLongTap.Listener() {
            override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
                tapListener?.invoke(ev)
                return true
            }

            override fun onLongTapConfirmed(ev: MotionEvent) {
                val listener = longTapListener
                if (listener != null && listener.invoke(ev)) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            }
        }

    /**
     * Gesture detector which handles motion events.
     */
    private val gestureDetector = GestureDetectorWithLongTap(context, gestureListener)

    /**
     * Whether the gesture detector is currently enabled.
     */
    private var isGestureDetectorEnabled = true

    /**
     * Dispatches a touch event.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            pageTransitionTouchState = pageTransitionTouchHandler?.invoke(ev) ?: PageTransitionTouchResult.REJECTED
        } else if (pageTransitionTouchState != PageTransitionTouchResult.REJECTED) {
            val previousState = pageTransitionTouchState
            pageTransitionTouchState = pageTransitionTouchHandler?.invoke(ev) ?: PageTransitionTouchResult.REJECTED
            if (previousState == PageTransitionTouchResult.PENDING &&
                pageTransitionTouchState == PageTransitionTouchResult.CLAIMED
            ) {
                // ViewPager and its image child already received DOWN. Cancel their sequence before
                // the move that crossed slop can start the stock pager animation.
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                gestureDetector.onTouchEvent(cancel)
                cancel.recycle()
            }
        }
        if (pageTransitionTouchState == PageTransitionTouchResult.CLAIMED) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                pageTransitionTouchState = PageTransitionTouchResult.REJECTED
            }
            return true
        }
        val handled = super.dispatchTouchEvent(ev)
        if (isGestureDetectorEnabled) {
            gestureDetector.onTouchEvent(ev)
        }
        return handled
    }

    /**
     * Whether the given [ev] should be intercepted. Only used to prevent crashes when child
     * views manipulate [requestDisallowInterceptTouchEvent].
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        try {
            super.onInterceptTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            false
        }

    /**
     * Handles a touch event. Only used to prevent crashes when child views manipulate
     * [requestDisallowInterceptTouchEvent].
     */
    override fun onTouchEvent(ev: MotionEvent): Boolean =
        try {
            super.onTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            false
        }

    /**
     * Executes the given key event when this pager has focus. Just do nothing because the reader
     * already dispatches key events to the viewer and has more control than this method.
     */
    override fun executeKeyEvent(event: KeyEvent): Boolean {
        // Disable viewpager's default key event handling
        return false
    }

    /**
     * Enables or disables the gesture detector.
     */
    fun setGestureDetectorEnabled(enabled: Boolean) {
        isGestureDetectorEnabled = enabled
    }
}

enum class PageTransitionTouchResult {
    REJECTED,
    PENDING,
    CLAIMED,
}

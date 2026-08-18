package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.settings.PageTransition
import eu.kanade.tachiyomi.ui.reader.viewer.BaseViewer
import eu.kanade.tachiyomi.ui.reader.viewer.GamepadHoldLoop
import eu.kanade.tachiyomi.ui.reader.viewer.JOYSTICK_DEADZONE
import eu.kanade.tachiyomi.ui.reader.viewer.JOYSTICK_PAN_INTERVAL_MS
import eu.kanade.tachiyomi.ui.reader.viewer.JOYSTICK_PAN_STEP
import eu.kanade.tachiyomi.ui.reader.viewer.PAN_STEP
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.ZOOM_HOLD_INTERVAL_MS
import eu.kanade.tachiyomi.ui.reader.viewer.gamepadZoomRate
import eu.kanade.tachiyomi.ui.reader.viewer.isDpadHatMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import uy.kohesive.injekt.injectLazy
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Implementation of a [BaseViewer] to display pages with a [ViewPager].
 */
@Suppress("LeakingThis")
abstract class PagerViewer(
    val activity: ReaderActivity,
) : BaseViewer {
    val downloadManager: DownloadManager by injectLazy()

    val scope = MainScope()

    /**
     * View pager used by this viewer. It's abstract to implement L2R, R2L and vertical pagers on
     * top of this class.
     */
    val pager = createPager()

    /**
     * Configuration used by the pager, like allow taps, scale mode on images, page transitions...
     */
    val config = PagerConfig(scope, this)

    /**
     * Adapter of the pager.
     */
    private val adapter = PagerViewerAdapter(this)

    private val curlSurfaceProvider = PagerCurlSurfaceProvider()
    private val curlView = PageCurlView(activity)
    private val viewerContainer = FrameLayout(activity)
    private val curlDiagnostics = TextView(activity)
    private var curlPrepareJob: Job? = null
    private var curlSurfaceReady = false
    private var curlTargetPosition = -1
    private var forwardCurlBinding: CurlSurfaceBinding? = null
    private var backwardCurlBinding: CurlSurfaceBinding? = null
    private var curlNavigationIntent: CurlNavigationIntent? = null
    private var curlFrontPage = "none"
    private var curlBackPage = "none"
    private var curlUnderPage = "none"
    private var curlDiagnosticsDismissed = false
    private var curlDiagnosticContext = ""
    private val curlDiagnosticsPanel = LinearLayout(activity)
    private val curlForceSwitch = Switch(activity)
    private var forceCurlGesture = false
    private var curlPreparedPosition = -1
    private var curlPreparingPosition = -1
    private var surfaceReadyCurrent = false
    private var surfaceReadyNext = false
    private var surfaceReadySpread = false
    private var curlTouchInActivationZone = false
    private var curlTouchInForwardZone = false
    private var curlTouchInBackwardZone = false
    private var curlCanPanForward = false
    private var curlCanPanBackward = false
    private var curlGesturePending = false
    private var curlGestureClaimed = false
    private var curlTouchDownX = 0f
    private var curlTouchDownY = 0f
    private var curlTouchDownTime = 0L
    private var curlFallbackReason = "not evaluated"
    private var curlScaleState = PagerCurlScaleState(null, null)
    private var curlExternalGestureActive = false
    private var pagerTouchX = 0f
    private var pagerTouchY = 0f
    private var curlLocalX = 0f
    private var curlLocalY = 0f
    private var curlRendererState = PageCurlInteractionPhase.IDLE
    private var curlSurfacePreparationState = "UNAVAILABLE"
    private val pagerWindowLocation = IntArray(2)
    private val curlWindowLocation = IntArray(2)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentPage: Any? = null

    /**
     * Viewer chapters to set when the pager enters idle mode. Otherwise, if the view was settling
     * or dragging, there'd be a noticeable and annoying jump.
     */
    private var awaitingIdleViewerChapters: ViewerChapters? = null

    /**
     * Whether the view pager is currently in idle mode. It sets the awaiting chapters if setting
     * this field to true.
     */
    private var isIdle = true
        set(value) {
            field = value
            if (value) {
                awaitingIdleViewerChapters?.let { viewerChapters ->
                    setChaptersDoubleShift(viewerChapters)
                    awaitingIdleViewerChapters = null
                    if (viewerChapters.currChapter.pages?.size == 1) {
                        adapter.nextTransition?.to?.let {
                            activity.requestPreloadChapter(it)
                        }
                    }
                }
            }
        }

    var hasMoved = false

    /**
     * Variable used to hold the forward pos for reader activity shared transitions
     * Without this var landscapezoom wont work with activity transitions
     * */
    var heldForwardZoom: Pair<Int, Boolean>? = null

    /**
     * Last known left analog stick position, used by [startJoystickPanLoop] to keep panning
     * for as long as it's held away from center.
     */
    private var joystickX = 0f
    private var joystickY = 0f

    /**
     * Job for the loop that keeps panning while the joystick is held, see [startJoystickPanLoop].
     */
    private var joystickPanJob: Job? = null

    /**
     * Last known combined zoom rate from the L2/R2 triggers and the right stick's Y axis, in
     * [-1, 1] (negative zooms out, positive zooms in). Applied by [zoomLoop].
     */
    private var zoomRate = 0f

    /**
     * Keeps zooming for as long as [zoomRate] is non-zero, e.g. while a trigger is held.
     */
    private val zoomLoop = GamepadHoldLoop(scope, activity, ZOOM_HOLD_INTERVAL_MS, { zoomRate }, ::zoomBy)

    /**
     * Which dpad directions are currently held, used by [startDpadPanLoop] to combine
     * simultaneously-held directions into a single diagonal pan.
     */
    private var isDpadUpHeld = false
    private var isDpadDownHeld = false
    private var isDpadLeftHeld = false
    private var isDpadRightHeld = false

    private var dpadPanDebounceJob: Job? = null

    private var pagerListener =
        object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                if (pager.isRestoring) return
                val page = adapter.joinedItems.getOrNull(position)
                if (!activity.isScrollingThroughPagesOrChapters && page?.first !is ChapterTransition) {
                    activity.hideMenu()
                }
                onPageChange(position)
            }

            override fun onPageScrollStateChanged(state: Int) {
                isIdle = state == ViewPager.SCROLL_STATE_IDLE
                if (!hasMoved) {
                    hasMoved = !isIdle
                }
            }
        }

    init {
        pager.isVisible = false // Don't lay out the pager yet
        pager.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pager.offscreenPageLimit = 1
        pager.id = R.id.reader_pager
        pager.adapter = adapter
        pager.addOnPageChangeListener(pagerListener)
        pager.tapListener = f@{ event ->
            val pos = PointF(event.x / pager.width, event.y / pager.height)
            val navigator = config.navigator
            when (navigator.getAction(pos)) {
                ViewerNavigation.NavigationRegion.MENU -> activity.toggleMenu()
                ViewerNavigation.NavigationRegion.NEXT -> moveToNext()
                ViewerNavigation.NavigationRegion.PREV -> moveToPrevious()
                ViewerNavigation.NavigationRegion.RIGHT -> moveRight()
                ViewerNavigation.NavigationRegion.LEFT -> moveLeft()
            }
        }
        pager.longTapListener = f@{
            if (activity.menuVisible || config.longTapEnabled) {
                val item = adapter.joinedItems.getOrNull(pager.currentItem)
                val firstPage = item?.first as? ReaderPage
                val secondPage = item?.second as? ReaderPage
                if (firstPage is ReaderPage) {
                    activity.onPageLongTap(firstPage, secondPage)
                    return@f true
                }
            }
            false
        }

        viewerContainer.addView(
            pager,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        viewerContainer.addView(
            curlView,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        curlView.visibility = View.INVISIBLE
        configureCurlDiagnostics()
        curlView.onCurlCompleted = { completeCurlTransition() }
        curlView.onCurlCancelled = { hideCurlSurface() }
        pager.pageTransitionTouchHandler = ::dispatchCurlTouch

        config.imagePropertyChangedListener = {
            activity.isScrollingThroughPagesOrChapters = true
            refreshAdapter()
            activity.isScrollingThroughPagesOrChapters = false
        }

        config.reloadChapterListener = {
            activity.reloadChapters(it)
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayForNewUser
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }
        config.navigationModeInvertedListener = { activity.binding.navigationOverlay.showNavigationAgain() }
        config.pageTransitionChangedListener = {
            curlDiagnosticsDismissed = false
            prepareCurlSurfaces(force = true)
        }
    }

    /**
     * Creates a new ViewPager.
     */
    abstract fun createPager(): Pager

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View = viewerContainer

    override fun destroy() {
        super.destroy()
        curlPrepareJob?.cancel()
        pager.pageTransitionTouchHandler = null
        hideCurlSurface()
        curlSurfaceProvider.close()
        scope.cancel()
    }

    /**
     * Returns the PagerPageHolder for the provided page
     */
    private fun getPageHolder(page: ReaderPage): PagerPageHolder? =
        pager.children
            .filterIsInstance(PagerPageHolder::class.java)
            .firstOrNull { it.item.first.index == page.index || it.item.second?.index == page.index }

    /**
     * Returns the [PagerPageHolder] of the page that's currently on screen, if any.
     */
    private fun currentPageHolder(): PagerPageHolder? = (currentPage as? ReaderPage)?.let { getPageHolder(it) }

    override fun isZoomedIn(): Boolean = currentPageHolder()?.isZoomedIn() ?: false

    override fun isAtEndOfReader(): Boolean = (currentPage as? ChapterTransition.Next)?.let { it.to == null } ?: false

    override fun zoomIn() {
        currentPageHolder()?.zoomIn()
    }

    override fun zoomOut() {
        currentPageHolder()?.zoomOut()
    }

    override fun pan(
        dxRatio: Float,
        dyRatio: Float,
    ) {
        currentPageHolder()?.panBy(dxRatio, dyRatio)
    }

    override fun zoomBy(rate: Float) {
        currentPageHolder()?.zoomBy(rate)
    }

    /**
     * Called when a new page (either a [ReaderPage] or [ChapterTransition]) is marked as active
     */
    fun onPageChange(position: Int) {
        val page = adapter.joinedItems.getOrNull(position)
        if (page != null && currentPage != page) {
            val pageF = page.first
            val allowPreload = checkAllowPreload(pageF as? ReaderPage)
            val forward =
                // if both pages have the same number, it's a split page with an InsertPage
                when {
                    // Use case happens on new chapter load
                    currentPage == pageF -> null
                    currentPage is ReaderPage && pageF is ReaderPage ->
                        if (pageF.number == (currentPage as ReaderPage).number) {
                            // the InsertPage is always the second in the reading direction
                            pageF is InsertPage
                        } else {
                            pageF.number > (currentPage as ReaderPage).number
                        }
                    currentPage is ChapterTransition.Prev && pageF is ReaderPage ->
                        (currentPage as ChapterTransition).from == pageF.chapter
                    currentPage is ChapterTransition.Next && pageF is ReaderPage ->
                        (currentPage as ChapterTransition).to == pageF.chapter
                    else -> true
                }
            currentPage = pageF
            when (pageF) {
                is ReaderPage -> {
                    onReaderPageSelected(pageF, allowPreload, page.second is ReaderPage, forward)
                }
                is ChapterTransition -> onTransitionSelected(pageF)
            }
        }
        prepareCurlSurfaces(position)
    }

    /** Retry after the real holder has obtained/decoded a previously unavailable stream. */
    internal fun onCurlPageReady() {
        prepareCurlSurfaces()
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        // Allow preload for
        // 1. Going to next chapter from chapter transition
        // 2. Going between pages of same chapter
        // 3. Next chapter page
        return when (page.chapter) {
            (currentPage as? ChapterTransition.Next)?.to -> true
            (currentPage as? ReaderPage)?.chapter -> true
            adapter.nextTransition?.to -> true
            else -> false
        }
    }

    /**
     * Called when a [ReaderPage] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onReaderPageSelected(
        page: ReaderPage,
        allowPreload: Boolean,
        hasExtraPage: Boolean,
        forward: Boolean?,
    ) {
        activity.onPageSelected(page, hasExtraPage)

        // Notify holder of page change
        val holder = getPageHolder(page)
        if (holder == null && forward != null && heldForwardZoom == null) {
            heldForwardZoom = page.index to forward
        } else {
            holder?.onPageSelected(forward)
        }
        val offset = if (hasExtraPage) 1 else 0
        val pages = page.chapter.pages ?: return
        if (hasExtraPage) {
            Timber.d("onReaderPageSelected: ${page.number}-${page.number + offset}/${pages.size}")
        } else {
            Timber.d("onReaderPageSelected: ${page.number}/${pages.size}")
        }
        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            Timber.d("Request preload next chapter because we're at page ${page.number} of ${pages.size}")
            adapter.nextTransition?.to?.let {
                activity.requestPreloadChapter(it)
            }
        }
    }

    /**
     * Called when a [ChapterTransition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        Timber.d("onTransitionSelected: $transition")
        val toChapter = transition.to
        if (toChapter != null) {
            Timber.d("Request preload destination chapter because we're on the transition")
            activity.requestPreloadChapter(toChapter)
        } else if (transition is ChapterTransition.Next) {
            // No more chapters, show menu because the user is probably going to close the reader
            activity.showMenu()
        }
    }

    private fun getItem(
        position: Int,
        currentChapter: ReaderChapter?,
    ): Pair<Any, Any?>? {
        return adapter.joinedItems.firstOrNull {
            val readerPage = it.first as? ReaderPage ?: return@firstOrNull false
            readerPage.index == position && readerPage.chapter.chapter.id == currentChapter?.chapter?.id
        }
    }

    fun hasExtraPage(
        position: Int,
        currentChapter: ReaderChapter?,
    ): Boolean {
        val item = getItem(position, currentChapter) ?: return false
        return item.second is ReaderPage
    }

    fun setChaptersDoubleShift(chapters: ViewerChapters) {
        // Remove Listener since we're about to change the size of the items
        // If we don't the size change could put us on a new chapter
        pager.removeOnPageChangeListener(pagerListener)
        setChaptersInternal(chapters)
        if (!hasMoved) {
            activity.isScrollingThroughPagesOrChapters = true
            chapters.currChapter.pages?.let { pages ->
                moveToPage(pages[chapters.currChapter.requestedPage], false)
            }
            activity.isScrollingThroughPagesOrChapters = false
        }
        pager.addOnPageChangeListener(pagerListener)
        // Since we removed the listener while shifting, call page change to update the ui
        onPageChange(pager.currentItem)
    }

    fun updateShifting(page: ReaderPage? = null) {
        adapter.pageToShift = page ?: adapter.joinedItems[pager.currentItem].first as? ReaderPage
    }

    fun getShiftedPage(): ReaderPage? = adapter.pageToShift

    /**
     * Tells this viewer to set the given [chapters] as active. If the pager is currently idle,
     * it sets the chapters immediately, otherwise they are saved and set when it becomes idle.
     */
    override fun setChapters(chapters: ViewerChapters) {
        if (isIdle) {
            setChaptersDoubleShift(chapters)
        } else {
            awaitingIdleViewerChapters = chapters
        }
    }

    /**
     * Sets the active [chapters] on this pager.
     */
    private fun setChaptersInternal(chapters: ViewerChapters) {
        Timber.d("setChaptersInternal")
        val forceTransition =
            config.alwaysShowChapterTransition ||
                adapter.joinedItems
                    .getOrNull(
                        pager
                            .currentItem,
                    )?.first is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        // Layout the pager once a chapter is being set
        if (pager.isGone) {
            Timber.d("Pager first layout")
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[chapters.currChapter.requestedPage])
            pager.isVisible = true
        }
        activity.invalidateOptionsMenu()
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(
        page: ReaderPage,
        animated: Boolean,
    ) {
        Timber.d("moveToPage ${page.number}")
        val position =
            adapter.joinedItems.indexOfFirst {
                it.first == page ||
                    it.second == page ||
                    (
                        config.splitPages &&
                            it.first is ReaderPage &&
                            (it.first as? ReaderPage)?.isFromSamePage(page) == true &&
                            (it.first as? ReaderPage)?.firstHalf != false
                    )
            }
        if (position != -1) {
            val currentPosition = pager.currentItem
            pager.setCurrentItem(position, animated)
            // manually call onPageChange since ViewPager listener is not triggered in this case
            if (currentPosition == position) {
                onPageChange(position)
            } else {
                // Call this since with double shift onPageChange wont get called (it shouldn't)
                // Instead just update the page count in ui
                val joinedItem = adapter.joinedItems.firstOrNull { it.first == page || it.second == page }
                activity.onPageSelected(
                    joinedItem?.first as? ReaderPage ?: page,
                    joinedItem?.second is ReaderPage,
                )
            }
        } else {
            Timber.d("Page $page not found in adapter")
        }
    }

    override fun moveToNext() {
        moveRight()
    }

    override fun moveToPrevious() {
        moveLeft()
    }

    /**
     * Moves to the page at the right.
     */
    protected open fun moveRight() {
        if (pager.currentItem != adapter.count - 1) {
            hasMoved = true
            val holder = (currentPage as? ReaderPage)?.let { getPageHolder(it) }
            if (holder != null && config.navigateToPan && holder.canPanRight()) {
                holder.panRight()
            } else {
                pager.setCurrentItem(pager.currentItem + 1, config.pageTransition.useViewPagerAnimation)
            }
        }
    }

    /**
     * Moves to the page at the left.
     */
    protected open fun moveLeft() {
        if (pager.currentItem != 0) {
            hasMoved = true
            val holder = (currentPage as? ReaderPage)?.let { getPageHolder(it) }
            if (holder != null && config.navigateToPan && holder.canPanLeft()) {
                holder.panLeft()
            } else {
                pager.setCurrentItem(pager.currentItem - 1, config.pageTransition.useViewPagerAnimation)
            }
        }
    }

    /**
     * Moves to the page at the top (or previous).
     */
    protected open fun moveUp() {
        moveToPrevious()
    }

    /**
     * Moves to the page at the bottom (or next).
     */
    protected open fun moveDown() {
        moveToNext()
    }

    /**
     * Resets the adapter in order to recreate all the views. Used when a image configuration is
     * changed.
     */
    private fun refreshAdapter() {
        val currentItem = pager.currentItem
        pager.adapter = adapter
        pager.setCurrentItem(currentItem, false)
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        // Page-turning/scrolling should only fire once per physical press - repeatCount == 0
        // excludes the auto-repeated ACTION_DOWN events a held key/button generates, matching
        // the old ACTION_UP-based firing (which has no repeat concept, only a single release).
        // Zoom/pan is allowed to repeat while held instead, since continuously zooming/panning
        // is the point, so those branches key off [isDown] alone.
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isInitialDown = isDown && event.repeatCount == 0

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.menuVisible) {
                    return false
                } else if (isInitialDown) {
                    if (!config.volumeKeysInverted) moveDown() else moveUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.menuVisible) {
                    return false
                } else if (isInitialDown) {
                    if (!config.volumeKeysInverted) moveUp() else moveDown()
                }
            }
            // While the menu is open, let the dpad/arrow keys drive normal Android focus
            // navigation between the menu's buttons instead of turning/panning pages. While
            // zoomed in, holding a direction pans via startDpadPanLoop rather than firing pan()
            // directly here, so simultaneously held directions combine into a diagonal pan
            // instead of relying on (unreliable, single-key) OS key-repeat for each axis.
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (activity.menuVisible) return false
                if (!isDown) {
                    isDpadRightHeld = false
                } else if (isZoomedIn()) {
                    isDpadRightHeld = true
                    if (isInitialDown && this !is VerticalPagerViewer && currentPageHolder()?.canPanRight() == false) {
                        moveRight()
                    } else {
                        startDpadPanLoop(event.keyCode, event.repeatCount > 0)
                    }
                } else if (isInitialDown) {
                    moveRight()
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (activity.menuVisible) return false
                if (!isDown) {
                    isDpadLeftHeld = false
                } else if (isZoomedIn()) {
                    isDpadLeftHeld = true
                    if (isInitialDown && this !is VerticalPagerViewer && currentPageHolder()?.canPanLeft() == false) {
                        moveLeft()
                    } else {
                        startDpadPanLoop(event.keyCode, event.repeatCount > 0)
                    }
                } else if (isInitialDown) {
                    moveLeft()
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (activity.menuVisible) return false
                if (!isDown) {
                    isDpadDownHeld = false
                } else if (isZoomedIn()) {
                    isDpadDownHeld = true
                    if (isInitialDown && this is VerticalPagerViewer && currentPageHolder()?.canPanDown() == false) {
                        moveDown()
                    } else {
                        startDpadPanLoop(event.keyCode, event.repeatCount > 0)
                    }
                } else if (isInitialDown) {
                    moveDown()
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (activity.menuVisible) return false
                if (!isDown) {
                    isDpadUpHeld = false
                } else if (isZoomedIn()) {
                    isDpadUpHeld = true
                    if (isInitialDown && this is VerticalPagerViewer && currentPageHolder()?.canPanUp() == false) {
                        moveUp()
                    } else {
                        startDpadPanLoop(event.keyCode, event.repeatCount > 0)
                    }
                } else if (isInitialDown) {
                    moveUp()
                }
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> if (isInitialDown) moveDown()
            KeyEvent.KEYCODE_PAGE_UP -> if (isInitialDown) moveUp()

            // Gamepad shoulder buttons seek pages left/right, matching the dpad's spatial mapping.
            KeyEvent.KEYCODE_BUTTON_L1 ->
                if (isInitialDown) pager.setCurrentItem(pager.currentItem - 1, config.pageTransition.useViewPagerAnimation)
            KeyEvent.KEYCODE_BUTTON_R1 ->
                if (isInitialDown) pager.setCurrentItem(pager.currentItem + 1, config.pageTransition.useViewPagerAnimation)

            // Gamepad X/Y zoom the current page in/out, repeating while held.
            KeyEvent.KEYCODE_BUTTON_Y -> if (isDown) zoomIn()
            KeyEvent.KEYCODE_BUTTON_X -> if (isDown) zoomOut()
            else -> return false
        }
        return true
    }

    fun splitDoublePages(currentPage: ReaderPage) {
        adapter.splitDoublePages(currentPage)
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            when (event.action) {
                MotionEvent.ACTION_SCROLL -> {
                    if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0.0f) {
                        moveDown()
                    } else {
                        moveUp()
                    }
                    return true
                }
            }
        }
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            if (event.isDpadHatMotion()) return false

            joystickX = event.getAxisValue(MotionEvent.AXIS_X)
            joystickY = event.getAxisValue(MotionEvent.AXIS_Y)
            val deflected = abs(joystickX) > JOYSTICK_DEADZONE || abs(joystickY) > JOYSTICK_DEADZONE
            if (deflected && !activity.menuVisible && isZoomedIn()) {
                startJoystickPanLoop()
            } else {
                joystickPanJob?.cancel()
            }

            zoomRate = event.gamepadZoomRate()
            zoomLoop.update()

            return (deflected && !activity.menuVisible && isZoomedIn()) || zoomRate != 0f
        }
        return false
    }

    /**
     * The joystick only sends a [MotionEvent] when its axes change, but panning should continue
     * for as long as the stick is held away from center. This starts a loop that keeps panning
     * using the last known [joystickX]/[joystickY] values until it's released, re-centered, the
     * menu opens, or the page is no longer zoomed in.
     */
    private fun startJoystickPanLoop() {
        if (joystickPanJob?.isActive == true) return
        joystickPanJob =
            scope.launch {
                while (isActive) {
                    val x = joystickX
                    val y = joystickY
                    if (activity.menuVisible ||
                        !isZoomedIn() ||
                        (abs(x) <= JOYSTICK_DEADZONE && abs(y) <= JOYSTICK_DEADZONE)
                    ) {
                        break
                    }
                    pan(x * JOYSTICK_PAN_STEP, y * JOYSTICK_PAN_STEP)
                    delay(JOYSTICK_PAN_INTERVAL_MS.milliseconds)
                }
            }
    }

    /**
     * Starts/continues panning for a held dpad key, combining every currently-held direction
     * into a single diagonal [pan] call rather than one call per axis - two separate calls for
     * the same tick would each read the page's center before the other's animation had visually
     * applied, fighting each other instead of composing.
     *
     * A repeat (native OS key-repeat, once it kicks in) always pans immediately from the current
     * held state. A key's very *first* press only does that immediately if some other direction
     * is already held - i.e. it's joining an existing hold, so it should contribute to the
     * diagonal right away. Otherwise, it's presumed to be a clean, isolated press, and waits for
     * the next UI frame instead of panning immediately: two real key presses meant as one
     * diagonal input are rarely perfectly simultaneous, so this gives the second one a chance to
     * also register and be included, rather than firing a single-axis pan for the first key
     * alone before the second key's event has even arrived. A frame (rather than a guessed
     * delay) is used since that's however long the *next* dispatch actually takes to reach us,
     * with no risk of firing before it or waiting longer than necessary.
     */
    private fun startDpadPanLoop(
        keyCode: Int,
        isRepeating: Boolean,
    ) {
        if (activity.menuVisible || !isZoomedIn()) return
        val otherDirectionHeld =
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> isDpadLeftHeld || isDpadUpHeld || isDpadDownHeld
                KeyEvent.KEYCODE_DPAD_LEFT -> isDpadRightHeld || isDpadUpHeld || isDpadDownHeld
                KeyEvent.KEYCODE_DPAD_UP -> isDpadDownHeld || isDpadLeftHeld || isDpadRightHeld
                KeyEvent.KEYCODE_DPAD_DOWN -> isDpadUpHeld || isDpadLeftHeld || isDpadRightHeld
                else -> false
            }
        if (isRepeating || otherDirectionHeld) {
            dpadPanDebounceJob?.cancel()
            panFromHeldDpadDirections()
        } else {
            // Cancels itself instead of a stray leftover firing later, e.g. a repeat coming in
            // and panning immediately (above) before this frame arrives.
            dpadPanDebounceJob =
                scope.launch {
                    awaitFrame()
                    panFromHeldDpadDirections()
                }
        }
    }

    private fun panFromHeldDpadDirections() {
        if (activity.menuVisible || !isZoomedIn()) return
        val dx = (if (isDpadRightHeld) PAN_STEP else 0f) - (if (isDpadLeftHeld) PAN_STEP else 0f)
        val dy = (if (isDpadDownHeld) PAN_STEP else 0f) - (if (isDpadUpHeld) PAN_STEP else 0f)
        if (dx == 0f && dy == 0f) return
        pan(dx, dy)
    }

    private fun prepareCurlSurfaces(
        position: Int = pager.currentItem,
        force: Boolean = false,
    ) {
        if (!force && curlPreparedPosition == position && curlSurfaceReady) {
            updateCurlDiagnostics("surfaces retained")
            return
        }
        if (!force && curlPreparingPosition == position && curlPrepareJob?.isActive == true) return
        curlPrepareJob?.cancel()
        curlPreparingPosition = position
        curlPreparedPosition = -1
        curlSurfaceReady = false
        curlTargetPosition = -1
        forwardCurlBinding = null
        backwardCurlBinding = null
        curlNavigationIntent = null
        curlFrontPage = "none"
        curlBackPage = "none"
        curlUnderPage = "none"
        surfaceReadyCurrent = false
        surfaceReadyNext = false
        surfaceReadySpread = false
        curlSurfacePreparationState = "PREPARING"
        hideCurlSurface()
        if (config.pageTransition != PageTransition.PAGE_CURL || this is VerticalPagerViewer) {
            curlPreparingPosition = -1
            curlSurfacePreparationState = "INACTIVE"
            updateCurlDiagnostics("inactive")
            return
        }
        val currentItem = adapter.joinedItems.getOrNull(position)
        curlDiagnosticContext = "current=$position ${if (this is R2LPagerViewer) "RTL" else "LTR"}"
        if (currentItem?.first !is ReaderPage) {
            curlPreparingPosition = -1
            curlSurfacePreparationState = "UNAVAILABLE"
            curlFallback("current item is a transition")
            return
        }
        val forwardStep = if (this is R2LPagerViewer) -1 else 1
        curlPrepareJob =
            scope.launch {
                val activePages = linkedSetOf<ReaderPage>()
                forwardCurlBinding =
                    createCurlBinding(position, position + forwardStep, CurlNavigationIntent.FORWARD, activePages)
                backwardCurlBinding =
                    createCurlBinding(position, position - forwardStep, CurlNavigationIntent.BACKWARD, activePages)
                if (pager.currentItem != position) return@launch
                curlSurfaceProvider.trimTo(activePages)
                curlSurfaceReady = forwardCurlBinding != null || backwardCurlBinding != null
                curlPreparedPosition = position
                curlPreparingPosition = -1
                surfaceReadyCurrent = curlSurfaceReady
                surfaceReadyNext = curlSurfaceReady
                surfaceReadySpread = listOfNotNull(forwardCurlBinding, backwardCurlBinding).any { it.isSpread }
                curlFallbackReason = if (curlSurfaceReady) "none" else "no safe adjacent mapping"
                curlSurfacePreparationState = if (curlSurfaceReady) "READY" else "UNAVAILABLE"
                val targetPosition = forwardCurlBinding?.targetPosition ?: backwardCurlBinding?.targetPosition ?: -1
                updateCurlDiagnostics("ready → item $targetPosition")
            }
    }

    private suspend fun createCurlBinding(
        position: Int,
        targetPosition: Int,
        intent: CurlNavigationIntent,
        activePages: MutableSet<ReaderPage>,
    ): CurlSurfaceBinding? {
        val currentItem = adapter.joinedItems.getOrNull(position) ?: return null
        val targetItem = adapter.joinedItems.getOrNull(targetPosition) ?: return null
        val currentFirst = currentItem.first as? ReaderPage ?: return null
        val targetFirst = targetItem.first as? ReaderPage ?: return null
        val currentSecond = currentItem.second as? ReaderPage
        val targetSecond = targetItem.second as? ReaderPage
        if ((currentSecond == null) != (targetSecond == null)) return null
        val forwardDirection =
            if (this is R2LPagerViewer) PageCurlDirection.RIGHT_TO_LEFT else PageCurlDirection.LEFT_TO_RIGHT
        val direction =
            if (intent == CurlNavigationIntent.FORWARD) forwardDirection else forwardDirection.opposite()

        if (currentSecond != null && targetSecond != null) {
            val current = spreadRoles(currentFirst, currentSecond)
            val target = spreadRoles(targetFirst, targetSecond)
            val fixedCurrentPage: ReaderPage
            val turningCurrentPage: ReaderPage
            val fixedTargetPage: ReaderPage
            val incomingTargetPage: ReaderPage
            if (intent == CurlNavigationIntent.FORWARD) {
                // LTR: fixed current | turning current -> fixed target (sheet back) | incoming target.
                // RTL uses the same roles with the renderer direction mirrored.
                fixedCurrentPage = current.fixed
                turningCurrentPage = current.turning
                fixedTargetPage = target.fixed
                incomingTargetPage = target.turning
            } else {
                // Reverse the same physical sheet: current fixed becomes the turning front, while
                // the target turning page is its back and the target fixed page is revealed below.
                fixedCurrentPage = current.turning
                turningCurrentPage = current.fixed
                fixedTargetPage = target.turning
                incomingTargetPage = target.fixed
            }
            val fixedCurrent = curlSurfaceProvider.load(fixedCurrentPage) ?: return null
            val turningCurrent = curlSurfaceProvider.load(turningCurrentPage) ?: return null
            val fixedTarget = curlSurfaceProvider.load(fixedTargetPage) ?: return null
            val incomingTarget = curlSurfaceProvider.load(incomingTargetPage) ?: return null
            activePages += listOf(currentFirst, currentSecond, targetFirst, targetSecond)
            return CurlSurfaceBinding(
                targetPosition,
                direction,
                fixedCurrent,
                turningCurrent,
                fixedTarget,
                incomingTarget,
                pageLabel(turningCurrentPage),
                pageLabel(fixedTargetPage),
                pageLabel(incomingTargetPage),
            )
        }

        val front = curlSurfaceProvider.load(currentFirst) ?: return null
        val back = curlSurfaceProvider.load(targetFirst) ?: return null
        activePages += listOf(currentFirst, targetFirst)
        val step = targetPosition - position
        val followingItem = adapter.joinedItems.getOrNull(targetPosition + step)
        val followingPage = (followingItem?.first as? ReaderPage)?.takeIf { followingItem.second == null }
        val underlying = followingPage?.let { curlSurfaceProvider.load(it) } ?: back
        followingPage?.let(activePages::add)
        return CurlSurfaceBinding(
            targetPosition,
            direction,
            null,
            front,
            back,
            underlying,
            pageLabel(currentFirst),
            pageLabel(targetFirst),
            followingPage?.let(::pageLabel) ?: "fallback:${pageLabel(targetFirst)}",
        )
    }

    private fun spreadRoles(
        first: ReaderPage,
        second: ReaderPage,
    ): CurlSpreadRoles = if (config.invertDoublePages) CurlSpreadRoles(second, first) else CurlSpreadRoles(first, second)

    private fun bindCurlSurface(binding: CurlSurfaceBinding) {
        curlTargetPosition = binding.targetPosition
        curlNavigationIntent =
            if (binding === forwardCurlBinding) CurlNavigationIntent.FORWARD else CurlNavigationIntent.BACKWARD
        curlFrontPage = binding.frontLabel
        curlBackPage = binding.backLabel
        curlUnderPage = binding.underLabel
        curlView.setDirection(binding.direction)
        if (binding.isSpread) {
            curlView.setSpreadPages(binding.fixedCurrent, binding.front, binding.back, binding.underlying)
        } else {
            curlView.setPages(binding.front, binding.back, binding.underlying)
        }
    }

    private fun pageLabel(page: ReaderPage): String = "${page.number}(index=${page.index})"

    private fun unavailable(role: String) {
        curlPreparingPosition = -1
        curlSurfacePreparationState = "UNAVAILABLE"
        curlFallback("$role unavailable")
    }

    private fun curlFallback(reason: String) {
        curlFallbackReason = reason
        Timber.d("Page curl using pager fallback: $reason ($curlDiagnosticContext)")
        updateCurlDiagnostics("slide fallback: $reason")
    }

    private fun dispatchCurlTouch(event: MotionEvent): PageTransitionTouchResult =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> evaluateCurlDown(event)
            MotionEvent.ACTION_MOVE -> evaluateCurlMove(event)
            MotionEvent.ACTION_UP -> finishCurlTouch(event, cancelled = false)
            MotionEvent.ACTION_CANCEL -> finishCurlTouch(event, cancelled = true)
            else -> if (curlGestureClaimed) PageTransitionTouchResult.CLAIMED else PageTransitionTouchResult.PENDING
        }

    private fun evaluateCurlDown(event: MotionEvent): PageTransitionTouchResult {
        curlGesturePending = false
        curlGestureClaimed = false
        curlNavigationIntent = null
        curlTargetPosition = -1
        curlTouchDownX = event.x
        curlTouchDownY = event.y
        curlTouchDownTime = event.downTime
        updateCurlLocalCoordinates(event.x, event.y)
        curlExternalGestureActive = false
        val isHorizontalViewer = this !is VerticalPagerViewer
        val isSpread = adapter.joinedItems.getOrNull(pager.currentItem)?.second is ReaderPage
        val activationFraction = if (isSpread) SPREAD_ACTIVATION_FRACTION else SINGLE_ACTIVATION_FRACTION
        curlView.activationZoneFraction = if (forceCurlGesture) 1f else activationFraction
        curlTouchInForwardZone =
            forceCurlGesture ||
            if (this is R2LPagerViewer) {
                event.x <= pager.width * activationFraction
            } else {
                event.x >= pager.width * (1f - activationFraction)
            }
        curlTouchInBackwardZone =
            forceCurlGesture ||
            if (this is R2LPagerViewer) {
                event.x >= pager.width * (1f - activationFraction)
            } else {
                event.x <= pager.width * activationFraction
            }
        curlTouchInActivationZone =
            (forwardCurlBinding != null && curlTouchInForwardZone) ||
            (backwardCurlBinding != null && curlTouchInBackwardZone)
        val holder = currentPageHolder()
        curlScaleState = holder?.curlScaleState() ?: PagerCurlScaleState(null, null)
        val rawCanPanForward =
            if (this is R2LPagerViewer) holder?.canPanLeft() == true else holder?.canPanRight() == true
        val rawCanPanBackward =
            if (this is R2LPagerViewer) holder?.canPanRight() == true else holder?.canPanLeft() == true
        curlCanPanForward = curlScaleState.isClearlyZoomed && rawCanPanForward
        curlCanPanBackward = curlScaleState.isClearlyZoomed && rawCanPanBackward
        curlFallbackReason =
            when {
                config.pageTransition != PageTransition.PAGE_CURL -> "transition mode is ${config.pageTransition}"
                !isHorizontalViewer -> "viewer is vertical"
                !curlSurfaceReady -> "surface binding not ready"
                !curlTouchInActivationZone -> "touch outside activation zone"
                else -> "none"
            }
        if (curlFallbackReason != "none") {
            Timber.d("Page curl ACTION_DOWN rejected: $curlFallbackReason (${diagnosticValues()})")
            updateCurlDiagnostics("ACTION_DOWN rejected")
            return PageTransitionTouchResult.REJECTED
        }
        curlGesturePending = true
        Timber.d("Page curl ACTION_DOWN pending (${diagnosticValues()})")
        updateCurlDiagnostics("TOUCH_PENDING")
        return PageTransitionTouchResult.PENDING
    }

    private fun evaluateCurlMove(event: MotionEvent): PageTransitionTouchResult {
        if (curlGestureClaimed) {
            updateCurlLocalCoordinates(event.x, event.y)
            if (curlExternalGestureActive) {
                curlView.updateExternalGesture(curlLocalX, curlLocalY, event.eventTime)
            }
            updateCurlDiagnostics(curlRendererState.name)
            return PageTransitionTouchResult.CLAIMED
        }
        if (!curlGesturePending) return PageTransitionTouchResult.REJECTED
        val dx = event.x - curlTouchDownX
        val dy = event.y - curlTouchDownY
        val slop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
        if (abs(dx) < slop && abs(dy) < slop) return PageTransitionTouchResult.PENDING
        val forwardDistance = if (this is R2LPagerViewer) dx else -dx
        if (abs(dx) <= abs(dy)) {
            curlGesturePending = false
            curlFallbackReason = "movement is vertical"
            Timber.d("Page curl gesture released to pager: $curlFallbackReason (${diagnosticValues()})")
            updateCurlDiagnostics("gesture rejected")
            return PageTransitionTouchResult.REJECTED
        }
        val intent = if (forwardDistance > slop) CurlNavigationIntent.FORWARD else CurlNavigationIntent.BACKWARD
        val binding = if (intent == CurlNavigationIntent.FORWARD) forwardCurlBinding else backwardCurlBinding
        val inIntentZone =
            if (intent == CurlNavigationIntent.FORWARD) curlTouchInForwardZone else curlTouchInBackwardZone
        val canPan = if (intent == CurlNavigationIntent.FORWARD) curlCanPanForward else curlCanPanBackward
        if (binding == null || !inIntentZone || canPan) {
            curlGesturePending = false
            curlNavigationIntent = intent
            curlFallbackReason =
                when {
                    binding == null -> "${intent.name.lowercase()} surface mapping unavailable"
                    !inIntentZone -> "touch outside ${intent.name.lowercase()} activation zone"
                    else -> "zoomed page can still pan ${intent.name.lowercase()}"
                }
            Timber.d("Page curl gesture released to pager: $curlFallbackReason (${diagnosticValues()})")
            updateCurlDiagnostics("gesture rejected")
            return PageTransitionTouchResult.REJECTED
        }

        // Ownership is committed before renderer initialization. From here onward this sequence
        // can never be returned to ViewPager, even if the renderer unexpectedly cannot start.
        curlGesturePending = false
        curlGestureClaimed = true
        bindCurlSurface(binding)
        curlView.visibility = View.VISIBLE
        updateCurlLocalCoordinates(curlTouchDownX, curlTouchDownY)
        curlExternalGestureActive =
            curlView.beginExternalGesture(curlLocalX, curlLocalY, curlTouchDownTime)
        if (!curlExternalGestureActive) {
            hideCurlSurface()
            curlFallbackReason =
                "external renderer initialization failed " +
                "(curlSize=${curlView.width}x${curlView.height})"
            Timber.d("Page curl claimed but renderer did not initialize (${diagnosticValues()})")
            updateCurlDiagnostics("claimed; renderer unavailable")
            return PageTransitionTouchResult.CLAIMED
        }
        updateCurlLocalCoordinates(event.x, event.y)
        curlView.updateExternalGesture(curlLocalX, curlLocalY, event.eventTime)
        curlFallbackReason = "none"
        Timber.d("Page curl gesture claimed (${diagnosticValues()})")
        updateCurlDiagnostics("gesture claimed")
        return PageTransitionTouchResult.CLAIMED
    }

    private fun finishCurlTouch(
        event: MotionEvent,
        cancelled: Boolean,
    ): PageTransitionTouchResult {
        if (!curlGestureClaimed) {
            curlGesturePending = false
            curlFallbackReason = if (cancelled) "pending gesture cancelled" else "released before forward slop"
            updateCurlDiagnostics("gesture not claimed")
            return PageTransitionTouchResult.REJECTED
        }
        updateCurlLocalCoordinates(event.x, event.y)
        if (curlExternalGestureActive) {
            if (cancelled) {
                curlView.cancelExternalGesture()
            } else {
                curlView.endExternalGesture(curlLocalX, curlLocalY, event.eventTime)
            }
        }
        curlExternalGestureActive = false
        curlGestureClaimed = false
        curlGesturePending = false
        updateCurlDiagnostics(if (cancelled) "curl cancelled" else "curl settling")
        return PageTransitionTouchResult.CLAIMED
    }

    private fun completeCurlTransition() {
        val target = curlTargetPosition
        if (!curlSurfaceReady || target !in 0 until adapter.count) {
            hideCurlSurface()
            return
        }
        hasMoved = true
        curlSurfaceReady = false
        hideCurlSurface()
        curlView.reset()
        pager.setCurrentItem(target, false)
        updateCurlDiagnostics("completed → item $target")
    }

    private fun hideCurlSurface() {
        curlView.visibility = View.INVISIBLE
    }

    private fun updateCurlLocalCoordinates(
        x: Float,
        y: Float,
    ) {
        pagerTouchX = x
        pagerTouchY = y
        pager.getLocationInWindow(pagerWindowLocation)
        curlView.getLocationInWindow(curlWindowLocation)
        curlLocalX = x + pagerWindowLocation[0] - curlWindowLocation[0]
        curlLocalY = y + pagerWindowLocation[1] - curlWindowLocation[1]
    }

    private fun configureCurlDiagnostics() {
        val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return
        curlDiagnostics.apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 10f
            setPadding(8, 4, 8, 4)
            setOnClickListener {
                curlDiagnosticsDismissed = true
                curlDiagnosticsPanel.visibility = View.GONE
            }
        }
        curlForceSwitch.apply {
            text = "Force Page Curl gesture"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setOnCheckedChangeListener { _, checked ->
                forceCurlGesture = checked
                curlFallbackReason = "force mode ${if (checked) "enabled" else "disabled"}"
                updateCurlDiagnostics("force mode changed")
            }
        }
        curlDiagnosticsPanel.apply {
            orientation = LinearLayout.VERTICAL
            addView(curlDiagnostics)
            addView(curlForceSwitch)
        }
        viewerContainer.addView(
            curlDiagnosticsPanel,
            FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
            },
        )
        curlView.debugFiniteChecks = true
        curlView.onDebugStateChanged = { state ->
            curlRendererState = state.phase
            updateCurlDiagnostics(state.phase.name)
        }
        updateCurlDiagnostics("initializing")
    }

    private fun updateCurlDiagnostics(message: String) {
        if (curlDiagnosticsPanel.parent == null) return
        if (config.pageTransition != PageTransition.PAGE_CURL) {
            curlDiagnosticsPanel.visibility = View.GONE
            return
        }
        if (!curlDiagnosticsDismissed) curlDiagnosticsPanel.visibility = View.VISIBLE
        curlDiagnostics.text = "PAGE CURL: $message\n${diagnosticValues()}\nTap text to hide"
    }

    private fun diagnosticValues(): String =
        buildString {
            appendLine("transitionMode=${config.pageTransition}")
            appendLine("horizontalViewer=${this@PagerViewer !is VerticalPagerViewer}")
            appendLine("direction=${if (this@PagerViewer is R2LPagerViewer) "RTL" else "LTR"}")
            appendLine("navigationIntent=${curlNavigationIntent ?: "none"}")
            appendLine("currentAdapterPosition=${pager.currentItem}")
            appendLine("targetAdapterPosition=$curlTargetPosition")
            appendLine("forwardTarget=${forwardCurlBinding?.targetPosition ?: "unavailable"}")
            appendLine("backwardTarget=${backwardCurlBinding?.targetPosition ?: "unavailable"}")
            appendLine("frontPage=$curlFrontPage")
            appendLine("backPage=$curlBackPage")
            appendLine("underPage=$curlUnderPage")
            appendLine("surfaceReadyCurrent=$surfaceReadyCurrent")
            appendLine("surfaceReadyNext=$surfaceReadyNext")
            appendLine("surfaceReadySpread=$surfaceReadySpread")
            appendLine("surfaces=$curlSurfacePreparationState")
            appendLine("touchInActivationZone=$curlTouchInActivationZone")
            appendLine("currentScale=${curlScaleState.current}")
            appendLine("minimumScale=${curlScaleState.minimum}")
            appendLine("canPanForward=$curlCanPanForward")
            appendLine("canPanBackward=$curlCanPanBackward")
            appendLine(
                "gestureState=" +
                    when {
                        curlGestureClaimed -> "CLAIMED"
                        curlGesturePending -> "PENDING"
                        else -> "IDLE"
                    },
            )
            appendLine("rendererState=$curlRendererState")
            appendLine("pagerTouchX/Y=$pagerTouchX/$pagerTouchY")
            appendLine("curlLocalX/Y=$curlLocalX/$curlLocalY")
            appendLine("curlWidth/Height=${curlView.width}/${curlView.height}")
            appendLine("activationZoneFraction=${curlView.activationZoneFraction}")
            append("fallbackReason=$curlFallbackReason")
        }

    fun hideMenuIfVisible(item: Any) {
        val currentItem = adapter.joinedItems.getOrNull(pager.currentItem)
        if (item == currentItem && isIdle) {
            activity.hideMenu()
        }
    }
}

private enum class CurlNavigationIntent {
    FORWARD,
    BACKWARD,
}

private data class CurlSpreadRoles(
    val fixed: ReaderPage,
    val turning: ReaderPage,
)

private data class CurlSurfaceBinding(
    val targetPosition: Int,
    val direction: PageCurlDirection,
    val fixedCurrent: Bitmap?,
    val front: Bitmap,
    val back: Bitmap,
    val underlying: Bitmap,
    val frontLabel: String,
    val backLabel: String,
    val underLabel: String,
) {
    val isSpread: Boolean
        get() = fixedCurrent != null
}

private const val SINGLE_ACTIVATION_FRACTION = 0.38f
private const val SPREAD_ACTIVATION_FRACTION = 0.25f

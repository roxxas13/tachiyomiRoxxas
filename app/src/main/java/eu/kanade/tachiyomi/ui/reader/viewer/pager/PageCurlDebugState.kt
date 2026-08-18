package eu.kanade.tachiyomi.ui.reader.viewer.pager

enum class PageCurlInteractionPhase {
    IDLE,
    TOUCH_PENDING,
    DRAGGING,
    SETTLING,
    EXITING,
    COMPLETED,
}

data class PageCurlDebugState(
    val phase: PageCurlInteractionPhase,
    val progress: Float,
    val physicalPullDistance: Float,
    val exitTranslation: Float,
    val touchX: Float,
    val touchY: Float,
)

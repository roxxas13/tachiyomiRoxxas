package eu.kanade.tachiyomi.ui.reader.viewer.pager

enum class PageCurlDirection {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,

    ;

    fun opposite(): PageCurlDirection = if (this == LEFT_TO_RIGHT) RIGHT_TO_LEFT else LEFT_TO_RIGHT
}

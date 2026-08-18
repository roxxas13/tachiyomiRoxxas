package eu.kanade.tachiyomi.ui.reader.settings

import androidx.annotation.StringRes
import eu.kanade.tachiyomi.R

enum class PageTransition(
    @StringRes val stringRes: Int,
) {
    NONE(R.string.none),
    SLIDE(R.string.slide),
    PAGE_CURL(R.string.page_curl),
    ;

    /** Non-gesture navigation and unsupported curl surfaces retain the stock pager animation. */
    val useViewPagerAnimation: Boolean
        get() = this != NONE
}

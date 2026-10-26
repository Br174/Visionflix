package com.streamflixreborn.streamflix.fragments.search

/** Native provider page first; a page empty AFTER filters is never an EOF signal. */
internal object Lab170NativePagingPolicy {
    const val MAX_SCAN_PAGE = 240
    const val MAX_SCAN_BATCH = 32
    const val MAX_CONSECUTIVE_EMPTY_RAW = 3
    const val MAX_REPEATED_RAW_PAGES = 2

    fun shouldContinue(page: Int, rawEmptyPages: Int, repeatedPages: Int): Boolean =
        page < MAX_SCAN_PAGE && rawEmptyPages < MAX_CONSECUTIVE_EMPTY_RAW &&
            repeatedPages < MAX_REPEATED_RAW_PAGES

    fun signature(ids: List<String>): String = ids.joinToString("|")
}

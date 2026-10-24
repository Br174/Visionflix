package com.streamflixreborn.streamflix.fragments.search

/** LAB154: bounded quick-first-page strategy; successive pages retain full coverage. */
internal object Lab154SearchPacing {
    fun providerPages(page: Int): IntRange {
        if (page <= 0) return IntRange.EMPTY
        return if (page == 1) 1..1 else {
            val first = 2 + (page - 2) * 3
            first..(first + 2)
        }
    }
    fun metadataPages(page: Int): IntRange {
        if (page <= 0) return IntRange.EMPTY
        return if (page == 1) 1..2 else {
            val first = 3 + (page - 2) * 4
            first..(first + 3)
        }
    }
    fun candidateLimit(firstPage: Boolean, originatingProvider: Boolean): Int =
        if (!firstPage) 24 else if (originatingProvider) 16 else 8
}

package com.streamflixreborn.streamflix.providers

/** LAB172: no runtime tests, no additional network lookups. */
internal object Lab172NativeYearPolicy {
    // CB01 strips "(1980)" to clean the display title; retain it as released.
    private val printedYear = Regex("""\(((?:18|19|20)\d{2})\)""")

    fun fromTitle(rawTitle: String): String? =
        printedYear.findAll(rawTitle).lastOrNull()?.groupValues?.getOrNull(1)

    /** 1980..1985 includes 1980, 1981, 1982, 1983, 1984 and 1985. */
    fun matchesRange(dateOrYear: String?, exact: Int?, from: Int?, to: Int?): Boolean {
        if (exact == null && from == null && to == null) return true
        val year = dateOrYear?.take(4)?.toIntOrNull() ?: return false
        return (exact == null || year == exact) &&
            (from == null || year >= from) && (to == null || year <= to)
    }
}

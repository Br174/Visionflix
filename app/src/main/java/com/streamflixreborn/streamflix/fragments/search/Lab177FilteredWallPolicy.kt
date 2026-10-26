package com.streamflixreborn.streamflix.fragments.search

/** LAB177 global filter coverage: progressive metadata windows, never provider rescans. */
internal object Lab177FilteredWallPolicy {
    fun shouldExpand(
        count: Int, currentPage: Int, hasMore: Boolean,
        minimumPosters: Int = 18, maximumAutoPage: Int = 9,
    ): Boolean = hasMore && count < minimumPosters &&
        currentPage < maximumAutoPage

    fun requiresAlias(localizedTitle: String, originalTitle: String?): Boolean =
        !originalTitle.isNullOrBlank() &&
            Lab175AvailabilityPolicy.normalizeTitle(localizedTitle) !=
            Lab175AvailabilityPolicy.normalizeTitle(originalTitle)

    /**
     * "Italy" is a TMDb country-of-origin filter, not Italian audio nor
     * provider location. Those remain separate dimensions.
     */
    fun italianCountryIsNotLanguage(
        countryCode: String?, italianOriginalLanguageOnly: Boolean,
    ): Boolean = countryCode == "IT" && !italianOriginalLanguageOnly
}

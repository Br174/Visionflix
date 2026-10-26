package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters

/**
 * LAB174: a source page is the only source of playable provider identities.
 * Missing card fields are UNKNOWN, not negative filter matches. One bounded,
 * batched metadata lookup can verify unknowns; no title-by-title queries.
 */
internal object Lab174FilteredSourcePolicy {
    fun requiresStrictMetadata(filters: AdvancedSearchFilters): Boolean =
        filters.italian != AdvancedSearchFilters.ItalianFilter.ALL ||
        filters.country != AdvancedSearchFilters.CountryFilter.ALL ||
        filters.platform != AdvancedSearchFilters.PlatformFilter.ALL ||
        !filters.actorId.isNullOrBlank() ||
        !filters.directorId.isNullOrBlank()

    /** Strip source decorations so localized years and quality don't block
     * the title+year match with a TMDb Discover catalogue result. */
    fun cleanProviderTitle(source: String): String = source
        .replace(Regex("""\((?:18|19|20)\d{2}\)"""), " ")
        .replace(Regex("""\[(?:HD|HD/3D|4K|FULL HD|ITA|SUB ITA)\]""", RegexOption.IGNORE_CASE), " ")
        .trim()
}

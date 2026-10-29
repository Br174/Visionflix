package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters

/** TMDb verifies source titles; a partial Discover catalogue never excludes them. */
internal object Lab182SourceMatchPolicy {
    fun requiresMetadata(filters: AdvancedSearchFilters): Boolean =
        filters.country != AdvancedSearchFilters.CountryFilter.ALL ||
        filters.platform != AdvancedSearchFilters.PlatformFilter.ALL ||
        !filters.actorId.isNullOrBlank() || !filters.directorId.isNullOrBlank() ||
        filters.effectiveGenres.isNotEmpty() ||
        filters.type == AdvancedSearchFilters.ContentType.CARTOON ||
        filters.year != null || !filters.dateFrom.isNullOrBlank() || !filters.dateTo.isNullOrBlank()

    fun matchesTypedTitle(source: String, localized: String, original: String): Boolean =
        Lab164SearchMatchPolicy.matchesMetadata(source, setOf(localized, original))
}

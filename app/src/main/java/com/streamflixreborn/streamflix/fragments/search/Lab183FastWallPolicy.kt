package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters

/** Only locally provable filters may display cached source posters instantly. */
internal object Lab183FastWallPolicy {
    fun canUseCachedPosters(filters: AdvancedSearchFilters): Boolean =
        filters.italian == AdvancedSearchFilters.ItalianFilter.ALL &&
        filters.country == AdvancedSearchFilters.CountryFilter.ALL &&
        filters.platform == AdvancedSearchFilters.PlatformFilter.ALL &&
        filters.actorId.isNullOrBlank() && filters.directorId.isNullOrBlank() &&
        filters.effectiveGenres.isEmpty() &&
        filters.type != AdvancedSearchFilters.ContentType.CARTOON &&
        filters.dateFrom?.endsWith("-01-01") != false &&
        filters.dateTo?.endsWith("-12-31") != false
}

package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters

/** LAB168: only provider-verifiable filters can bypass slow remote verification. */
internal object Lab168FastResultsPolicy {
    fun canUseDirectProviderResults(filters: AdvancedSearchFilters): Boolean =
        filters.italian == AdvancedSearchFilters.ItalianFilter.ALL &&
            filters.country == AdvancedSearchFilters.CountryFilter.ALL &&
            filters.platform == AdvancedSearchFilters.PlatformFilter.ALL &&
            filters.actorId.isNullOrBlank() &&
            filters.directorId.isNullOrBlank()
}

package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters

/**
 * LAB173: TV primary search combinability. Native provider pagination stays
 * authoritative. Country metadata comes from a bounded TMDb Discover window,
 * not remote checks launched for each provider poster.
 */
internal object Lab173TvCombinedFilterPolicy {
    fun effectiveMultiGenres(
        single: AdvancedSearchFilters.GenreFilter,
        multiple: Set<AdvancedSearchFilters.GenreFilter>,
    ): Set<AdvancedSearchFilters.GenreFilter> =
        if (single == AdvancedSearchFilters.GenreFilter.ALL) multiple else emptySet()

    fun needsBroaderCountryWindow(
        filters: AdvancedSearchFilters, text: String,
    ): Boolean =
        text.isBlank() && filters.country != AdvancedSearchFilters.CountryFilter.ALL

    // Metadata pages 1-3, then 4-6 etc; single bounded batch, one
    // TMDb Discover request per catalogue page, never one per source title.
    fun metadataPages(providerPage: Int): IntRange {
        if (providerPage <= 0) return IntRange.EMPTY
        val first = (providerPage - 1) * 3 + 1
        return first..(first + 2)
    }
}

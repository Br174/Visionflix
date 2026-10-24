package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters

/**
 * LAB165: separate first-page provider availability discovery from UI sorting.
 * Advanced actor/director searches always retain their existing original
 * discover sort and full candidate limits, including Cineblog/matrioska flows.
 */
internal object Lab165FastDiscoveryPolicy {
    fun metadataSort(
        requested: AdvancedSearchFilters.SortFilter,
        query: String,
        actorId: String?,
        directorId: String?,
        page: Int,
        usedForProviderLookup: Boolean,
    ): AdvancedSearchFilters.SortFilter =
        if (usedForProviderLookup && query.isBlank() && page == 1 &&
            actorId.isNullOrBlank() && directorId.isNullOrBlank())
            AdvancedSearchFilters.SortFilter.POPULARITY
        else requested

    fun candidateLimit(
        existingLimit: Int,
        query: String,
        actorId: String?,
        directorId: String?,
        page: Int,
    ): Int = if (query.isBlank() && page == 1 &&
        actorId.isNullOrBlank() && directorId.isNullOrBlank())
        existingLimit.coerceAtMost(12)
    else existingLimit
}

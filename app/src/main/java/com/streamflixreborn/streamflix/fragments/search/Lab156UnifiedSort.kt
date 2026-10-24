package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters

/** The only TV ordering menu: one selection, one SearchFilter state. */
internal object Lab156UnifiedSort {
    val options = listOf(
        AdvancedSearchFilters.SortFilter.DATE,
        AdvancedSearchFilters.SortFilter.OLDEST,
        AdvancedSearchFilters.SortFilter.POPULARITY,
        AdvancedSearchFilters.SortFilter.VOTE,
    )

    fun label(option: AdvancedSearchFilters.SortFilter): String = when (option) {
        AdvancedSearchFilters.SortFilter.DATE -> "Più nuovi"
        AdvancedSearchFilters.SortFilter.OLDEST -> "Più vecchi"
        AdvancedSearchFilters.SortFilter.POPULARITY -> "Popolarità"
        AdvancedSearchFilters.SortFilter.VOTE -> "Voto"
    }
}

package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters
import org.junit.Assert.*
import org.junit.Test

class Lab173TvCombinedFilterPolicyTest {
    @Test fun singleGenreOverridesStaleMultipleGenreChoicesOnTv() {
        val old = setOf(AdvancedSearchFilters.GenreFilter.WESTERN)
        assertTrue(Lab173TvCombinedFilterPolicy.effectiveMultiGenres(
            AdvancedSearchFilters.GenreFilter.COMEDY, old).isEmpty())
        assertEquals(old, Lab173TvCombinedFilterPolicy.effectiveMultiGenres(
            AdvancedSearchFilters.GenreFilter.ALL, old))
    }

    @Test fun franceComedyCountryIndexIsBroadenedWithoutPerTitleLookups() {
        val filters = AdvancedSearchFilters(
            country = AdvancedSearchFilters.CountryFilter.FRANCE,
            genre = AdvancedSearchFilters.GenreFilter.COMEDY)
        assertTrue(Lab173TvCombinedFilterPolicy.needsBroaderCountryWindow(filters, ""))
        assertFalse(Lab173TvCombinedFilterPolicy.needsBroaderCountryWindow(filters, "film"))
        assertEquals(1..3, Lab173TvCombinedFilterPolicy.metadataPages(1))
        assertEquals(4..6, Lab173TvCombinedFilterPolicy.metadataPages(2))
        assertEquals(7..9, Lab173TvCombinedFilterPolicy.metadataPages(3))
    }

    @Test fun independentGenreAndInclusiveYearConstraintsDoNotClash() {
        val filters = AdvancedSearchFilters(
            genre = AdvancedSearchFilters.GenreFilter.COMEDY,
            dateFrom = "1980-01-01", dateTo = "1985-12-31")
        assertTrue(Lab173TvCombinedFilterPolicy.effectiveMultiGenres(
            filters.genre, setOf(AdvancedSearchFilters.GenreFilter.WESTERN)).isEmpty())
        for (year in 1980..1985) {
            assertTrue(com.streamflixreborn.streamflix.providers.Lab172NativeYearPolicy.matchesRange(
                "${year}-06-01", null, 1980, 1985))
        }
    }
}

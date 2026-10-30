package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAB189: the six selectable content types must not conflate series and IPTV. */
class Lab189QuickBarContractTest {
    @Test fun sixCategoriesAreOrderedAndDistinct() {
        assertEquals(
            listOf("ALL", "MOVIE", "TV", "CARTOON", "ANIME", "LIVE_TV"),
            AdvancedSearchFilters.ContentType.entries.map { it.name }
        )
    }

    @Test fun channelsAreNotSeriesOrGeneralSearch() {
        val live = AdvancedSearchFilters.ContentType.LIVE_TV
        assertTrue(live != AdvancedSearchFilters.ContentType.TV)
        assertTrue(live != AdvancedSearchFilters.ContentType.ALL)
        assertTrue(AdvancedSearchFilters(type = live).isActive)
        assertFalse(AdvancedSearchFilters().isActive)
    }

    @Test fun quickBarGenresRetainTheExistingCombinedFilterModel() {
        val both = setOf(
            AdvancedSearchFilters.GenreFilter.ROMANCE,
            AdvancedSearchFilters.GenreFilter.COMEDY
        )
        val selection = AdvancedSearchFilters(genres = both)
        assertEquals(both, selection.effectiveGenres)
        assertTrue(selection.isActive)
    }

    @Test fun otherFilterSelectionsRemainIndependentOfType() {
        val f = AdvancedSearchFilters(
            type = AdvancedSearchFilters.ContentType.TV,
            country = AdvancedSearchFilters.CountryFilter.ITALY,
            year = 2022,
            sort = AdvancedSearchFilters.SortFilter.OLDEST,
        )
        assertEquals(AdvancedSearchFilters.ContentType.TV, f.type)
        assertEquals(AdvancedSearchFilters.CountryFilter.ITALY, f.country)
        assertEquals(2022, f.year)
        assertEquals(AdvancedSearchFilters.SortFilter.OLDEST, f.sort)
    }
}

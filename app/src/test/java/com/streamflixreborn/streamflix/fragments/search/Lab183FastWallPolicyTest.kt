package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters
import org.junit.Assert.*
import org.junit.Test

class Lab183FastWallPolicyTest {
    @Test fun typeAndWholeYearRangesMayUseCachedPosters() {
        assertTrue(Lab183FastWallPolicy.canUseCachedPosters(AdvancedSearchFilters()))
        assertTrue(Lab183FastWallPolicy.canUseCachedPosters(
            AdvancedSearchFilters(type = AdvancedSearchFilters.ContentType.MOVIE,
                dateFrom = "1980-01-01", dateTo = "1989-12-31")))
    }
    @Test fun strictMetadataAndPartialDatesRequireVerification() {
        assertFalse(Lab183FastWallPolicy.canUseCachedPosters(AdvancedSearchFilters(
            country = AdvancedSearchFilters.CountryFilter.FRANCE)))
        assertFalse(Lab183FastWallPolicy.canUseCachedPosters(AdvancedSearchFilters(
            genre = AdvancedSearchFilters.GenreFilter.ANIMATION)))
        assertFalse(Lab183FastWallPolicy.canUseCachedPosters(AdvancedSearchFilters(actorId = "123")))
        assertFalse(Lab183FastWallPolicy.canUseCachedPosters(AdvancedSearchFilters(
            dateFrom = "1980-07-01")))
    }
}

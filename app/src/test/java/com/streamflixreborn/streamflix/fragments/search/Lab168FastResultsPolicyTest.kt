package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Lab168FastResultsPolicyTest {
    @Test fun genreAndDateRemainFast() {
        assertTrue(Lab168FastResultsPolicy.canUseDirectProviderResults(
            AdvancedSearchFilters(type = AdvancedSearchFilters.ContentType.MOVIE,
                genres = setOf(AdvancedSearchFilters.GenreFilter.COMEDY),
                dateFrom = "2010-01-01", dateTo = "2019-12-31",
                sort = AdvancedSearchFilters.SortFilter.DATE)))
    }

    @Test fun actorDirectorPhotoFiltersRemainVerified() {
        assertFalse(Lab168FastResultsPolicy.canUseDirectProviderResults(
            AdvancedSearchFilters(actorId = "123", directorId = "456")))
        assertFalse(Lab168FastResultsPolicy.canUseDirectProviderResults(
            AdvancedSearchFilters(directorId = "456")))
    }

    @Test fun geoLanguageAndPlatformsRemainVerified() {
        assertFalse(Lab168FastResultsPolicy.canUseDirectProviderResults(
            AdvancedSearchFilters(country = AdvancedSearchFilters.CountryFilter.FRANCE)))
        assertFalse(Lab168FastResultsPolicy.canUseDirectProviderResults(
            AdvancedSearchFilters(italian = AdvancedSearchFilters.ItalianFilter.YES)))
        assertFalse(Lab168FastResultsPolicy.canUseDirectProviderResults(
            AdvancedSearchFilters(italian = AdvancedSearchFilters.ItalianFilter.NO)))
        assertFalse(Lab168FastResultsPolicy.canUseDirectProviderResults(
            AdvancedSearchFilters(platform = AdvancedSearchFilters.PlatformFilter.NETFLIX)))
    }

    @Test fun plainSearchAndSeriesRemainDirect() {
        assertTrue(Lab168FastResultsPolicy.canUseDirectProviderResults(AdvancedSearchFilters()))
        assertTrue(Lab168FastResultsPolicy.canUseDirectProviderResults(
            AdvancedSearchFilters(type = AdvancedSearchFilters.ContentType.TV)))
    }
}

package com.streamflixreborn.streamflix.fragments.search

import org.junit.Assert.*
import org.junit.Test

class Lab164SearchMatchPolicyTest {
    @Test fun exactMetadataMatchWorks() {
        assertTrue(Lab164SearchMatchPolicy.matchesMetadata("movie|lavitaebella|1997", setOf("movie|lavitaebella|1997")))
    }

    @Test fun localizedMovieReleaseYearHasSmallTolerance() {
        assertTrue(Lab164SearchMatchPolicy.matchesMetadata("movie|filmtest|2017", setOf("movie|filmtest|2016")))
        assertFalse(Lab164SearchMatchPolicy.matchesMetadata("movie|filmtest|2024", setOf("movie|filmtest|2016")))
    }

    @Test fun tvLastEpisodeYearCanBeMuchLaterThanOriginalAirDate() {
        assertTrue(Lab164SearchMatchPolicy.matchesMetadata("tv|serialtest|2025", setOf("tv|serialtest|2017")))
        assertFalse(Lab164SearchMatchPolicy.matchesMetadata("tv|serialtest|2045", setOf("tv|serialtest|2017")))
        assertFalse(Lab164SearchMatchPolicy.matchesMetadata("tv|other|2025", setOf("tv|serialtest|2017")))
    }

    @Test fun typeIdentityPreventsFilmAndSeriesMixup() {
        assertFalse(Lab164SearchMatchPolicy.matchesMetadata("movie|filmtest|2017", setOf("tv|filmtest|2017")))
    }

    @Test fun typedYearDoesNotLaunchUnboundedMetadataDiscovery() {
        val filters = com.streamflixreborn.streamflix.providers.AdvancedSearchFilters(year=2018)
        assertTrue(Lab164SearchMatchPolicy.providerFirstYearQuery("Il nome della rosa", filters))
        assertFalse(Lab164SearchMatchPolicy.providerFirstYearQuery("", filters))
        assertFalse(Lab164SearchMatchPolicy.providerFirstYearQuery("Il nome della rosa",
            filters.copy(genres=setOf(
                com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.GenreFilter.COMEDY))))
        assertFalse(Lab164SearchMatchPolicy.providerFirstYearQuery("Il nome della rosa",
            filters.copy(year=null)))
    }

    @Test fun providerFirstSupportsDecadeRangeWithoutGlobalDiscovery() {
        val dateRange = com.streamflixreborn.streamflix.providers.AdvancedSearchFilters(
            dateFrom="2010-01-01",dateTo="2019-12-31")
        assertTrue(Lab164SearchMatchPolicy.providerFirstYearQuery("natale", dateRange))
    }

    @Test fun providerTitleMayCarryVerifiedYearOrFilmSuffix() {
        assertTrue(Lab164SearchMatchPolicy.matchesMetadata(
            "movie|insideout22024|2024", setOf("movie|insideout2|2024")
        ))
        assertTrue(Lab164SearchMatchPolicy.matchesMetadata(
            "movie|insideout2filmcompleto|2024", setOf("movie|insideout2|2024")
        ))
        assertFalse(Lab164SearchMatchPolicy.matchesMetadata(
            "movie|insideout3|2024", setOf("movie|insideout2|2024")
        ))
    }

    @Test fun missingProviderYearCanUseVerifiedMetadata() {
        assertTrue(Lab164SearchMatchPolicy.matchesMetadata("movie|filmtest|", setOf("movie|filmtest|2017")))
        assertFalse(Lab164SearchMatchPolicy.matchesMetadata("movie|other|", setOf("movie|filmtest|2017")))
    }

    @Test fun malformedKeysAndUnknownTypesNeverPassMetadataValidation() {
        assertFalse(Lab164SearchMatchPolicy.matchesMetadata("unknown|test|2023", setOf("unknown|test|2023")))
        assertFalse(Lab164SearchMatchPolicy.matchesMetadata("movie||2023", setOf("movie|test|2023")))
    }

    @Test fun localFilteredEmptyPageDoesNotTerminatePrematurely() {
        assertTrue(Lab164SearchMatchPolicy.canLoadLocalMore(false, true, 2))
        assertFalse(Lab164SearchMatchPolicy.canLoadLocalMore(false, false, 2))
        assertFalse(Lab164SearchMatchPolicy.canLoadLocalMore(false, true, 15))
    }

    @Test fun filteredCatalogScansTwoBoundedWindowsAfterAnEmptyFirstWindow() {
        assertEquals((1..4).toList(), Lab164SearchMatchPolicy.catalogPages(1).toList())
        assertEquals((5..8).toList(), Lab164SearchMatchPolicy.catalogPages(2).toList())
        assertTrue(Lab164SearchMatchPolicy.catalogPages(3).isEmpty())
    }

    @Test fun providerGenresMatchItalianAndEnglishNamesWithoutFalsePositives() {
        val comedy = com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.GenreFilter.COMEDY
        val romance = com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.GenreFilter.ROMANCE
        assertTrue(Lab164SearchMatchPolicy.localGenreMatches("Comedy", comedy))
        assertTrue(Lab164SearchMatchPolicy.localGenreMatches("Commedia", comedy))
        assertTrue(Lab164SearchMatchPolicy.localGenreMatches("Romance", romance))
        assertFalse(Lab164SearchMatchPolicy.localGenreMatches("Horror", comedy))
        assertFalse(Lab164SearchMatchPolicy.localGenreMatches("", comedy))
    }

    @Test fun globalFilteredCanCrossSparsePagesButHasFiniteBound() {
        assertTrue(Lab164SearchMatchPolicy.canLoadGlobalMore(5, 3, true))
        assertFalse(Lab164SearchMatchPolicy.canLoadGlobalMore(5, 4, true))
        assertFalse(Lab164SearchMatchPolicy.canLoadGlobalMore(15, 0, true))
        assertFalse(Lab164SearchMatchPolicy.canLoadGlobalMore(5, 2, false))
    }
}

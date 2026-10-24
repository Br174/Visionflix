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

    @Test fun tvSeasonYearToleranceDoesNotMergeDistantReboots() {
        assertTrue(Lab164SearchMatchPolicy.matchesMetadata("tv|serialtest|2020", setOf("tv|serialtest|2017")))
        assertFalse(Lab164SearchMatchPolicy.matchesMetadata("tv|serialtest|2025", setOf("tv|serialtest|2017")))
    }

    @Test fun typeIdentityPreventsFilmAndSeriesMixup() {
        assertFalse(Lab164SearchMatchPolicy.matchesMetadata("movie|filmtest|2017", setOf("tv|filmtest|2017")))
    }

    @Test fun missingProviderYearCanUseVerifiedMetadata() {
        assertTrue(Lab164SearchMatchPolicy.matchesMetadata("movie|filmtest|", setOf("movie|filmtest|2017")))
        assertFalse(Lab164SearchMatchPolicy.matchesMetadata("movie|other|", setOf("movie|filmtest|2017")))
    }

    @Test fun localFilteredEmptyPageDoesNotTerminatePrematurely() {
        assertTrue(Lab164SearchMatchPolicy.canLoadLocalMore(false, true, 2))
        assertFalse(Lab164SearchMatchPolicy.canLoadLocalMore(false, false, 2))
        assertFalse(Lab164SearchMatchPolicy.canLoadLocalMore(false, true, 15))
    }

    @Test fun globalFilteredCanCrossSparsePagesButHasFiniteBound() {
        assertTrue(Lab164SearchMatchPolicy.canLoadGlobalMore(5, 3, true))
        assertFalse(Lab164SearchMatchPolicy.canLoadGlobalMore(5, 4, true))
        assertFalse(Lab164SearchMatchPolicy.canLoadGlobalMore(15, 0, true))
        assertFalse(Lab164SearchMatchPolicy.canLoadGlobalMore(5, 2, false))
    }
}

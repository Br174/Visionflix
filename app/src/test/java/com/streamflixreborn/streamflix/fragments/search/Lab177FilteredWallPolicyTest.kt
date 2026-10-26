package com.streamflixreborn.streamflix.fragments.search

import org.junit.Assert.*
import org.junit.Test

class Lab177FilteredWallPolicyTest {
    @Test fun oneOrTwoPostersDoNotStopFilteredGlobalDiscovery() {
        assertTrue(Lab177FilteredWallPolicy.shouldExpand(0, 1, true))
        assertTrue(Lab177FilteredWallPolicy.shouldExpand(1, 1, true))
        assertTrue(Lab177FilteredWallPolicy.shouldExpand(2, 3, true))
        assertTrue(Lab177FilteredWallPolicy.shouldExpand(10, 8, true))
        assertFalse(Lab177FilteredWallPolicy.shouldExpand(18, 3, true))
        assertFalse(Lab177FilteredWallPolicy.shouldExpand(2, 9, true))
        assertFalse(Lab177FilteredWallPolicy.shouldExpand(0, 1, false))
    }

    @Test fun originalAndLocalizedTitlesAreSeparateValidLookupAliases() {
        assertTrue(Lab177FilteredWallPolicy.requiresAlias(
            "Il mondo fantastico", "The Wonderful World"))
        assertFalse(Lab177FilteredWallPolicy.requiresAlias("Amélie", "Amelie"))
        assertFalse(Lab177FilteredWallPolicy.requiresAlias("Città", "Citta"))
        assertFalse(Lab177FilteredWallPolicy.requiresAlias("Film", null))
    }

    @Test fun countryOfProductionIsIndependentOfOriginalItalianLanguage() {
        assertTrue(Lab177FilteredWallPolicy.italianCountryIsNotLanguage("IT", false))
        assertFalse(Lab177FilteredWallPolicy.italianCountryIsNotLanguage(null, false))
        assertFalse(Lab177FilteredWallPolicy.italianCountryIsNotLanguage("IT", true))
    }

    @Test fun sourceBackedSafetyRemainsMandatory() {
        val tmdb = listOf(
            Lab175AvailabilityPolicy.candidate("movie", "Il film", 2010) to "tmdb-1",
            Lab175AvailabilityPolicy.candidate("movie", "Assente", 2012) to "tmdb-2",
        )
        val available = listOf(Lab175AvailabilityPolicy.candidate("movie", "Il film", 2010))
        assertEquals(listOf("tmdb-1"),
            Lab175AvailabilityPolicy.onlyConfirmed(tmdb, available))
    }
}

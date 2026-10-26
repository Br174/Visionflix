package com.streamflixreborn.streamflix.fragments.search

import org.junit.Assert.*
import org.junit.Test

class Lab175ProviderIntegrationFixtureTest {
    private val policy = Lab175AvailabilityPolicy
    private fun movie(name: String, year: Int?) = policy.candidate("movie", name, year)
    private fun show(name: String, year: Int?) = policy.candidate("tv", name, year)

    @Test fun fakeMultipleProvidersOnlyShowTmdbFilteredSourceBackedItems() {
        // TMDb Discover has already applied comedy + 2010..2014 + Italian.
        val filtered = listOf(
            movie("Una commedia", 2011) to "tmdb-101",
            movie("Il film assente", 2012) to "tmdb-102",
            movie("Un'altra commedia", 2014) to "tmdb-103",
        )
        val confirmed = policy.onlyConfirmed(filtered, listOf(
            movie("Una commedia (2011) [HD]", 2011),
            movie("Un'altra commedia", 2014),
        ))
        assertEquals(listOf("tmdb-101", "tmdb-103"), confirmed)
        assertFalse("tmdb-102" in confirmed)
    }

    @Test fun unverifiedFilmAndUnknownYearsAreNotShown() {
        val filtered = listOf(
            movie("Sconosciuto", 2012) to "a",
            movie("Con anno", 2014) to "b",
        )
        assertEquals(listOf("b"), policy.onlyConfirmed(
            filtered, listOf(movie("Sconosciuto", null), movie("Con anno", 2014))))
    }

    @Test fun movieAndSeriesDoNotCrossContaminateTheFourContentModes() {
        val filtered = listOf(movie("Il titolo", 2012) to "movie",
            show("Il titolo", 2012) to "series")
        assertEquals(listOf("movie"),
            policy.onlyConfirmed(filtered, listOf(movie("Il titolo", 2012))))
        assertEquals(listOf("series"),
            policy.onlyConfirmed(filtered, listOf(show("Il titolo", 2012))))
    }

    @Test fun sortAndProviderOrderDoNotChangeWhichTitlesAreAvailable() {
        val filtered = listOf(
            movie("Uno", 2014) to "2014",
            movie("Due", 2010) to "2010",
            movie("Tre", 2012) to "2012",
        )
        val indexed = listOf(movie("Tre", 2012), movie("Uno", 2014))
        val newest = policy.onlyConfirmed(filtered, indexed)
        val oldest = policy.onlyConfirmed(filtered.reversed(), indexed)
        assertEquals(newest.toSet(), oldest.toSet())
        assertEquals(2, newest.size)
    }

    @Test fun outageWithoutCatalogueEvidenceDoesNotProduceFakeAvailablePosters() {
        val filtered = listOf(movie("Uno", 2010) to 1)
        assertTrue(policy.onlyConfirmed(filtered, emptyList()).isEmpty())
    }
}

package com.streamflixreborn.streamflix.providers

import org.junit.Assert.*
import org.junit.Test

class Lab150AdvancedFiltersTest {
    @Test fun defaultSelectionIsNotActive() {
        assertFalse(AdvancedSearchFilters().isActive)
        assertTrue(AdvancedSearchFilters().effectiveGenres.isEmpty())
    }

    @Test fun aSingleLegacyGenreStillWorks() {
        val f = AdvancedSearchFilters(genre = AdvancedSearchFilters.GenreFilter.ACTION)
        assertTrue(f.isActive)
        assertEquals(setOf(AdvancedSearchFilters.GenreFilter.ACTION), f.effectiveGenres)
    }

    @Test fun multipleGenresActivateSearchWithoutText() {
        val genres = setOf(AdvancedSearchFilters.GenreFilter.ACTION, AdvancedSearchFilters.GenreFilter.COMEDY)
        val filters = AdvancedSearchFilters(genres = genres)
        assertTrue(filters.isActive)
        assertEquals(genres, filters.effectiveGenres)
    }

    @Test fun multiGenreOverridesSingleLegacySelectorWithoutLosingOptions() {
        val genres = setOf(AdvancedSearchFilters.GenreFilter.ACTION, AdvancedSearchFilters.GenreFilter.SCIENCE_FICTION)
        val filters = AdvancedSearchFilters(genre = AdvancedSearchFilters.GenreFilter.DRAMA, genres = genres)
        assertEquals(genres, filters.effectiveGenres)
    }

    @Test fun allIsNotARealGenreConstraint() {
        val f = AdvancedSearchFilters(genres = setOf(AdvancedSearchFilters.GenreFilter.ALL, AdvancedSearchFilters.GenreFilter.DRAMA))
        assertEquals(setOf(AdvancedSearchFilters.GenreFilter.DRAMA), f.effectiveGenres)
    }

    @Test fun sortingAloneTriggersFilterOnlySearch() {
        assertTrue(AdvancedSearchFilters(sort = AdvancedSearchFilters.SortFilter.VOTE).isActive)
    }

    @Test fun typeAloneTriggersFilterOnlySearch() {
        for (type in AdvancedSearchFilters.ContentType.entries) {
            assertEquals(type != AdvancedSearchFilters.ContentType.ALL, AdvancedSearchFilters(type = type).isActive)
        }
    }

    @Test fun platformAloneTriggersFilterOnlySearch() {
        for (platform in AdvancedSearchFilters.PlatformFilter.entries) {
            assertEquals(platform != AdvancedSearchFilters.PlatformFilter.ALL, AdvancedSearchFilters(platform = platform).isActive)
        }
    }

    @Test fun yearAndRangeCanBeUsedWithoutAName() {
        assertTrue(AdvancedSearchFilters(year = 2024).isActive)
        assertTrue(AdvancedSearchFilters(dateFrom = "2020-01-01").isActive)
        assertTrue(AdvancedSearchFilters(dateTo = "2026-12-31").isActive)
    }

    @Test fun actorsAndDirectorsCanBeCombined() {
        assertTrue(AdvancedSearchFilters(actorId = "123", directorId = "456").isActive)
    }

    @Test fun matrixOfCombinedSelections() {
        val genres = listOf(
            emptySet(),
            setOf(AdvancedSearchFilters.GenreFilter.ACTION),
            setOf(AdvancedSearchFilters.GenreFilter.COMEDY, AdvancedSearchFilters.GenreFilter.DRAMA),
        )
        var assertions = 0
        for (type in AdvancedSearchFilters.ContentType.entries) {
            for (group in genres) {
                for (platform in AdvancedSearchFilters.PlatformFilter.entries) {
                    val filters = AdvancedSearchFilters(type = type, genres = group, platform = platform, year = 2024)
                    assertTrue(filters.isActive)
                    assertEquals(group, filters.effectiveGenres)
                    assertions += 2
                }
            }
        }
        // >= 50 unique combinations, in addition to the individual regression tests.
        assertTrue(assertions >= 50)
    }
}

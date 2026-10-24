package com.streamflixreborn.streamflix.providers

import org.junit.Assert.*
import org.junit.Test

/** LAB151 scope contract: production country and extended genres must coexist with legacy filters. */
class Lab151CountryAndGenreTest {
    @Test fun defaultSearchRemainsUnfiltered() {
        assertFalse(AdvancedSearchFilters().isActive)
        assertEquals(AdvancedSearchFilters.CountryFilter.ALL, AdvancedSearchFilters().country)
    }

    @Test fun italyFirstWithTwentyOtherCountries() {
        val countries = AdvancedSearchFilters.CountryFilter.entries
        assertEquals(22, countries.size) // ALL + Italy + 20 additional countries
        assertEquals(AdvancedSearchFilters.CountryFilter.ALL, countries.first())
        assertEquals(AdvancedSearchFilters.CountryFilter.ITALY, countries[1])
        assertEquals("IT", countries[1].isoCode)
        assertEquals(21, countries.count { it.isoCode != null })
    }

    @Test fun codesAreDistinctAndUppercase() {
        val codes = AdvancedSearchFilters.CountryFilter.entries.mapNotNull { it.isoCode }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it.length == 2 && it.all(Char::isUpperCase) })
    }

    @Test fun everyRealCountryActivatesSearchWithoutATitle() {
        AdvancedSearchFilters.CountryFilter.entries.forEach { country ->
            assertEquals(country != AdvancedSearchFilters.CountryFilter.ALL,
                AdvancedSearchFilters(country = country).isActive)
        }
    }

    @Test fun countryAndLanguageAreIndependent() {
        val italianLanguageUsProduction = AdvancedSearchFilters(
            italian = AdvancedSearchFilters.ItalianFilter.YES,
            country = AdvancedSearchFilters.CountryFilter.USA,
        )
        assertTrue(italianLanguageUsProduction.isActive)
        assertEquals("US", italianLanguageUsProduction.country.isoCode)
        assertEquals(AdvancedSearchFilters.ItalianFilter.YES, italianLanguageUsProduction.italian)
    }

    @Test fun filmOnlyGenresMustNotRequestTvGenres() {
        listOf(AdvancedSearchFilters.GenreFilter.ROMANCE,
            AdvancedSearchFilters.GenreFilter.HORROR,
            AdvancedSearchFilters.GenreFilter.THRILLER,
            AdvancedSearchFilters.GenreFilter.HISTORY,
            AdvancedSearchFilters.GenreFilter.MUSIC).forEach { genre ->
            assertNotNull(genre.movieId)
            assertNull(genre.tvId)
        }
    }

    @Test fun romanticMovieMapsToTmdb10749() {
        assertEquals(10749, AdvancedSearchFilters.GenreFilter.ROMANCE.movieId)
        assertEquals("Romantico", AdvancedSearchFilters.GenreFilter.ROMANCE.label)
    }

    @Test fun multiGenreOrWithCountryAndDateAndPlatform() {
        val selected = setOf(AdvancedSearchFilters.GenreFilter.ROMANCE,
            AdvancedSearchFilters.GenreFilter.COMEDY)
        val filters = AdvancedSearchFilters(
            genres = selected,
            country = AdvancedSearchFilters.CountryFilter.ITALY,
            dateFrom = "1990-01-01",
            dateTo = "2020-12-31",
            type = AdvancedSearchFilters.ContentType.MOVIE,
            platform = AdvancedSearchFilters.PlatformFilter.NETFLIX,
        )
        assertTrue(filters.isActive)
        assertEquals(selected, filters.effectiveGenres)
        assertEquals("IT", filters.country.isoCode)
    }

    @Test fun animationWithRomanceRequiresBothGenres() {
        val filters = AdvancedSearchFilters(
            type = AdvancedSearchFilters.ContentType.CARTOON,
            genre = AdvancedSearchFilters.GenreFilter.ROMANCE,
        )
        assertFalse(filters.acceptsGenreIds(listOf(16), movie = true))
        assertFalse(filters.acceptsGenreIds(listOf(10749), movie = true))
        assertTrue(filters.acceptsGenreIds(listOf(16, 10749), movie = true))
        assertFalse(filters.acceptsGenreIds(listOf(16, 35), movie = true))
    }

    @Test fun genreSelectionsUseOrWithinTheSameMedium() {
        val filters = AdvancedSearchFilters(
            genres = setOf(
                AdvancedSearchFilters.GenreFilter.ROMANCE,
                AdvancedSearchFilters.GenreFilter.COMEDY,
            ),
        )
        assertTrue(filters.acceptsGenreIds(listOf(10749), movie = true))
        assertTrue(filters.acceptsGenreIds(listOf(35), movie = true))
        assertFalse(filters.acceptsGenreIds(listOf(28), movie = true))
        assertTrue(filters.acceptsGenreIds(listOf(35), movie = false))
        assertFalse(filters.acceptsGenreIds(listOf(28), movie = false))
    }

    @Test fun movieOnlyGenreDoesNotMatchTvWithUnrelatedGenreId() {
        val filters = AdvancedSearchFilters(genre = AdvancedSearchFilters.GenreFilter.HORROR)
        assertTrue(filters.acceptsGenreIds(listOf(27), movie = true))
        assertFalse(filters.acceptsGenreIds(listOf(27), movie = false))
        assertFalse(filters.acceptsGenreIds(emptyList(), movie = true))
    }

    @Test fun allGenreSelectionRetainsAnimationTypeConstraint() {
        val cartoons = AdvancedSearchFilters(type = AdvancedSearchFilters.ContentType.CARTOON)
        assertFalse(cartoons.acceptsGenreIds(emptyList(), movie = true))
        assertFalse(cartoons.acceptsGenreIds(listOf(35), movie = true))
        assertTrue(cartoons.acceptsGenreIds(listOf(16), movie = true))
        assertTrue(AdvancedSearchFilters().acceptsGenreIds(emptyList(), movie = true))
    }

    @Test fun northernEuropeAndBeneluxCountriesHaveStableIsoCodes() {
        assertEquals("SE", AdvancedSearchFilters.CountryFilter.SWEDEN.isoCode)
        assertEquals("DK", AdvancedSearchFilters.CountryFilter.DENMARK.isoCode)
        assertEquals("NO", AdvancedSearchFilters.CountryFilter.NORWAY.isoCode)
        assertEquals("NL", AdvancedSearchFilters.CountryFilter.NETHERLANDS.isoCode)
        assertEquals("BE", AdvancedSearchFilters.CountryFilter.BELGIUM.isoCode)
    }

    @Test fun countryGenreMatrixKeepsSelection() {
        for (country in AdvancedSearchFilters.CountryFilter.entries) {
            for (genre in AdvancedSearchFilters.GenreFilter.entries) {
                val filters = AdvancedSearchFilters(country = country, genre = genre)
                assertEquals(country, filters.country)
                assertEquals(genre == AdvancedSearchFilters.GenreFilter.ALL ||
                    filters.effectiveGenres == setOf(genre), true)
                assertEquals(country != AdvancedSearchFilters.CountryFilter.ALL ||
                    genre != AdvancedSearchFilters.GenreFilter.ALL, filters.isActive)
            }
        }
    }
}

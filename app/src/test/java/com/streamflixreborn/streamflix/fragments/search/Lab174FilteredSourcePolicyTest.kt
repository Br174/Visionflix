package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters
import org.junit.Assert.*
import org.junit.Test

class Lab174FilteredSourcePolicyTest {
    @Test fun oneNativeGenreFilterDoesNotRequireCountryMetadata() {
        assertFalse(Lab174FilteredSourcePolicy.requiresStrictMetadata(
            AdvancedSearchFilters(genre=AdvancedSearchFilters.GenreFilter.COMEDY)))
        assertFalse(Lab174FilteredSourcePolicy.requiresStrictMetadata(
            AdvancedSearchFilters(dateFrom="1980-01-01",dateTo="1985-12-31")))
    }

    @Test fun countryCombinationsRequireVerifiedProductionCountry() {
        val f=AdvancedSearchFilters(
            genre=AdvancedSearchFilters.GenreFilter.COMEDY,
            country=AdvancedSearchFilters.CountryFilter.FRANCE)
        assertTrue(Lab174FilteredSourcePolicy.requiresStrictMetadata(f))
        assertFalse(Lab173TvCombinedFilterPolicy.effectiveMultiGenres(
            f.genre, setOf(AdvancedSearchFilters.GenreFilter.WESTERN)).isNotEmpty())
    }

    @Test fun sourceTitleDecorationsDoNotEraseMetadataMatches() {
        assertEquals("Una commedia", Lab174FilteredSourcePolicy.cleanProviderTitle(
            "Una commedia (1983) [HD]"))
        assertEquals("Film di prova", Lab174FilteredSourcePolicy.cleanProviderTitle(
            "Film di prova [4K]"))
    }

    @Test fun otherStrictMetadataConstraintsStayStrict() {
        assertTrue(Lab174FilteredSourcePolicy.requiresStrictMetadata(
            AdvancedSearchFilters(platform=AdvancedSearchFilters.PlatformFilter.NETFLIX)))
        assertTrue(Lab174FilteredSourcePolicy.requiresStrictMetadata(
            AdvancedSearchFilters(italian=AdvancedSearchFilters.ItalianFilter.YES)))
        assertTrue(Lab174FilteredSourcePolicy.requiresStrictMetadata(
            AdvancedSearchFilters(actorId="123")))
    }
}

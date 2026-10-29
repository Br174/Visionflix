package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters
import org.junit.Assert.*
import org.junit.Test

class Lab182SourceMatchPolicyTest {
    @Test fun contentTypeOnlyDoesNotNeedTmdb() {
        assertFalse(Lab182SourceMatchPolicy.requiresMetadata(AdvancedSearchFilters()))
        assertFalse(Lab182SourceMatchPolicy.requiresMetadata(
            AdvancedSearchFilters(type = AdvancedSearchFilters.ContentType.MOVIE)))
    }
    @Test fun decadeFilterRequiresVerification() {
        assertTrue(Lab182SourceMatchPolicy.requiresMetadata(
            AdvancedSearchFilters(dateFrom = "1980-01-01", dateTo = "1989-12-31")))
    }
    @Test fun genreAndActorRequireSourceTitleMetadata() {
        assertTrue(Lab182SourceMatchPolicy.requiresMetadata(
            AdvancedSearchFilters(genre = AdvancedSearchFilters.GenreFilter.ANIMATION)))
        assertTrue(Lab182SourceMatchPolicy.requiresMetadata(
            AdvancedSearchFilters(actorId = "1234")))
    }

    @Test fun localizedAliasMatchesOnlyCorrectTypeAndYear() {
        assertTrue(Lab182SourceMatchPolicy.matchesTypedTitle(
            "movie|ilmondo|1965", "movie|theworld|1965", "movie|ilmondo|1965"))
        assertFalse(Lab182SourceMatchPolicy.matchesTypedTitle(
            "tv|ilmondo|1965", "movie|theworld|1965", "movie|ilmondo|1965"))
        assertFalse(Lab182SourceMatchPolicy.matchesTypedTitle(
            "movie|ilmondo|1965", "movie|theworld|2024", "movie|ilmondo|2024"))
    }
}

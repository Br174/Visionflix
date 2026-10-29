package com.streamflixreborn.streamflix.providers
import org.junit.Assert.*
import org.junit.Test
class Scudo360GenreAndTypeTest {
 @Test fun movieOrTvSelectionActivatesFiltering() { assertTrue(AdvancedSearchFilters(type=AdvancedSearchFilters.ContentType.MOVIE).isActive); assertTrue(AdvancedSearchFilters(type=AdvancedSearchFilters.ContentType.TV).isActive) }
 @Test fun genreSelectionActivatesFiltering() { assertTrue(AdvancedSearchFilters(genre=AdvancedSearchFilters.GenreFilter.DRAMA).isActive) }
 @Test fun combinationRemainsActive() { assertTrue(AdvancedSearchFilters(type=AdvancedSearchFilters.ContentType.MOVIE,genre=AdvancedSearchFilters.GenreFilter.COMEDY,year=1999).isActive) }
 @Test fun emptySelectionInactive() { assertFalse(AdvancedSearchFilters().isActive) }
}

package com.streamflixreborn.streamflix.providers
import org.junit.Assert.*
import org.junit.Test
class Scudo360LanguageFilterTest {
 @Test fun italianDubbingActivatesFiltering() { assertTrue(AdvancedSearchFilters(italian=AdvancedSearchFilters.ItalianFilter.YES).isActive) }
 @Test fun originalLanguageActivatesFiltering() { assertTrue(AdvancedSearchFilters(italian=AdvancedSearchFilters.ItalianFilter.NO).isActive) }
 @Test fun noLanguagePreferenceIsNotAFilter() { assertFalse(AdvancedSearchFilters(italian=AdvancedSearchFilters.ItalianFilter.ALL).isActive) }
}

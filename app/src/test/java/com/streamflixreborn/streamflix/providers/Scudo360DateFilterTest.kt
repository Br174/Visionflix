package com.streamflixreborn.streamflix.providers
import org.junit.Assert.*
import org.junit.Test
class Scudo360DateFilterTest {
 @Test fun singleYearActivatesFiltering() { assertTrue(AdvancedSearchFilters(year=1983).isActive) }
 @Test fun yearRangeBoundariesActivateFiltering() { assertTrue(AdvancedSearchFilters(dateFrom="1980").isActive); assertTrue(AdvancedSearchFilters(dateTo="1990").isActive); assertTrue(AdvancedSearchFilters(dateFrom="1980",dateTo="1990").isActive) }
 @Test fun blankDateRangeIsInactive() { assertFalse(AdvancedSearchFilters(dateFrom=" ",dateTo="").isActive) }
}

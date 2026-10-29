package com.streamflixreborn.streamflix.providers
import org.junit.Assert.*
import org.junit.Test
class Scudo360OrderingPlatformTest {
 @Test fun nondefaultSortActivatesFiltering() { assertTrue(AdvancedSearchFilters(sort=AdvancedSearchFilters.SortFilter.VOTE).isActive); assertTrue(AdvancedSearchFilters(sort=AdvancedSearchFilters.SortFilter.DATE).isActive) }
 @Test fun defaultSortingIsInactive() { assertFalse(AdvancedSearchFilters(sort=AdvancedSearchFilters.SortFilter.POPULARITY).isActive) }
 @Test fun platformAndPersonIdsActivateFiltering() { assertTrue(AdvancedSearchFilters(platform=AdvancedSearchFilters.PlatformFilter.NETFLIX).isActive); assertTrue(AdvancedSearchFilters(actorId="42").isActive); assertTrue(AdvancedSearchFilters(directorId="47").isActive) }
 @Test fun platformCodeIsPreserved() { assertEquals("8",AdvancedSearchFilters.PlatformFilter.NETFLIX.providerIds) }
}

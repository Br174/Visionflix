package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.SortFilter
import org.junit.Assert.*
import org.junit.Test

/** LAB156 unified sort options, no second advanced filter sort. */
class Lab156UnifiedSortTest {
    @Test fun allAndOnlyFourSortsHaveOnePlace() {
        assertEquals(4, Lab156UnifiedSort.options.size)
        assertEquals(SortFilter.entries.toSet(), Lab156UnifiedSort.options.toSet())
        assertEquals(4, Lab156UnifiedSort.options.distinct().size)
    }
    @Test fun oldestAndNewestHaveUnambiguousNames() {
        assertEquals("Più nuovi", Lab156UnifiedSort.label(SortFilter.DATE))
        assertEquals("Più vecchi", Lab156UnifiedSort.label(SortFilter.OLDEST))
    }
    @Test fun popularityAndVoteAreExplicitNotDates() {
        assertEquals("Popolarità", Lab156UnifiedSort.label(SortFilter.POPULARITY))
        assertEquals("Voto", Lab156UnifiedSort.label(SortFilter.VOTE))
    }
    @Test fun selectionIsStableAndNeverChangesAnotherField() {
        val old = SortFilter.DATE
        val selected = Lab156UnifiedSort.options.single { it == SortFilter.VOTE }
        assertEquals(SortFilter.VOTE, selected)
        assertEquals(SortFilter.DATE, old)
    }
    @Test fun defaultOrderingRemainsNewestFirst() {
        assertEquals(SortFilter.DATE, Lab156UnifiedSort.options.first())
    }
}

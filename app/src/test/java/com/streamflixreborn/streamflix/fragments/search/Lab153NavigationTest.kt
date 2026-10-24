package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.models.Movie
import org.junit.Assert.*
import org.junit.Test

/** LAB153 remote navigation and poster-source preference, deterministic and offline. */
class Lab153NavigationTest {
    @Test fun everySixthPosterCanReachTheRightArrow() {
        for (row in 0 until 20) {
            val last = row * 6 + 5
            assertTrue(SearchRowShortcut.isRowEnd(last, 6, 120))
            assertFalse(SearchRowShortcut.isRowEnd(last - 1, 6, 120))
        }
    }

    @Test fun incompleteLastRowStillHasOneRightArrow() {
        assertEquals(20, SearchRowShortcut.lastIndex(3, 6, 21))
        assertTrue(SearchRowShortcut.isRowEnd(20, 6, 21))
        assertFalse(SearchRowShortcut.isRowEnd(19, 6, 21))
    }

    @Test fun firstAndMiddleRowsAreIndependentOfScrollPosition() {
        assertEquals(5, SearchRowShortcut.lastIndex(0, 6, 185))
        assertEquals(113, SearchRowShortcut.lastIndex(18, 6, 185))
        assertEquals(184, SearchRowShortcut.lastIndex(30, 6, 185))
    }

    @Test fun handlesFiveColumnBrowsingAndShortLastRow() {
        assertEquals(4, SearchRowShortcut.lastIndex(0, 5, 23))
        assertEquals(22, SearchRowShortcut.lastIndex(4, 5, 23))
        assertTrue(SearchRowShortcut.isRowEnd(22, 5, 23))
    }

    @Test fun removedResultsRestoreClosestAvailablePoster() {
        assertEquals(15, SearchRowShortcut.clampToAvailable(187, 16))
        assertEquals(9, SearchRowShortcut.clampToAvailable(9, 16))
    }

    @Test fun emptyAndInvalidRowsDoNotProduceFocusTargets() {
        assertEquals(-1, SearchRowShortcut.lastIndex(0, 6, 0))
        assertEquals(-1, SearchRowShortcut.lastIndex(-1, 6, 20))
        assertEquals(-1, SearchRowShortcut.rowOf(-1, 6))
        assertEquals(-1, SearchRowShortcut.clampToAvailable(5, 0))
    }

    @Test fun preferredProviderWinsAsSourceWithoutAddingDuplicatePosters() {
        val home = Movie(id="h",title="Opera",released="2024-01-01",
            poster="https://img.test/home.jpg").apply { providerName="Casa" }
        val other = Movie(id="o",title="Opera",released="2024-01-01",
            poster="https://img.test/w500/other.jpg").apply { providerName="Altro" }
        val merged = GlobalCatalogMerger.mergeItems(listOf(listOf(home),listOf(other)),
            preferredProviderName="Casa")
        assertEquals(1, merged.posters.size)
        assertEquals(2, merged.alternatives.values.single().size)
        assertEquals("Casa", (merged.posters.single() as Movie).providerName)
    }
}

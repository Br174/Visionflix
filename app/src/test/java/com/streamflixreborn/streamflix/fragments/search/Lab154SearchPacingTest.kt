package com.streamflixreborn.streamflix.fragments.search

import org.junit.Assert.*
import org.junit.Test

/** LAB154: deterministic speed and coverage safeguards, no remote provider calls. */
class Lab154SearchPacingTest {
    @Test fun firstProviderPageDoesNotBlockForThreeCatalogPages() {
        assertEquals(listOf(1), Lab154SearchPacing.providerPages(1).toList())
    }

    @Test fun subsequentProviderPagesExhaustTheCatalogWithoutGapsOrOverlap() {
        val pages = (1..12).flatMap(Lab154SearchPacing::providerPages)
        assertEquals((1..34).toList(), pages)
        assertEquals(pages.size, pages.distinct().size)
    }

    @Test fun metadataDiscoveryStartsWithOnlyTwoPages() {
        assertEquals(listOf(1,2), Lab154SearchPacing.metadataPages(1).toList())
    }

    @Test fun metadataDeepPagesContinueWithoutLosingCoverage() {
        val pages = (1..8).flatMap(Lab154SearchPacing::metadataPages)
        assertEquals((1..30).toList(), pages)
        assertEquals(pages.size, pages.distinct().size)
    }

    @Test fun foregroundProviderHasPriorityOnInitialFilteredPass() {
        assertEquals(24, Lab154SearchPacing.candidateLimit(true, true))
        assertEquals(12, Lab154SearchPacing.candidateLimit(true, false))
        assertTrue(Lab154SearchPacing.candidateLimit(true, true) >
                   Lab154SearchPacing.candidateLimit(true, false))
    }

    @Test fun deepPagesRecoverMoreCandidates() {
        assertEquals(32, Lab154SearchPacing.candidateLimit(false, false))
        assertEquals(32, Lab154SearchPacing.candidateLimit(false, true))
    }

    @Test fun invalidPageNeverFetchesAnySource() {
        assertTrue(Lab154SearchPacing.providerPages(0).isEmpty())
        assertTrue(Lab154SearchPacing.metadataPages(0).isEmpty())
    }

    @Test fun rowNavigationSupportsDeepScrollAfterFilterRefinement() {
        assertEquals(185, SearchRowShortcut.clampToAvailable(187, 186))
        assertEquals(185, SearchRowShortcut.lastIndex(30, 6, 186))
        assertTrue(SearchRowShortcut.isRowEnd(185,6,186))
    }
}

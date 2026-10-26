package com.streamflixreborn.streamflix.fragments.search

import org.junit.Assert.*
import org.junit.Test

class Lab175IndexRotationTest {
    /**
     * Regression example of LAB175's original starvation:
     * sorting all due=0 sources alphabetically selects the same first 18
     * after each successful fetch. New scheduler sets a cooldown on accepted
     * pages and ranks untouched sources ahead of page-two sources.
     */
    @Test fun visitedSourcesMustReleaseBudgetToUnvisitedSources() {
        val allSources = (1..30).map { "Provider_$it" }
        val firstBatch = allSources.sorted().take(18)
        val successfulPages = firstBatch.associateWith { 5L * 60 * 1000 }
        val candidates = allSources.map { it to (successfulPages[it] ?: 0L) }
        val remaining = Lab175AvailabilityPolicy.chooseNext(candidates, now = 1000L, limit = 18)
        assertEquals(12, remaining.size)
        assertFalse(remaining.any { it in firstBatch })
        assertTrue((allSources - firstBatch.toSet()).all { it in remaining })
    }

    @Test fun retriesAreNotConsideredPositiveAvailability() {
        val candidates = listOf("Broken" to 30L * 60 * 1000, "Healthy" to 0L)
        assertEquals(listOf("Healthy"),
            Lab175AvailabilityPolicy.chooseNext(candidates, now = 1000L, limit = 10))
    }
}

package com.streamflixreborn.streamflix.fragments.search

import org.junit.Assert.*
import org.junit.Test

class Lab170NativePagingPolicyTest {
    @Test fun emptyFilteredPageCanBeFollowedByWestern1970To1990() {
        assertTrue(Lab170NativePagingPolicy.shouldContinue(2, 0, 0))
        assertTrue(Lab170NativePagingPolicy.shouldContinue(50, 0, 0))
    }

    @Test fun rawEndOfCatalogueAndRepeatedUnchangingPagesStopScanning() {
        assertTrue(Lab170NativePagingPolicy.shouldContinue(3, 2, 0))
        assertFalse(Lab170NativePagingPolicy.shouldContinue(3, 3, 0))
        assertFalse(Lab170NativePagingPolicy.shouldContinue(3, 0, 2))
        assertFalse(Lab170NativePagingPolicy.shouldContinue(240, 0, 0))
    }

    @Test fun pageFingerprintComesFromUnfilteredProviderItems() {
        val a = Lab170NativePagingPolicy.signature(listOf("m1", "m2"))
        assertNotEquals(a, Lab170NativePagingPolicy.signature(listOf("m3")))
        assertEquals(a, Lab170NativePagingPolicy.signature(listOf("m1", "m2")))
    }
}

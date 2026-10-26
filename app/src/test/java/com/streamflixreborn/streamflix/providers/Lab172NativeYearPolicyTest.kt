package com.streamflixreborn.streamflix.providers

import org.junit.Assert.*
import org.junit.Test

class Lab172NativeYearPolicyTest {
    @Test fun sourcePrintedYearIsPreservedWithoutNetworkQueries() {
        assertEquals("1983", Lab172NativeYearPolicy.fromTitle("Western (1983) [HD]"))
        assertEquals("1980", Lab172NativeYearPolicy.fromTitle("Commedia [HD] (1980)"))
        assertNull(Lab172NativeYearPolicy.fromTitle("Titolo senza anno"))
        assertNull(Lab172NativeYearPolicy.fromTitle("Titolo 1984"))
    }

    @Test fun inclusiveSixYearRangeUsesOneLocalComparison() {
        for (year in 1980..1985) {
            assertTrue(Lab172NativeYearPolicy.matchesRange("${year}-07-15", null, 1980, 1985))
        }
        assertFalse(Lab172NativeYearPolicy.matchesRange("1979-12-31", null, 1980, 1985))
        assertFalse(Lab172NativeYearPolicy.matchesRange("1986-01-01", null, 1980, 1985))
    }

    @Test fun unknownYearIsNotInvented() {
        assertFalse(Lab172NativeYearPolicy.matchesRange(null, null, 1980, 1985))
        assertTrue(Lab172NativeYearPolicy.matchesRange(null, null, null, null))
        assertTrue(Lab172NativeYearPolicy.matchesRange("1985", 1985, null, null))
        assertFalse(Lab172NativeYearPolicy.matchesRange("1984", 1985, null, null))
    }
}

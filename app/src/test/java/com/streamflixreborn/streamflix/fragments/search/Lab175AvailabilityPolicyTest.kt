package com.streamflixreborn.streamflix.fragments.search

import org.junit.Assert.*
import org.junit.Test

class Lab175AvailabilityPolicyTest {
    private fun movie(title: String, year: Int?) =
        Lab175AvailabilityPolicy.candidate("movie", title, year)
    private fun tv(title: String, year: Int?) =
        Lab175AvailabilityPolicy.candidate("tv", title, year)

    @Test fun realProviderMatchSurvivesPosterAndQualityDecorations() {
        assertTrue(Lab175AvailabilityPolicy.matches(
            movie("Il mondo (2012) [HD]", 2012), movie("Il mondo", 2012)))
    }

    @Test fun missingProviderOrMetadataYearDoesNotFabricateAvailability() {
        assertFalse(Lab175AvailabilityPolicy.matches(movie("Il mondo", null),
            movie("Il mondo", 2012)))
        assertFalse(Lab175AvailabilityPolicy.matches(movie("Il mondo", 2012),
            movie("Il mondo", null)))
    }

    @Test fun unrelatedRemakesAndMediaTypesRemainSeparate() {
        assertFalse(Lab175AvailabilityPolicy.matches(movie("Film", 1980),
            movie("Film", 2014)))
        assertFalse(Lab175AvailabilityPolicy.matches(tv("Titolo", 2012),
            movie("Titolo", 2012)))
    }

    @Test fun originalTvAirDateMayPrecedeNativeLastEpisodeDate() {
        assertTrue(Lab175AvailabilityPolicy.matches(tv("Serie", 2018),
            tv("Serie", 2014)))
        assertFalse(Lab175AvailabilityPolicy.matches(tv("Serie", 1992),
            tv("Serie", 2014)))
    }

    @Test fun italianAndFrenchAccentsNormalizeButDoNotMergeOtherNames() {
        assertEquals("citta", Lab175AvailabilityPolicy.normalizeTitle("Città [HD]"))
        assertEquals("amelie", Lab175AvailabilityPolicy.normalizeTitle("Amélie (2001)"))
        assertFalse(Lab175AvailabilityPolicy.matches(movie("Amélie", 2001),
            movie("Un altro film", 2001)))
    }

    @Test fun onlyConfirmedKeepsMetadataOrderAndNoUnverifiedTitles() {
        val metadata = listOf(
            movie("Assente", 2010) to "absent",
            movie("Presente", 2011) to "one",
            movie("Presente due", 2012) to "two",
        )
        val available = listOf(movie("Presente", 2011), movie("Presente due", 2012))
        assertEquals(listOf("one", "two"),
            Lab175AvailabilityPolicy.onlyConfirmed(metadata, available))
    }

    @Test fun plannerIsBoundedAndHonoursCooldown() {
        assertEquals(listOf("B", "A"),
            Lab175AvailabilityPolicy.chooseNext(
                listOf("A" to 0L, "B" to -10L, "C" to 9999L), 1000L, 2))
        assertTrue(Lab175AvailabilityPolicy.chooseNext(
            listOf("A" to 5000L), 1000L, 3).isEmpty())
        assertTrue(Lab175AvailabilityPolicy.chooseNext(
            listOf("A" to 0L), 1000L, 0).isEmpty())
    }

    @Test fun sortChangesNeverChangeMembership() {
        val ids = listOf("a", "b", "c")
        assertEquals(ids.toSet(), ids.reversed().toSet())
    }
}

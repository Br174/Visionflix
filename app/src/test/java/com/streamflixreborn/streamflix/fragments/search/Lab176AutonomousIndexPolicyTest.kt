package com.streamflixreborn.streamflix.fragments.search

import org.junit.Assert.*
import org.junit.Test

class Lab176AutonomousIndexPolicyTest {
    @Test fun overdueSourceWakeIsCooperativeAndNeverBusySpins() {
        assertEquals(1000L, Lab175AvailabilityPolicy.boundedIdleDelayMillis(1000L, 2000L))
        assertEquals(1000L, Lab175AvailabilityPolicy.boundedIdleDelayMillis(2001L, 2000L))
        assertEquals(5000L, Lab175AvailabilityPolicy.boundedIdleDelayMillis(7000L, 2000L))
        assertEquals(15000L, Lab175AvailabilityPolicy.boundedIdleDelayMillis(90000L, 2000L))
        assertEquals(15000L, Lab175AvailabilityPolicy.boundedIdleDelayMillis(null, 2000L))
    }

    @Test fun resumeUsesPersistedDueTimeAndDoesNotResetPages() {
        // Restarted view model sees recorded next-page & due-time, not
        // whatever was most recently searched in the TMDb UI.
        val resumed = listOf("StreamingCommunity|site" to 300_000L,
            "CB01|site" to 0L, "Other|site" to 0L)
        val selected = Lab175AvailabilityPolicy.chooseNext(resumed, 40_000L, 2)
        assertEquals(2, selected.size)
        assertFalse(selected.contains("StreamingCommunity|site"))
        assertEquals(2, selected.distinct().size)
        assertTrue(selected.all { it in resumed.map { it.first } })
    }

    @Test fun realCountersNeverPretendToShowPercentOfUnknownCatalogue() {
        val progress = Lab175AvailabilityPolicy.progressLabel(1250, 8, 20, 30, true)
        assertTrue(progress.contains("1250 titoli"))
        assertTrue(progress.contains("8/20 fonti"))
        assertTrue(progress.contains("30 pagine"))
        assertTrue(progress.contains("aggiornamento in corso"))
        assertFalse(progress.contains("%"))
        assertTrue(Lab175AvailabilityPolicy.progressLabel(1, 1, 2, 1, false)
            .contains("in pausa"))
    }

    @Test fun everyProviderEventuallyBecomesEligibleAfterCooldown() {
        val visited = (1..18).map { "Visited$it" to 300_000L }
        val unvisited = (1..14).map { "Fresh$it" to 0L }
        assertEquals(14, Lab175AvailabilityPolicy.chooseNext(
            visited + unvisited, 1000L, 18).size)
        val later = Lab175AvailabilityPolicy.chooseNext(
            visited + unvisited, 600_000L, 40)
        assertEquals(32, later.size)
    }
}

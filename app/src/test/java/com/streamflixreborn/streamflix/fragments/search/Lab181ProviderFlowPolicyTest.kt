package com.streamflixreborn.streamflix.fragments.search

import org.junit.Assert.*
import org.junit.Test

class Lab181ProviderFlowPolicyTest {
    @Test fun failingSourceIsBoundedAndMetadataHasOwnBudget() {
        assertTrue(Lab181ProviderFlowPolicy.SOURCE_TIMEOUT_MS in 1L..10_000L)
        assertTrue(Lab181ProviderFlowPolicy.METADATA_TIMEOUT_MS >=
            Lab181ProviderFlowPolicy.SOURCE_TIMEOUT_MS)
        assertTrue(Lab181ProviderFlowPolicy.REPAIR_TIMEOUT_MS >
            Lab181ProviderFlowPolicy.SOURCE_TIMEOUT_MS)
    }

    @Test fun recoveryHasCooldownButProviderIsNotRemoved() {
        val now = 30_000_000L
        assertTrue(Lab181ProviderFlowPolicy.allowRecovery(now, null))
        assertFalse(Lab181ProviderFlowPolicy.allowRecovery(now, now - 1_000))
        assertTrue(Lab181ProviderFlowPolicy.allowRecovery(
            now, now - Lab181ProviderFlowPolicy.REPAIR_COOLDOWN_MS))
    }

    @Test fun failedSourceIsSkippedOnlyForCurrentScan() {
        assertTrue(Lab181ProviderFlowPolicy.skipCurrentScan(true, true))
        assertFalse(Lab181ProviderFlowPolicy.skipCurrentScan(false, true))
        assertTrue(Lab181ProviderFlowPolicy.skipCurrentScan(false, false))
    }
}

package com.streamflixreborn.streamflix.fragments.search

import org.junit.Assert.*
import org.junit.Test

/** Pure offline regression checks for HTTP 451 recovery in app-level Cerca. */
class Lab155SearchSafetyTest {
    @Test fun restrictedResponse451NeverQualifiesAsMissingEndpoint() {
        assertTrue(Lab155SearchSafety.isRestricted(451))
        assertFalse(Lab155SearchSafety.canRefreshMissingEndpoint(451, true))
        assertFalse(Lab155SearchSafety.canRefreshMissingEndpoint(451, false))
    }
    @Test fun missingEndpoint404PreservesExistingSingleRecoveryGate() {
        assertTrue(Lab155SearchSafety.canRefreshMissingEndpoint(404, true))
        assertFalse(Lab155SearchSafety.canRefreshMissingEndpoint(404, false))
    }
    @Test fun transientOrUnauthorizedCodesDoNotTriggerEndpointRewrites() {
        listOf(403, 408, 429, 451, 500, 503).forEach { code ->
            assertFalse(Lab155SearchSafety.canRefreshMissingEndpoint(code, true))
        }
    }
    @Test fun otherCodesAreNotMisclassifiedAsLegalRestriction() {
        listOf(200, 403, 404, 408, 429, 500, 503).forEach { code ->
            assertFalse(Lab155SearchSafety.isRestricted(code))
        }
    }
    @Test fun restrictedProviderErrorRemainsReadableWithoutExposingAnUrl() {
        val message = Lab155SearchSafety.explainRestrictedProvider("Provider di prova")
        assertTrue(message.contains("HTTP 451"))
        assertTrue(message.contains("Cerca resta aperta"))
        assertFalse(message.contains("https://"))
    }
}

package com.streamflixreborn.streamflix.providers

import org.junit.Assert.*
import org.junit.Test

class Lab160AdaptiveSourceRulesTest {
    @Test fun permitsPublicHttpsCatalogues() {
        assertTrue(AdaptiveSourceRules.isPublicHttpUrl("https://pastebin.com/raw/KgQ4jTy6"))
        assertTrue(AdaptiveSourceRules.isPublicHttpUrl("https://example.org/films"))
    }

    @Test fun refusesNonWebAndPrivateNetworkEndpoints() {
        listOf(
            "file:///sdcard/secret", "javascript:alert(1)", "http://localhost",
            "http://127.0.0.1:8080/", "http://10.0.0.3/",
            "http://192.168.1.10/", "http://172.18.0.1/",
            "http://169.254.2.1/", "http://[::1]/",
            "http://router.local/", "https://example.invalid/",
            "https://username:password@example.org/secret"
        ).forEach { candidate ->
            assertFalse(candidate, AdaptiveSourceRules.isPublicHttpUrl(candidate))
        }
    }

    @Test fun catalogResultsCannotEscapeTheirOwnHost() {
        assertTrue(AdaptiveSourceRules.matchesSameHost("https://example.org/", "https://example.org/movie/1"))
        assertFalse(AdaptiveSourceRules.matchesSameHost("https://example.org/", "https://other.org/movie/1"))
        assertFalse(AdaptiveSourceRules.matchesSameHost("https://example.org/", "http://localhost:8080/movie/1"))
    }
}

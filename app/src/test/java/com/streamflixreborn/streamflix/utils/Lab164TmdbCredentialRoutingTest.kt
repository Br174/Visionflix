package com.streamflixreborn.streamflix.utils

import org.junit.Assert.*
import org.junit.Test

class Lab164TmdbCredentialRoutingTest {
    @Test fun personalKeyAlwaysWinsOverDummyBuildDefault() {
        assertEquals(Lab164TmdbCredentialRouting.Credential.V3("personal-v3"),
            Lab164TmdbCredentialRouting.resolve(" personal-v3 ", "dummy"))
    }

    @Test fun missingOrDummyKeysAreUnavailable() {
        assertFalse(Lab164TmdbCredentialRouting.hasKey("", "dummy"))
        assertFalse(Lab164TmdbCredentialRouting.hasKey("DUMMY", "dummy"))
        assertEquals(Lab164TmdbCredentialRouting.Credential.Missing,
            Lab164TmdbCredentialRouting.resolve("", ""))
    }

    @Test fun v4ReadAccessTokenUsesBearerAuthorization() {
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.signature"
        assertEquals(Lab164TmdbCredentialRouting.Credential.V4(token),
            Lab164TmdbCredentialRouting.resolve("Bearer $token", "dummy"))
        assertEquals(Lab164TmdbCredentialRouting.Credential.V4(token),
            Lab164TmdbCredentialRouting.resolve(token, "dummy"))
    }

    @Test fun v3KeysRemainV3Credentials() {
        assertEquals(Lab164TmdbCredentialRouting.Credential.V3("abcdef012345"),
            Lab164TmdbCredentialRouting.resolve("abcdef012345", "dummy"))
    }
}

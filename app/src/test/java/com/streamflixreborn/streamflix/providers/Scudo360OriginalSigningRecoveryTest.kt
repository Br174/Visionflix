package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Scudo360OriginalSigningRecoveryTest {
    private fun workflow(): String {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = File(dir, ".github/workflows/build-apks.yml")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: dir
        }
        error("Build workflow unavailable: refusing to certify original-signing recovery")
    }

    @Test fun recoveryKeepsOriginalCacheBeforeAuthorizedSecret() {
        val source = workflow()
        val cache = source.indexOf("key: visionflix-debug-keystore-v1")
        val pin = source.indexOf("- name: Pin signing identity")
        val secret = source.indexOf("secrets.VISIONFLIX_ORIGINAL_KEYSTORE_B64")
        assertTrue("Original cache key must remain", cache >= 0)
        assertTrue("Cache miss must reach secure recovery", source.contains("fail-on-cache-miss: false"))
        assertTrue("Only the approved GitHub Secret may be used", secret > cache && pin > cache)
        assertTrue("Missing original key must block build", source.contains("if [ -z") && source.contains("VISIONFLIX_ORIGINAL_KEYSTORE_B64:-"))
        assertTrue("Secret must be decoded without printing", source.contains("base64 --decode >"))
    }

    @Test fun incompatibleSignatureCannotProduceAnApk() {
        val source = workflow()
        val pinned = "55d9c2dc94f32e6b7685994f2652eec3d8086432e9de94e0645a8bf30f41918e"
        val verify = source.indexOf("sha256sum --check")
        val upload = source.indexOf("- name: Upload temporary build artifacts")
        val build = source.indexOf("- name: Build Android mobile debug APK")
        assertTrue("Original certificate SHA256 must remain pinned", source.contains(pinned))
        assertTrue("Signing certificate must be verified before APK build", verify >= 0 && build > verify)
        assertTrue("Signing certificate must be verified before upload", upload > verify)
        assertTrue("Missing or mismatched signing key must fail closed", source.contains("exit 78"))
        assertTrue("Private keystore must be written with restricted permissions", source.contains("umask 077"))
        assertFalse("No new signing certificate may be generated", source.contains("keytool -genkeypair"))
    }

    @Test fun labBuildCannotPublishMadre() {
        val source = workflow()
        assertTrue("Private MADRE publication must remain stage-gated",
            source.contains("- name: Publish immutable private MADRE release\n        if: steps.stage.outputs.stage == 'MADRE'"))
        assertTrue("Public Downloader publication must remain stage-gated",
            source.contains("- name: Publish immutable Downloader MADRE release\n        if: steps.stage.outputs.stage == 'MADRE'"))
    }
}

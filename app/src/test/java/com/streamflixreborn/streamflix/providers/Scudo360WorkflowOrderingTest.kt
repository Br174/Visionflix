package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Scudo360WorkflowOrderingTest {
    @Test fun gradleIsExecutableBeforeScudoGateAndSigning() {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        var workflow: File? = null
        repeat(6) {
            val candidate = File(dir, ".github/workflows/build-apks.yml")
            if (candidate.isFile) { workflow = candidate; return@repeat }
            dir = dir.parentFile ?: dir
        }
        val text = requireNotNull(workflow) { "Build workflow unavailable; cannot certify Scudo gate" }.readText()
        val step = text.indexOf("- name: Scudo360 native regression gate before any APK")
        val gradle = text.indexOf("chmod +x ./gradlew", step)
        val gate = text.indexOf("node .motorlab/regression/scudo360-gate.mjs", step)
        val signing = text.indexOf("- name: Restore stable debug signing key")
        assertTrue("Missing native regression gate", step >= 0)
        assertTrue("Gradle executable step must precede the native gate", gradle > step && gate > gradle)
        assertTrue("The gate must run before signing", signing > gate)
    }
}

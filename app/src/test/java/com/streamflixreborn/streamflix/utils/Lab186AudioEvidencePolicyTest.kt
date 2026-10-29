package com.streamflixreborn.streamflix.utils

import org.junit.Assert.*
import org.junit.Test

class Lab186AudioEvidencePolicyTest {
    @Test fun subtitleOnlyIsNotItalianDub() {
        assertTrue(Lab186AudioEvidencePolicy.explicitlySubtitleOnly("Film - SUB ITA"))
        assertTrue(Lab186AudioEvidencePolicy.explicitlySubtitleOnly("Film", "SUB ITA"))
        assertTrue(Lab186AudioEvidencePolicy.explicitlySubtitleOnly("Film VOSTFR"))
        assertFalse(Lab186AudioEvidencePolicy.explicitlySubtitleOnly("Italian Job", "HD"))
        assertFalse(Lab186AudioEvidencePolicy.explicitlySubtitleOnly("Film ITA", "HD"))
        assertFalse(Lab186AudioEvidencePolicy.explicitlySubtitleOnly("Film originale italiano", "HD"))
    }
    @Test fun onlyDeclaredAudioTagsCountAndLanguagesMatchExactly() {
        val en = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="English",LANGUAGE="en",DEFAULT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=1250000,AUDIO="a"
        """.trimIndent()
        assertEquals(Lab186AudioEvidencePolicy.Evidence.FOREIGN,
            Lab186AudioEvidencePolicy.fromHlsMaster(en))
        val italian = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,NAME="Italiano",LANGUAGE="it"
            #EXT-X-MEDIA:TYPE=AUDIO,NAME="English",LANGUAGE="en"
            #EXT-X-MEDIA:TYPE=AUDIO,NAME="Italiano",LANGUAGE="ita"
        """.trimIndent()
        assertEquals(Lab186AudioEvidencePolicy.Evidence.ITALIAN,
            Lab186AudioEvidencePolicy.fromHlsMaster(italian))
        assertEquals(Lab186AudioEvidencePolicy.Evidence.UNKNOWN,
            Lab186AudioEvidencePolicy.fromHlsMaster("#EXT-X-MEDIA:TYPE=SUBTITLES,NAME=\"Italiano\",LANGUAGE=\"it\""))
    }
    @Test fun unknownAudioNeverProvesDubbedAndDoesNotForceWrongExclusion() {
        assertEquals(Lab186AudioEvidencePolicy.Evidence.UNKNOWN,
            Lab186AudioEvidencePolicy.classifyAudioLanguages(listOf("und")))
        assertEquals(Lab186AudioEvidencePolicy.Evidence.UNKNOWN,
            Lab186AudioEvidencePolicy.classifyAudioLanguages(listOf("en", null)))
        assertEquals(Lab186AudioEvidencePolicy.Evidence.FOREIGN,
            Lab186AudioEvidencePolicy.classifyAudioLanguages(listOf("eng")))
        assertEquals(Lab186AudioEvidencePolicy.Evidence.ITALIAN,
            Lab186AudioEvidencePolicy.classifyAudioLanguages(listOf("ita", "eng")))
    }
}

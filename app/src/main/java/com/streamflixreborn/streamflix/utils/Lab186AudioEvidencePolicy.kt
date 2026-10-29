package com.streamflixreborn.streamflix.utils

import java.util.Locale

/** Audio evidence never comes from TMDb original language or provider country. */
internal object Lab186AudioEvidencePolicy {
    enum class Evidence { ITALIAN, FOREIGN, UNKNOWN }
    private val itCodes = setOf("it", "ita", "italian", "italiano", "italiana")
    private val unknownCodes = setOf("", "und", "unknown", "none", "mul", "zxx")
    private val attribute = Regex("""(?:^|,)\s*([A-Za-z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^,]*))""")
    private val subtitleOnly = Regex("""(?i)\b(?:sub[\s._-]*ita|subbed|sottotitolat[oaie]|vost(?:fr)?|v\.o\.\s*sub)\b""")

    fun explicitlySubtitleOnly(title: String, quality: String? = null): Boolean =
        subtitleOnly.containsMatchIn(title + " " + quality.orEmpty())

    fun classifyAudioLanguages(codes: List<String?>): Evidence {
        val normalized = codes.mapNotNull {
            it?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-')?.substringBefore('-')
        }.filter { it !in unknownCodes }
        if (normalized.isEmpty()) return Evidence.UNKNOWN
        if (normalized.any { it in itCodes }) return Evidence.ITALIAN
        if (codes.size > normalized.size) return Evidence.UNKNOWN
        return Evidence.FOREIGN
    }

    fun audioLanguagesFromHlsMaster(manifest: String): List<String?> =
        manifest.lineSequence().map { it.trim() }
            .filter { it.startsWith("#EXT-X-MEDIA:", ignoreCase = true) }
            .mapNotNull { line ->
                val attrs = attribute.findAll(line.substringAfter(':'))
                    .associate { m ->
                        m.groupValues[1].uppercase(Locale.ROOT) to
                            m.groupValues[2].ifEmpty {
                                m.groupValues[3].ifEmpty { m.groupValues[4] }
                            }.trim()
                    }
                if (!attrs["TYPE"].equals("AUDIO", ignoreCase = true)) null
                else attrs["LANGUAGE"] ?: attrs["NAME"]?.takeIf {
                    it.lowercase(Locale.ROOT) in itCodes ||
                        it.lowercase(Locale.ROOT) in setOf("english", "french", "spanish", "german")
                } ?: "und"
            }.toList()

    fun fromHlsMaster(manifest: String): Evidence =
        classifyAudioLanguages(audioLanguagesFromHlsMaster(manifest))
}

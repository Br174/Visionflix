package com.streamflixreborn.streamflix.extractors

/** Pure parser: unit-testable without initializing Android extractor registry. */
internal object MaxstreamSourcePolicy {

        /** Accept already exposed video URLs from common Maxstream player source shapes. */
        internal fun sourceFromHtml(html: String): String? {
            val patterns = listOf(
                Regex("""(?is)\bsources\s*:\s*\[\s*\{.{0,320}?["']?(?:src|file)["']?\s*:\s*["']([^"']+)["']"""),
                Regex("""(?is)\bfile\s*:\s*["'](https?:\\?/\\?/[^"']+)["']""")
            )
            for (pattern in patterns) {
                for (match in pattern.findAll(html)) {
                    val raw = match.groupValues[1].replace("\\/", "/").replace("&amp;", "&")
                    val result = when {
                        raw.startsWith("//") -> "https:$raw"
                        raw.startsWith("https://") || raw.startsWith("http://") -> raw
                        else -> continue
                    }
                    return result
                }
            }
            return null
        }
}

package com.streamflixreborn.streamflix.providers

import org.jsoup.nodes.Document

/**
 * Conservative HTML5 media candidate discovery for publicly accessible pages.
 * Direct media is passed to the existing player; embedded pages must be resolved
 * by a supported, already-installed extractor. No iframe navigation or DRM bypass.
 */
object AdaptiveMediaLinks {
    enum class Kind { DIRECT, EMBED }
    data class Candidate(val url: String, val kind: Kind)

    fun fromDocument(doc: Document, limit: Int = 8): List<Candidate> {
        val found = linkedMapOf<String, Candidate>()
        fun include(raw: String, kind: Kind) {
            val url = raw.trim()
            if (!AdaptiveSourceRules.isPublicHttpUrl(url)) return
            found.putIfAbsent(url, Candidate(url, kind))
        }

        doc.select("video[src], video[data-src], video source[src], source[type^=video/]," +
            "meta[property=og:video][content], meta[property=og:video:url][content]," +
            "meta[property=og:video:secure_url][content]").forEach { element ->
            val url = listOf("src", "data-src", "content")
                .firstNotNullOfOrNull { key -> element.absUrl(key).takeIf { it.isNotBlank() } }
            if (url != null) include(url, Kind.DIRECT)
        }

        doc.select("a[href]").forEach { anchor ->
            val url = anchor.absUrl("href")
            if (Regex("(?i)\\.(mp4|m3u8|webm|mpd)([?#].*)?$").containsMatchIn(url)) {
                include(url, Kind.DIRECT)
            }
        }

        doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEach { iframe ->
            val url = listOf("src", "data-src", "data-lazy-src")
                .firstNotNullOfOrNull { key -> iframe.absUrl(key).takeIf { it.isNotBlank() } }
            if (url != null) include(url, Kind.EMBED)
        }
        return found.values.take(limit.coerceIn(1, 12))
    }
}

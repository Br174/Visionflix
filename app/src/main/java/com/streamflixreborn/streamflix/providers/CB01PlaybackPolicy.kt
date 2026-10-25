package com.streamflixreborn.streamflix.providers

import java.net.URI

/** Pure URL safety and intermediary recognition for the CB01 provider. */
internal object CB01PlaybackPolicy {
    fun normalizeLink(raw: String, pageUrl: String): String? = runCatching {
        val value = raw.trim()
        if (value.isEmpty()) return@runCatching null
        val resolved = URI(pageUrl).resolve(value).normalize()
        if (resolved.scheme?.lowercase() !in listOf("http", "https")) return@runCatching null
        if (resolved.host.isNullOrBlank()) return@runCatching null
        resolved.toString()
    }.getOrNull()

    fun isStayOnline(url: String): Boolean =
        host(url).let { it == "stayonline.pro" || it.endsWith(".stayonline.pro") }

    fun isUprot(url: String): Boolean =
        host(url).let { it == "uprot.net" || it.endsWith(".uprot.net") }

    private fun host(url: String): String =
        runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
}

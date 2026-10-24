package com.streamflixreborn.streamflix.providers

import java.net.URI

/**
 * Only publicly routable web sources belong in the adaptive fallback.
 * Reject local endpoints even if a user-supplied source list contains them.
 */
object AdaptiveSourceRules {
    fun isPublicHttpUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.userInfo != null) return false
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return false
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal") ||
            host.endsWith(".test") || host.endsWith(".invalid")) return false
        if (host == "0.0.0.0" || host.startsWith("127.") ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            host.startsWith("169.254.") || host.startsWith("172.") ||
            host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) return false
        if (host.contains(':') || !host.contains('.')) return false
        return true
    }

    fun matchesSameHost(source: String, target: String): Boolean {
        if (!isPublicHttpUrl(source) || !isPublicHttpUrl(target)) return false
        return runCatching { URI(source).host.equals(URI(target).host, ignoreCase = true) }.getOrDefault(false)
    }
}

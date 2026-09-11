package com.streamflixreborn.streamflix.utils

import android.content.Context
import com.streamflixreborn.streamflix.BuildConfig
import org.json.JSONArray

object SourcePreferences {
    enum class SourceType { IPTV, WEB }

    const val DEFAULT_IPTV_SOURCE = "https://iptv-org.github.io/iptv/index.m3u"
    const val DEFAULT_WEB_SOURCE = "https://pastebin.com/raw/KgQ4jTy6"

    private const val PREFS_SUFFIX = ".source_lists"
    private const val KEY_IPTV = "IPTV_SOURCES"
    private const val KEY_WEB = "WEB_SOURCES"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        BuildConfig.APPLICATION_ID + PREFS_SUFFIX,
        Context.MODE_PRIVATE,
    )

    fun get(context: Context, type: SourceType): List<String> {
        val key = if (type == SourceType.IPTV) KEY_IPTV else KEY_WEB
        val defaults = if (type == SourceType.IPTV) {
            listOf(DEFAULT_IPTV_SOURCE)
        } else {
            listOf(DEFAULT_WEB_SOURCE)
        }

        val raw = prefs(context).getString(key, null) ?: return defaults
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    array.optString(i)
                        .trim()
                        .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        ?.let(::add)
                }
            }.distinct()
        }.getOrDefault(defaults)
    }

    fun set(context: Context, type: SourceType, values: List<String>) {
        val key = if (type == SourceType.IPTV) KEY_IPTV else KEY_WEB
        val normalized = values
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
        prefs(context).edit().putString(key, JSONArray(normalized).toString()).apply()
    }

    fun add(context: Context, type: SourceType, value: String): Boolean {
        val normalized = value.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return false
        val current = get(context, type)
        if (current.any { it.equals(normalized, ignoreCase = true) }) return false
        set(context, type, current + normalized)
        return true
    }

    fun remove(context: Context, type: SourceType, value: String) {
        set(context, type, get(context, type).filterNot { it == value })
    }
}

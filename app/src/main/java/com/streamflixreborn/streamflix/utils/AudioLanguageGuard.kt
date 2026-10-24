package com.streamflixreborn.streamflix.utils

import android.util.Base64
import com.streamflixreborn.streamflix.models.Video
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object AudioLanguageGuard {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun requireCompatibleAudio(video: Video, language: String) {
        val lang = language.lowercase().substringBefore('-')
        if (lang.isBlank()) return

        val manifest = readManifest(video) ?: return
        val audioLines = manifest.lineSequence()
            .filter {
                it.startsWith("#EXT-X-MEDIA", ignoreCase = true) &&
                    it.contains("TYPE=AUDIO", ignoreCase = true)
            }
            .toList()

        if (audioLines.isEmpty()) return

        if (audioLines.none { matchesLanguage(it, lang) }) {
            val label = when (lang) {
                "it" -> "italiano"
                "fr" -> "francese"
                "de" -> "tedesco"
                "es" -> "spagnolo"
                "en" -> "inglese"
                else -> lang
            }
            throw Exception("Audio $label non disponibile per questa sorgente")
        }
    }

    private fun matchesLanguage(line: String, lang: String): Boolean {
        val aliases = when (lang) {
            "it" -> listOf("it", "ita", "italian", "italiano")
            "fr" -> listOf("fr", "fra", "fre", "french", "français", "francais")
            "de" -> listOf("de", "deu", "ger", "german", "deutsch")
            "es" -> listOf("es", "spa", "spanish", "español", "espanol", "castellano")
            "en" -> listOf("en", "eng", "english")
            else -> listOf(lang)
        }
        return aliases.any { alias ->
            line.contains("LANGUAGE=\"$alias", ignoreCase = true) ||
                line.contains("NAME=\"$alias", ignoreCase = true) ||
                line.contains(alias, ignoreCase = true)
        }
    }

    private suspend fun readManifest(video: Video): String? = withContext(Dispatchers.IO) {
        val source = video.source
        if (source.startsWith("data:application/vnd.apple.mpegurl;base64,")) {
            return@withContext runCatching {
                String(Base64.decode(source.substringAfter("base64,"), Base64.DEFAULT))
            }.getOrNull()
        }

        if (!(video.type?.contains("mpegurl", ignoreCase = true) == true ||
                source.contains(".m3u8", ignoreCase = true))) return@withContext null

        runCatching {
            val requestBuilder = Request.Builder().url(source)
            video.headers.orEmpty().forEach { (key, value) -> requestBuilder.header(key, value) }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull()
    }
}

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
        // LAB186: declared HLS AUDIO tracks only, never the subtitle language,
        // poster locale or an arbitrary substring such as "BITRATE".
        val audioLanguages = Lab186AudioEvidencePolicy.audioLanguagesFromHlsMaster(manifest)
        if (audioLanguages.isEmpty()) return
        if (lang == "it") {
            when (Lab186AudioEvidencePolicy.classifyAudioLanguages(audioLanguages)) {
                Lab186AudioEvidencePolicy.Evidence.ITALIAN,
                Lab186AudioEvidencePolicy.Evidence.UNKNOWN -> return
                Lab186AudioEvidencePolicy.Evidence.FOREIGN ->
                    throw Exception("Audio italiano assente: disponibile solo audio straniero")
            }
        }
        if (audioLanguages.none { matchesLanguage(it, lang) } &&
            audioLanguages.none { it.isNullOrBlank() || it.equals("und", ignoreCase = true) }) {
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

    private fun matchesLanguage(language: String?, lang: String): Boolean {
        val name = language?.trim()?.lowercase()?.substringBefore('-') ?: return false
        val aliases = when (lang) {
            "fr" -> setOf("fr", "fra", "fre", "french", "français", "francais")
            "de" -> setOf("de", "deu", "ger", "german", "deutsch")
            "es" -> setOf("es", "spa", "spanish", "español", "espanol")
            "en" -> setOf("en", "eng", "english")
            else -> setOf(lang)
        }
        return name in aliases
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

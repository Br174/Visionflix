package com.streamflixreborn.streamflix.providers

import android.net.Uri
import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import java.text.Normalizer
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

object WebSourceFallback {
    private const val TAG = "WebSourceFallback"
    private const val PLACEHOLDER_PREFIX = "visionflix-fallback://"
    private const val CACHE_MS = 15 * 60 * 1000L

    suspend fun searchFallback(
        query: String,
        excludeProviderName: String? = null,
    ): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        val providers = configuredProviders(excludeProviderName)
        if (providers.isEmpty()) return emptyList()

        val providerResults = coroutineScope {
            providers.map { provider ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(9_000L) {
                        runCatching { provider.search(query, 1) }
                            .getOrElse {
                                Log.w(TAG, "Search failed on ${provider.name}: ${it.message}")
                                emptyList()
                            }
                            .mapNotNull { item ->
                                when (item) {
                                    is Movie -> item.apply { providerName = provider.name }
                                    is TvShow -> item.apply { providerName = provider.name }
                                    else -> null
                                }
                            }
                    }.orEmpty()
                }
            }.awaitAll()
        }

        // One visible poster per title/year. The alternative providers remain
        // available to the playback fallback engine and are tried if needed.
        val seen = linkedSetOf<String>()
        return buildList {
            providerResults.flatten().forEach { item ->
                val key = canonicalKey(item)
                if (seen.add(key)) add(item)
            }
        }
    }

    suspend fun fallbackPlaceholders(excludeProviderName: String? = null): List<Video.Server> {
        return configuredProviders(excludeProviderName).map { provider ->
            Video.Server(
                id = PLACEHOLDER_PREFIX + Uri.encode(provider.name),
                name = "Alternativa · ${provider.name}",
            )
        }
    }

    fun isFallbackPlaceholder(server: Video.Server): Boolean =
        server.id.startsWith(PLACEHOLDER_PREFIX)

    suspend fun resolveFallbackVideo(
        placeholder: Video.Server,
        videoType: Video.Type,
    ): Video {
        val providerName = Uri.decode(placeholder.id.removePrefix(PLACEHOLDER_PREFIX))
        val provider = configuredProviders().firstOrNull { it.name == providerName }
            ?: throw Exception("Fonte alternativa non più disponibile: $providerName")

        val match = findBestMatch(provider, videoType)
            ?: throw Exception("${provider.name}: contenuto non trovato")

        val (contentId, adaptedType) = when (videoType) {
            is Video.Type.Movie -> {
                val movie = match as Movie
                movie.id to videoType.copy(
                    id = movie.id,
                    title = movie.title,
                    releaseDate = movie.released?.format("yyyy-MM-dd") ?: videoType.releaseDate,
                    poster = movie.poster ?: videoType.poster,
                    imdbId = movie.imdbId ?: videoType.imdbId,
                )
            }

            is Video.Type.Episode -> {
                val show = match as TvShow
                val details = runCatching { provider.getTvShow(show.id) }.getOrElse { show }
                val season = details.seasons.firstOrNull { it.number == videoType.season.number }
                    ?: throw Exception("${provider.name}: stagione ${videoType.season.number} non trovata")
                val episode = provider.getEpisodesBySeason(season.id)
                    .firstOrNull { it.number == videoType.number }
                    ?: throw Exception("${provider.name}: episodio ${videoType.number} non trovato")

                episode.id to videoType.copy(
                    id = episode.id,
                    title = episode.title ?: videoType.title,
                    poster = episode.poster ?: videoType.poster,
                    overview = episode.overview ?: videoType.overview,
                    tvShow = videoType.tvShow.copy(
                        id = details.id,
                        title = details.title,
                        poster = details.poster ?: videoType.tvShow.poster,
                        banner = details.banner ?: videoType.tvShow.banner,
                        releaseDate = details.released?.format("yyyy-MM-dd") ?: videoType.tvShow.releaseDate,
                        imdbId = details.imdbId ?: videoType.tvShow.imdbId,
                    ),
                    season = videoType.season.copy(title = season.title ?: videoType.season.title),
                )
            }
        }

        val servers = withTimeoutOrNull(12_000L) {
            provider.getServers(contentId, adaptedType)
        }.orEmpty()

        if (servers.isEmpty()) throw Exception("${provider.name}: nessun server disponibile")

        var lastError: Throwable? = null
        for (server in servers) {
            val video = runCatching {
                withTimeoutOrNull(15_000L) { provider.getVideo(server) }
                    ?: throw Exception("Timeout server ${server.name}")
            }.onFailure { lastError = it }.getOrNull()

            if (video != null && video.source.isNotBlank()) {
                Log.i(TAG, "Fallback OK: ${provider.name} / ${server.name}")
                return video
            }
        }

        throw Exception(
            "${provider.name}: tutti i server hanno fallito${lastError?.message?.let { " ($it)" } ?: ""}",
        )
    }

    suspend fun configuredProviders(
        excludeProviderName: String? = null,
        targetLanguage: String? = UserPreferences.currentProvider?.language,
    ): List<Provider> {
        val entries = ProviderEndpointRegistry.refreshIfNeeded(Provider.providers.keys)
        val result = linkedMapOf<String, Provider>()

        entries.forEach { entry ->
            val provider = Provider.providers.keys.firstOrNull { it.name == entry.engineName }
                ?: return@forEach
            if (provider is IptvProvider || provider is TmdbProvider) return@forEach
            if (targetLanguage != null && provider.language != targetLanguage) return@forEach
            if (provider.name == excludeProviderName) return@forEach
            result.putIfAbsent(provider.name, provider)
        }

        return result.values.toList()
    }

    private suspend fun findBestMatch(provider: Provider, videoType: Video.Type): AppAdapter.Item? {
        val targetTitle = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }
        val targetYear = when (videoType) {
            is Video.Type.Movie -> videoType.releaseDate.take(4).toIntOrNull()
            is Video.Type.Episode -> videoType.tvShow.releaseDate?.take(4)?.toIntOrNull()
        }
        val targetNormalized = normalizeTitle(targetTitle)

        val results = withTimeoutOrNull(9_000L) { provider.search(targetTitle, 1) }.orEmpty()
        return results
            .filter {
                when (videoType) {
                    is Video.Type.Movie -> it is Movie
                    is Video.Type.Episode -> it is TvShow
                }
            }
            .map { item ->
                val title = when (item) {
                    is Movie -> item.title
                    is TvShow -> item.title
                    else -> ""
                }
                val year = when (item) {
                    is Movie -> item.released?.get(Calendar.YEAR)
                    is TvShow -> item.released?.get(Calendar.YEAR)
                    else -> null
                }
                val normalized = normalizeTitle(title)
                val score = when {
                    normalized == targetNormalized && targetYear != null && year == targetYear -> 100
                    normalized == targetNormalized -> 90
                    targetYear != null && year == targetYear &&
                        (normalized.contains(targetNormalized) || targetNormalized.contains(normalized)) -> 75
                    normalized.contains(targetNormalized) || targetNormalized.contains(normalized) -> 60
                    else -> 0
                }
                item to score
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun canonicalKey(item: AppAdapter.Item): String = when (item) {
        is Movie -> "movie|${normalizeTitle(item.title)}|${item.released?.get(Calendar.YEAR) ?: ""}"
        is TvShow -> "tv|${normalizeTitle(item.title)}|${item.released?.get(Calendar.YEAR) ?: ""}"
        else -> item.toString()
    }

    private fun normalizeTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")

    private fun hostOf(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val normalized = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
            URI(normalized).host.orEmpty().lowercase().removePrefix("www.")
        }.getOrDefault("")
    }

    private fun domainStem(host: String): String = host
        .removePrefix("www.")
        .substringBefore('.')
        .lowercase()
        .replace("-", "")
}

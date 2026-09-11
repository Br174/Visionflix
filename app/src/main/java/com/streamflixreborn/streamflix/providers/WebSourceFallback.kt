package com.streamflixreborn.streamflix.providers

import android.net.Uri
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.SourcePreferences
import com.streamflixreborn.streamflix.utils.format
import java.net.URI
import java.text.Normalizer
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

object WebSourceFallback {
    private const val TAG = "WebSourceFallback"
    private const val PLACEHOLDER_PREFIX = "visionflix-fallback://"
    private const val CACHE_MS = 15 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var cachedSignature: String = ""
    private var cachedAt: Long = 0L
    private var cachedSites: List<String> = emptyList()

    private val aliasRules = listOf(
        listOf("streamingunity", "streamingcommunity") to "StreamingCommunityProvider",
        listOf("cb01") to "CB01Provider",
        listOf("altadefinizione") to "Altadefinizione01Provider",
        listOf("guardaserie") to "GuardaSerieProvider",
        listOf("animeworld") to "AnimeWorldProvider",
        listOf("animeunity") to "AnimeUnityProvider",
        listOf("animesaturn") to "AnimeSaturnProvider",
        listOf("eurostreaming") to "EurostreamingProvider",
        listOf("guardaplay") to "GuardaPlayProvider",
    )

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

    suspend fun configuredProviders(excludeProviderName: String? = null): List<Provider> {
        val sites = expandedSites()
        val result = linkedMapOf<String, Provider>()

        sites.forEach { site ->
            findProviderForSite(site)?.let { provider ->
                if (provider.name != excludeProviderName && provider !is IptvProvider && provider !is TmdbProvider) {
                    result.putIfAbsent(provider.name, provider)
                }
            }
        }
        return result.values.toList()
    }

    private suspend fun expandedSites(): List<String> = withContext(Dispatchers.IO) {
        val context = StreamFlixApp.instance
        val sources = SourcePreferences.get(context, SourcePreferences.SourceType.WEB)
        val signature = sources.joinToString("\n")
        val now = System.currentTimeMillis()

        if (signature == cachedSignature && now - cachedAt < CACHE_MS) {
            return@withContext cachedSites
        }

        val sites = buildList {
            for (source in sources) {
                val expanded = runCatching { expandSource(source) }
                    .onFailure { Log.w(TAG, "Cannot read $source: ${it.message}") }
                    .getOrDefault(listOf(source))
                addAll(expanded)
            }
        }.map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()

        cachedSignature = signature
        cachedAt = now
        cachedSites = sites
        sites
    }

    private fun expandSource(source: String): List<String> {
        val request = Request.Builder()
            .url(source)
            .header("User-Agent", "Mozilla/5.0 (Android) Visionflix/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return listOf(source)
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return listOf(source)

            val lineUrls = body.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .map { it.substringBefore(' ').trim() }
                .toList()

            if (lineUrls.isNotEmpty() && !body.contains("<html", ignoreCase = true)) {
                return lineUrls
            }

            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.contains("text/plain") || contentType.contains("application/json")) {
                val urls = Regex("https?://[^\\s\\\"'<>]+")
                    .findAll(body)
                    .map { it.value.trimEnd(',', ';', ']') }
                    .toList()
                if (urls.isNotEmpty()) return urls
            }
        }

        return listOf(source)
    }

    private fun findProviderForSite(site: String): Provider? {
        val host = hostOf(site)
        if (host.isBlank()) return null
        val hostCompact = host.replace(".", "").replace("-", "")

        aliasRules.firstOrNull { (tokens, _) -> tokens.any { hostCompact.contains(it) } }
            ?.second
            ?.let { className ->
                Provider.providers.keys.firstOrNull { provider ->
                    provider::class.java.simpleName == className && provider.language.lowercase().startsWith("it")
                }?.let { return it }
                Provider.providers.keys.firstOrNull { it::class.java.simpleName == className }?.let { return it }
            }

        val siteStem = domainStem(host)
        return Provider.providers.keys.firstOrNull { provider ->
            if (provider is IptvProvider || provider is TmdbProvider) return@firstOrNull false
            val providerHost = hostOf(provider.baseUrl)
            providerHost.isNotBlank() && (
                host == providerHost ||
                    domainStem(providerHost) == siteStem
                )
        }
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

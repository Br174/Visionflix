package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.SourcePreferences
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * An isolated, conservative adapter for publicly accessible HTML catalogues.
 * Known providers keep their specialized engines; this provider is for unknown
 * source domains that expose ordinary search pages and HTML5 video sources.
 * No browser emulation, protected-session replay, DRM or anti-bot bypass.
 */
object AdaptiveCinemaProvider : EndpointManagedProvider {
    override val name = "Adaptive Cinema"
    override val language = "it"
    override val builtInBaseUrl = SourcePreferences.DEFAULT_WEB_SOURCE
    override val baseUrl: String
        get() = ProviderEndpointRegistry.effectiveBaseUrl(name, builtInBaseUrl)
    override val logo: String = "https://upload.wikimedia.org/wikipedia/commons/thumb/8/89/Tmdb.new.logo.svg/1280px-Tmdb.new.logo.svg.png"

    private const val MAX_SOURCES = 6
    private const val MAX_CARDS = 24
    private const val FETCH_TIMEOUT = 5_000
    private const val UA = "Mozilla/5.0 (Android; Verlezza Vision) AppleWebKit/537.36"

    private fun publicUrl(raw: String): Boolean = AdaptiveSourceRules.isPublicHttpUrl(raw)


    private suspend fun activeSites(): List<String> {
        val entries = ProviderEndpointRegistry.refreshIfNeeded(Provider.providers.keys)
        val selected = ProviderEndpointRegistry.selectedOverride(name)
        if (selected != null) return listOf(selected).filter(::publicUrl)
        val manual = ProviderEndpointRegistry.manualEndpoint(name)
        if (manual != null) return listOf(manual).filter(::publicUrl)
        return entries.asSequence()
            .filter { it.engineName == name && it.healthy }
            .map { it.url }
            .filter(::publicUrl)
            .distinct()
            .take(MAX_SOURCES).toList()
    }

    private suspend fun page(url: String): Document? = withContext(Dispatchers.IO) {
        if (!publicUrl(url)) return@withContext null
        withTimeoutOrNull(FETCH_TIMEOUT.toLong() + 1_000L) {
            runCatching {
                Jsoup.connect(url).userAgent(UA).timeout(FETCH_TIMEOUT)
                    .followRedirects(false).maxBodySize(512 * 1024).get()
            }.getOrNull()?.takeIf { publicUrl(it.location()) }
        }
    }

    private fun cards(doc: Document): List<Movie> {
        val elements = doc.select("article:has(h2 a[href]), article:has(h3 a[href]), .post:has(h2 a[href]), .movie:has(a[href]), .film:has(a[href])")
        return elements.mapNotNull { element ->
            val a = element.selectFirst("h2 a[href], h3 a[href], a[title][href], a[href]") ?: return@mapNotNull null
            val title = a.attr("title").ifBlank { a.text() }.trim()
            val url = a.absUrl("href")
            if (title.length !in 3..140 || !publicUrl(url)) return@mapNotNull null
            if (!AdaptiveSourceRules.matchesSameHost(doc.location(), url)) return@mapNotNull null
            val poster = element.selectFirst("img")?.let { img ->
                img.absUrl("data-src").ifBlank { img.absUrl("src") }
            }
            Movie(id = url, title = title, poster = poster, providerName = name, providerEndpoint = doc.location())
        }.distinctBy { it.id }.take(MAX_CARDS)
    }

    private suspend fun scan(site: String, query: String?, pageNumber: Int): List<Movie> {
        val candidate = if (query.isNullOrBlank()) {
            if (pageNumber > 1) site.trimEnd('/') + "/page/$pageNumber/" else site
        } else {
            val term = URLEncoder.encode(query, "UTF-8")
            val suffix = if (pageNumber > 1) "/page/$pageNumber/" else "/"
            site.trimEnd('/') + suffix + "?s=$term"
        }
        return page(candidate)?.let(::cards).orEmpty()
    }

    private suspend fun collect(query: String?, pageNumber: Int): List<Movie> = coroutineScope {
        activeSites().map { site ->
            async(Dispatchers.IO) { scan(site, query, pageNumber) }
        }.awaitAll().flatten().distinctBy { it.id }.take(MAX_CARDS)
    }

    override suspend fun getHome(): List<Category> {
        val films = getMovies()
        return if (films.isEmpty()) emptyList() else listOf(Category("Film da sorgenti web compatibili", films))
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return getMovies(page)
        return collect(query, page)
    }

    override suspend fun getMovies(page: Int): List<Movie> = collect(null, page)
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()
    override suspend fun getMovie(id: String): Movie {
        val doc = page(id) ?: throw IllegalArgumentException("Pagina non accessibile")
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() } ?: doc.title()
        val poster = doc.selectFirst("meta[property=og:image]")?.absUrl("content")
        return Movie(id = id, title = title, poster = poster, providerName = name, providerEndpoint = id)
    }
    override suspend fun getTvShow(id: String): TvShow = throw UnsupportedOperationException("Catalogo serie non riconosciuto")
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = emptyList()
    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id, "")
    override suspend fun getPeople(id: String, page: Int): People = People(id, "")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val doc = page(id) ?: return emptyList()
        val candidates = mutableListOf<String>()
        doc.select("video[src], video source[src], source[type^=video/], meta[property=og:video][content]").forEach { el ->
            val url = if (el.tagName() == "meta") el.absUrl("content") else el.absUrl("src")
            if (publicUrl(url)) candidates.add(url)
        }
        return candidates.distinct().take(8).mapIndexed { index, url ->
            Video.Server(id = url, name = "HTML5 ${index + 1}", src = url)
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val source = server.src.ifBlank { server.id }
        require(publicUrl(source)) { "Flusso non accessibile" }
        return Video(source = source)
    }
}

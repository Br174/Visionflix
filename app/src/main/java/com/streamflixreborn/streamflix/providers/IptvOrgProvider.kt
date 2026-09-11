package com.streamflixreborn.streamflix.providers

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.sync.Mutex
import okhttp3.*
import java.net.URI
import java.util.concurrent.TimeUnit

object IptvOrgProvider : IptvProvider, ProviderConfigUrl {

    private const val DEFAULT_PLAYLIST = "https://iptv-org.github.io/iptv/index.m3u"
    private const val MAX_SOURCES = 10

    override val name = "IPTV-All World"
    override val baseUrl = "https://iptv-org.github.io/iptv"
    override val logo = "https://i.ibb.co/W1d0CxF/Logo-IPTV-All-World.jpg"
    override val language = "en"

    override val defaultBaseUrl: String = DEFAULT_PLAYLIST
    override val changeUrlMutex = Mutex()

    private const val TAG = "IptvOrgProvider"

    private val OFFICIAL_CATEGORIES = listOf(
        "Animation", "Auto", "Business", "Classic", "Comedy", "Cooking", "Culture",
        "Documentary", "Education", "Entertainment", "Family", "General", "Interactive",
        "Kids", "Legislative", "Lifestyle", "Movies", "Music", "News", "Outdoor",
        "Public", "Relax", "Religious", "Science", "Series", "Shop", "Sports",
        "Travel", "Weather", "Undefined", "Custom"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(object : CookieJar {
            private val cookieStore = mutableMapOf<String, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: listOf()
            }
        })
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
                )
                .build()
            chain.proceed(request)
        }
        .build()

    private var cachedChannels: List<M3UChannel>? = null
    private var lastFetchTime: Long = 0
    private var lastSourcesSignature: String = ""
    private const val CACHE_DURATION = 30 * 60 * 1000

    data class M3UChannel(
        val name: String,
        val url: String,
        val logo: String?,
        val group: String?,
        val userAgent: String? = null
    )

    /**
     * The generic Provider URL preference is intentionally used as a multi-source box.
     * Users can paste up to ten source URLs, one per line or separated by ';'.
     * If it is empty, the official iptv-org playlist is used.
     */
    private fun configuredSources(): List<String> {
        val raw = UserPreferences
            .getProviderCache(this, UserPreferences.PROVIDER_URL)
            .ifBlank { DEFAULT_PLAYLIST }

        return raw
            .split(Regex("[\\r\\n;]+"))
            .map { it.trim() }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .map(::repairUiTrailingSlash)
            .distinct()
            .take(MAX_SOURCES)
            .ifEmpty { listOf(DEFAULT_PLAYLIST) }
    }

    private fun repairUiTrailingSlash(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.endsWith('/')) return trimmed

        val withoutSlash = trimmed.dropLast(1)
        val lower = withoutSlash.lowercase()
        return when {
            lower.endsWith(".m3u") ||
                lower.endsWith(".m3u8") ||
                lower.endsWith(".mpd") ||
                lower.contains("pastebin.com/raw/") -> withoutSlash
            else -> trimmed
        }
    }

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        cachedChannels = null
        lastFetchTime = 0
        lastSourcesSignature = ""
        return configuredSources().joinToString("\n")
    }

    private fun createId(channel: M3UChannel): String {
        val rawId = "${channel.url}|${channel.name}|${channel.logo ?: ""}|${channel.userAgent ?: ""}"
        return Base64.encodeToString(rawId.toByteArray(), Base64.NO_WRAP)
    }

    private fun decodeId(id: String): Triple<String, String, String> {
        if (id == "creador-info" || id == "apoyo-nando") return Triple(id, "", "")

        return try {
            val decoded = String(Base64.decode(id, Base64.DEFAULT))
            val parts = decoded.split("|")
            Triple(parts[0], parts[1], parts.getOrNull(2) ?: "")
        } catch (e: Exception) {
            Triple(id, "Canal Desconocido", "")
        }
    }

    private fun getUAFromId(id: String): String? {
        return try {
            val decoded = String(Base64.decode(id, Base64.DEFAULT))
            val parts = decoded.split("|")
            if (parts.size >= 4 && parts[3].isNotEmpty()) parts[3] else null
        } catch (e: Exception) {
            null
        }
    }

    private fun getAllChannels(): List<M3UChannel> {
        val sources = configuredSources()
        val signature = sources.joinToString("|")
        val now = System.currentTimeMillis()

        if (
            cachedChannels != null &&
            signature == lastSourcesSignature &&
            (now - lastFetchTime) < CACHE_DURATION
        ) {
            return cachedChannels!!
        }

        val merged = mutableListOf<M3UChannel>()
        for ((index, source) in sources.withIndex()) {
            try {
                val channels = loadSource(source, sourceNumber = index + 1)
                Log.d(TAG, "Source ${index + 1}: ${channels.size} channels from $source")
                merged += channels
            } catch (e: Exception) {
                Log.w(TAG, "Source ${index + 1} ignored: ${e.message}")
            }
        }

        val channels = merged
            .filter { it.url.startsWith("http://", true) || it.url.startsWith("https://", true) }
            .distinctBy { "${it.name.lowercase()}|${it.url}" }

        cachedChannels = channels
        lastFetchTime = now
        lastSourcesSignature = signature
        return channels
    }

    private fun loadSource(source: String, sourceNumber: Int): List<M3UChannel> {
        if (isDirectStreamUrl(source)) {
            return listOf(
                M3UChannel(
                    name = deriveChannelName(source, sourceNumber),
                    url = source,
                    logo = null,
                    group = "Custom"
                )
            )
        }

        val body = fetchText(source) ?: return emptyList()

        // Standard IPTV playlist.
        if (body.contains("#EXTINF", ignoreCase = true)) {
            return parseM3U(body)
        }

        // A raw text source (Pastebin/Gist/etc.) may itself contain playlist or stream URLs.
        // We intentionally ignore normal website URLs: only explicit IPTV/media links are accepted.
        val result = mutableListOf<M3UChannel>()
        val candidateUrls = body.lineSequence()
            .map { it.trim().trim('"', '\'', ',', '[', ']') }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            .distinct()
            .take(200)
            .toList()

        for (candidate in candidateUrls) {
            when {
                isDirectStreamUrl(candidate) -> {
                    result += M3UChannel(
                        name = deriveChannelName(candidate, sourceNumber),
                        url = candidate,
                        logo = null,
                        group = "Custom"
                    )
                }

                isPlaylistUrl(candidate) -> {
                    val nestedBody = fetchText(candidate) ?: continue
                    if (nestedBody.contains("#EXTINF", ignoreCase = true)) {
                        result += parseM3U(nestedBody)
                    }
                }
            }
        }

        return result
    }

    private fun fetchText(url: String): String? {
        val candidates = listOf(url, repairUiTrailingSlash(url)).distinct()
        for (candidate in candidates) {
            try {
                val request = Request.Builder().url(candidate).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return response.body?.string()
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Fetch failed for $candidate: ${e.message}")
            }
        }
        return null
    }

    private fun isPlaylistUrl(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return clean.endsWith(".m3u") || clean.endsWith(".m3u.txt")
    }

    private fun isDirectStreamUrl(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return clean.endsWith(".m3u8") || clean.endsWith(".mpd") || clean.endsWith(".ts")
    }

    private fun deriveChannelName(url: String, sourceNumber: Int): String {
        return runCatching {
            val uri = URI(url)
            val file = uri.path.substringAfterLast('/').substringBeforeLast('.').takeIf { it.isNotBlank() }
            file ?: uri.host ?: "Canale sorgente $sourceNumber"
        }.getOrDefault("Canale sorgente $sourceNumber")
    }

    override suspend fun getHome(): List<Category> {
        val channels = getAllChannels()
        val homeGroups = listOf("Animation", "Comedy", "Series", "Entertainment", "News", "Movies", "Sports", "Custom")
        val categories = mutableListOf<Category>()

        val channelCategories = channels
            .filter { it.group != null && homeGroups.any { target -> it.group!!.contains(target, ignoreCase = true) } }
            .groupBy { channel ->
                homeGroups.find { channel.group!!.contains(it, ignoreCase = true) } ?: "Otros"
            }
            .map { (groupName, channelList) ->
                Category(
                    name = groupName,
                    list = channelList.distinctBy { it.name }.take(50).map { channel ->
                        TvShow(
                            id = createId(channel),
                            title = channel.name,
                            poster = channel.logo ?: "",
                            banner = channel.logo ?: ""
                        )
                    }
                )
            }.sortedBy { it.name }

        categories.addAll(channelCategories)

        categories.add(
            Category(
                name = "Soporte y Ayuda",
                list = listOf(
                    getInfoItem("creador-info"),
                    getInfoItem("apoyo-nando")
                )
            )
        )

        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val allChannels = getAllChannels()
        val results = mutableListOf<AppAdapter.Item>()

        OFFICIAL_CATEGORIES.filter { it.contains(query, ignoreCase = true) }.sorted().forEach {
            results.add(Genre(id = it, name = "📂 Categoría: $it", shows = emptyList()))
        }

        val channelResults = allChannels.filter {
            it.name.contains(query, ignoreCase = true)
        }.distinctBy { it.name }.take(100).map { channel ->
            TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "")
        }

        results.addAll(channelResults)
        return results
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val groupChannels = getAllChannels().filter {
            it.group?.equals(id, ignoreCase = true) ?: false
        }.distinctBy { it.name }

        val pageSize = 40
        val start = (page - 1) * pageSize
        val pagedList = groupChannels.drop(start).take(pageSize).map { channel ->
            TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "")
        }

        return Genre(id = id, name = id, shows = pagedList)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        TODO("Not yet implemented")
    }

    override suspend fun getTvShow(id: String): TvShow {
        if (id == "creador-info" || id == "apoyo-nando") return getInfoItem(id)

        val (_, name, logo) = decodeId(id)
        return TvShow(
            id = id,
            title = name,
            poster = logo,
            banner = logo,
            overview = "Canal: $name\nFonte: IPTV configurabile",
            seasons = listOf(Season(id = id, number = 1, title = "En Vivo"))
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        if (seasonId == "creador-info" || seasonId == "apoyo-nando") return emptyList()
        return listOf(Episode(id = seasonId, number = 1, title = "Ver Señal en Directo", season = null))
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (id == "creador-info" || id == "apoyo-nando") return emptyList()
        return listOf(Video.Server(id = id, name = "IPTV Direct"))
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val (url, _, _) = decodeId(server.id)
        val customUA = getUAFromId(server.id)
        Log.d(TAG, "Play: $url | UA: $customUA")
        return Video(source = url, subtitles = emptyList())
    }

    private fun getInfoItem(id: String): TvShow {
        val isReport = id == "creador-info"
        return TvShow(
            id = id,
            title = if (isReport) "Reportar problemas" else "Apoya al Proveedor",
            poster = if (isReport) "https://i.ibb.co/dsknGBHT/Imagen-de-Whats-App-2025-09-06-a-las-19-00-50-e8e5bcaa.jpg" else "https://i.ibb.co/B5gKLkqS/nuevo-formato-2-K-202604112205.jpg",
            banner = if (isReport) "https://i.ibb.co/dsknGBHT/Imagen-de-Whats-App-2025-09-06-a-las-19-00-50-e8e5bcaa.jpg" else "https://i.ibb.co/B5gKLkqS/nuevo-formato-2-K-202604112205.jpg",
            overview = if (isReport) {
                "Si algún canal no funciona o encuentras errores en el proveedor, por favor repórtalo en nuestro grupo oficial de Telegram."
            } else {
                "Si te gusta nuestro contenido y quieres ayudarnos a mantener los servidores activos, puedes realizar una donación voluntaria. ¡Gracias por tu apoyo!"
            },
            seasons = emptyList()
        )
    }

    private fun parseM3U(m3uRaw: String): List<M3UChannel> {
        val channels = mutableListOf<M3UChannel>()
        val lines = m3uRaw.lines()
        var curName = ""
        var curLogo = ""
        var curGroup = ""
        var curUA: String? = null

        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("#EXTINF")) {
                curName = t.substringAfterLast(",").trim()
                curLogo = Regex("""tvg-logo="([^"]+)"""").find(t)?.groupValues?.get(1) ?: ""
                curGroup = Regex("""group-title="([^"]+)"""").find(t)?.groupValues?.get(1) ?: ""
                curUA = Regex("""http-user-agent="([^"]+)"""").find(t)?.groupValues?.get(1)
            } else if (t.startsWith("#EXTVLCOPT:http-user-agent=")) {
                curUA = t.substringAfter("http-user-agent=").trim()
            } else if (t.startsWith("http")) {
                if (curName.isNotEmpty()) {
                    channels.add(M3UChannel(curName, t, curLogo, curGroup.ifBlank { "Custom" }, curUA))
                    curName = ""
                    curLogo = ""
                    curGroup = ""
                    curUA = null
                }
            }
        }
        return channels
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getMovie(id: String): Movie = Movie(id = id, title = "Live", poster = "")

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val channels = getAllChannels()
        val pageSize = 50
        val start = (page - 1) * pageSize
        if (start >= channels.size) return emptyList()
        return channels.drop(start).take(pageSize).map { channel ->
            TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "")
        }
    }
}

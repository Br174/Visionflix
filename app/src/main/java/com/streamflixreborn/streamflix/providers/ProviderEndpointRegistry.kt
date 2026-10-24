package com.streamflixreborn.streamflix.providers

import android.content.Context
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.utils.SourcePreferences
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object ProviderEndpointRegistry {
    data class RemoteEntry(
        val displayName: String,
        val engineName: String,
        val url: String,
        val logo: String? = null,
        val language: String = "it",
        val enabled: Boolean = true,
        val healthy: Boolean = false,
    )

    private const val PREFS_SUFFIX = ".provider_endpoint_registry"
    private const val KEY_REMOTE = "remote_entries"
    private const val KEY_REFRESH_AT = "refresh_at"
    private const val REFRESH_TTL_MS = 10 * 60 * 1000L

    private val aliases = linkedMapOf(
        "streamingunity" to "StreamingCommunity",
        "streamingcommunity" to "StreamingCommunity",
        "cb01" to "CB01",
        "altadefinizione" to "Altadefinizione01",
        "guardaserie" to "GuardaSerie",
        "guardaplay" to "GuardaFlix",
        "guardaflix" to "GuardaFlix",
        "animeworld" to "AnimeWorld",
        "animeunity" to "AnimeUnity",
        "animesaturn" to "AnimeSaturn",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val refreshMutex = Mutex()

    private fun prefs() = StreamFlixApp.instance.getSharedPreferences(
        BuildConfig.APPLICATION_ID + PREFS_SUFFIX,
        Context.MODE_PRIVATE,
    )

    private fun key(prefix: String, providerName: String) =
        prefix + "_" + providerName.lowercase().replace(Regex("[^a-z0-9]+"), "_")

    fun effectiveBaseUrl(providerName: String, builtInBaseUrl: String): String =
        runCatching {
            val p = prefs()
            p.getString(key("selected", providerName), null)
                ?.takeIf { it.isNotBlank() }
                ?: p.getString(key("auto", providerName), null)
                    ?.takeIf { it.isNotBlank() }
                ?: p.getString(key("lastgood", providerName), null)
                    ?.takeIf { it.isNotBlank() }
                ?: builtInBaseUrl
        }.getOrDefault(builtInBaseUrl).trimEnd('/')

    fun selectOriginal(providerName: String) {
        runCatching { prefs().edit().remove(key("selected", providerName)).apply() }
    }

    fun selectedOverride(providerName: String): String? =
        runCatching { prefs().getString(key("selected", providerName), null) }.getOrNull()

    fun restoreSelection(providerName: String, endpoint: String?) {
        if (endpoint.isNullOrBlank()) selectOriginal(providerName) else selectRemote(providerName, endpoint)
    }

    fun selectRemote(providerName: String, url: String) {
        val normalized = normalizeUrl(url) ?: return
        runCatching {
            prefs().edit().putString(key("selected", providerName), normalized).apply()
        }
    }

    fun invalidateRemoteCache() {
        runCatching {
            prefs().edit().putLong(KEY_REFRESH_AT, 0L).apply()
        }
    }

    fun cachedRemoteEntries(language: String? = null): List<RemoteEntry> {
        val raw = runCatching { prefs().getString(KEY_REMOTE, null) }.getOrNull() ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val entry = RemoteEntry(
                        displayName = o.optString("displayName"),
                        engineName = o.optString("engineName"),
                        url = o.optString("url"),
                        logo = o.optString("logo").takeIf { it.isNotBlank() },
                        language = o.optString("language", "it"),
                        enabled = o.optBoolean("enabled", true),
                        healthy = o.optBoolean("healthy", false),
                    )
                    if (entry.enabled && (language == null || entry.language == language)) add(entry)
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun refreshIfNeeded(
        providers: Collection<Provider>,
        force: Boolean = false,
    ): List<RemoteEntry> = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastRefresh = runCatching { prefs().getLong(KEY_REFRESH_AT, 0L) }.getOrDefault(0L)
        if (!force && now - lastRefresh < REFRESH_TTL_MS) {
            val cached = cachedRemoteEntries()
            if (cached.isNotEmpty()) return@withContext cached
        }

        val sourceUrls = SourcePreferences.get(StreamFlixApp.instance, SourcePreferences.SourceType.WEB)
        val rawEntries = sourceUrls.flatMap { source ->
            runCatching { readRemoteSource(source) }.getOrDefault(emptyList())
        }

        val providerByName = providers.associateBy { it.name }
        val parsed = rawEntries
            .mapNotNull(::parseEntry)
            .mapNotNull { entry ->
                val engine = providerByName[entry.engineName] ?: return@mapNotNull null
                if (!engine.language.lowercase().startsWith(entry.language.lowercase())) return@mapNotNull null
                entry
            }
            .distinctBy { it.engineName.lowercase() + "|" + it.url.lowercase() }

        val checked = coroutineScope {
            parsed.map { entry ->
                async {
                    val probe = probe(entry.url)
                    entry.copy(url = probe.second ?: entry.url, healthy = probe.first)
                }
            }.awaitAll()
        }

        // One-provider updates must not discard every other remote source entry.
        val retained = if (providers.size == 1) {
            cachedRemoteEntries().filterNot { existing ->
                providers.any { it.name == existing.engineName }
            }
        } else emptyList()
        persistRemoteEntries(retained + checked)
        refreshManagedEndpoints(providers, checked)
        runCatching { prefs().edit().putLong(KEY_REFRESH_AT, now).apply() }
        checked.filter { it.enabled }
        }
    }

    private suspend fun refreshManagedEndpoints(
        providers: Collection<Provider>,
        remoteEntries: List<RemoteEntry>,
    ) = coroutineScope {
        providers.filterIsInstance<EndpointManagedProvider>().map { provider ->
            async(Dispatchers.IO) {
                val p = prefs()
                val autoKey = key("auto", provider.name)
                val lastGoodKey = key("lastgood", provider.name)
                val currentAuto = p.getString(autoKey, null)
                val candidates = buildList {
                    remoteEntries
                        .filter { it.engineName == provider.name && it.healthy }
                        .forEach { add(it.url) }
                    currentAuto?.takeIf { it.isNotBlank() }?.let(::add)
                    add(provider.builtInBaseUrl)
                }.mapNotNull(::normalizeUrl).distinct()

                var chosen: String? = null
                for (candidate in candidates) {
                    val probe = probe(candidate)
                    if (probe.first) {
                        chosen = probe.second ?: candidate
                        break
                    }
                }

                if (chosen != null) {
                    p.edit()
                        .putString(autoKey, chosen)
                        .putString(lastGoodKey, chosen)
                        .apply()
                    provider.onEndpointSelectionChanged()
                } else {
                    // A previous URL is NOT automatically healthy after this check.
                    // Preserve it only as history, never silently route to an unverified 404.
                    p.edit().remove(autoKey).apply()
                    provider.onEndpointSelectionChanged()
                }
            }
        }.awaitAll()
        Unit
    }

    data class RefreshResult(
        val providerName: String,
        val success: Boolean,
        val message: String,
    )

    /** Manual refresh is scoped to a single provider; a reachable homepage is not proof. */
    suspend fun refreshOne(
        provider: Provider,
        refreshRegistry: Boolean = true,
        requestedEndpoint: String? = null,
    ): RefreshResult = withContext(Dispatchers.IO) {
        val managed = provider as? EndpointManagedProvider
        val originalSelection = selectedOverride(provider.name)
        try {
        // Check the exact remote source the user requested, not a different engine homepage.
        if (!requestedEndpoint.isNullOrBlank()) {
            if (!probe(requestedEndpoint).first) return@withContext RefreshResult(
                provider.name, false, "Fonte selezionata non raggiungibile"
            )
        }
        val configured = selectedOverride(provider.name)
        if (configured != null && !probe(configured).first) {
            selectOriginal(provider.name)
            managed?.onEndpointSelectionChanged()
        }
        if (managed != null && refreshRegistry) refreshIfNeeded(listOf(provider), force = true)
        if (!requestedEndpoint.isNullOrBlank()) {
            selectRemote(provider.name, requestedEndpoint)
            managed?.onEndpointSelectionChanged()
        }
        if (requestedEndpoint.isNullOrBlank() && provider is ProviderConfigUrl) {
            runCatching { provider.onChangeUrl(forceRefresh = true) }
        }
        val url = provider.baseUrl
        if (!probe(url).first) {
            return@withContext RefreshResult(provider.name, false, "Indirizzo non raggiungibile; nessun endpoint verificato")
        }
        val functional = runCatching {
            // A real search tests the provider engine, not just its HTTP homepage.
            withTimeoutOrNull(15_000) { provider.search("Roma", 1).isNotEmpty() } == true
        }.getOrDefault(false)
        if (functional) {
            RefreshResult(provider.name, true, "Ricerca di prova riuscita")
        } else {
            RefreshResult(provider.name, false, "Sito raggiungibile ma ricerca non verificata: nessun risultato o timeout")
        }
        } finally {
            if (!requestedEndpoint.isNullOrBlank()) {
                restoreSelection(provider.name, originalSelection)
                managed?.onEndpointSelectionChanged()
            }
        }
    }

    /** A single registry refresh, then bounded tests to avoid provider floods. */
    suspend fun refreshAll(providers: Collection<Provider>): List<RefreshResult> = coroutineScope {
        refreshIfNeeded(providers, force = true)
        val gate = Semaphore(3)
        providers.map { provider ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    withTimeoutOrNull(15_000) { refreshOne(provider, refreshRegistry = false) }
                        ?: RefreshResult(provider.name, false, "Timeout di verifica (15s)")
                }
            }
        }.awaitAll()
    }

    private fun persistRemoteEntries(entries: List<RemoteEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("displayName", entry.displayName)
                put("engineName", entry.engineName)
                put("url", entry.url)
                put("logo", entry.logo ?: "")
                put("language", entry.language)
                put("enabled", entry.enabled)
                put("healthy", entry.healthy)
            })
        }
        prefs().edit().putString(KEY_REMOTE, array.toString()).apply()
    }

    private fun readRemoteSource(source: String): List<String> {
        val fetchSource = normalizeSourceUrl(source)
        val request = Request.Builder()
            .url(fetchSource)
            .header("User-Agent", "Mozilla/5.0 (Android) VerlezzaVision/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return listOf(source)
            val body = response.body?.string().orEmpty().trim()
            if (body.isBlank()) return emptyList()

            if (body.startsWith("[")) {
                val array = runCatching { JSONArray(body) }.getOrNull()
                if (array != null) {
                    return buildList {
                        for (i in 0 until array.length()) {
                            when (val value = array.opt(i)) {
                                is JSONObject -> add(value.toString())
                                is String -> add(value)
                            }
                        }
                    }
                }
            }

            return body.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .toList()
        }
    }

    private fun parseEntry(raw: String): RemoteEntry? {
        val trimmed = raw.trim()

        if (trimmed.startsWith("{")) {
            val o = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            if (!o.optBoolean("enabled", true)) return null
            val url = normalizeUrl(o.optString("url")) ?: return null
            val language = o.optString("language", "it").ifBlank { "it" }
            val explicitEngine = o.optString("engine").ifBlank { o.optString("provider") }
            val engine = resolveEngine(url, explicitEngine) ?: return null
            return RemoteEntry(
                displayName = o.optString("name").ifBlank { "$engine · ${hostOf(url)}" },
                engineName = engine,
                url = url,
                logo = o.optString("logo").takeIf { it.isNotBlank() },
                language = language,
                enabled = true,
            )
        }

        if ('|' in trimmed) {
            val parts = trimmed.split('|').map { it.trim() }
            val name = parts.getOrNull(0).orEmpty()
            val url = normalizeUrl(parts.getOrNull(1).orEmpty()) ?: return null
            val language = parts.getOrNull(2).orEmpty().ifBlank { "it" }
            val explicitEngine = parts.getOrNull(3).orEmpty()
            val logo = parts.getOrNull(4)?.takeIf { it.startsWith("http") }
            val enabled = parts.getOrNull(5)?.lowercase()?.let { it !in setOf("off", "false", "0") } ?: true
            if (!enabled) return null
            val engine = resolveEngine(url, explicitEngine) ?: return null
            return RemoteEntry(
                displayName = name.ifBlank { "$engine · ${hostOf(url)}" },
                engineName = engine,
                url = url,
                logo = logo,
                language = language,
                enabled = true,
            )
        }

        val url = normalizeUrl(trimmed) ?: return null
        val engine = resolveEngine(url, "") ?: return null
        return RemoteEntry(
            displayName = "$engine · ${hostOf(url)}",
            engineName = engine,
            url = url,
            language = "it",
        )
    }

    private fun resolveEngine(url: String, explicitEngine: String): String? {
        if (explicitEngine.isNotBlank()) {
            return aliases.entries.firstOrNull {
                explicitEngine.contains(it.key, ignoreCase = true) ||
                    explicitEngine.equals(it.value, ignoreCase = true)
            }?.value ?: explicitEngine
        }
        val compact = hostOf(url).replace(".", "").replace("-", "")
        return aliases.entries.firstOrNull { compact.contains(it.key) }?.value
    }

    private fun probe(url: String): Pair<Boolean, String?> {
        val normalized = normalizeUrl(url) ?: return false to null
        return runCatching {
            val request = Request.Builder()
                .url(normalized)
                .header("User-Agent", "Mozilla/5.0 (Android) VerlezzaVision/1.0")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val content = if (response.code in 200..299) {
                    runCatching { response.peekBody(1024).string().lowercase() }.getOrDefault("")
                } else ""
                val blocked = "just a moment" in content || "checking your browser" in content ||
                    "access denied" in content || "captcha" in content
                val alive = response.code in 200..299 && !blocked
                val finalUrl = response.request.url.let {
                    val defaultPort = when (it.scheme.lowercase()) {
                        "http" -> 80
                        "https" -> 443
                        else -> it.port
                    }
                    "${it.scheme}://${it.host}" + if (it.port != defaultPort) ":${it.port}" else ""
                }
                alive to finalUrl
            }
        }.getOrDefault(false to null)
    }

    private fun normalizeSourceUrl(raw: String): String {
        val value = raw.trim()
        return runCatching {
            val uri = URI(value)
            if (uri.host.equals("pastebin.com", ignoreCase = true) && !uri.path.startsWith("/raw/")) {
                val id = uri.path.trim('/').substringBefore('/')
                if (id.isNotBlank()) "https://pastebin.com/raw/$id" else value
            } else {
                value
            }
        }.getOrDefault(value)
    }

    private fun normalizeUrl(raw: String): String? {
        val value = raw.trim()
        if (!value.startsWith("http://") && !value.startsWith("https://")) return null
        return runCatching {
            val uri = URI(value)
            if (uri.host.isNullOrBlank()) null else value.trimEnd('/')
        }.getOrNull()
    }

    private fun hostOf(raw: String): String =
        runCatching { URI(raw).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")
}

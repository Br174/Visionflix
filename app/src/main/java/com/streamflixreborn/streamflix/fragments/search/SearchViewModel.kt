package com.streamflixreborn.streamflix.fragments.search

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters
import com.streamflixreborn.streamflix.providers.Lab172NativeYearPolicy
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.EndpointManagedProvider
import com.streamflixreborn.streamflix.providers.ProviderConfigUrl
import com.streamflixreborn.streamflix.providers.ProviderEndpointRegistry
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.ParentalControlUtils
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.Locale

sealed class State {
    data object Searching : State()
    data object SearchingMore : State()
    data class SuccessSearching(val results: List<AppAdapter.Item>, val hasMore: Boolean) : State()
    data class FailedSearching(val error: Exception) : State()
    data object GlobalSearching : State()
    data class SuccessGlobalSearching(
        val providerResults: List<ProviderResult>,
        val hasMore: Boolean = true,
        val catalog: GlobalCatalogMerger.Catalog = GlobalCatalogMerger.merge(providerResults),
        val indexPartial: Boolean = false,
    ) : State()
}

data class ProviderResult(
    val provider: Provider,
    val state: State,
    val displayName: String = provider.name,
) {
    sealed class State {
        data object Loading : State()
        data class Success(val results: List<AppAdapter.Item>) : State()
        data class Error(val error: Exception) : State()
    }
}

private data class GlobalSearchTarget(
    val provider: Provider,
    val displayName: String,
    val endpoint: String?,
)

class SearchViewModel(database: AppDatabase, availabilityContext: Context? = null) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.SuccessSearching(emptyList(), hasMore = false))
    private val _actorSuggestions = MutableStateFlow<List<People>>(emptyList())
    val actorSuggestions: Flow<List<People>> = _actorSuggestions
    private val _directorSuggestions = MutableStateFlow<List<People>>(emptyList())
    val directorSuggestions: Flow<List<People>> = _directorSuggestions

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessSearching -> {
                    val movies = state.results.filterIsInstance<Movie>()
                    if (movies.isEmpty()) emit(emptyList())
                    else emitAll(database.movieDao().getByIds(movies.map { it.id }))
                }
                else -> emit(emptyList<Movie>())
            }
        },
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessSearching -> {
                    val tvShows = state.results.filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) emit(emptyList())
                    else emitAll(database.tvShowDao().getByIds(tvShows.map { it.id }))
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, moviesDb, tvShowsDb ->
        when (state) {
            is State.SuccessSearching -> {
                val moviesById = moviesDb.associateBy { it.id }
                val tvShowsById = tvShowsDb.associateBy { it.id }
                State.SuccessSearching(
                    results = state.results.map { item ->
                        when (item) {
                            is Movie -> moviesById[item.id]
                                ?.takeIf { !item.isSame(it) }
                                ?.let { item.copy().merge(it) }
                                ?: item
                            is TvShow -> tvShowsById[item.id]
                                ?.takeIf { !item.isSame(it) }
                                ?.let { item.copy().merge(it) }
                                ?: item
                            else -> item
                        }
                    },
                    hasMore = state.hasMore,
                )
            }
            else -> state
        }
    }.flowOn(Dispatchers.IO)

    var query = ""
    var advancedFilters = AdvancedSearchFilters()
        private set

    var selectedActorId: String? = null
        private set
    var selectedActorName: String = ""
        private set
    var selectedDirectorId: String? = null
        private set
    var selectedDirectorName: String = ""
        private set

    private var page = 1
    private var localLoadingMore = false
    private val nativeGenreCache = mutableMapOf<String, List<Genre>>()
    private val nativeGenreCacheMutex = Mutex()
    private var usingWebFallback = false
    private var globalTargets: List<GlobalSearchTarget> = emptyList()
    private var globalPage = 1
    private var globalLoadingMore = false
    private var globalEmptyPages = 0
    private var activeGlobalGeneration = 0
    // LAB181: one recoverable background repair per provider/cooldown, never
    // part of the wall's latency-critical search job.
    private val globalRecoveryMutex = Mutex()
    private val lastGlobalRecoveryAt = mutableMapOf<String, Long>()
    private fun scheduleGlobalRecovery(provider: Provider) {
        if (provider !is EndpointManagedProvider && provider !is ProviderConfigUrl) return
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val mayRetry = globalRecoveryMutex.withLock {
                val last = lastGlobalRecoveryAt[provider.name]
                if (!Lab181ProviderFlowPolicy.allowRecovery(now, last)) false
                else {
                    lastGlobalRecoveryAt[provider.name] = now
                    true
                }
            }
            if (!mayRetry) return@launch
            try {
                val refreshed = withTimeoutOrNull(Lab181ProviderFlowPolicy.REPAIR_TIMEOUT_MS) {
                    ProviderEndpointRegistry.refreshOne(provider)
                }
                if (refreshed?.success == true)
                    Log.i("SearchViewModel", "LAB181 refreshed URL for ${provider.name}")
                else
                    Log.w("SearchViewModel", "LAB181 recovery pending for ${provider.name}")
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w("SearchViewModel", "LAB181 recovery failed for ${provider.name}", error)
            }
        }
    }
    // LAB175: independent, persistent catalogue for Android TV. Mobile keeps
    // the historical search path until TV end-to-end acceptance is verified.
    private val availabilityIndex = availabilityContext?.let { Lab175AvailabilityIndex(it) }
    private var availabilityMaintenance: Job? = null
    private var maintenanceGeneration = 0

    data class AvailabilityProgress(
        val indexedTitles: Int = 0,
        val providersStarted: Int = 0,
        val providerTotal: Int = 0,
        val pagesIndexed: Int = 0,
        val running: Boolean = false,
    ) {
        val description: String get() = Lab175AvailabilityPolicy.progressLabel(
            indexedTitles, providersStarted, providerTotal, pagesIndexed, running
        )
    }

    private val _availabilityProgress = MutableStateFlow(AvailabilityProgress())
    val availabilityProgress: Flow<AvailabilityProgress> = _availabilityProgress
    private var activeIndexedSearchGeneration = 0
    private var indexedGlobalEnabled = false
    private var indexedMetadataReady = false
    private var indexedPage = 1
    private var indexedMetadata = listOf<AppAdapter.Item>()
    private var indexedLanguage = "it"
    private val INDEXED_MAX_TMDB_PAGES = 12
    private val INDEXED_AUTO_DISCOVERY_LIMIT = 9
    private val INDEXED_VISIBLE_TARGET = 18

    /**
     * LAB176: autonomous while the TV Search screen is visible, cooperative
     * and bounded, never triggered by Cerca/Sort/tapping a film. Page
     * checkpoints and cooldowns survive app restarts. No paid workers.
     */
    fun startAvailabilityMaintenance(language: String) {
        val index = availabilityIndex ?: return
        if (availabilityMaintenance?.isActive == true) return
        val generation = ++maintenanceGeneration
        availabilityMaintenance = viewModelScope.launch(Dispatchers.IO) {
            val providers = Provider.providers.keys.filter {
                it.language == language && it !is IptvProvider && it !is TmdbProvider
            }.associateBy { it.name }
            val keys = providers.map { (name, engine) ->
                Lab175AvailabilityIndex.SourceKey(
                    name,
                    (engine as? EndpointManagedProvider)?.baseUrl.orEmpty(),
                )
            }
            suspend fun report(running: Boolean) {
                val statistics = index.statistics(keys, System.currentTimeMillis())
                if (generation == maintenanceGeneration) {
                    _availabilityProgress.value = AvailabilityProgress(
                        statistics.indexedTitles, statistics.providersStarted,
                        statistics.providerTotal, statistics.pagesIndexed, running,
                    )
                }
            }
            try {
                report(true)
                while (isActive && generation == maintenanceGeneration) {
                    // Exactly two native catalogue pages per batch, never a
                    // wall-sized burst of dozens of simultaneous requests.
                    val now = System.currentTimeMillis()
                    val planned = index.chooseSources(keys, now, 2)
                    if (planned.isEmpty()) {
                        report(true)
                        delay(index.nextWakeDelay(keys, now))
                        continue
                    }
                    coroutineScope {
                        planned.map { (key, pageToLoad) -> async {
                            val engine = providers[key.name] ?: return@async
                            try {
                                val cards = withTimeoutOrNull(8_000L) {
                                    fetchAvailabilityPage(engine, pageToLoad)
                                }
                                if (cards == null) {
                                    index.markFailure(key, pageToLoad, System.currentTimeMillis())
                                } else {
                                    index.recordPage(key, pageToLoad, cards, System.currentTimeMillis())
                                }
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                throw cancel
                            } catch (error: Exception) {
                                Log.w("SearchViewModel", "LAB176 provider catalogue " + key.name, error)
                                index.markFailure(key, pageToLoad, System.currentTimeMillis())
                            }
                        } }.awaitAll()
                    }
                    report(true)
                    if (indexedGlobalEnabled && indexedMetadataReady) refreshIndexedWall()
                    // Always yield the main app time and network bandwidth.
                    delay(1_500L)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } finally {
                if (generation == maintenanceGeneration) report(false)
            }
        }
    }

    fun stopAvailabilityMaintenance() {
        ++maintenanceGeneration
        availabilityMaintenance?.cancel()
        availabilityMaintenance = null
        _availabilityProgress.value = _availabilityProgress.value.copy(running = false)
    }

    /** A provider failure is not a valid empty page. Let the caller retry. */
    private suspend fun fetchAvailabilityPage(
        provider: Provider, page: Int,
    ): List<AppAdapter.Item> = coroutineScope {
        val movies = if (Provider.supportsMovies(provider)) async { provider.getMovies(page) } else null
        val series = if (Provider.supportsTvShows(provider)) async { provider.getTvShows(page) } else null
        val combined = mutableListOf<AppAdapter.Item>()
        movies?.await()?.let { combined.addAll(it) }
        series?.await()?.let { combined.addAll(it) }
        combined.distinctBy { it.searchIdentityKey() }
    }

    /** TMDb filters the universe; the offline index proves provider presence. */
    private suspend fun searchIndexedGlobal(
        searchText: String, language: String, filters: AdvancedSearchFilters,
    ) {
        val index = availabilityIndex ?: return
        val generation = ++activeIndexedSearchGeneration
        indexedGlobalEnabled = true
        indexedMetadataReady = false
        indexedLanguage = language
        indexedPage = 1
        indexedMetadata = emptyList()
        advancedFilters = normalizeFilters(filters)
        query = searchText
        _state.emit(State.GlobalSearching)
        if (!tmdbConfigured()) {
            _state.emit(State.FailedSearching(IllegalStateException(
                "Ricerca globale: configura una chiave TMDb gratuita valida nelle Impostazioni."
            )))
            return
        }
        try {
            val results = withTimeoutOrNull(15000L) {
                searchTmdbAdvanced(
                    TmdbProvider(language), searchText, advancedFilters, 1,
                    candidateSelection = false,
                    sourceTitleAliases = true,
                )
            } ?: throw IllegalStateException(
                "TheMovieDB non risponde entro il limite previsto; nessuna scansione infinita dei provider."
            )
            if (generation != activeIndexedSearchGeneration || !indexedGlobalEnabled) return
            indexedMetadata = results
            indexedMetadataReady = true
            refreshIndexedWall()
            // LAB177: one matched poster is NOT a complete search. Continue
            // through further filtered TMDb windows in a bounded background
            // expansion, progressively displaying verified provider posters.
            // Never contact native providers as part of these searches.
            withTimeoutOrNull(20_000L) {
                while (generation == activeIndexedSearchGeneration) {
                    val wall = _state.value as? State.SuccessGlobalSearching
                        ?: break
                    if (!Lab177FilteredWallPolicy.shouldExpand(
                            count = wall.catalog.posters.size,
                            currentPage = indexedPage,
                            hasMore = wall.hasMore,
                            minimumPosters = INDEXED_VISIBLE_TARGET,
                            maximumAutoPage = INDEXED_AUTO_DISCOVERY_LIMIT,
                        )) break
                    val priorPage = indexedPage
                    loadMoreIndexedGlobal()
                    if (indexedPage <= priorPage) break
                }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (generation == activeIndexedSearchGeneration && indexedGlobalEnabled) {
                _state.emit(State.FailedSearching(error))
            }
        }
    }

    /** No provider HTTP here; SQLite lookup and list sorting only. */
    private fun refreshIndexedWall() {
        val index = availabilityIndex ?: return
        if (!indexedGlobalEnabled || !indexedMetadataReady) return
        val matching = index.findMatches(indexedMetadata)
        val sorted = applyProviderFilters(matching, advancedFilters, trustMetadata = true)
        val catalog = GlobalCatalogMerger.mergeItems(listOf(sorted))
        // Preserve TMDb discovery order for popularity, but never invent
        // provider IDs. Other sorts are applied to actual source-backed items.
        val posters = applyProviderFilters(catalog.posters, advancedFilters, trustMetadata = true)
        val groups = matching.groupBy { when (it) {
            is Movie -> it.providerName.orEmpty()
            is TvShow -> it.providerName.orEmpty()
            else -> ""
        } }.mapNotNull { (name, list) ->
            Provider.findByName(name)?.let { provider ->
                ProviderResult(provider, ProviderResult.State.Success(list),
                    "Indicizzato · " + name)
            }
        }
        val prior = _state.value as? State.SuccessGlobalSearching
        if (prior != null && prior.catalog.posters.map { it.searchIdentityKey() } ==
            posters.map { it.searchIdentityKey() } && prior.hasMore ==
            (indexedPage < INDEXED_MAX_TMDB_PAGES)) return
        _state.value = State.SuccessGlobalSearching(
            groups, hasMore = indexedPage < INDEXED_MAX_TMDB_PAGES,
            catalog = catalog.copy(posters = posters),
            indexPartial = true,
        )
    }

    private suspend fun loadMoreIndexedGlobal() {
        if (!indexedGlobalEnabled || indexedPage >= INDEXED_MAX_TMDB_PAGES) return
        val next = indexedPage + 1
        val generation = activeIndexedSearchGeneration
        val prior = _state.value
        try {
            val extra = searchTmdbAdvanced(
                TmdbProvider(indexedLanguage), query, advancedFilters, next,
                candidateSelection = false,
                sourceTitleAliases = true,
            )
            if (generation != activeIndexedSearchGeneration || !indexedGlobalEnabled) return
            indexedPage = next
            indexedMetadata = (indexedMetadata + extra)
                .distinctBy(::metadataAliasKey)
            refreshIndexedWall()
            if (extra.isEmpty()) (_state.value as? State.SuccessGlobalSearching)
                ?.let { _state.value = it.copy(hasMore = false) }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w("SearchViewModel", "LAB175 metadata next page", error)
            if (prior is State.SuccessGlobalSearching) {
                _state.value = prior.copy(hasMore = false)
            }
        }
    }

    /** Sorting already-displayed items must not start a provider scan. */
    fun reorderVisibleResults(sort: AdvancedSearchFilters.SortFilter): Boolean {
        advancedFilters = advancedFilters.copy(sort = sort)
        val existing = _state.value
        return when (existing) {
            is State.SuccessGlobalSearching -> {
                if (indexedGlobalEnabled) refreshIndexedWall()
                else _state.value = globalWallState(existing.providerResults, existing.hasMore)
                true
            }
            is State.SuccessSearching -> {
                _state.value = existing.copy(
                    results = applyProviderFilters(existing.results,
                        advancedFilters.copy(
                            dateFrom = null, dateTo = null, year = null,
                            genre = AdvancedSearchFilters.GenreFilter.ALL,
                            genres = emptySet(),
                        ), trustMetadata = true)
                )
                true
            }
            else -> false
        }
    }
    // Independent Streamflix page-exhaustion bookkeeping per provider endpoint.
    private val globalEmptyRawByTarget = mutableMapOf<Int, Int>()
    private val globalRepeatedRawByTarget = mutableMapOf<Int, Int>()
    private val globalLastRawByTarget = mutableMapOf<Int, String>()
    private val globalFinishedTargets = mutableSetOf<Int>()

    private fun globalWallState(results: List<ProviderResult>, hasMore: Boolean = true): State.SuccessGlobalSearching {
        val previous = (_state.value as? State.SuccessGlobalSearching)?.catalog
        val catalog = GlobalCatalogMerger.merge(results, previous,
            preferredProviderName = UserPreferences.currentProvider?.name)
        val ordered = when (advancedFilters.sort) {
            AdvancedSearchFilters.SortFilter.DATE -> catalog.posters.sortedByDescending {
                when (it) {
                    is Movie -> it.released?.timeInMillis ?: Long.MIN_VALUE
                    is TvShow -> it.released?.timeInMillis ?: Long.MIN_VALUE
                    else -> Long.MIN_VALUE
                }
            }
            AdvancedSearchFilters.SortFilter.OLDEST -> catalog.posters.sortedBy {
                when (it) {
                    is Movie -> it.released?.timeInMillis ?: Long.MAX_VALUE
                    is TvShow -> it.released?.timeInMillis ?: Long.MAX_VALUE
                    else -> Long.MAX_VALUE
                }
            }
            AdvancedSearchFilters.SortFilter.VOTE -> catalog.posters.sortedByDescending {
                when (it) {
                    is Movie -> it.rating ?: Double.NEGATIVE_INFINITY
                    is TvShow -> it.rating ?: Double.NEGATIVE_INFINITY
                    else -> Double.NEGATIVE_INFINITY
                }
            }
            AdvancedSearchFilters.SortFilter.POPULARITY -> catalog.posters
        }
        return State.SuccessGlobalSearching(results, hasMore, catalog.copy(posters = ordered))
    }

    fun search(query: String) = viewModelScope.launch(Dispatchers.IO) {
        advancedFilters = AdvancedSearchFilters()
        selectedActorId = null
        selectedActorName = ""
        selectedDirectorId = null
        selectedDirectorName = ""
        _actorSuggestions.value = emptyList()
        _directorSuggestions.value = emptyList()
        executeSearch(query, advancedFilters, 1)
    }

    fun searchAdvanced(query: String, filters: AdvancedSearchFilters) =
        viewModelScope.launch(Dispatchers.IO) {
            advancedFilters = normalizeFilters(filters)
            selectedActorId = advancedFilters.actorId
            selectedActorName = advancedFilters.actorName
            selectedDirectorId = advancedFilters.directorId
            selectedDirectorName = advancedFilters.directorName
            _actorSuggestions.value = emptyList()
            _directorSuggestions.value = emptyList()
            executeSearch(query, advancedFilters, 1)
        }

    fun selectActorFilter(actorId: String, actorName: String) {
        selectedActorId = actorId
        selectedActorName = actorName
        _actorSuggestions.value = emptyList()
    }

    fun clearActorSelection() {
        selectedActorId = null
        selectedActorName = ""
        _actorSuggestions.value = emptyList()
    }

    fun clearActorSuggestions() {
        _actorSuggestions.value = emptyList()
    }

    fun selectDirectorFilter(directorId: String, directorName: String) {
        selectedDirectorId = directorId
        selectedDirectorName = directorName
        _directorSuggestions.value = emptyList()
    }

    fun clearDirectorSelection() {
        selectedDirectorId = null
        selectedDirectorName = ""
        _directorSuggestions.value = emptyList()
    }

    fun clearDirectorSuggestions() {
        _directorSuggestions.value = emptyList()
    }

    fun searchActors(actorQuery: String) = viewModelScope.launch(Dispatchers.IO) {
        _actorSuggestions.value = searchPeopleSuggestions(actorQuery)
    }

    fun searchDirectors(directorQuery: String) = viewModelScope.launch(Dispatchers.IO) {
        _directorSuggestions.value = searchPeopleSuggestions(directorQuery)
    }

    private suspend fun searchPeopleSuggestions(personQuery: String): List<People> {
        val cleanQuery = personQuery.trim()
        if (cleanQuery.length < 2) return emptyList()

        return try {
            val language = UserPreferences.currentProvider?.language
                ?.takeIf { it.isNotBlank() }
                ?: "it"
            val people = TMDb3.Search.multi(
                query = cleanQuery,
                page = 1,
                language = language,
            ).results
                .filterIsInstance<TMDb3.Person>()
                .sortedByDescending { it.popularity }
                .distinctBy { it.id }
                .take(12)

            coroutineScope {
                people.mapIndexed { index, person ->
                    async {
                        val image = person.profilePath?.w500 ?: if (index < 3) runCatching {
                            TMDb3.People.details(
                                personId = person.id,
                                language = language,
                            ).profilePath?.w500
                        }.getOrNull() else null
                        People(
                            id = person.id.toString(),
                            name = person.name,
                            image = image,
                        )
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            Log.e("SearchViewModel", "searchPeopleSuggestions: ", e)
            emptyList()
        }
    }

    fun searchByActor(actorId: String, actorName: String) =
        searchAdvanced(
            query,
            advancedFilters.copy(actorId = actorId, actorName = actorName),
        )

    private fun tmdbConfigured(): Boolean =
        com.streamflixreborn.streamflix.utils.Lab164TmdbCredentialRouting
            .hasKey(UserPreferences.tmdbApiKey, BuildConfig.TMDB_API_KEY)

    private suspend fun browseProvider(
        provider: Provider,
        page: Int,
        type: AdvancedSearchFilters.ContentType = AdvancedSearchFilters.ContentType.ALL,
    ): List<AppAdapter.Item> = coroutineScope {
        // LAB168: Streamflix's direct provider page, avoiding unrequested media types.
        val movies: kotlinx.coroutines.Deferred<List<Movie>>? =
            if (type == AdvancedSearchFilters.ContentType.TV) null
            else async { runCatching { provider.getMovies(page) }.getOrDefault(emptyList()) }
        val series: kotlinx.coroutines.Deferred<List<TvShow>>? =
            if (type == AdvancedSearchFilters.ContentType.MOVIE) null
            else async { runCatching { provider.getTvShows(page) }.getOrDefault(emptyList()) }
        val results = mutableListOf<AppAdapter.Item>()
        movies?.await()?.let { results.addAll(it) }
        series?.await()?.let { results.addAll(it) }
        results.distinctBy { it.searchIdentityKey() }
    }

    private suspend fun searchProviderWithRecovery(provider: Provider, text: String, page: Int, inlineRecovery: Boolean = true): List<AppAdapter.Item> {
        return try {
            provider.search(text, page)
        } catch (error: retrofit2.HttpException) {
            Log.w("SearchViewModel", "Provider=${provider.name}, search HTTP=${error.code()}, page=$page")
            if (Lab155SearchSafety.isRestricted(error.code())) {
                throw IllegalStateException(
                    Lab155SearchSafety.explainRestrictedProvider(provider.name), error
                )
            }
            // Global wall lookups must never run the expensive URL discovery
            // synchronously. Recovery is scheduled after the source group finishes.
            if (!inlineRecovery || !Lab155SearchSafety.canRefreshMissingEndpoint(
                    error.code(), provider is EndpointManagedProvider)) throw error
            val recovery = ProviderEndpointRegistry.refreshOne(provider)
            if (!recovery.success) throw IllegalStateException(
                "${provider.name}: HTTP 404, aggiornamento non riuscito (${recovery.message})", error
            )
            provider.search(text, page) // exactly one retry; never spin
        }
    }

    private suspend fun executeSearch(
        query: String,
        filters: AdvancedSearchFilters,
        targetPage: Int,
    ) {
        ++activeIndexedSearchGeneration
        indexedGlobalEnabled = false
        indexedMetadataReady = false
        _state.emit(State.Searching)
        localLoadingMore = false
        try {
            val provider = UserPreferences.currentProvider
                ?: throw IllegalStateException("Seleziona prima un provider")
            usingWebFallback = false
            nativeGenreCacheMutex.withLock { nativeGenreCache.clear() }
            if (provider is TmdbProvider && !tmdbConfigured()) {
                throw IllegalStateException("TMDb richiede una chiave API gratuita valida nelle Impostazioni. La chiave 'dummy' non funziona.")
            }
            val results = when {
                provider is TmdbProvider && (filters.isActive || query.isNotBlank()) ->
                    searchTmdbAdvanced(provider, query, filters, targetPage)
                filters.isActive -> searchProviderAdvanced(provider, query, filters, targetPage)
                query.isBlank() -> browseProvider(provider, targetPage)
                else -> searchProviderWithRecovery(provider, query, targetPage)
            }
            this@SearchViewModel.query = query
            page = targetPage
            val filtered = ParentalControlUtils.filterItems(results)
            val hasMore = !usingWebFallback &&
                Lab164SearchMatchPolicy.canLoadLocalMore(filtered.isNotEmpty(), filters.isActive, targetPage)
            _state.emit(State.SuccessSearching(filtered, hasMore = hasMore))
            // No items -> RecyclerView never binds a last poster, so its scroll
            // listener cannot request the next page. Continue on the same native
            // source automatically until matching posters or source exhaustion.
            if (filtered.isEmpty() && filters.isActive && hasMore) loadMore()
        } catch (e: Exception) {
            Log.e("SearchViewModel", "executeSearch", e)
            _state.emit(State.FailedSearching(e))
        }
    }


    /**
     * LAB171 regression fix: source listing cards often contain only title +
     * poster, and no item.genres. Streamflix's built-in genre navigation uses
     * provider.search("", 1) -> Genre -> provider.getGenre(id, page).
     * Use THAT native source route instead of discarding every poster.
     */
    private suspend fun nativeGenrePage(
        provider: Provider,
        filters: AdvancedSearchFilters,
        requestedPage: Int,
    ): List<AppAdapter.Item>? {
        if (filters.effectiveGenres.isEmpty() || provider is TmdbProvider) return null
        val cacheKey = provider.name + "|" + (ProviderEndpointRegistry.selectedOverride(provider.name) ?: "")
        val choices = nativeGenreCacheMutex.withLock {
            nativeGenreCache[cacheKey] ?: run {
                val entries = try {
                    provider.search("", 1).filterIsInstance<Genre>()
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.w("SearchViewModel", "Unable to list native genres for ${provider.name}", error)
                    emptyList()
                }
                val available = if (entries.isNotEmpty()) entries else try {
                    provider.getHome().flatMap { category ->
                        category.list.filterIsInstance<Genre>()
                    }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.w("SearchViewModel", "Native home genres unavailable for ${provider.name}", error)
                    emptyList()
                }
                nativeGenreCache[cacheKey] = available
                available
            }
        }
        val ids = Lab171NativeGenrePolicy.matchingNativeIds(choices, filters.effectiveGenres)
        if (ids.isEmpty()) return null
        return coroutineScope {
            val permits = Semaphore(3)
            ids.map { id -> async(Dispatchers.IO) {
                permits.withPermit {
                    try {
                        provider.getGenre(id, requestedPage).shows.filterIsInstance<AppAdapter.Item>()
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        Log.w("SearchViewModel", "Native genre page ${provider.name} ${id} / ${requestedPage}", error)
                        emptyList()
                    }
                }
            } }.awaitAll().flatten().distinctBy { it.searchIdentityKey() }
        }
    }

    private suspend fun searchProviderAdvanced(
        provider: Provider,
        query: String,
        filters: AdvancedSearchFilters,
        page: Int,
        sharedMetadata: List<AppAdapter.Item>? = null,
        candidateLimit: Int = 24,
        providedPage: List<AppAdapter.Item>? = null,
        inlineRecovery: Boolean = true,
    ): List<AppAdapter.Item> {
        // LAB170: exactly one native Streamflix source page, for EVERY page.
        // Do not search individually for dozens of TMDb candidate titles.
        val nativeGenre = if (providedPage == null && query.isBlank())
            nativeGenrePage(provider, filters, page) else null
        val raw = providedPage ?: nativeGenre
            ?: if (query.isBlank()) browseProvider(provider, page, filters.type)
            else searchProviderWithRecovery(provider, query, page, inlineRecovery)
        val effectiveFilters = if (nativeGenre != null) filters.copy(
            genre = AdvancedSearchFilters.GenreFilter.ALL,
            genres = emptySet(),
        ) else filters
        // A thin provider poster is not proof of a non-matching genre/year.
        // Local facts first, one batched TMDb catalogue lookup ONLY when a
        // requested filter cannot be evaluated from available source fields.
        if (raw.isEmpty()) return emptyList()
        val local = applyProviderFilters(raw, effectiveFilters)
        val hasYearFilter = filters.year != null ||
            !filters.dateFrom.isNullOrBlank() || !filters.dateTo.isNullOrBlank()
        val missingGenre = effectiveFilters.effectiveGenres.isNotEmpty() && raw.any { item ->
            val entries = when (item) {
                is Movie -> item.genres
                is TvShow -> item.genres
                else -> emptyList()
            }
            entries.isEmpty()
        }
        val missingYear = hasYearFilter && raw.any { item ->
            val released = when (item) {
                is Movie -> item.released
                is TvShow -> item.released
                else -> null
            }
            val title = when (item) {
                is Movie -> item.title
                is TvShow -> item.title
                else -> ""
            }
            released == null && Lab172NativeYearPolicy.fromTitle(title) == null
        }
        val strictMetadata = Lab174FilteredSourcePolicy.requiresStrictMetadata(filters)
        if (!strictMetadata && !missingGenre && !missingYear) return local
        if (!tmdbConfigured()) {
            if (!strictMetadata && local.isNotEmpty()) return local
            throw IllegalStateException(
                "Il provider non fornisce tutti i metadati per i filtri selezionati. " +
                "Per verificarli serve una chiave TMDb gratuita nelle Impostazioni, " +
                "oppure una categoria nativa del provider."
            )
        }
        val approved = sharedMetadata ?: searchTmdbAdvanced(
            TmdbProvider(provider.language),
            query, filters, page,
            candidateSelection = !Lab173TvCombinedFilterPolicy.needsBroaderCountryWindow(filters, query) &&
                !missingGenre && !missingYear
        )
        if (approved.isEmpty()) {
            if (!strictMetadata && local.isNotEmpty()) return local
            return emptyList()
        }
        val keys = approved.mapNotNull(::filterMatchKey).toSet()
        val metadataVerified = applyProviderFilters(raw, effectiveFilters, trustMetadata = true)
            .filter { item -> filterMatchKey(item)?.let { key ->
                Lab164SearchMatchPolicy.matchesMetadata(key, keys)
            } == true }
        // Strict country/language/platform must never be inferred locally.
        // For genre/year, locally proven matches remain visible immediately.
        return if (strictMetadata) metadataVerified
            else (local + metadataVerified).distinctBy { it.searchIdentityKey() }
    }

    private fun filterMatchKey(item: AppAdapter.Item): String? {
        // Type is part of the identity: a TV title must never inherit a movie-only genre.
        val (type, title, year) = when (item) {
            is Movie -> Triple("movie", item.title, item.released?.get(java.util.Calendar.YEAR))
            is TvShow -> Triple("tv", item.title, item.released?.get(java.util.Calendar.YEAR))
            else -> return null
        }
        return "${type}|${normalizeFilterTitle(title)}|${year ?: ""}"
    }

    private fun itemMatchesMetadata(
        item: AppAdapter.Item,
        metadataKeys: Set<String>,
    ): Boolean {
        val key = filterMatchKey(item) ?: return false
        if (key in metadataKeys) return true
        return Lab164SearchMatchPolicy.matchesMetadata(key, metadataKeys)
    }

    private fun normalizeFilterTitle(value: String): String =
        java.text.Normalizer.normalize(
            Lab174FilteredSourcePolicy.cleanProviderTitle(value),
            java.text.Normalizer.Form.NFD,
        )
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "")

    private fun applyProviderFilters(
        results: List<AppAdapter.Item>,
        filters: AdvancedSearchFilters,
        trustMetadata: Boolean = false,
    ): List<AppAdapter.Item> {
        val filtered = results.filter { item ->
            val typeOk = when (filters.type) {
                AdvancedSearchFilters.ContentType.ALL -> true
                AdvancedSearchFilters.ContentType.MOVIE -> item is Movie
                AdvancedSearchFilters.ContentType.TV -> item is TvShow
                AdvancedSearchFilters.ContentType.CARTOON -> {
                    val genres = when (item) {
                        is Movie -> item.genres
                        is TvShow -> item.genres
                        else -> emptyList()
                    }
                    trustMetadata || genres.any { Lab164SearchMatchPolicy.localGenreMatches(it.name, AdvancedSearchFilters.GenreFilter.ANIMATION) }
                }
            }
            if (!typeOk) return@filter false

            val date = when (item) {
                is Movie -> item.released?.format("yyyy-MM-dd")
                is TvShow -> item.released?.format("yyyy-MM-dd")
                else -> null
            }
            // LAB172: use native year metadata or the year already printed on
            // the card title. Do not start remote per-title verification.
            val nativeDate = date ?: Lab172NativeYearPolicy.fromTitle(
                when (item) {
                    is Movie -> item.title
                    is TvShow -> item.title
                    else -> ""
                }
            )
            if (!trustMetadata && !matchesYearFilter(nativeDate, filters)) return@filter false

            if (filters.effectiveGenres.isNotEmpty() && !trustMetadata) {
                val genres = when (item) {
                    is Movie -> item.genres
                    is TvShow -> item.genres
                    else -> emptyList()
                }
                if (genres.isEmpty() || genres.none { present ->
                    filters.effectiveGenres.any { chosen ->
                        Lab164SearchMatchPolicy.localGenreMatches(present.name, chosen)
                    }
                }) return@filter false
            }
            true
        }

        return when (filters.sort) {
            AdvancedSearchFilters.SortFilter.OLDEST -> filtered.sortedBy {
                when (it) {
                    is Movie -> it.released
                    is TvShow -> it.released
                    else -> null
                }
            }
            AdvancedSearchFilters.SortFilter.DATE -> filtered.sortedByDescending {
                when (it) {
                    is Movie -> it.released
                    is TvShow -> it.released
                    else -> null
                }
            }
            AdvancedSearchFilters.SortFilter.VOTE -> filtered.sortedByDescending {
                when (it) {
                    is Movie -> it.rating ?: 0.0
                    is TvShow -> it.rating ?: 0.0
                    else -> 0.0
                }
            }
            AdvancedSearchFilters.SortFilter.POPULARITY -> filtered
        }
    }

    /** One unfiltered provider API page, then optional filters; never skip pages. */
    private suspend fun nativeProviderPage(
        provider: Provider, pageIndex: Int, filters: AdvancedSearchFilters,
        text: String,
        metadata: List<AppAdapter.Item>? = null,
        inlineRecovery: Boolean = true,
    ): Pair<List<AppAdapter.Item>, List<AppAdapter.Item>> {
        if (provider is TmdbProvider && (filters.isActive || text.isNotBlank())) {
            val direct = searchTmdbAdvanced(provider, text, filters, pageIndex)
            return direct to direct
        }
        val nativeGenre = if (text.isBlank()) nativeGenrePage(provider, filters, pageIndex) else null
        val raw = nativeGenre ?: if (text.isBlank()) browseProvider(provider, pageIndex, filters.type)
            else searchProviderWithRecovery(provider, text, pageIndex, inlineRecovery)
        val safeFilters = if (nativeGenre != null) filters.copy(
            genre = AdvancedSearchFilters.GenreFilter.ALL, genres = emptySet()
        ) else filters
        val displayed = if (filters.isActive) searchProviderAdvanced(
            provider, text, safeFilters, pageIndex,
            providedPage = raw, sharedMetadata = metadata, inlineRecovery = inlineRecovery
        ) else raw
        return raw to displayed
    }

    fun loadMore(): kotlinx.coroutines.Job = viewModelScope.launch(Dispatchers.IO) {
        if (localLoadingMore) return@launch
        val initial = _state.value as? State.SuccessSearching ?: return@launch
        if (!initial.hasMore) return@launch
        if (usingWebFallback) {
            _state.emit(initial.copy(hasMore = false))
            return@launch
        }
        localLoadingMore = true
        var continueScanning = false
        _state.emit(State.SearchingMore)
        try {
            val provider = UserPreferences.currentProvider
                ?: throw IllegalStateException("Seleziona prima un provider")
            val keys = initial.results.mapTo(hashSetOf()) { it.searchIdentityKey() }
            val additions = mutableListOf<AppAdapter.Item>()
            var batch = 0
            var rawEmpty = 0
            var rawRepeated = 0
            var lastSignature: String? = null
            var sourceHasMore = true
            while (batch < Lab170NativePagingPolicy.MAX_SCAN_BATCH &&
                additions.isEmpty() && sourceHasMore) {
                val nextPage = page + 1
                val (raw, shown) = nativeProviderPage(provider, nextPage, advancedFilters, query)
                page = nextPage
                batch++
                val signature = Lab170NativePagingPolicy.signature(raw.map { it.searchIdentityKey() })
                rawEmpty = if (raw.isEmpty()) rawEmpty + 1 else 0
                rawRepeated = if (raw.isNotEmpty() && signature == lastSignature)
                    rawRepeated + 1 else 0
                lastSignature = signature
                ParentalControlUtils.filterItems(shown).forEach {
                    if (keys.add(it.searchIdentityKey())) additions.add(it)
                }
                sourceHasMore = Lab170NativePagingPolicy.shouldContinue(page, rawEmpty, rawRepeated)
                kotlinx.coroutines.yield()
            }
            continueScanning = additions.isEmpty() && sourceHasMore
            _state.emit(State.SuccessSearching(initial.results + additions, sourceHasMore))
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.e("SearchViewModel", "LAB170 native page scan", error)
            _state.emit(initial.copy(hasMore = false))
        } finally {
            localLoadingMore = false
        }
        if (continueScanning) loadMore().join()
    }

    private fun normalizeFilters(filters: AdvancedSearchFilters): AdvancedSearchFilters {
        val hasRange = !filters.dateFrom.isNullOrBlank() || !filters.dateTo.isNullOrBlank()
        return if (hasRange) filters.copy(year = null) else filters
    }

    /**
     * Bounded validation for a typed title + date/year. No Discover fan-out:
     * limit requests to the first TMDb text page and do not launch per-provider
     * copies when a shared global metadata result is already present.
     */
    private suspend fun searchTmdbYearCandidates(
        query: String,
        filters: AdvancedSearchFilters,
        language: String,
    ): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        return TMDb3.Search.multi(query=query, page=1, language=language).results
            .mapNotNull { result ->
                when (result) {
                    is TMDb3.Movie -> {
                        if (filters.type == AdvancedSearchFilters.ContentType.TV ||
                            !matchesYearFilter(result.releaseDate, filters)) null
                        else Movie(
                            id=result.id.toString(), title=result.title,
                            released=result.releaseDate
                        ) as AppAdapter.Item
                    }
                    is TMDb3.Tv -> {
                        if (filters.type == AdvancedSearchFilters.ContentType.MOVIE ||
                            !matchesYearFilter(result.firstAirDate, filters)) null
                        else TvShow(
                            id=result.id.toString(), title=result.name,
                            released=result.firstAirDate
                        ) as AppAdapter.Item
                    }
                    else -> null
                }
            }
    }

    private fun sourceTitleVariants(
        item: AppAdapter.Item, original: String?, enabled: Boolean,
    ): List<AppAdapter.Item> {
        if (!enabled || original.isNullOrBlank()) return listOf(item)
        return when (item) {
            is Movie -> if (!Lab177FilteredWallPolicy.requiresAlias(
                item.title, original)) listOf(item)
                else listOf(item, item.copy(title = original))
            is TvShow -> if (!Lab177FilteredWallPolicy.requiresAlias(
                item.title, original)) listOf(item)
                else listOf(item, item.copy(title = original))
            else -> listOf(item)
        }
    }

    private fun metadataAliasKey(item: AppAdapter.Item): String {
        val name = when (item) {
            is Movie -> item.title
            is TvShow -> item.title
            else -> ""
        }
        return item.searchIdentityKey() + "|" + normalizeFilterTitle(name)
    }

    private suspend fun searchTmdbAdvanced(
        provider: TmdbProvider,
        query: String,
        filters: AdvancedSearchFilters,
        page: Int,
        candidateSelection: Boolean = false,
        sourceTitleAliases: Boolean = false,
    ): List<AppAdapter.Item> = coroutineScope {
        val availableGenres = filters.effectiveGenres
        val includeMovies = (filters.type == AdvancedSearchFilters.ContentType.ALL ||
            filters.type == AdvancedSearchFilters.ContentType.MOVIE ||
            filters.type == AdvancedSearchFilters.ContentType.CARTOON) &&
            (availableGenres.isEmpty() || availableGenres.any { it.movieId != null })
        val includeTv = (filters.type == AdvancedSearchFilters.ContentType.ALL ||
            filters.type == AdvancedSearchFilters.ContentType.TV ||
            filters.type == AdvancedSearchFilters.ContentType.CARTOON) &&
            (availableGenres.isEmpty() || availableGenres.any { it.tvId != null })

        val language = provider.language
        val italianOnly = filters.italian == AdvancedSearchFilters.ItalianFilter.YES
        val excludeItalian = filters.italian == AdvancedSearchFilters.ItalianFilter.NO
        val cartoonOnly = filters.type == AdvancedSearchFilters.ContentType.CARTOON
        val selectedGenres = filters.effectiveGenres
        val productionCountry = filters.country.isoCode
        val actorId = filters.actorId?.takeIf { it.isNotBlank() }
        val directorId = filters.directorId?.takeIf { it.isNotBlank() }
        val platformProviderIds = filters.platform.providerIds
        val directorDetailsDeferred = directorId?.toIntOrNull()?.let { personId ->
            async {
                TMDb3.People.details(
                    personId = personId,
                    appendToResponse = listOf(
                        TMDb3.Params.AppendToResponse.Person.MOVIE_CREDITS,
                        TMDb3.Params.AppendToResponse.Person.TV_CREDITS,
                    ),
                    language = language,
                )
            }
        }
        // First locate titles that providers are likely to carry. Keep the
        // requested chronological/vote sort for rendering and later pages.
        // Actor/director filmography paths must never be rerouted.
        val discoverySort = Lab165FastDiscoveryPolicy.metadataSort(
            filters.sort, query, actorId, directorId, page, candidateSelection
        )
        val movieSort = when (discoverySort) {
            AdvancedSearchFilters.SortFilter.DATE -> "primary_release_date.desc"
            AdvancedSearchFilters.SortFilter.OLDEST -> "primary_release_date.asc"
            AdvancedSearchFilters.SortFilter.VOTE -> "vote_average.desc"
            AdvancedSearchFilters.SortFilter.POPULARITY -> "popularity.desc"
        }
        val tvSort = when (discoverySort) {
            AdvancedSearchFilters.SortFilter.DATE -> "first_air_date.desc"
            AdvancedSearchFilters.SortFilter.OLDEST -> "first_air_date.asc"
            AdvancedSearchFilters.SortFilter.VOTE -> "vote_average.desc"
            AdvancedSearchFilters.SortFilter.POPULARITY -> "popularity.desc"
        }

        if (query.isNotBlank()) {
            val queryVariants = if (candidateSelection) buildQueryVariants(query).take(2)
                else buildQueryVariants(query)
            val pagesPerVariant = if (candidateSelection) 1
                else if (queryVariants.size <= 2) 3 else 2
            val firstSearchPage = ((page - 1) * pagesPerVariant) + 1
            val searchDeferred = queryVariants.flatMap { variant ->
                (firstSearchPage until firstSearchPage + pagesPerVariant).map { tmdbPage ->
                    async {
                        TMDb3.Search.multi(
                            query = variant,
                            page = tmdbPage,
                            language = language,
                        ).results
                    }
                }
            }
            val firstDiscoverPage = ((page - 1) * 6) + 1

            val actorCreditsDeferred = actorId?.let { id ->
                async { provider.getPeople(id, page = 1).filmography }
            }

            val needsDiscoverConstraint = platformProviderIds != null || productionCountry != null
            val movieConstraintDeferred = if (needsDiscoverConstraint && includeMovies) async {
                (firstDiscoverPage until firstDiscoverPage + 6).map { discoverPage ->
                    async {
                        val params = mutableMapOf(
                            "language" to language,
                            "page" to discoverPage.toString(),
                            "sort_by" to movieSort,
                            "include_adult" to "false",
                        )
                        platformProviderIds?.let {
                            params["watch_region"] = "IT"
                            params["with_watch_providers"] = it
                        }
                        productionCountry?.let { params["with_origin_country"] = it }
                        TMDb3.Discover.movie(params).results.map { it.id.toString() }
                    }
                }.awaitAll().flatten().toHashSet()
            } else null
            val tvConstraintDeferred = if (needsDiscoverConstraint && includeTv) async {
                (firstDiscoverPage until firstDiscoverPage + 6).map { discoverPage ->
                    async {
                        val params = mutableMapOf(
                            "language" to language,
                            "page" to discoverPage.toString(),
                            "sort_by" to tvSort,
                            "include_adult" to "false",
                        )
                        platformProviderIds?.let {
                            params["watch_region"] = "IT"
                            params["with_watch_providers"] = it
                        }
                        productionCountry?.let { params["with_origin_country"] = it }
                        TMDb3.Discover.tv(params).results.map { it.id.toString() }
                    }
                }.awaitAll().flatten().toHashSet()
            } else null

            val searchResults = searchDeferred.awaitAll().flatten()
            val actorCredits = actorCreditsDeferred?.await().orEmpty()
            val actorMovieIds = actorCredits.filterIsInstance<Movie>().mapTo(hashSetOf()) { it.id }
            val actorTvIds = actorCredits.filterIsInstance<TvShow>().mapTo(hashSetOf()) { it.id }
            val restrictToActor = actorId != null
            val directorDetails = directorDetailsDeferred?.await()
            val directorMovieIds = directorDetails?.movieCredits?.crew
                ?.filter { it.job.equals("Director", ignoreCase = true) }
                ?.mapTo(hashSetOf()) { it.id.toString() }
                .orEmpty()
            val directorTvIds = directorDetails?.tvCredits?.crew
                ?.filter { it.job.equals("Director", ignoreCase = true) }
                ?.mapTo(hashSetOf()) { it.id.toString() }
                .orEmpty()
            val restrictToDirector = directorId != null
            val constrainedMovieIds = movieConstraintDeferred?.await()
            val constrainedTvIds = tvConstraintDeferred?.await()
            val sortedSearchResults = when (filters.sort) {
                AdvancedSearchFilters.SortFilter.OLDEST -> searchResults.sortedBy {
                    when (it) {
                        is TMDb3.Movie -> it.releaseDate.orEmpty()
                        is TMDb3.Tv -> it.firstAirDate.orEmpty()
                        else -> ""
                    }
                }
                AdvancedSearchFilters.SortFilter.DATE -> searchResults.sortedByDescending {
                    when (it) {
                        is TMDb3.Movie -> it.releaseDate.orEmpty()
                        is TMDb3.Tv -> it.firstAirDate.orEmpty()
                        else -> ""
                    }
                }
                AdvancedSearchFilters.SortFilter.VOTE -> searchResults.sortedByDescending {
                    when (it) {
                        is TMDb3.Movie -> it.voteAverage
                        is TMDb3.Tv -> it.voteAverage
                        else -> 0f
                    }
                }
                AdvancedSearchFilters.SortFilter.POPULARITY -> searchResults.sortedByDescending {
                    when (it) {
                        is TMDb3.Movie -> it.popularity
                        is TMDb3.Tv -> it.popularity
                        is TMDb3.Person -> it.popularity
                    }
                }
            }

            val originals: Map<String, String> = if (sourceTitleAliases)
                sortedSearchResults.mapNotNull { multi ->
                    when (multi) {
                        is TMDb3.Movie -> "movie|" + multi.id.toString() to multi.originalTitle
                        is TMDb3.Tv -> "tv|" + multi.id.toString() to multi.originalName
                        else -> null
                    }
                }.toMap()
            else emptyMap()
            return@coroutineScope sortedSearchResults.mapNotNull { multi ->
                when (multi) {
                    is TMDb3.Movie -> {
                        if (!includeMovies) return@mapNotNull null
                        if (italianOnly && multi.originalLanguage != "it") return@mapNotNull null
                        if (excludeItalian && multi.originalLanguage == "it") return@mapNotNull null
                        if (!filters.acceptsGenreIds(multi.genresIds, movie = true)) return@mapNotNull null
                        if (restrictToActor && multi.id.toString() !in actorMovieIds) return@mapNotNull null
                        if (restrictToDirector && multi.id.toString() !in directorMovieIds) return@mapNotNull null
                        if (constrainedMovieIds != null && multi.id.toString() !in constrainedMovieIds) return@mapNotNull null
                        if (!matchesYearFilter(multi.releaseDate, filters)) return@mapNotNull null

                        Movie(
                            id = multi.id.toString(),
                            title = multi.title,
                            overview = multi.overview,
                            released = multi.releaseDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                        ) as AppAdapter.Item
                    }

                    is TMDb3.Tv -> {
                        if (!includeTv) return@mapNotNull null
                        if (italianOnly && multi.originalLanguage != "it") return@mapNotNull null
                        if (excludeItalian && multi.originalLanguage == "it") return@mapNotNull null
                        if (!filters.acceptsGenreIds(multi.genresIds, movie = false)) return@mapNotNull null
                        if (restrictToActor && multi.id.toString() !in actorTvIds) return@mapNotNull null
                        if (restrictToDirector && multi.id.toString() !in directorTvIds) return@mapNotNull null
                        if (constrainedTvIds != null && multi.id.toString() !in constrainedTvIds) return@mapNotNull null
                        if (!matchesYearFilter(multi.firstAirDate, filters)) return@mapNotNull null

                        TvShow(
                            id = multi.id.toString(),
                            title = multi.name,
                            overview = multi.overview,
                            released = multi.firstAirDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                        ) as AppAdapter.Item
                    }

                    else -> null
                }
            }.flatMap { item ->
                val key = when (item) {
                    is Movie -> "movie|" + item.id
                    is TvShow -> "tv|" + item.id
                    else -> ""
                }
                sourceTitleVariants(item, originals[key], sourceTitleAliases)
            }.distinctBy { item ->
                if (sourceTitleAliases) metadataAliasKey(item)
                else item.searchIdentityKey()
            }
        }

        val movieDeferred = if (includeMovies) async {
            val params = mutableMapOf(
                "language" to language,
                "page" to page.toString(),
                "sort_by" to movieSort,
                "include_adult" to "false",
            )
            if (italianOnly) params["with_original_language"] = "it"
            actorId?.let { params["with_cast"] = it }
            directorId?.let { params["with_crew"] = it }
            platformProviderIds?.let {
                params["watch_region"] = "IT"
                params["with_watch_providers"] = it
            }
            productionCountry?.let { params["with_origin_country"] = it }

            val genreIds = selectedGenres.mapNotNull { it.movieId }.distinct()
            if (cartoonOnly) params["with_genres"] = "16"
            else if (genreIds.isNotEmpty()) params["with_genres"] = genreIds.joinToString("|")

            filters.year?.let { params["primary_release_year"] = it.toString() }
            filters.dateFrom?.takeIf { it.isNotBlank() }?.let { params["primary_release_date.gte"] = it }
            filters.dateTo?.takeIf { it.isNotBlank() }?.let { params["primary_release_date.lte"] = it }
            val pages = if (candidateSelection) page..page
                else if (Lab173TvCombinedFilterPolicy.needsBroaderCountryWindow(filters, query))
                    Lab173TvCombinedFilterPolicy.metadataPages(page)
                else Lab154SearchPacing.metadataPages(page)
            pages.map { discoverPage -> async {
                TMDb3.Discover.movie(params + ("page" to discoverPage.toString())).results
            } }.awaitAll().flatten()
        } else null

        val tvDeferred = if (includeTv) async {
            val params = mutableMapOf(
                "language" to language,
                "page" to page.toString(),
                "sort_by" to tvSort,
                "include_adult" to "false",
            )
            if (italianOnly) params["with_original_language"] = "it"
            actorId?.let { params["with_cast"] = it }
            directorId?.let { params["with_crew"] = it }
            platformProviderIds?.let {
                params["watch_region"] = "IT"
                params["with_watch_providers"] = it
            }
            productionCountry?.let { params["with_origin_country"] = it }

            val genreIds = selectedGenres.mapNotNull { it.tvId }.distinct()
            if (cartoonOnly) params["with_genres"] = "16"
            else if (genreIds.isNotEmpty()) params["with_genres"] = genreIds.joinToString("|")

            filters.year?.let { params["first_air_date_year"] = it.toString() }
            filters.dateFrom?.takeIf { it.isNotBlank() }?.let { params["first_air_date.gte"] = it }
            filters.dateTo?.takeIf { it.isNotBlank() }?.let { params["first_air_date.lte"] = it }
            val pages = if (candidateSelection) page..page
                else if (Lab173TvCombinedFilterPolicy.needsBroaderCountryWindow(filters, query))
                    Lab173TvCombinedFilterPolicy.metadataPages(page)
                else Lab154SearchPacing.metadataPages(page)
            pages.map { discoverPage -> async {
                TMDb3.Discover.tv(params + ("page" to discoverPage.toString())).results
            } }.awaitAll().flatten()
        } else null

        val blankDirectorDetails = directorDetailsDeferred?.await()
        val blankDirectorMovieIds = blankDirectorDetails?.movieCredits?.crew
            ?.filter { it.job.equals("Director", ignoreCase = true) }
            ?.mapTo(hashSetOf()) { it.id.toString() }
            .orEmpty()
        val blankDirectorTvIds = blankDirectorDetails?.tvCredits?.crew
            ?.filter { it.job.equals("Director", ignoreCase = true) }
            ?.mapTo(hashSetOf()) { it.id.toString() }
            .orEmpty()

        val movieItems = movieDeferred?.await().orEmpty()
            .asSequence()
            .filter { !excludeItalian || it.originalLanguage != "it" }
            .filter { filters.acceptsGenreIds(it.genresIds, movie = true) }
            .filter { directorId == null || it.id.toString() in blankDirectorMovieIds }
            .flatMap { movie ->
                sourceTitleVariants(
                    Movie(
                        id = movie.id.toString(),
                        title = movie.title,
                        overview = movie.overview,
                        released = movie.releaseDate,
                        rating = movie.voteAverage.toDouble(),
                        poster = movie.posterPath?.w500,
                        banner = movie.backdropPath?.original,
                    ),
                    movie.originalTitle, sourceTitleAliases,
                ).asSequence()
            }
            .toList()

        val tvItems = tvDeferred?.await().orEmpty()
            .asSequence()
            .filter { !excludeItalian || it.originalLanguage != "it" }
            .filter { filters.acceptsGenreIds(it.genresIds, movie = false) }
            .filter { directorId == null || it.id.toString() in blankDirectorTvIds }
            .flatMap { tv ->
                sourceTitleVariants(
                    TvShow(
                        id = tv.id.toString(),
                        title = tv.name,
                        overview = tv.overview,
                        released = tv.firstAirDate,
                        rating = tv.voteAverage.toDouble(),
                        poster = tv.posterPath?.w500,
                        banner = tv.backdropPath?.original,
                    ),
                    tv.originalName, sourceTitleAliases,
                ).asSequence()
            }
            .toList()

        val combined = (movieItems + tvItems)
            .distinctBy { item ->
                if (sourceTitleAliases) metadataAliasKey(item)
                else item.searchIdentityKey()
            }
        // Candidate lookups need popularity-first order; UI sorts accepted
        // provider titles separately. TMDb-only views still use requested sort.
        when (discoverySort) {
            AdvancedSearchFilters.SortFilter.OLDEST -> combined.sortedBy {
                when (it) {
                    is Movie -> it.released?.timeInMillis ?: Long.MAX_VALUE
                    is TvShow -> it.released?.timeInMillis ?: Long.MAX_VALUE
                    else -> Long.MAX_VALUE
                }
            }
            AdvancedSearchFilters.SortFilter.DATE -> combined.sortedByDescending {
                when (it) {
                    is Movie -> it.released?.timeInMillis ?: Long.MIN_VALUE
                    is TvShow -> it.released?.timeInMillis ?: Long.MIN_VALUE
                    else -> Long.MIN_VALUE
                }
            }
            AdvancedSearchFilters.SortFilter.VOTE -> combined.sortedByDescending {
                when (it) {
                    is Movie -> it.rating
                    is TvShow -> it.rating
                    else -> 0.0
                }
            }
            AdvancedSearchFilters.SortFilter.POPULARITY -> combined
        }
    }

    private fun matchesYearFilter(
        date: String?,
        filters: AdvancedSearchFilters,
    ): Boolean {
        return Lab172NativeYearPolicy.matchesRange(
            date,
            filters.year,
            filters.dateFrom?.take(4)?.toIntOrNull(),
            filters.dateTo?.take(4)?.toIntOrNull()
        )
    }

    private fun buildQueryVariants(rawQuery: String): List<String> {
        val clean = rawQuery.trim().replace(Regex("""\s+"""), " ")
        if (clean.isBlank()) return emptyList()
        val generic = setOf(
            "film", "films", "movie", "movies", "serie", "series", "tv",
            "cartone", "cartoni", "cartoon", "cartoons", "animazione", "animation",
            "italia", "italy", "italiano", "italiana", "italiani", "italiane")
        val words = clean.split(' ').filter { it.isNotBlank() }
        val coreWords = words.filterNot { it.lowercase(Locale.ROOT) in generic }
        val variants = linkedSetOf<String>()
        variants += clean
        val core = coreWords.joinToString(" ").trim()
        if (core.length >= 2) variants += core
        coreWords.filter { it.length >= 4 }.forEach { variants += it }
        val low = core.lowercase(Locale.ROOT)
        if ("natale" in low) { variants += core.replace(Regex("natale", RegexOption.IGNORE_CASE), "Christmas"); variants += "Christmas" }
        if ("christmas" in low) variants += "Natale"
        if ("pasqua" in low) variants += core.replace(Regex("pasqua", RegexOption.IGNORE_CASE), "Easter")
        if ("easter" in low) variants += "Pasqua"
        return variants.map { it.trim() }.filter { it.length >= 2 }
            .distinctBy { it.lowercase(Locale.ROOT) }.take(6)
    }


    // LAB182: Verify the titles actually returned by Italian providers.
    // Discover's first page is not a complete admissible-title set.
    private val sourceTmdbGate = Semaphore(4)
    private val sourceTmdbMutex = Mutex()
    private val sourceTmdbCache = mutableMapOf<String, Boolean>()

    private suspend fun fetchItalianSourcePage(
        provider: Provider, text: String, filters: AdvancedSearchFilters, index: Int,
    ): Pair<List<AppAdapter.Item>, Boolean> =
        withTimeoutOrNull(Lab181ProviderFlowPolicy.SOURCE_TIMEOUT_MS) {
            val genreItems = if (filters.isActive && text.isBlank())
                nativeGenrePage(provider, filters, index) else null
            val nativeItems = genreItems ?: if (text.isBlank())
                browseProvider(provider, index, filters.type)
            else searchProviderWithRecovery(provider, text, index, inlineRecovery = false)
            nativeItems to (genreItems != null)
        } ?: throw java.io.IOException("Provider source timed out: " + provider.name)

    private suspend fun verifyItalianSourcePage(
        sourceItems: List<AppAdapter.Item>, filters: AdvancedSearchFilters,
    ): List<AppAdapter.Item> = coroutineScope {
        if (!filters.isActive) return@coroutineScope sourceItems
        val local = applyProviderFilters(sourceItems, filters)
        if (!Lab182SourceMatchPolicy.requiresMetadata(filters)) return@coroutineScope local
        val localProof = if (Lab174FilteredSourcePolicy.requiresStrictMetadata(filters))
            emptySet() else local.toSet()
        sourceItems.filter { it is Movie || it is TvShow }.map { item ->
            async(Dispatchers.IO) {
                if (item in localProof) return@async item
                if (!tmdbConfigured()) return@async null
                val key = filterMatchKey(item).orEmpty() + "|" + filters.hashCode()
                val known = sourceTmdbMutex.withLock { sourceTmdbCache[key] }
                if (known != null) return@async item.takeIf { known }
                val verified: Boolean? = try {
                    sourceTmdbGate.withPermit {
                        withTimeoutOrNull(7_000L) { verifyItalianSourceTitle(item, filters) }
                    }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    Log.w("SearchViewModel", "LAB182 metadata unavailable " + key, e)
                    null
                }
                if (verified != null) sourceTmdbMutex.withLock {
                    sourceTmdbCache[key] = verified
                }
                item.takeIf { verified == true }
            }
        }.awaitAll().filterNotNull().distinctBy { it.searchIdentityKey() }
    }

    private suspend fun verifyItalianSourceTitle(
        source: AppAdapter.Item, filters: AdvancedSearchFilters,
    ): Boolean {
        val title = when (source) {
            is Movie -> source.title
            is TvShow -> source.title
            else -> return false
        }
        val clean = Lab174FilteredSourcePolicy.cleanProviderTitle(title)
        val sourceKey = filterMatchKey(source) ?: return false
        if (clean.isBlank()) return false
        val country = filters.country.isoCode
        val actorId = filters.actorId?.toIntOrNull()
        val directorId = filters.directorId?.toIntOrNull()
        val platformIds = filters.platform.providerIds
            ?.split('|', ',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
        val needsCredits = actorId != null || directorId != null
        val candidates = TMDb3.Search.multi(query = clean, page = 1, language = "it").results
        for (candidate in candidates) {
            if (source is Movie && candidate is TMDb3.Movie) {
                val year = candidate.releaseDate?.take(4).orEmpty()
                val localized = "movie|" + normalizeFilterTitle(candidate.title) + "|" + year
                val original = "movie|" + normalizeFilterTitle(candidate.originalTitle) + "|" + year
                if (!Lab182SourceMatchPolicy.matchesTypedTitle(sourceKey, localized, original)) continue
                if (filters.type == AdvancedSearchFilters.ContentType.TV) continue
                if (filters.type == AdvancedSearchFilters.ContentType.CARTOON && 16 !in candidate.genresIds) continue
                if (!filters.acceptsGenreIds(candidate.genresIds, movie = true)) continue
                if (!matchesYearFilter(candidate.releaseDate, filters)) continue
                if (filters.italian == AdvancedSearchFilters.ItalianFilter.YES && candidate.originalLanguage != "it") continue
                if (filters.italian == AdvancedSearchFilters.ItalianFilter.NO && candidate.originalLanguage == "it") continue
                val details = if (country != null || platformIds != null || needsCredits)
                    TMDb3.Movies.details(
                        movieId = candidate.id,
                        appendToResponse = buildList {
                            if (needsCredits) add(TMDb3.Params.AppendToResponse.Movie.CREDITS)
                            if (platformIds != null) add(TMDb3.Params.AppendToResponse.Movie.WATCH_PROVIDERS)
                        },
                        language = "it",
                    ) else null
                if (country != null && details?.productionCountries?.any {
                        it.iso3166.equals(country, ignoreCase = true)
                    } != true) continue
                if (platformIds != null && details?.watchProviders?.results?.get("IT")?.let {
                        p -> (p.flatrate + p.buy).any { it.providerId in platformIds }
                    } != true) continue
                if (actorId != null && details?.credits?.cast?.any { it.id == actorId } != true) continue
                if (directorId != null && details?.credits?.crew?.any {
                        it.id == directorId && it.job.equals("Director", ignoreCase = true)
                    } != true) continue
                return true
            }
            if (source is TvShow && candidate is TMDb3.Tv) {
                val year = candidate.firstAirDate?.take(4).orEmpty()
                val localized = "tv|" + normalizeFilterTitle(candidate.name) + "|" + year
                val original = "tv|" + normalizeFilterTitle(candidate.originalName) + "|" + year
                if (!Lab182SourceMatchPolicy.matchesTypedTitle(sourceKey, localized, original)) continue
                if (filters.type == AdvancedSearchFilters.ContentType.MOVIE) continue
                if (filters.type == AdvancedSearchFilters.ContentType.CARTOON && 16 !in candidate.genresIds) continue
                if (!filters.acceptsGenreIds(candidate.genresIds, movie = false)) continue
                if (!matchesYearFilter(candidate.firstAirDate, filters)) continue
                if (filters.italian == AdvancedSearchFilters.ItalianFilter.YES && candidate.originalLanguage != "it") continue
                if (filters.italian == AdvancedSearchFilters.ItalianFilter.NO && candidate.originalLanguage == "it") continue
                if (country != null && candidate.originCountry.none {
                        it.equals(country, ignoreCase = true)
                    }) continue
                val details = if (platformIds != null || needsCredits)
                    TMDb3.TvSeries.details(
                        seriesId = candidate.id,
                        appendToResponse = buildList {
                            if (needsCredits) add(TMDb3.Params.AppendToResponse.Tv.CREDITS)
                            if (platformIds != null) add(TMDb3.Params.AppendToResponse.Tv.WATCH_PROVIDERS)
                        },
                        language = "it",
                    ) else null
                if (platformIds != null && details?.watchProviders?.results?.get("IT")?.let {
                        p -> (p.flatrate + p.buy).any { it.providerId in platformIds }
                    } != true) continue
                if (actorId != null && details?.credits?.cast?.any { it.id == actorId } != true) continue
                if (directorId != null && details?.credits?.crew?.any {
                        it.id == directorId && it.job.equals("Director", ignoreCase = true)
                    } != true) continue
                return true
            }
        }
        return false
    }

    fun searchGlobal(
        query: String,
        currentLanguage: String,
        filters: AdvancedSearchFilters = AdvancedSearchFilters(),
    ) = viewModelScope.launch(Dispatchers.IO) {
        // Search every Italian-language source live; the partial index is not a complete search.
        ++activeIndexedSearchGeneration
        indexedGlobalEnabled = false
        advancedFilters = normalizeFilters(filters)
        this@SearchViewModel.query = query
        page = 1
        selectedActorId = advancedFilters.actorId
        selectedActorName = advancedFilters.actorName
        selectedDirectorId = advancedFilters.directorId
        selectedDirectorName = advancedFilters.directorName
        _actorSuggestions.value = emptyList()
        _directorSuggestions.value = emptyList()
        _state.emit(State.GlobalSearching)
        val generation = ++activeGlobalGeneration
        globalEmptyPages = 0

        val italianProviders = Provider.providers.keys.filter {
            it.language.equals("it", ignoreCase = true) && it !is TmdbProvider
        }
        val originals = italianProviders
            .sortedBy { it.name != UserPreferences.currentProvider?.name }
            .map { GlobalSearchTarget(it, "Originale · ${it.name}", null) }

        // Only configured mirrors of Italian engines belong to this scope.
        val remotes = ProviderEndpointRegistry.cachedRemoteEntries("it")
            .filter { it.healthy }
            .mapNotNull { entry ->
                val engine = italianProviders.firstOrNull { it.name == entry.engineName }
                    as? EndpointManagedProvider ?: return@mapNotNull null
                GlobalSearchTarget(engine, "Pastebin · ${entry.displayName}", entry.url)
            }
        val targets = originals + remotes
        globalTargets = targets
        nativeGenreCacheMutex.withLock { nativeGenreCache.clear() }
        globalEmptyRawByTarget.clear()
        globalRepeatedRawByTarget.clear()
        globalLastRawByTarget.clear()
        globalFinishedTargets.clear()
        sourceTmdbMutex.withLock { sourceTmdbCache.clear() }
        globalPage = 1
        globalLoadingMore = false
        if (targets.isEmpty()) {
            _state.emit(globalWallState(emptyList(), hasMore = false))
            return@launch
        }

        if (Lab174FilteredSourcePolicy.requiresStrictMetadata(advancedFilters) && !tmdbConfigured()) {
            _state.emit(State.FailedSearching(IllegalStateException(
                "I filtri richiedono una chiave TMDb valida"
            )))
            return@launch
        }
        val mutableResults = targets.map { target ->
            ProviderResult(
                provider = target.provider,
                state = ProviderResult.State.Loading,
                displayName = target.displayName,
            )
        }.toMutableList()
        _state.emit(globalWallState(mutableResults.toList(), hasMore = false))

        val resultsMutex = Mutex()
        val globalProviderGate = Semaphore(6)
        var completedTargets = 0

        coroutineScope {
            targets.withIndex()
                .groupBy { it.value.provider.name }
                .values
                .forEach { group ->
                    launch {
                        val provider = group.first().value.provider
                        val previousSelection = ProviderEndpointRegistry.selectedOverride(provider.name)
                        var needsRecovery = false
                        try {
                            for ((index, target) in group) {
                                if (target.endpoint.isNullOrBlank()) {
                                    ProviderEndpointRegistry.selectOriginal(provider.name)
                                } else {
                                    ProviderEndpointRegistry.selectRemote(provider.name, target.endpoint)
                                }
                                (provider as? EndpointManagedProvider)?.onEndpointSelectionChanged()

                                val result = try {
                                    val (raw, nativeGenre) = globalProviderGate.withPermit {
                                        fetchItalianSourcePage(provider, query, advancedFilters, 1)
                                    }
                                    val effective = if (nativeGenre) advancedFilters.copy(
                                        genre = AdvancedSearchFilters.GenreFilter.ALL,
                                        genres = emptySet(),
                                    ) else advancedFilters
                                    val matched = verifyItalianSourcePage(raw, effective)
                                    val items = ParentalControlUtils.filterItems(matched.onEach { item ->
                                        when (item) {
                                            is Movie -> {
                                                item.providerName = provider.name
                                                item.providerEndpoint = target.endpoint
                                            }
                                            is TvShow -> {
                                                item.providerName = provider.name
                                                item.providerEndpoint = target.endpoint
                                            }
                                        }
                                    })
                                    if (query.isBlank() && raw.isEmpty() && provider is EndpointManagedProvider)
                                        needsRecovery = true
                                    ProviderResult(provider, ProviderResult.State.Success(items), target.displayName)
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
                                } catch (e: Exception) {
                                    needsRecovery = true
                                    Log.e("SearchViewModel", "searchGlobal for ${target.displayName}: ", e)
                                    ProviderResult(
                                        provider = provider,
                                        state = ProviderResult.State.Error(e),
                                        displayName = target.displayName,
                                    )
                                }

                                resultsMutex.withLock {
                                    if (generation != activeGlobalGeneration) return@withLock
                                    mutableResults[index] = result
                                    completedTargets++
                                    // Batch UI updates for a fluid poster wall instead of rebuilding
                                    // RecyclerView on every individual provider completion.
                                    // Streamflix publishes results per provider instead of
                                    // waiting for three or the entire provider pool.
                                    val nonempty = (result.state as? ProviderResult.State.Success)
                                        ?.results?.isNotEmpty() == true
                                    if (nonempty || completedTargets == targets.size) {
                                        _state.emit(globalWallState(
                                            mutableResults.toList(),
                                            hasMore = completedTargets == targets.size,
                                        ))
                                    }
                                }
                            }
                        } finally {
                            ProviderEndpointRegistry.restoreSelection(provider.name, previousSelection)
                            (provider as? EndpointManagedProvider)?.onEndpointSelectionChanged()
                            if (needsRecovery && generation == activeGlobalGeneration)
                                scheduleGlobalRecovery(provider)
                        }
                    }
                }
        }
        if (generation == activeGlobalGeneration) {
            val failures = mutableResults.mapNotNull {
                (it.state as? ProviderResult.State.Error)?.error
            }
            if (failures.size == targets.size && targets.isNotEmpty()) {
                _state.emit(State.FailedSearching(IllegalStateException(
                    "Nessun provider ha potuto verificare i filtri scelti. " +
                    "Controlla provider e metadati nelle Impostazioni.", failures.first()
                )))
                return@launch
            }
            // LAB171: an empty filtered first screen has no cards whose bind
            // can trigger load-more; start the native on-demand scanner once.
            val wall = _state.value as? State.SuccessGlobalSearching
            if (advancedFilters.isActive && wall != null &&
                wall.catalog.posters.isEmpty() && wall.hasMore) loadMoreGlobal()
        }
    }

    /**
     * LAB170: apply Streamflix's native one-page provider API consistently
     * on global pages too. No filtered-empty page can end pagination.
     */
    fun loadMoreGlobal(): kotlinx.coroutines.Job = viewModelScope.launch(Dispatchers.IO) {
        if (indexedGlobalEnabled) {
            loadMoreIndexedGlobal()
            return@launch
        }
        if (globalLoadingMore || globalTargets.isEmpty()) return@launch
        val initial = _state.value as? State.SuccessGlobalSearching ?: return@launch
        if (!initial.hasMore) return@launch
        globalLoadingMore = true
        val generation = activeGlobalGeneration
        var continuation = false
        try {
            val targets = globalTargets.toList()
            val updated = targets.mapIndexed { i, target ->
                initial.providerResults.getOrNull(i)
                    ?: ProviderResult(target.provider, ProviderResult.State.Loading, target.displayName)
            }.toMutableList()
            val gate = Semaphore(6)
            val mutex = Mutex()
            var batch = 0
            var totalAdded = 0
            var sourceHasMore = true
            while (batch < Lab170NativePagingPolicy.MAX_SCAN_BATCH &&
                totalAdded == 0 && sourceHasMore && generation == activeGlobalGeneration) {
                val nextPage = globalPage + 1
                val active = targets.withIndex().filter { it.index !in globalFinishedTargets }
                if (active.isEmpty()) break
                var nextAdded = 0
                coroutineScope {
                    active.groupBy { it.value.provider.name }.values.forEach { group ->
                        launch {
                            val engine = group.first().value.provider
                            val selection = ProviderEndpointRegistry.selectedOverride(engine.name)
                            var needsRecovery = false
                            try {
                                for ((index, target) in group) {
                                    if (generation != activeGlobalGeneration) break
                                    if (target.endpoint.isNullOrBlank())
                                        ProviderEndpointRegistry.selectOriginal(engine.name)
                                    else ProviderEndpointRegistry.selectRemote(engine.name, target.endpoint)
                                    (engine as? EndpointManagedProvider)?.onEndpointSelectionChanged()
                                    var timedOutOrFailed = false
                                    val (raw, nativeGenre) = try {
                                        gate.withPermit {
                                            fetchItalianSourcePage(engine, query, advancedFilters, nextPage)
                                        }
                                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                                        throw cancel
                                    } catch (error: Exception) {
                                        timedOutOrFailed = true
                                        Log.w("SearchViewModel", "LAB182 source " + target.displayName, error)
                                        emptyList<AppAdapter.Item>() to false
                                    }
                                    if (timedOutOrFailed) needsRecovery = true
                                    val effective = if (nativeGenre) advancedFilters.copy(
                                        genre = AdvancedSearchFilters.GenreFilter.ALL,
                                        genres = emptySet(),
                                    ) else advancedFilters
                                    val filtered = if (timedOutOrFailed) emptyList<AppAdapter.Item>()
                                        else verifyItalianSourcePage(raw, effective)
                                    filtered.forEach { item ->
                                        when (item) {
                                            is Movie -> {
                                                item.providerName = engine.name
                                                item.providerEndpoint = target.endpoint
                                            }
                                            is TvShow -> {
                                                item.providerName = engine.name
                                                item.providerEndpoint = target.endpoint
                                            }
                                        }
                                    }
                                    mutex.withLock {
                                        if (generation != activeGlobalGeneration) return@withLock
                                        val prior = (updated[index].state as? ProviderResult.State.Success)
                                            ?.results.orEmpty()
                                        val existingKeys = prior.map { it.searchIdentityKey() }.toHashSet()
                                        val fresh = ParentalControlUtils.filterItems(filtered)
                                            .filter { existingKeys.add(it.searchIdentityKey()) }
                                        val signature = Lab170NativePagingPolicy.signature(
                                            raw.map { it.searchIdentityKey() }
                                        )
                                        val emptyCount = if (raw.isEmpty())
                                            (globalEmptyRawByTarget[index] ?: 0) + 1 else 0
                                        val repeatedCount = if (raw.isNotEmpty() &&
                                            signature == globalLastRawByTarget[index])
                                            (globalRepeatedRawByTarget[index] ?: 0) + 1 else 0
                                        globalEmptyRawByTarget[index] = emptyCount
                                        globalRepeatedRawByTarget[index] = repeatedCount
                                        globalLastRawByTarget[index] = signature
                                        if (timedOutOrFailed || !Lab170NativePagingPolicy.shouldContinue(
                                                nextPage, emptyCount, repeatedCount)) {
                                            // Skip a broken source for this search only. It stays
                                            // registered and will be retried on the next search.
                                            globalFinishedTargets.add(index)
                                        }
                                        nextAdded += fresh.size
                                        updated[index] = ProviderResult(engine,
                                            ProviderResult.State.Success(prior + fresh),
                                            target.displayName)
                                        if (fresh.isNotEmpty()) {
                                            _state.emit(globalWallState(updated.toList(), hasMore = true))
                                        }
                                    }
                                }
                            } finally {
                                ProviderEndpointRegistry.restoreSelection(engine.name, selection)
                                (engine as? EndpointManagedProvider)?.onEndpointSelectionChanged()
                                if (needsRecovery && generation == activeGlobalGeneration)
                                    scheduleGlobalRecovery(engine)
                            }
                        }
                    }
                }
                globalPage = nextPage
                batch++
                totalAdded += nextAdded
                sourceHasMore = globalPage < Lab170NativePagingPolicy.MAX_SCAN_PAGE &&
                    globalFinishedTargets.size < targets.size
                kotlinx.coroutines.yield()
            }
            if (generation == activeGlobalGeneration) {
                continuation = totalAdded == 0 && sourceHasMore
                _state.emit(globalWallState(updated, hasMore = sourceHasMore))
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.e("SearchViewModel", "LAB170 global native paging", error)
            if (generation == activeGlobalGeneration) _state.emit(initial.copy(hasMore = false))
        } finally {
            globalLoadingMore = false
        }
        if (continuation) loadMoreGlobal().join()
    }

}

private fun AppAdapter.Item.searchIdentityKey(): String = when (this) {
    is Movie -> "movie:$id"
    is TvShow -> "tvshow:$id"
    is People -> "people:$id"
    else -> "${this::class.java.name}:${hashCode()}"
}

package com.streamflixreborn.streamflix.fragments.search

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
import com.streamflixreborn.streamflix.providers.ProviderEndpointRegistry
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.ParentalControlUtils
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.Dispatchers
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

class SearchViewModel(database: AppDatabase) : ViewModel() {

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

    private suspend fun searchProviderWithRecovery(provider: Provider, text: String, page: Int): List<AppAdapter.Item> {
        return try {
            provider.search(text, page)
        } catch (error: retrofit2.HttpException) {
            Log.w("SearchViewModel", "Provider=${provider.name}, search HTTP=${error.code()}, page=$page")
            if (Lab155SearchSafety.isRestricted(error.code())) {
                throw IllegalStateException(
                    Lab155SearchSafety.explainRestrictedProvider(provider.name), error
                )
            }
            if (!Lab155SearchSafety.canRefreshMissingEndpoint(
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
    ): List<AppAdapter.Item> {
        // LAB170: exactly one native Streamflix source page, for EVERY page.
        // Do not search individually for dozens of TMDb candidate titles.
        val nativeGenre = if (providedPage == null && query.isBlank())
            nativeGenrePage(provider, filters, page) else null
        val raw = providedPage ?: nativeGenre
            ?: if (query.isBlank()) browseProvider(provider, page, filters.type)
            else searchProviderWithRecovery(provider, query, page)
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
    ): Pair<List<AppAdapter.Item>, List<AppAdapter.Item>> {
        if (provider is TmdbProvider && (filters.isActive || text.isNotBlank())) {
            val direct = searchTmdbAdvanced(provider, text, filters, pageIndex)
            return direct to direct
        }
        val nativeGenre = if (text.isBlank()) nativeGenrePage(provider, filters, pageIndex) else null
        val raw = nativeGenre ?: if (text.isBlank()) browseProvider(provider, pageIndex, filters.type)
            else searchProviderWithRecovery(provider, text, pageIndex)
        val safeFilters = if (nativeGenre != null) filters.copy(
            genre = AdvancedSearchFilters.GenreFilter.ALL, genres = emptySet()
        ) else filters
        val displayed = if (filters.isActive) searchProviderAdvanced(
            provider, text, safeFilters, pageIndex,
            providedPage = raw, sharedMetadata = metadata
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

    private suspend fun searchTmdbAdvanced(
        provider: TmdbProvider,
        query: String,
        filters: AdvancedSearchFilters,
        page: Int,
        candidateSelection: Boolean = false,
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
            }.distinctBy { it.searchIdentityKey() }
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
            .map { movie ->
                Movie(
                    id = movie.id.toString(),
                    title = movie.title,
                    overview = movie.overview,
                    released = movie.releaseDate,
                    rating = movie.voteAverage.toDouble(),
                    poster = movie.posterPath?.w500,
                    banner = movie.backdropPath?.original,
                ) as AppAdapter.Item
            }
            .toList()

        val tvItems = tvDeferred?.await().orEmpty()
            .asSequence()
            .filter { !excludeItalian || it.originalLanguage != "it" }
            .filter { filters.acceptsGenreIds(it.genresIds, movie = false) }
            .filter { directorId == null || it.id.toString() in blankDirectorTvIds }
            .map { tv ->
                TvShow(
                    id = tv.id.toString(),
                    title = tv.name,
                    overview = tv.overview,
                    released = tv.firstAirDate,
                    rating = tv.voteAverage.toDouble(),
                    poster = tv.posterPath?.w500,
                    banner = tv.backdropPath?.original,
                ) as AppAdapter.Item
            }
            .toList()

        val combined = (movieItems + tvItems)
            .distinctBy { it.searchIdentityKey() }
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

    fun searchGlobal(
        query: String,
        currentLanguage: String,
        filters: AdvancedSearchFilters = AdvancedSearchFilters(),
    ) = viewModelScope.launch(Dispatchers.IO) {
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

        val isCurrentProviderIptv = UserPreferences.currentProvider is IptvProvider
        val originals = Provider.providers.keys
            .filter { it.language == currentLanguage && (it is IptvProvider) == isCurrentProviderIptv }
            .sortedBy { it.name != UserPreferences.currentProvider?.name }
            .map { GlobalSearchTarget(it, "Originale · ${it.name}", null) }

        val remotes = if (!isCurrentProviderIptv) {
            // Use cached known-good mirrors immediately; network registry refresh no longer
            // holds the first screen hostage before the current provider can search.
            ProviderEndpointRegistry.cachedRemoteEntries(currentLanguage)
                .filter { it.healthy }
                .mapNotNull { entry ->
                    val engine = Provider.providers.keys.firstOrNull { it.name == entry.engineName }
                        as? EndpointManagedProvider ?: return@mapNotNull null
                    GlobalSearchTarget(
                        provider = engine,
                        displayName = "Pastebin · ${entry.displayName}",
                        endpoint = entry.url,
                    )
                }
        } else {
            emptyList()
        }

        val targets = originals + remotes
        globalTargets = targets
        nativeGenreCacheMutex.withLock { nativeGenreCache.clear() }
        globalEmptyRawByTarget.clear()
        globalRepeatedRawByTarget.clear()
        globalLastRawByTarget.clear()
        globalFinishedTargets.clear()
        globalPage = 1
        globalLoadingMore = false
        if (targets.isEmpty()) {
            _state.emit(globalWallState(emptyList(), hasMore = false))
            return@launch
        }

        val needsGlobalMetadata = advancedFilters.isActive && (
            advancedFilters.italian != AdvancedSearchFilters.ItalianFilter.ALL ||
            advancedFilters.country != AdvancedSearchFilters.CountryFilter.ALL ||
            advancedFilters.effectiveGenres.isNotEmpty() ||
            advancedFilters.type == AdvancedSearchFilters.ContentType.CARTOON ||
            advancedFilters.platform != AdvancedSearchFilters.PlatformFilter.ALL ||
            !advancedFilters.actorId.isNullOrBlank() ||
            !advancedFilters.directorId.isNullOrBlank() ||
            (tmdbConfigured() && (advancedFilters.year != null ||
                !advancedFilters.dateFrom.isNullOrBlank() || !advancedFilters.dateTo.isNullOrBlank()))
        )
        if (needsGlobalMetadata && !tmdbConfigured() &&
            !Lab168FastResultsPolicy.canUseDirectProviderResults(advancedFilters)) {
            _state.emit(State.FailedSearching(IllegalStateException(
                "I filtri globali per Paese, genere, lingua e metadati richiedono una chiave TMDb valida"
            )))
            return@launch
        }
        // Discover metadata concurrently rather than blocking the local provider's
        // immediate text results. All displayed items still pass their filters.
        var metadataLookupError: Exception? = null
        val metadataTask: kotlinx.coroutines.Deferred<List<AppAdapter.Item>>? =
            if (needsGlobalMetadata &&
                !Lab168FastResultsPolicy.canUseDirectProviderResults(advancedFilters)) async {
            // An HTTP 451 / blocked metadata endpoint is NOT an unhandled
            // child-coroutine failure. Keep the app-level Cerca screen alive.
            try {
                if (Lab164SearchMatchPolicy.providerFirstYearQuery(query, advancedFilters))
                    searchTmdbYearCandidates(query, advancedFilters, currentLanguage)
                else
                    searchTmdbAdvanced(TmdbProvider(currentLanguage), query, advancedFilters, 1,
                        candidateSelection = !Lab173TvCombinedFilterPolicy.needsBroaderCountryWindow(
                            advancedFilters, query))
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                metadataLookupError = error
                Log.w("SearchViewModel", "LAB155 filtered metadata unavailable, continuing safely", error)
                emptyList<AppAdapter.Item>()
            }
        } else null

        val mutableResults = targets.map { target ->
            ProviderResult(
                provider = target.provider,
                state = ProviderResult.State.Loading,
                displayName = target.displayName,
            )
        }.toMutableList()
        _state.emit(globalWallState(mutableResults.toList(), hasMore = false))

        val resultsMutex = Mutex()
        val globalProviderGate = Semaphore(3)
        var completedTargets = 0

        coroutineScope {
            targets.withIndex()
                .groupBy { it.value.provider.name }
                .values
                .forEach { group ->
                    launch {
                        val provider = group.first().value.provider
                        val previousSelection = ProviderEndpointRegistry.selectedOverride(provider.name)
                        try {
                            for ((index, target) in group) {
                                if (target.endpoint.isNullOrBlank()) {
                                    ProviderEndpointRegistry.selectOriginal(provider.name)
                                } else {
                                    ProviderEndpointRegistry.selectRemote(provider.name, target.endpoint)
                                }
                                (provider as? EndpointManagedProvider)?.onEndpointSelectionChanged()

                                val result = try {
                                    // Await metadata before acquiring one of the limited provider permits.
                                    // Otherwise slow TMDb blocks unrelated fast provider results.
                                    val sharedFilteredMetadata =
                                        if (advancedFilters.isActive && provider !is TmdbProvider)
                                            metadataTask?.await()
                                        else null
                                    val found = globalProviderGate.withPermit {
                                        when {
                                            provider is TmdbProvider && advancedFilters.isActive ->
                                                searchTmdbAdvanced(provider, query, advancedFilters, 1)
                                            advancedFilters.isActive ->
                                                searchProviderAdvanced(
                                                    provider, query, advancedFilters, 1,
                                                    sharedMetadata = sharedFilteredMetadata,
                                                    candidateLimit = Lab154SearchPacing.candidateLimit(
                                                        firstPage = true,
                                                        originatingProvider = provider.name == UserPreferences.currentProvider?.name),
                                                )
                                            query.isBlank() -> browseProvider(provider, 1)
                                            else -> searchProviderWithRecovery(provider, query, 1)
                                        }
                                    }
                                    val items = ParentalControlUtils.filterItems(
                                        found.onEach { item ->
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
                                        }
                                    )
                                    ProviderResult(
                                        provider = provider,
                                        state = ProviderResult.State.Success(items),
                                        displayName = target.displayName,
                                    )
                                } catch (e: Exception) {
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
            if (metadataLookupError != null &&
                (_state.value as? State.SuccessGlobalSearching)?.catalog?.posters?.isEmpty() == true) {
                _state.emit(State.FailedSearching(IllegalStateException(
                    "Filtri non disponibili: metadati bloccati o non raggiungibili. " +
                        "La schermata Cerca resta aperta; riprova senza questi filtri.",
                    metadataLookupError
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
            val gate = Semaphore(3)
            val mutex = Mutex()
            var batch = 0
            var totalAdded = 0
            var sourceHasMore = true
            while (batch < Lab170NativePagingPolicy.MAX_SCAN_BATCH &&
                totalAdded == 0 && sourceHasMore && generation == activeGlobalGeneration) {
                val nextPage = globalPage + 1
                val active = targets.withIndex().filter { it.index !in globalFinishedTargets }
                if (active.isEmpty()) break
                val strict = advancedFilters.italian != AdvancedSearchFilters.ItalianFilter.ALL ||
                    advancedFilters.country != AdvancedSearchFilters.CountryFilter.ALL ||
                    advancedFilters.platform != AdvancedSearchFilters.PlatformFilter.ALL ||
                    !advancedFilters.actorId.isNullOrBlank() ||
                    !advancedFilters.directorId.isNullOrBlank()
                val metadata = if (strict && tmdbConfigured()) {
                    searchTmdbAdvanced(
                        TmdbProvider(active.first().value.provider.language),
                        query, advancedFilters, nextPage,
                        candidateSelection = !Lab173TvCombinedFilterPolicy.needsBroaderCountryWindow(
                            advancedFilters, query)
                    )
                } else null

                var nextAdded = 0
                coroutineScope {
                    active.groupBy { it.value.provider.name }.values.forEach { group ->
                        launch {
                            val engine = group.first().value.provider
                            val selection = ProviderEndpointRegistry.selectedOverride(engine.name)
                            try {
                                for ((index, target) in group) {
                                    if (generation != activeGlobalGeneration) break
                                    if (target.endpoint.isNullOrBlank())
                                        ProviderEndpointRegistry.selectOriginal(engine.name)
                                    else ProviderEndpointRegistry.selectRemote(engine.name, target.endpoint)
                                    (engine as? EndpointManagedProvider)?.onEndpointSelectionChanged()
                                    val rawAndFiltered = try {
                                        gate.withPermit {
                                            nativeProviderPage(engine, nextPage, advancedFilters, query, metadata)
                                        }
                                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                                        throw cancel
                                    } catch (error: Exception) {
                                        Log.w("SearchViewModel", "LAB170 global native page for ${target.displayName}", error)
                                        emptyList<AppAdapter.Item>() to emptyList<AppAdapter.Item>()
                                    }
                                    val (raw, filtered) = rawAndFiltered
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
                                        if (!Lab170NativePagingPolicy.shouldContinue(
                                                nextPage, emptyCount, repeatedCount)) {
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

package com.streamflixreborn.streamflix.fragments.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters
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
    private var usingWebFallback = false
    private var globalTargets: List<GlobalSearchTarget> = emptyList()
    private var globalPage = 1
    private var globalLoadingMore = false
    private var globalEmptyPages = 0
    private var activeGlobalGeneration = 0

    private fun globalWallState(results: List<ProviderResult>, hasMore: Boolean = true): State.SuccessGlobalSearching {
        val previous = (_state.value as? State.SuccessGlobalSearching)?.catalog
        val catalog = GlobalCatalogMerger.merge(results, previous,
            preferredProviderName = UserPreferences.currentProvider?.name)
        val newestFirst = advancedFilters.sort != AdvancedSearchFilters.SortFilter.OLDEST
        val ordered = catalog.posters.sortedWith(compareBy<AppAdapter.Item> {
            val release = when (it) {
                is Movie -> it.released?.timeInMillis
                is TvShow -> it.released?.timeInMillis
                else -> null
            }
            if (release == null) Long.MAX_VALUE else if (newestFirst) -release else release
        })
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
        UserPreferences.tmdbApiKey.takeIf { it.isNotBlank() && it != "dummy" } != null ||
            BuildConfig.TMDB_API_KEY.takeIf { it.isNotBlank() && it != "dummy" } != null

    private suspend fun browseProvider(provider: Provider, page: Int): List<AppAdapter.Item> = coroutineScope {
        // Browse instead of asking providers to text-search an empty string.
        val movies = async { runCatching { provider.getMovies(page) }.getOrDefault(emptyList()) }
        val series = async { runCatching { provider.getTvShows(page) }.getOrDefault(emptyList()) }
        (movies.await() + series.await()).distinctBy { it.searchIdentityKey() }
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
        try {
            val provider = UserPreferences.currentProvider
                ?: throw IllegalStateException("Seleziona prima un provider")
            usingWebFallback = false
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
            _state.emit(State.SuccessSearching(filtered, hasMore = !usingWebFallback && filtered.isNotEmpty()))
        } catch (e: Exception) {
            Log.e("SearchViewModel", "executeSearch", e)
            _state.emit(State.FailedSearching(e))
        }
    }

    private suspend fun searchProviderAdvanced(
        provider: Provider,
        query: String,
        filters: AdvancedSearchFilters,
        page: Int,
        sharedMetadata: List<AppAdapter.Item>? = null,
        candidateLimit: Int = 24,
    ): List<AppAdapter.Item> = coroutineScope {
        val needsTmdbMetadata =
            filters.italian != AdvancedSearchFilters.ItalianFilter.ALL ||
                !filters.actorId.isNullOrBlank() ||
                !filters.directorId.isNullOrBlank() ||
                filters.platform != AdvancedSearchFilters.PlatformFilter.ALL ||
                filters.country != AdvancedSearchFilters.CountryFilter.ALL ||
                filters.effectiveGenres.isNotEmpty() ||
                filters.type == AdvancedSearchFilters.ContentType.CARTOON ||
                // Date ranges should discover relevant titles across decades, not only first catalog pages.
                (tmdbConfigured() && (filters.year != null ||
                    !filters.dateFrom.isNullOrBlank() || !filters.dateTo.isNullOrBlank()))

        val directResults = when {
            query.isNotBlank() -> searchProviderWithRecovery(provider, query, page)
            needsTmdbMetadata -> emptyList() // TMDb title discovery below drives this filter-only search.
            else -> {
                // Scan several real provider catalog pages before applying local year/type filters.
                // Each explicit load-more advances by the same bounded page window.
                val pages = Lab154SearchPacing.providerPages(page)
                pages.flatMap { sourcePage ->
                    browseProvider(provider, sourcePage)
                }.distinctBy { it.searchIdentityKey() }
            }
        }

        if (!needsTmdbMetadata) return@coroutineScope applyProviderFilters(directResults, filters)
        if (!tmdbConfigured()) {
            throw IllegalStateException("Filtri per Paese, genere, lingua, attori, registi o piattaforma richiedono la chiave TMDb gratuita nelle Impostazioni")
        }

        val metadataMatches = sharedMetadata ?: searchTmdbAdvanced(
            TmdbProvider(provider.language),
            query,
            filters,
            page,
        )

        if (metadataMatches.isEmpty()) return@coroutineScope emptyList()

        val metadataKeys = metadataMatches
            .mapNotNull(::filterMatchKey)
            .toSet()

        if (query.isNotBlank()) {
            return@coroutineScope applyProviderFilters(directResults, filters, trustMetadata = true)
                .filter { itemMatchesMetadata(it, metadataKeys) }
        }

        // No more than three simultaneous requests to a single provider engine.
        val providerGate = Semaphore(3)
        val discovered = metadataMatches
            .take(candidateLimit)
            .map { candidate ->
                async(Dispatchers.IO) {
                    providerGate.withPermit {
                        val title = when (candidate) {
                            is Movie -> candidate.title
                            is TvShow -> candidate.title
                            else -> ""
                        }
                        if (title.isBlank()) emptyList()
                        else runCatching { searchProviderWithRecovery(provider, title, 1) }
                            .getOrDefault(emptyList())
                    }
                }
            }
            .awaitAll()
            .flatten()
            .distinctBy { it.searchIdentityKey() }

        applyProviderFilters(discovered, filters, trustMetadata = true)
            .filter { itemMatchesMetadata(it, metadataKeys) }
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
        val itemTypeAndTitle = key.substringBeforeLast('|')
        val itemYear = key.substringAfterLast('|')
        return metadataKeys.any { meta ->
            meta.substringBeforeLast('|') == itemTypeAndTitle &&
                (itemYear.isBlank() || meta.substringAfterLast('|').isBlank())
        }
    }

    private fun normalizeFilterTitle(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
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
                    trustMetadata || genres.any { it.name.contains("anim", ignoreCase = true) }
                }
            }
            if (!typeOk) return@filter false

            val date = when (item) {
                is Movie -> item.released?.format("yyyy-MM-dd")
                is TvShow -> item.released?.format("yyyy-MM-dd")
                else -> null
            }
            if (!matchesYearFilter(date, filters)) return@filter false

            if (filters.effectiveGenres.isNotEmpty() && !trustMetadata) {
                val genres = when (item) {
                    is Movie -> item.genres
                    is TvShow -> item.genres
                    else -> emptyList()
                }
                if (genres.isEmpty() || genres.none { present ->
                    filters.effectiveGenres.any { chosen ->
                        present.name.equals(chosen.label, ignoreCase = true) ||
                            present.name.contains(chosen.label, ignoreCase = true)
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

    fun loadMore() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessSearching) {
            if (usingWebFallback) {
                _state.emit(State.SuccessSearching(currentState.results, hasMore = false))
                return@launch
            }

            _state.emit(State.SearchingMore)
            try {
                val provider = UserPreferences.currentProvider!!
                var nextPage = page
                val existingKeys = currentState.results
                    .asSequence()
                    .map { it.searchIdentityKey() }
                    .toHashSet()
                var newUniqueResults: List<AppAdapter.Item> = emptyList()
                var attempts = 0
                // A partially filtered page may be empty while later provider pages contain matches.
                // Bounded skips prevent infinite retries and preserve provider rate limits.
                while (attempts < 3 && newUniqueResults.isEmpty()) {
                    nextPage++
                    attempts++
                    val results = when {
                        provider is TmdbProvider && (advancedFilters.isActive || query.isNotBlank()) ->
                            searchTmdbAdvanced(provider, query, advancedFilters, nextPage)
                        advancedFilters.isActive -> searchProviderAdvanced(provider, query, advancedFilters, nextPage)
                        query.isBlank() -> browseProvider(provider, nextPage)
                        else -> searchProviderWithRecovery(provider, query, nextPage)
                    }
                    if (results.isEmpty() && !advancedFilters.isActive && query.isBlank()) break
                    newUniqueResults = ParentalControlUtils.filterItems(results)
                        .filterNot { it.searchIdentityKey() in existingKeys }
                }
                page = nextPage
                _state.emit(
                    State.SuccessSearching(
                        results = currentState.results + newUniqueResults,
                        hasMore = newUniqueResults.isNotEmpty(),
                    )
                )
            } catch (e: Exception) {
                Log.e("SearchViewModel", "loadMore: ", e)
                _state.emit(State.FailedSearching(e))
            }
        }
    }

    private fun normalizeFilters(filters: AdvancedSearchFilters): AdvancedSearchFilters {
        val hasRange = !filters.dateFrom.isNullOrBlank() || !filters.dateTo.isNullOrBlank()
        return if (hasRange) filters.copy(year = null) else filters
    }

    private suspend fun searchTmdbAdvanced(
        provider: TmdbProvider,
        query: String,
        filters: AdvancedSearchFilters,
        page: Int,
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
        val movieSort = when (filters.sort) {
            AdvancedSearchFilters.SortFilter.DATE -> "primary_release_date.desc"
            AdvancedSearchFilters.SortFilter.OLDEST -> "primary_release_date.asc"
            AdvancedSearchFilters.SortFilter.VOTE -> "vote_average.desc"
            AdvancedSearchFilters.SortFilter.POPULARITY -> "popularity.desc"
        }
        val tvSort = when (filters.sort) {
            AdvancedSearchFilters.SortFilter.DATE -> "first_air_date.desc"
            AdvancedSearchFilters.SortFilter.OLDEST -> "first_air_date.asc"
            AdvancedSearchFilters.SortFilter.VOTE -> "vote_average.desc"
            AdvancedSearchFilters.SortFilter.POPULARITY -> "popularity.desc"
        }

        if (query.isNotBlank()) {
            val queryVariants = buildQueryVariants(query)
            val pagesPerVariant = if (queryVariants.size <= 2) 3 else 2
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
            val pages = Lab154SearchPacing.metadataPages(page)
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
            val pages = Lab154SearchPacing.metadataPages(page)
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
        when (filters.sort) {
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
        val selectedYear = filters.year
        val fromYear = filters.dateFrom?.take(4)?.toIntOrNull()
        val toYear = filters.dateTo?.take(4)?.toIntOrNull()
        val hasYearFilter = selectedYear != null || fromYear != null || toYear != null

        if (!hasYearFilter) return true
        val itemYear = date?.take(4)?.toIntOrNull() ?: return false

        return (selectedYear == null || itemYear == selectedYear) &&
            (fromYear == null || itemYear >= fromYear) &&
            (toYear == null || itemYear <= toYear)
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
        if (needsGlobalMetadata && !tmdbConfigured()) {
            _state.emit(State.FailedSearching(IllegalStateException(
                "I filtri globali per Paese, genere, lingua e metadati richiedono una chiave TMDb valida"
            )))
            return@launch
        }
        // Discover metadata concurrently rather than blocking the local provider's
        // immediate text results. All displayed items still pass their filters.
        var metadataLookupError: Exception? = null
        val metadataTask = if (needsGlobalMetadata) async {
            // An HTTP 451 / blocked metadata endpoint is NOT an unhandled
            // child-coroutine failure. Keep the app-level Cerca screen alive.
            try {
                searchTmdbAdvanced(TmdbProvider(currentLanguage), query, advancedFilters, 1)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                metadataLookupError = error
                Log.w("SearchViewModel", "LAB155 filtered metadata unavailable, continuing safely", error)
                emptyList()
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
                                    val found = globalProviderGate.withPermit {
                                        when {
                                            provider is TmdbProvider && advancedFilters.isActive ->
                                                searchTmdbAdvanced(provider, query, advancedFilters, 1)
                                            advancedFilters.isActive ->
                                                searchProviderAdvanced(
                                                    provider, query, advancedFilters, 1,
                                                    sharedMetadata = metadataTask?.await(),
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
                                    if (completedTargets == 1 || completedTargets % 3 == 0 || completedTargets == targets.size) {
                                        _state.emit(globalWallState(mutableResults.toList(), hasMore = completedTargets == targets.size))
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
            if (metadataLookupError != null &&
                (_state.value as? State.SuccessGlobalSearching)?.catalog?.posters?.isEmpty() == true) {
                _state.emit(State.FailedSearching(IllegalStateException(
                    "Filtri non disponibili: metadati bloccati o non raggiungibili. " +
                        "La schermata Cerca resta aperta; riprova senza questi filtri.",
                    metadataLookupError
                )))
                return@launch
            }
            // Continue filling the catalog without a remote-control scroll event.
            // Two more page windows, bounded to avoid monopolizing provider quotas.
            repeat(2) {
                kotlinx.coroutines.delay(450)
                if (generation != activeGlobalGeneration ||
                    (_state.value as? State.SuccessGlobalSearching)?.hasMore != true) return@repeat
                loadMoreGlobal().join()
            }
        }
    }

    /** LAB152: independently page all global sources while preserving the existing wall. */
    fun loadMoreGlobal() = viewModelScope.launch(Dispatchers.IO) {
        if (globalLoadingMore || globalTargets.isEmpty()) return@launch
        val existing = _state.value as? State.SuccessGlobalSearching ?: return@launch
        if (!existing.hasMore) return@launch
        globalLoadingMore = true
        try {
            val currentTargets = globalTargets.toList()
            val nextPage = globalPage + 1
            val currentLanguage = currentTargets.firstOrNull()?.provider?.language ?: "it"
            val needsMetadata = advancedFilters.isActive && (
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
            val metadata = if (needsMetadata && tmdbConfigured()) {
                searchTmdbAdvanced(TmdbProvider(currentLanguage), query, advancedFilters, nextPage)
            } else null
            // Result positions remain in target order; distinct mirrors can share the same display name.
            val updated = currentTargets.mapIndexed { index, target ->
                existing.providerResults.getOrNull(index)
                    ?: ProviderResult(target.provider, ProviderResult.State.Loading, target.displayName)
            }.toMutableList()
            val gate = Semaphore(3)
            val mergeMutex = Mutex()
            var addedCount = 0
            coroutineScope {
                currentTargets.withIndex().groupBy { it.value.provider.name }.values.forEach { group ->
                    launch {
                        val engine = group.first().value.provider
                        val saved = ProviderEndpointRegistry.selectedOverride(engine.name)
                        try {
                            for ((index, target) in group) {
                                if (target.endpoint.isNullOrBlank()) ProviderEndpointRegistry.selectOriginal(engine.name)
                                else ProviderEndpointRegistry.selectRemote(engine.name, target.endpoint)
                                (engine as? EndpointManagedProvider)?.onEndpointSelectionChanged()
                                val found = try {
                                    gate.withPermit {
                                        when {
                                            engine is TmdbProvider && advancedFilters.isActive ->
                                                searchTmdbAdvanced(engine, query, advancedFilters, nextPage)
                                            advancedFilters.isActive ->
                                                searchProviderAdvanced(
                                                    engine, query, advancedFilters, nextPage,
                                                    sharedMetadata = metadata, candidateLimit = Lab154SearchPacing.candidateLimit(firstPage = false, originatingProvider = false),
                                                )
                                            query.isBlank() -> browseProvider(engine, nextPage)
                                            else -> searchProviderWithRecovery(engine, query, nextPage)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w("SearchViewModel", "Global next page: ${engine.name}: ${e.message}")
                                    emptyList()
                                }
                                val tagged = ParentalControlUtils.filterItems(found.onEach { item ->
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
                                })
                                mergeMutex.withLock {
                                    val prior = (updated[index].state as? ProviderResult.State.Success)?.results.orEmpty()
                                    val known = prior.map { it.searchIdentityKey() }.toHashSet()
                                    val fresh = tagged.filter { it.searchIdentityKey() !in known }
                                    addedCount += fresh.size
                                    updated[index] = ProviderResult(
                                        engine, ProviderResult.State.Success(prior + fresh), target.displayName
                                    )
                                }
                            }
                        } finally {
                            ProviderEndpointRegistry.restoreSelection(engine.name, saved)
                            (engine as? EndpointManagedProvider)?.onEndpointSelectionChanged()
                        }
                    }
                }
            }
            globalPage = nextPage
            // If all sources produced nothing, stop automatic paging to avoid endless calls.
            globalEmptyPages = if (addedCount == 0) globalEmptyPages + 1 else 0
            _state.emit(globalWallState(updated,
                hasMore = nextPage < 15 && globalEmptyPages < 2))
        } catch (e: Exception) {
            Log.e("SearchViewModel", "loadMoreGlobal", e)
            _state.emit(existing.copy(hasMore = false))
        } finally {
            globalLoadingMore = false
        }
    }
}

private fun AppAdapter.Item.searchIdentityKey(): String = when (this) {
    is Movie -> "movie:$id"
    is TvShow -> "tvshow:$id"
    is People -> "people:$id"
    else -> "${this::class.java.name}:${hashCode()}"
}

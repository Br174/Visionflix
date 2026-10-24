package com.streamflixreborn.streamflix.fragments.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.sync.withLock
import java.util.Locale

sealed class State {
    data object Searching : State()
    data object SearchingMore : State()
    data class SuccessSearching(val results: List<AppAdapter.Item>, val hasMore: Boolean) : State()
    data class FailedSearching(val error: Exception) : State()
    data object GlobalSearching : State()
    data class SuccessGlobalSearching(val providerResults: List<ProviderResult>) : State()
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
                people.map { person ->
                    async {
                        val image = person.profilePath?.w500 ?: runCatching {
                            TMDb3.People.details(
                                personId = person.id,
                                language = language,
                            ).profilePath?.w500
                        }.getOrNull()
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

    private suspend fun executeSearch(
        query: String,
        filters: AdvancedSearchFilters,
        targetPage: Int,
    ) {
        _state.emit(State.Searching)
        try {
            val provider = UserPreferences.currentProvider!!
            usingWebFallback = false

            val results = if (provider is TmdbProvider && (filters.isActive || query.isNotBlank())) {
                searchTmdbAdvanced(provider, query, filters, targetPage)
            } else {
                applyProviderFilters(provider.search(query, targetPage), filters)
            }

            this@SearchViewModel.query = query
            page = targetPage
            val filtered = ParentalControlUtils.filterItems(results)
            _state.emit(
                State.SuccessSearching(
                    filtered,
                    hasMore = !usingWebFallback && filtered.isNotEmpty(),
                )
            )
        } catch (e: Exception) {
            Log.e("SearchViewModel", "executeSearch: ", e)
            _state.emit(State.FailedSearching(e))
        }
    }

    private fun applyProviderFilters(
        results: List<AppAdapter.Item>,
        filters: AdvancedSearchFilters,
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
                    genres.any { it.name.contains("anim", ignoreCase = true) }
                }
            }
            if (!typeOk) return@filter false

            val date = when (item) {
                is Movie -> item.released?.format("yyyy-MM-dd")
                is TvShow -> item.released?.format("yyyy-MM-dd")
                else -> null
            }
            if (!matchesYearFilter(date, filters)) return@filter false

            if (filters.genre != AdvancedSearchFilters.GenreFilter.ALL) {
                val genres = when (item) {
                    is Movie -> item.genres
                    is TvShow -> item.genres
                    else -> emptyList()
                }
                if (genres.isNotEmpty() && genres.none {
                        it.name.equals(filters.genre.label, ignoreCase = true) ||
                            it.name.contains(filters.genre.label, ignoreCase = true)
                    }) return@filter false
            }
            true
        }

        return when (filters.sort) {
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
                val nextPage = page + 1
                val results = if (provider is TmdbProvider && (advancedFilters.isActive || query.isNotBlank())) {
                    searchTmdbAdvanced(provider, query, advancedFilters, nextPage)
                } else {
                    applyProviderFilters(provider.search(query, nextPage), advancedFilters)
                }

                val filtered = ParentalControlUtils.filterItems(results)
                val existingKeys = currentState.results
                    .asSequence()
                    .map { it.searchIdentityKey() }
                    .toHashSet()
                val newUniqueResults = filtered.filterNot { it.searchIdentityKey() in existingKeys }
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
        val includeMovies = filters.type == AdvancedSearchFilters.ContentType.ALL ||
            filters.type == AdvancedSearchFilters.ContentType.MOVIE ||
            filters.type == AdvancedSearchFilters.ContentType.CARTOON
        val includeTv = filters.type == AdvancedSearchFilters.ContentType.ALL ||
            filters.type == AdvancedSearchFilters.ContentType.TV ||
            filters.type == AdvancedSearchFilters.ContentType.CARTOON

        val language = provider.language
        val italianOnly = filters.italian == AdvancedSearchFilters.ItalianFilter.YES
        val excludeItalian = filters.italian == AdvancedSearchFilters.ItalianFilter.NO
        val cartoonOnly = filters.type == AdvancedSearchFilters.ContentType.CARTOON
        val selectedGenre = filters.genre
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
            AdvancedSearchFilters.SortFilter.VOTE -> "vote_average.desc"
            AdvancedSearchFilters.SortFilter.POPULARITY -> "popularity.desc"
        }
        val tvSort = when (filters.sort) {
            AdvancedSearchFilters.SortFilter.DATE -> "first_air_date.desc"
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

            val needsDiscoverConstraint = platformProviderIds != null
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
                        if (cartoonOnly && 16 !in multi.genresIds) return@mapNotNull null
                        if (selectedGenre != AdvancedSearchFilters.GenreFilter.ALL) {
                            val genreId = selectedGenre.movieId ?: return@mapNotNull null
                            if (genreId !in multi.genresIds) return@mapNotNull null
                        }
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
                        if (cartoonOnly && 16 !in multi.genresIds) return@mapNotNull null
                        if (selectedGenre != AdvancedSearchFilters.GenreFilter.ALL) {
                            val genreId = selectedGenre.tvId ?: return@mapNotNull null
                            if (genreId !in multi.genresIds) return@mapNotNull null
                        }
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

            val genreIds = buildList {
                if (cartoonOnly) add(16)
                selectedGenre.movieId?.let { add(it) }
            }.distinct()
            if (genreIds.isNotEmpty()) params["with_genres"] = genreIds.joinToString(",")

            filters.year?.let { params["primary_release_year"] = it.toString() }
            filters.dateFrom?.takeIf { it.isNotBlank() }?.let { params["primary_release_date.gte"] = it }
            filters.dateTo?.takeIf { it.isNotBlank() }?.let { params["primary_release_date.lte"] = it }
            TMDb3.Discover.movie(params).results
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

            val genreIds = buildList {
                if (cartoonOnly) add(16)
                selectedGenre.tvId?.let { add(it) }
            }.distinct()
            if (genreIds.isNotEmpty()) params["with_genres"] = genreIds.joinToString(",")

            filters.year?.let { params["first_air_date_year"] = it.toString() }
            filters.dateFrom?.takeIf { it.isNotBlank() }?.let { params["first_air_date.gte"] = it }
            filters.dateTo?.takeIf { it.isNotBlank() }?.let { params["first_air_date.lte"] = it }
            TMDb3.Discover.tv(params).results
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

    fun searchGlobal(query: String, currentLanguage: String) = viewModelScope.launch(Dispatchers.IO) {
        selectedActorId = null
        selectedActorName = ""
        selectedDirectorId = null
        selectedDirectorName = ""
        _actorSuggestions.value = emptyList()
        _directorSuggestions.value = emptyList()
        _state.emit(State.GlobalSearching)

        val isCurrentProviderIptv = UserPreferences.currentProvider is IptvProvider
        val originals = Provider.providers.keys
            .filter { it.language == currentLanguage && (it is IptvProvider) == isCurrentProviderIptv }
            .map { GlobalSearchTarget(it, "Originale · ${it.name}", null) }

        val remotes = if (!isCurrentProviderIptv) {
            ProviderEndpointRegistry.refreshIfNeeded(Provider.providers.keys)
                .filter { it.language == currentLanguage && it.healthy }
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
        if (targets.isEmpty()) {
            _state.emit(State.SuccessGlobalSearching(emptyList()))
            return@launch
        }

        val mutableResults = targets.map { target ->
            ProviderResult(
                provider = target.provider,
                state = ProviderResult.State.Loading,
                displayName = target.displayName,
            )
        }.toMutableList()
        _state.emit(State.SuccessGlobalSearching(mutableResults.toList()))

        val stateComparator = compareBy<ProviderResult> { providerResult ->
            when (val state = providerResult.state) {
                is ProviderResult.State.Success -> if (state.results.isNotEmpty()) 1 else 3
                is ProviderResult.State.Loading -> 2
                is ProviderResult.State.Error -> 4
            }
        }
        val resultsMutex = Mutex()

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
                                    val items = ParentalControlUtils.filterItems(
                                        provider.search(query).onEach { item ->
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
                                    mutableResults[index] = result
                                    _state.emit(
                                        State.SuccessGlobalSearching(
                                            mutableResults.toList().sortedWith(stateComparator)
                                        )
                                    )
                                }
                            }
                        } finally {
                            ProviderEndpointRegistry.restoreSelection(provider.name, previousSelection)
                            (provider as? EndpointManagedProvider)?.onEndpointSelectionChanged()
                        }
                    }
                }
        }
    }
}

private fun AppAdapter.Item.searchIdentityKey(): String = when (this) {
    is Movie -> "movie:$id"
    is TvShow -> "tvshow:$id"
    is People -> "people:$id"
    else -> "${this::class.java.name}:${hashCode()}"
}

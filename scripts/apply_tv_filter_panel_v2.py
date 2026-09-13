from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"anchor not found: {label}")
    return text.replace(old, new, 1)


def sub_once(text, pattern, replacement, label):
    new, n = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if n != 1:
        raise SystemExit(f"regex anchor not found or ambiguous: {label} ({n})")
    return new

# 1) AdvancedSearchFilters: shared data model only; defaults preserve Mobile behavior.
p = ROOT / "app/src/main/java/com/streamflixreborn/streamflix/providers/AdvancedSearch.kt"
t = p.read_text()
t = replace_once(t,
'''    val actorId: String? = null,
    val actorName: String = "",
) {''',
'''    val actorId: String? = null,
    val actorName: String = "",
    val directorId: String? = null,
    val directorName: String = "",
    val sort: SortFilter = SortFilter.POPULARITY,
    val platform: PlatformFilter = PlatformFilter.ALL,
) {''', "advanced fields")
t = replace_once(t,
'''    enum class ContentType { ALL, MOVIE, TV, CARTOON }
    enum class ItalianFilter { ALL, YES, NO }
''',
'''    enum class ContentType { ALL, MOVIE, TV, CARTOON }
    enum class ItalianFilter { ALL, YES, NO }

    enum class SortFilter(val label: String) {
        POPULARITY("Popolarità"),
        DATE("Data"),
        VOTE("Voto"),
    }

    enum class PlatformFilter(val label: String, val providerIds: String?) {
        ALL("Tutte", null),
        NETFLIX("Netflix", "8"),
        DISNEY_PLUS("Disney+", "337"),
        PRIME_VIDEO("Prime Video", "119"),
        APPLE_TV_PLUS("Apple TV+", "350"),
        SKY_NOW("Sky / NOW", "29|39"),
        HBO_MAX("HBO Max", "1899"),
    }
''', "advanced enums")
t = replace_once(t,
'''            !dateTo.isNullOrBlank() ||
            !actorId.isNullOrBlank()''',
'''            !dateTo.isNullOrBlank() ||
            !actorId.isNullOrBlank() ||
            !directorId.isNullOrBlank() ||
            sort != SortFilter.POPULARITY ||
            platform != PlatformFilter.ALL''', "advanced active")
p.write_text(t)

# 2) AppAdapter/PeopleViewHolder: allow Search TV to select a person card with OK.
p = ROOT / "app/src/main/java/com/streamflixreborn/streamflix/adapters/AppAdapter.kt"
t = p.read_text()
t = replace_once(t,
'''            is PeopleViewHolder -> holder.bind(
                items[adjustedPosition] as People
            ) // Tu original no pasaba listener, lo respeto''',
'''            is PeopleViewHolder -> holder.bind(
                items[adjustedPosition] as People,
                onPeopleClickListener,
            )''', "AppAdapter People listener")
p.write_text(t)

p = ROOT / "app/src/main/java/com/streamflixreborn/streamflix/adapters/viewholders/PeopleViewHolder.kt"
t = p.read_text()
t = replace_once(t,
'''    fun bind(people: People) {
        this.people = people

        when (_binding) {
            is ItemPeopleMobileBinding -> displayMobileItem(_binding)
            is ItemPeopleTvBinding -> displayTvItem(_binding)
        }
    }


    private fun displayMobileItem(binding: ItemPeopleMobileBinding) {''',
'''    fun bind(people: People, onPeopleClickListener: ((People) -> Unit)? = null) {
        this.people = people

        when (_binding) {
            is ItemPeopleMobileBinding -> displayMobileItem(_binding, onPeopleClickListener)
            is ItemPeopleTvBinding -> displayTvItem(_binding, onPeopleClickListener)
        }
    }


    private fun displayMobileItem(
        binding: ItemPeopleMobileBinding,
        onPeopleClickListener: ((People) -> Unit)?,
    ) {''', "People bind signature")
t = replace_once(t,
'''            setOnClickListener {
                when (context.toActivity()?.getCurrentFragment()) {''',
'''            setOnClickListener {
                if (onPeopleClickListener != null) {
                    onPeopleClickListener(people)
                    return@setOnClickListener
                }
                when (context.toActivity()?.getCurrentFragment()) {''', "People mobile click")
t = replace_once(t,
'''    private fun displayTvItem(binding: ItemPeopleTvBinding) {
        binding.root.apply {
            setOnClickListener {
                when (context.toActivity()?.getCurrentFragment()) {''',
'''    private fun displayTvItem(
        binding: ItemPeopleTvBinding,
        onPeopleClickListener: ((People) -> Unit)?,
    ) {
        binding.root.apply {
            setOnClickListener {
                if (onPeopleClickListener != null) {
                    onPeopleClickListener(people)
                    return@setOnClickListener
                }
                when (context.toActivity()?.getCurrentFragment()) {''', "People TV click")
p.write_text(t)

# 3) SearchViewModel: director, platform, sort and better person-photo fallback.
p = ROOT / "app/src/main/java/com/streamflixreborn/streamflix/fragments/search/SearchViewModel.kt"
t = p.read_text()
t = replace_once(t,
'''    private val _actorSuggestions = MutableStateFlow<List<People>>(emptyList())
    val actorSuggestions: Flow<List<People>> = _actorSuggestions
''',
'''    private val _actorSuggestions = MutableStateFlow<List<People>>(emptyList())
    val actorSuggestions: Flow<List<People>> = _actorSuggestions
    private val _directorSuggestions = MutableStateFlow<List<People>>(emptyList())
    val directorSuggestions: Flow<List<People>> = _directorSuggestions
''', "director flow")
t = replace_once(t,
'''    var selectedActorName: String = ""
        private set

    private var page = 1''',
'''    var selectedActorName: String = ""
        private set
    var selectedDirectorId: String? = null
        private set
    var selectedDirectorName: String = ""
        private set

    private var page = 1''', "director selection fields")
t = replace_once(t,
'''        selectedActorId = null
        selectedActorName = ""
        _actorSuggestions.value = emptyList()
        executeSearch(query, advancedFilters, 1)''',
'''        selectedActorId = null
        selectedActorName = ""
        selectedDirectorId = null
        selectedDirectorName = ""
        _actorSuggestions.value = emptyList()
        _directorSuggestions.value = emptyList()
        executeSearch(query, advancedFilters, 1)''', "search reset director")
t = replace_once(t,
'''            selectedActorId = advancedFilters.actorId
            selectedActorName = advancedFilters.actorName
            _actorSuggestions.value = emptyList()
            executeSearch(query, advancedFilters, 1)''',
'''            selectedActorId = advancedFilters.actorId
            selectedActorName = advancedFilters.actorName
            selectedDirectorId = advancedFilters.directorId
            selectedDirectorName = advancedFilters.directorName
            _actorSuggestions.value = emptyList()
            _directorSuggestions.value = emptyList()
            executeSearch(query, advancedFilters, 1)''', "advanced set director")
t = replace_once(t,
'''    fun clearActorSuggestions() {
        _actorSuggestions.value = emptyList()
    }

    fun searchActors(actorQuery: String) = viewModelScope.launch(Dispatchers.IO) {
        val cleanQuery = actorQuery.trim()
        if (cleanQuery.length < 2) {
            _actorSuggestions.value = emptyList()
            return@launch
        }

        try {
            val language = UserPreferences.currentProvider?.language
                ?.takeIf { it.isNotBlank() }
                ?: "it"

            _actorSuggestions.value = TMDb3.Search.multi(
                query = cleanQuery,
                page = 1,
                language = language,
            ).results
                .filterIsInstance<TMDb3.Person>()
                .sortedByDescending { it.popularity }
                .distinctBy { it.id }
                .take(12)
                .map { person ->
                    People(
                        id = person.id.toString(),
                        name = person.name,
                        image = person.profilePath?.w500,
                    )
                }
        } catch (e: Exception) {
            Log.e("SearchViewModel", "searchActors: ", e)
            _actorSuggestions.value = emptyList()
        }
    }
''',
'''    fun clearActorSuggestions() {
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
''', "people/director search")

t = replace_once(t,
'''        val selectedGenre = filters.genre
        val actorId = filters.actorId?.takeIf { it.isNotBlank() }
''',
'''        val selectedGenre = filters.genre
        val actorId = filters.actorId?.takeIf { it.isNotBlank() }
        val directorId = filters.directorId?.takeIf { it.isNotBlank() }
        val platformProviderIds = filters.platform.providerIds
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
''', "advanced search variables")

t = replace_once(t,
'''            val actorCreditsDeferred = actorId?.let { id ->
                async { provider.getPeople(id, page = 1).filmography }
            }

            val searchResults = searchDeferred.awaitAll().flatten()
            val actorCredits = actorCreditsDeferred?.await().orEmpty()
            val actorMovieIds = actorCredits.filterIsInstance<Movie>().mapTo(hashSetOf()) { it.id }
            val actorTvIds = actorCredits.filterIsInstance<TvShow>().mapTo(hashSetOf()) { it.id }
            val restrictToActor = actorId != null

            return@coroutineScope searchResults.mapNotNull { multi ->''',
'''            val actorCreditsDeferred = actorId?.let { id ->
                async { provider.getPeople(id, page = 1).filmography }
            }

            val needsDiscoverConstraint = directorId != null || platformProviderIds != null
            val movieConstraintDeferred = if (needsDiscoverConstraint && includeMovies) async {
                (firstTmdbPage..firstTmdbPage + 2).map { discoverPage ->
                    async {
                        val params = mutableMapOf(
                            "language" to language,
                            "page" to discoverPage.toString(),
                            "sort_by" to movieSort,
                            "include_adult" to "false",
                        )
                        directorId?.let { params["with_crew"] = it }
                        platformProviderIds?.let {
                            params["watch_region"] = "IT"
                            params["with_watch_providers"] = it
                        }
                        TMDb3.Discover.movie(params).results.map { it.id.toString() }
                    }
                }.awaitAll().flatten().toHashSet()
            } else null
            val tvConstraintDeferred = if (needsDiscoverConstraint && includeTv) async {
                (firstTmdbPage..firstTmdbPage + 2).map { discoverPage ->
                    async {
                        val params = mutableMapOf(
                            "language" to language,
                            "page" to discoverPage.toString(),
                            "sort_by" to tvSort,
                            "include_adult" to "false",
                        )
                        directorId?.let { params["with_crew"] = it }
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

            return@coroutineScope sortedSearchResults.mapNotNull { multi ->''', "query constraint block")

t = replace_once(t,
'''                        if (restrictToActor && multi.id.toString() !in actorMovieIds) return@mapNotNull null
                        if (!matchesYearFilter(multi.releaseDate, filters)) return@mapNotNull null
''',
'''                        if (restrictToActor && multi.id.toString() !in actorMovieIds) return@mapNotNull null
                        if (constrainedMovieIds != null && multi.id.toString() !in constrainedMovieIds) return@mapNotNull null
                        if (!matchesYearFilter(multi.releaseDate, filters)) return@mapNotNull null
''', "movie query constraints")
t = replace_once(t,
'''                        if (restrictToActor && multi.id.toString() !in actorTvIds) return@mapNotNull null
                        if (!matchesYearFilter(multi.firstAirDate, filters)) return@mapNotNull null
''',
'''                        if (restrictToActor && multi.id.toString() !in actorTvIds) return@mapNotNull null
                        if (constrainedTvIds != null && multi.id.toString() !in constrainedTvIds) return@mapNotNull null
                        if (!matchesYearFilter(multi.firstAirDate, filters)) return@mapNotNull null
''', "tv query constraints")
t = replace_once(t, '"sort_by" to "popularity.desc",', '"sort_by" to movieSort,', "movie sort param")
# replace second fixed sort only after first was changed
t = replace_once(t, '"sort_by" to "popularity.desc",', '"sort_by" to tvSort,', "tv sort param")
t = replace_once(t,
'''            if (italianOnly) params["with_original_language"] = "it"
            actorId?.let { params["with_cast"] = it }

            val genreIds = buildList {''',
'''            if (italianOnly) params["with_original_language"] = "it"
            actorId?.let { params["with_cast"] = it }
            directorId?.let { params["with_crew"] = it }
            platformProviderIds?.let {
                params["watch_region"] = "IT"
                params["with_watch_providers"] = it
            }

            val genreIds = buildList {''', "movie director platform")
# same block for TV
t = replace_once(t,
'''            if (italianOnly) params["with_original_language"] = "it"
            actorId?.let { params["with_cast"] = it }

            val genreIds = buildList {''',
'''            if (italianOnly) params["with_original_language"] = "it"
            actorId?.let { params["with_cast"] = it }
            directorId?.let { params["with_crew"] = it }
            platformProviderIds?.let {
                params["watch_region"] = "IT"
                params["with_watch_providers"] = it
            }

            val genreIds = buildList {''', "tv director platform")
t = replace_once(t,
'''        (movieItems + tvItems)
            .distinctBy { it.searchIdentityKey() }
            .sortedByDescending {
                when (it) {
                    is Movie -> it.rating
                    is TvShow -> it.rating
                    else -> 0.0
                }
            }
''',
'''        val combined = (movieItems + tvItems)
            .distinctBy { it.searchIdentityKey() }
        when (filters.sort) {
            AdvancedSearchFilters.SortFilter.DATE -> combined.sortedByDescending {
                when (it) {
                    is Movie -> it.released.orEmpty()
                    is TvShow -> it.released.orEmpty()
                    else -> ""
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
''', "combined sort")
t = replace_once(t,
'''        selectedActorId = null
        selectedActorName = ""
        _actorSuggestions.value = emptyList()
        _state.emit(State.GlobalSearching)''',
'''        selectedActorId = null
        selectedActorName = ""
        selectedDirectorId = null
        selectedDirectorName = ""
        _actorSuggestions.value = emptyList()
        _directorSuggestions.value = emptyList()
        _state.emit(State.GlobalSearching)''', "global reset director")
p.write_text(t)

# 4) SearchTvFragment: TV-only UI/navigation. Mobile fragment/layout untouched.
p = ROOT / "app/src/main/java/com/streamflixreborn/streamflix/fragments/search/SearchTvFragment.kt"
t = p.read_text()
t = replace_once(t,
'''    private var actorSearchJob: Job? = null
    private var isGlobalSearchChecked = false
    private var currentGridColumns = 1
    private var searchHeaderOffset = 0
    private var suppressActorTextChange = false
''',
'''    private var actorSearchJob: Job? = null
    private var directorSearchJob: Job? = null
    private var isGlobalSearchChecked = false
    private var currentGridColumns = 1
    private var searchHeaderOffset = 0
    private var suppressActorTextChange = false
    private var suppressDirectorTextChange = false
    private var filterPanelExpanded = true
    private var pendingFocusFirstResult = false
''', "TV state fields")
t = replace_once(t,
'''    private var fromSelector: DropdownSelector<Int?>? = null
    private var toSelector: DropdownSelector<Int?>? = null
    private var actorEdit: EditText? = null
    private var actorSuggestionsGrid: HorizontalGridView? = null
    private var actorFallbackSuggestions: LinearLayout? = null
    private var btnApplyFilters: TextView? = null
    private var btnResetFilters: TextView? = null
''',
'''    private var fromSelector: DropdownSelector<Int?>? = null
    private var toSelector: DropdownSelector<Int?>? = null
    private var sortSelector: DropdownSelector<AdvancedSearchFilters.SortFilter>? = null
    private var platformSelector: DropdownSelector<AdvancedSearchFilters.PlatformFilter>? = null
    private var actorEdit: EditText? = null
    private var directorEdit: EditText? = null
    private var actorSuggestionsGrid: HorizontalGridView? = null
    private var directorSuggestionsGrid: HorizontalGridView? = null
    private var actorFallbackSuggestions: LinearLayout? = null
    private var directorFallbackSuggestions: LinearLayout? = null
    private var btnActorSearch: TextView? = null
    private var btnDirectorSearch: TextView? = null
    private var btnApplyFilters: TextView? = null
    private var btnResetFilters: TextView? = null
    private var filterHandle: TextView? = null
''', "TV control fields")
t = replace_once(t,
'''    private val actorSuggestionsAdapter by lazy {
        AppAdapter().apply {
            onPeopleClickListener = { people -> selectActor(people) }
        }
    }
''',
'''    private val actorSuggestionsAdapter by lazy {
        AppAdapter().apply {
            onPeopleClickListener = { people -> selectActor(people) }
        }
    }

    private val directorSuggestionsAdapter by lazy {
        AppAdapter().apply {
            onPeopleClickListener = { people -> selectDirector(people) }
        }
    }
''', "director adapter")
t = replace_once(t,
'''            collectSearchStateSafely()
            collectActorSuggestionsSafely()''',
'''            collectSearchStateSafely()
            collectActorSuggestionsSafely()
            collectDirectorSuggestionsSafely()''', "collect director")
t = replace_once(t,
'''        actorSearchJob?.cancel()
        voiceHelper?.stopRecognition()
        voiceHelper = null
        actorSuggestionsGrid = null
        actorFallbackSuggestions = null
        actorEdit = null
''',
'''        actorSearchJob?.cancel()
        directorSearchJob?.cancel()
        voiceHelper?.stopRecognition()
        voiceHelper = null
        actorSuggestionsGrid = null
        directorSuggestionsGrid = null
        actorFallbackSuggestions = null
        directorFallbackSuggestions = null
        actorEdit = null
        directorEdit = null
''', "destroy director")
t = replace_once(t,
'''        fromSelector = null
        toSelector = null
        btnApplyFilters = null
        btnResetFilters = null
''',
'''        fromSelector = null
        toSelector = null
        sortSelector = null
        platformSelector = null
        btnActorSearch = null
        btnDirectorSearch = null
        btnApplyFilters = null
        btnResetFilters = null
        filterHandle = null
''', "destroy selectors")

# First poster row goes back to the tiny filter handle, not directly into an overlaying panel.
t = replace_once(t,
'''                    if (position in 0 until currentGridColumns.coerceAtLeast(1)) {
                        btnApplyFilters?.let { child?.itemView?.nextFocusUpId = it.id }
                    }''',
'''                    if (position in 0 until currentGridColumns.coerceAtLeast(1)) {
                        val target = filterHandle?.id ?: btnApplyFilters?.id
                        if (target != null) child?.itemView?.nextFocusUpId = target
                    }''', "first row up focus")

# Add director collector.
t = replace_once(t,
'''    private fun buildAdvancedFiltersSafely() {''',
'''    private fun collectDirectorSuggestionsSafely() {
        val vm = searchViewModel ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                vm.directorSuggestions
                    .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                    .collect { displayDirectorSuggestions(it) }
            } catch (error: Throwable) {
                Log.e(TAG, "Director suggestions collector failed", error)
            }
        }
    }

    private fun buildAdvancedFiltersSafely() {''', "director collector function")

build_fn = r'''    private fun buildAdvancedFiltersSafely\(\) \{.*?\n    \}\n\n    private fun configureFilterFocus'''
build_repl = '''    private fun buildAdvancedFiltersSafely() {
        if (filterContainer.childCount > 0) return
        try {
            val panel = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(8))
                setBackgroundResource(R.drawable.bg_filter_panel_tv)
            }
            filterContainer.addView(
                panel,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )

            // TV only: search bar + clear + mic + Ricerca globale now belong to the same box.
            val searchBar = etSearch.parent as? View
            (searchBar?.parent as? ViewGroup)?.removeView(searchBar)
            if (searchBar != null) {
                panel.addView(
                    searchBar,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            (llGlobalSearch.parent as? ViewGroup)?.removeView(llGlobalSearch)
            panel.addView(
                llGlobalSearch,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )

            panel.addView(makeLabel("Filtri avanzati", 18f, true).apply { setPadding(0, dp(10), 0, 0) })
            panel.addView(makeLabel(
                "Combina testo, tipo, lingua, anno, genere, attore, regista, piattaforma e ordinamento.",
                12f,
                false,
                Color.rgb(169, 178, 194),
            ))

            val typeValues = listOf(
                AdvancedSearchFilters.ContentType.ALL,
                AdvancedSearchFilters.ContentType.MOVIE,
                AdvancedSearchFilters.ContentType.TV,
                AdvancedSearchFilters.ContentType.CARTOON,
            )
            typeSelector = makeSelector("Tipo", typeValues) {
                when (it) {
                    AdvancedSearchFilters.ContentType.ALL -> "Tutti"
                    AdvancedSearchFilters.ContentType.MOVIE -> "Film"
                    AdvancedSearchFilters.ContentType.TV -> "Serie"
                    AdvancedSearchFilters.ContentType.CARTOON -> "Cartoni"
                }
            }
            italianSelector = makeSelector(
                "Italiano",
                listOf(
                    AdvancedSearchFilters.ItalianFilter.ALL,
                    AdvancedSearchFilters.ItalianFilter.YES,
                    AdvancedSearchFilters.ItalianFilter.NO,
                ),
            ) {
                when (it) {
                    AdvancedSearchFilters.ItalianFilter.ALL -> "Tutti"
                    AdvancedSearchFilters.ItalianFilter.YES -> "Sì"
                    AdvancedSearchFilters.ItalianFilter.NO -> "No"
                }
            }
            val currentYear = Calendar.getInstance().get(Calendar.YEAR).coerceAtLeast(2026)
            val yearValues: List<Int?> = listOf<Int?>(null) + (currentYear downTo 1900).map { it }
            yearSelector = makeSelector("Anno", yearValues) { it?.toString() ?: "Tutti" }
            genreSelector = makeSelector("Genere", AdvancedSearchFilters.GenreFilter.entries.toList()) { it.label }

            val firstRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            panel.addView(firstRow)
            addSelectorToRow(firstRow, typeSelector)
            addSelectorToRow(firstRow, italianSelector)
            addSelectorToRow(firstRow, yearSelector)
            addSelectorToRow(firstRow, genreSelector)

            fromSelector = makeSelector("Dal", yearValues) { it?.toString() ?: "Tutti" }
            toSelector = makeSelector("Al", yearValues) { it?.toString() ?: "Tutti" }
            val secondRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            panel.addView(secondRow)
            addSelectorToRow(secondRow, fromSelector)
            addSelectorToRow(secondRow, toSelector)

            val actorColumn = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            secondRow.addView(
                actorColumn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { marginStart = dp(10) },
            )
            actorColumn.addView(makeLabel("Attore", 12f, false, Color.rgb(169, 178, 194)))
            val actorRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            actorColumn.addView(actorRow)
            actorEdit = EditText(requireContext()).apply {
                id = View.generateViewId()
                isSingleLine = true
                hint = "Scrivi almeno 2 lettere"
                setHintTextColor(Color.rgb(150, 158, 172))
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(dp(12), 0, dp(12), 0)
                setBackgroundResource(R.drawable.bg_filter_control_tv)
                setOnFocusChangeListener { _, hasFocus -> if (hasFocus) revealSearchHeader() }
            }
            actorRow.addView(actorEdit, LinearLayout.LayoutParams(0, dp(46), 1f))
            btnActorSearch = makeActionButton("Cerca attore", primary = false)
            actorRow.addView(
                btnActorSearch,
                LinearLayout.LayoutParams(dp(142), dp(46)).apply { marginStart = dp(8) },
            )
            btnActorSearch?.setOnClickListener { triggerActorSearch() }
            btnActorSearch?.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) revealSearchHeader() }
            setupActorTextSearch()
            addActorSuggestionsSurface(panel)

            sortSelector = makeSelector(
                "Ordina per",
                AdvancedSearchFilters.SortFilter.entries.toList(),
            ) { it.label }
            platformSelector = makeSelector(
                "Piattaforma",
                AdvancedSearchFilters.PlatformFilter.entries.toList(),
            ) { it.label }

            val thirdRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            panel.addView(thirdRow)
            addSelectorToRow(thirdRow, sortSelector)
            addSelectorToRow(thirdRow, platformSelector)

            val directorColumn = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            thirdRow.addView(
                directorColumn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { marginStart = dp(10) },
            )
            directorColumn.addView(makeLabel("Regista", 12f, false, Color.rgb(169, 178, 194)))
            val directorRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            directorColumn.addView(directorRow)
            directorEdit = EditText(requireContext()).apply {
                id = View.generateViewId()
                isSingleLine = true
                hint = "Scrivi almeno 2 lettere"
                setHintTextColor(Color.rgb(150, 158, 172))
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(dp(12), 0, dp(12), 0)
                setBackgroundResource(R.drawable.bg_filter_control_tv)
                setOnFocusChangeListener { _, hasFocus -> if (hasFocus) revealSearchHeader() }
            }
            directorRow.addView(directorEdit, LinearLayout.LayoutParams(0, dp(46), 1f))
            btnDirectorSearch = makeActionButton("Cerca regista", primary = false)
            directorRow.addView(
                btnDirectorSearch,
                LinearLayout.LayoutParams(dp(142), dp(46)).apply { marginStart = dp(8) },
            )
            btnDirectorSearch?.setOnClickListener { triggerDirectorSearch() }
            btnDirectorSearch?.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) revealSearchHeader() }
            setupDirectorTextSearch()
            addDirectorSuggestionsSurface(panel)

            val actions = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                setPadding(0, dp(10), 0, 0)
            }
            panel.addView(actions)
            btnResetFilters = makeActionButton("Azzera", primary = false).also { button ->
                actions.addView(
                    button,
                    LinearLayout.LayoutParams(dp(150), dp(48)).apply { marginEnd = dp(10) },
                )
                button.setOnClickListener { resetFilters() }
                button.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) revealSearchHeader() }
            }
            btnApplyFilters = makeActionButton("Applica filtri", primary = true).also { button ->
                actions.addView(button, LinearLayout.LayoutParams(dp(190), dp(48)))
                button.setOnClickListener { submitSearch() }
                button.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) revealSearchHeader() }
            }

            filterHandle = makeActionButton("▲ Filtri", primary = false).apply {
                textSize = 11f
                setOnFocusChangeListener { _, hasFocus -> if (hasFocus) setFilterPanelExpanded(true, true) }
                setOnClickListener { setFilterPanelExpanded(true, true) }
            }
            panel.addView(
                filterHandle,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(FILTER_PEEK_DP)).apply {
                    topMargin = dp(5)
                },
            )

            configureFilterFocus()
            searchHeader.post {
                setFilterPanelExpanded(true, false)
                updateGridTopPadding()
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Advanced filter panel failed", error)
            filterContainer.removeAllViews()
            val fallback = makeActionButton("Ricerca base disponibile — filtri non caricati", primary = false)
            fallback.isClickable = false
            filterContainer.addView(
                fallback,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)),
            )
            showSafeToast("Cerca è aperta; i filtri avanzati non sono stati caricati")
        }
    }

    private fun configureFilterFocus'''
t = sub_once(t, build_fn, build_repl, "buildAdvancedFiltersSafely")

focus_fn = r'''    private fun configureFilterFocus\(.*?\n    \}\n\n    private fun setupActorTextSearch'''
focus_repl = '''    private fun configureFilterFocus() {
        val type = typeSelector ?: return
        val italian = italianSelector ?: return
        val year = yearSelector ?: return
        val genre = genreSelector ?: return
        val from = fromSelector ?: return
        val to = toSelector ?: return
        val sort = sortSelector ?: return
        val platform = platformSelector ?: return
        val actor = actorEdit ?: return
        val director = directorEdit ?: return
        val actorSearch = btnActorSearch ?: return
        val directorSearch = btnDirectorSearch ?: return
        val reset = btnResetFilters ?: return
        val apply = btnApplyFilters ?: return

        etSearch.nextFocusRightId = btnSearchClear.id
        etSearch.nextFocusDownId = llGlobalSearch.id
        btnSearchClear.nextFocusLeftId = etSearch.id
        btnSearchClear.nextFocusRightId = btnSearchVoice.id
        btnSearchClear.nextFocusDownId = llGlobalSearch.id
        btnSearchVoice.nextFocusLeftId = btnSearchClear.id
        btnSearchVoice.nextFocusDownId = llGlobalSearch.id
        llGlobalSearch.nextFocusUpId = etSearch.id
        llGlobalSearch.nextFocusDownId = type.viewId

        type.setNextFocus(right = italian.viewId, up = llGlobalSearch.id, down = from.viewId)
        italian.setNextFocus(left = type.viewId, right = year.viewId, up = llGlobalSearch.id, down = to.viewId)
        year.setNextFocus(left = italian.viewId, right = genre.viewId, up = llGlobalSearch.id, down = actor.id)
        genre.setNextFocus(left = year.viewId, up = llGlobalSearch.id, down = actorSearch.id)

        from.setNextFocus(right = to.viewId, up = type.viewId, down = sort.viewId)
        to.setNextFocus(left = from.viewId, right = actor.id, up = italian.viewId, down = platform.viewId)
        actor.nextFocusLeftId = to.viewId
        actor.nextFocusRightId = actorSearch.id
        actor.nextFocusUpId = year.viewId
        actor.nextFocusDownId = director.id
        actorSearch.nextFocusLeftId = actor.id
        actorSearch.nextFocusUpId = genre.viewId
        actorSearch.nextFocusDownId = directorSearch.id

        sort.setNextFocus(right = platform.viewId, up = from.viewId, down = reset.id)
        platform.setNextFocus(left = sort.viewId, right = director.id, up = to.viewId, down = apply.id)
        director.nextFocusLeftId = platform.viewId
        director.nextFocusRightId = directorSearch.id
        director.nextFocusUpId = actor.id
        director.nextFocusDownId = apply.id
        directorSearch.nextFocusLeftId = director.id
        directorSearch.nextFocusUpId = actorSearch.id
        directorSearch.nextFocusDownId = apply.id

        reset.nextFocusUpId = sort.viewId
        reset.nextFocusRightId = apply.id
        reset.nextFocusDownId = vgvSearch.id
        apply.nextFocusUpId = director.id
        apply.nextFocusLeftId = reset.id
        apply.nextFocusDownId = vgvSearch.id
        vgvSearch.nextFocusUpId = filterHandle?.id ?: apply.id
    }

    private fun setupActorTextSearch'''
t = sub_once(t, focus_fn, focus_repl, "configureFilterFocus")

# Insert director text-search + surfaces before triggerActorSearch.
t = replace_once(t,
'''    private fun triggerActorSearch() {''',
'''    private fun setupDirectorTextSearch() {
        val edit = directorEdit ?: return
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (suppressDirectorTextChange) return
                directorSearchJob?.cancel()
                searchViewModel?.clearDirectorSelection()
                val value = s?.toString().orEmpty().trim()
                if (value.length < 2) {
                    searchViewModel?.clearDirectorSuggestions()
                    displayDirectorSuggestions(emptyList())
                    return
                }
                directorSearchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    searchViewModel?.searchDirectors(value)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        edit.setOnEditorActionListener { _, actionId, event ->
            val submit = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (submit) {
                triggerDirectorSearch()
                hideKeyboard()
                true
            } else false
        }
    }

    private fun addDirectorSuggestionsSurface(panel: LinearLayout) {
        try {
            directorSuggestionsGrid = HorizontalGridView(requireContext()).apply {
                id = View.generateViewId()
                visibility = View.GONE
                adapter = directorSuggestionsAdapter
                setItemSpacing(dp(12))
            }
            panel.addView(
                directorSuggestionsGrid,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(136)).apply { topMargin = dp(8) },
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Horizontal director grid unavailable, using fallback", error)
            directorSuggestionsGrid = null
        }
        directorFallbackSuggestions = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        panel.addView(
            directorFallbackSuggestions,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )
    }

    private fun triggerDirectorSearch() {
        val name = directorEdit?.text?.toString().orEmpty().trim()
        if (name.length < 2) {
            showSafeToast("Scrivi almeno 2 lettere del nome del regista")
            return
        }
        val vm = searchViewModel ?: run {
            showSafeToast("Motore di ricerca ancora in inizializzazione")
            return
        }
        vm.searchDirectors(name)
    }

    private fun triggerActorSearch() {''', "director UI functions")

# Upgrade actor display focus wiring and append director display/select.
t = replace_once(t,
'''            actorSuggestionsGrid?.let { grid ->
                actorSuggestionsAdapter.submitList(items)
                grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            }''',
'''            actorSuggestionsGrid?.let { grid ->
                actorSuggestionsAdapter.submitList(items)
                grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                if (items.isNotEmpty()) {
                    actorEdit?.nextFocusDownId = grid.id
                    btnActorSearch?.nextFocusDownId = grid.id
                    grid.nextFocusUpId = actorEdit?.id ?: View.NO_ID
                    grid.nextFocusDownId = sortSelector?.viewId ?: View.NO_ID
                } else {
                    actorEdit?.nextFocusDownId = directorEdit?.id ?: View.NO_ID
                    btnActorSearch?.nextFocusDownId = btnDirectorSearch?.id ?: View.NO_ID
                }
            }''', "actor focus list")
t = replace_once(t,
'''        btnApplyFilters?.requestFocus()
        hideKeyboard()
        searchHeader.post { updateGridTopPadding() }
    }

    private fun resetFilters() {''',
'''        sortSelector?.requestFocus()
        hideKeyboard()
        searchHeader.post { updateGridTopPadding() }
    }

    private fun displayDirectorSuggestions(list: List<People>) {
        if (!isAdded || !this::searchHeader.isInitialized) return
        try {
            val items = list.onEach { it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM }
            directorSuggestionsGrid?.let { grid ->
                directorSuggestionsAdapter.submitList(items)
                grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                if (items.isNotEmpty()) {
                    directorEdit?.nextFocusDownId = grid.id
                    btnDirectorSearch?.nextFocusDownId = grid.id
                    grid.nextFocusUpId = directorEdit?.id ?: View.NO_ID
                    grid.nextFocusDownId = btnApplyFilters?.id ?: View.NO_ID
                } else {
                    directorEdit?.nextFocusDownId = btnApplyFilters?.id ?: View.NO_ID
                    btnDirectorSearch?.nextFocusDownId = btnApplyFilters?.id ?: View.NO_ID
                }
            }
            directorFallbackSuggestions?.let { fallback ->
                fallback.removeAllViews()
                if (directorSuggestionsGrid == null && items.isNotEmpty()) {
                    fallback.visibility = View.VISIBLE
                    items.take(5).forEach { person ->
                        val chip = makeActionButton(person.name, primary = false)
                        chip.setOnClickListener { selectDirector(person) }
                        fallback.addView(
                            chip,
                            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) },
                        )
                    }
                } else {
                    fallback.visibility = View.GONE
                }
            }
            searchHeader.post { updateGridTopPadding() }
        } catch (error: Throwable) {
            Log.e(TAG, "Displaying director suggestions failed", error)
        }
    }

    private fun selectDirector(people: People) {
        suppressDirectorTextChange = true
        directorEdit?.setText(people.name)
        directorEdit?.setSelection(directorEdit?.text?.length ?: 0)
        suppressDirectorTextChange = false
        searchViewModel?.selectDirectorFilter(people.id, people.name)
        directorSuggestionsGrid?.visibility = View.GONE
        directorFallbackSuggestions?.visibility = View.GONE
        btnApplyFilters?.requestFocus()
        hideKeyboard()
        searchHeader.post { updateGridTopPadding() }
    }

    private fun resetFilters() {''', "director display/select")

# Reset new controls and director state.
t = replace_once(t,
'''        fromSelector?.reset()
        toSelector?.reset()
        suppressActorTextChange = true''',
'''        fromSelector?.reset()
        toSelector?.reset()
        sortSelector?.reset()
        platformSelector?.reset()
        suppressActorTextChange = true''', "reset selectors")
t = replace_once(t,
'''        suppressActorTextChange = false
        searchViewModel?.clearActorSelection()
        searchViewModel?.clearActorSuggestions()
        displayActorSuggestions(emptyList())
        searchViewModel?.search(etSearch.text?.toString().orEmpty().trim())''',
'''        suppressActorTextChange = false
        suppressDirectorTextChange = true
        directorEdit?.setText("")
        suppressDirectorTextChange = false
        searchViewModel?.clearActorSelection()
        searchViewModel?.clearActorSuggestions()
        searchViewModel?.clearDirectorSelection()
        searchViewModel?.clearDirectorSuggestions()
        displayActorSuggestions(emptyList())
        displayDirectorSuggestions(emptyList())
        searchViewModel?.search(etSearch.text?.toString().orEmpty().trim())''', "reset director")

# Validate director choice and emit new fields.
t = replace_once(t,
'''        if (actorText.isNotBlank() && vm?.selectedActorId == null) {
            triggerActorSearch()
            showSafeToast("Seleziona l'attore dai suggerimenti")
            return null
        }

        return AdvancedSearchFilters(''',
'''        if (actorText.isNotBlank() && vm?.selectedActorId == null) {
            triggerActorSearch()
            showSafeToast("Seleziona l'attore dai suggerimenti")
            return null
        }
        val directorText = directorEdit?.text?.toString().orEmpty().trim()
        if (directorText.isNotBlank() && vm?.selectedDirectorId == null) {
            triggerDirectorSearch()
            showSafeToast("Seleziona il regista dai suggerimenti")
            return null
        }

        return AdvancedSearchFilters(''', "validate director")
t = replace_once(t,
'''            actorId = vm?.selectedActorId,
            actorName = vm?.selectedActorName.orEmpty(),
        )''',
'''            actorId = vm?.selectedActorId,
            actorName = vm?.selectedActorName.orEmpty(),
            directorId = vm?.selectedDirectorId,
            directorName = vm?.selectedDirectorName.orEmpty(),
            sort = sortSelector?.value ?: AdvancedSearchFilters.SortFilter.POPULARITY,
            platform = platformSelector?.value ?: AdvancedSearchFilters.PlatformFilter.ALL,
        )''', "emit new filters")

# Submit always collapses the TV panel only after a valid search was actually launched.
t = replace_once(t,
'''            vm.searchAdvanced(query, filters)
            return true''',
'''            vm.searchAdvanced(query, filters)
            collapseFiltersAndFocusResults()
            return true''', "collapse advanced submit")
t = replace_once(t,
'''            vm.searchGlobal(query, UserPreferences.currentProvider?.language ?: "it")
        } else {
            vm.search(query)
        }
        return true''',
'''            vm.searchGlobal(query, UserPreferences.currentProvider?.language ?: "it")
            collapseFiltersAndFocusResults()
        } else {
            vm.search(query)
            collapseFiltersAndFocusResults()
        }
        return true''', "collapse normal/global submit")

# Replace old scroll-coupled header with deterministic expanded/collapsed TV panel.
scroll_fn = r'''    private fun initializeScrollableHeader\(\) \{.*?\n    \}\n\n    private fun updateGridTopPadding\(\) \{.*?\n    \}\n'''
scroll_repl = '''    private fun initializeScrollableHeader() {
        vgvSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && filterPanelExpanded) {
                setFilterPanelExpanded(false, true)
            }
        }
    }

    private fun updateGridTopPadding() {
        if (!isAdded || !this::searchHeader.isInitialized) return
        val visibleHeader = if (filterPanelExpanded) searchHeader.height else dp(FILTER_PEEK_DP)
        val top = visibleHeader + dp(10)
        if (top <= 0) return
        if (vgvSearch.paddingTop != top) {
            vgvSearch.setPadding(
                vgvSearch.paddingLeft,
                top,
                vgvSearch.paddingRight,
                vgvSearch.paddingBottom,
            )
        }
    }
'''
t = sub_once(t, scroll_fn, scroll_repl, "deterministic panel movement")

# Old sync functions may stay unused, but set/reveal must use the new state.
set_fn = r'''    private fun setSearchHeaderOffset\(offset: Int, animate: Boolean\) \{.*?\n    \}\n\n    private fun revealSearchHeader\(\) \{.*?\n    \}\n'''
set_repl = '''    private fun setSearchHeaderOffset(offset: Int, animate: Boolean) {
        if (!this::searchHeader.isInitialized) return
        val maxOffset = searchHeader.height.coerceAtLeast(0)
        val safeOffset = offset.coerceIn(0, maxOffset)
        searchHeaderOffset = safeOffset
        val translation = -safeOffset.toFloat()
        searchHeader.animate().cancel()
        if (animate) {
            searchHeader.animate().translationY(translation).setDuration(140L).start()
        } else {
            searchHeader.translationY = translation
        }
    }

    private fun setFilterPanelExpanded(expanded: Boolean, animate: Boolean) {
        if (!this::searchHeader.isInitialized) return
        filterPanelExpanded = expanded
        searchHeader.post {
            val offset = if (expanded) 0 else (searchHeader.height - dp(FILTER_PEEK_DP)).coerceAtLeast(0)
            setSearchHeaderOffset(offset, animate)
            updateGridTopPadding()
        }
    }

    private fun collapseFiltersAndFocusResults() {
        pendingFocusFirstResult = true
        setFilterPanelExpanded(false, true)
        focusFirstResultWhenReady()
    }

    private fun focusFirstResultWhenReady() {
        if (appAdapter.items.isEmpty()) return
        vgvSearch.scrollToPosition(0)
        vgvSearch.post {
            if (vgvSearch.requestFocus()) pendingFocusFirstResult = false
        }
    }

    private fun revealSearchHeader() {
        setFilterPanelExpanded(true, true)
    }
'''
t = sub_once(t, set_fn, set_repl, "panel state methods")

# When results arrive after Apply, focus first poster and keep panel collapsed.
t = replace_once(t,
'''        searchHeader.post { updateGridTopPadding() }
    }

    private fun displayGlobalSearch''',
'''        searchHeader.post {
            updateGridTopPadding()
            if (pendingFocusFirstResult) focusFirstResultWhenReady()
        }
    }

    private fun displayGlobalSearch''', "focus first result")
t = replace_once(t,
'''        appAdapter.setOnLoadMoreListener(null)
        searchHeader.post { updateGridTopPadding() }
    }

    private fun startVoiceSearch''',
'''        appAdapter.setOnLoadMoreListener(null)
        searchHeader.post {
            updateGridTopPadding()
            if (pendingFocusFirstResult) focusFirstResultWhenReady()
        }
    }

    private fun startVoiceSearch''', "focus global result")

# Dropdown helper needs a focus method for post-selection navigation.
t = replace_once(t,
'''        fun reset() {
            spinner.setSelection(0, false)
        }

        fun setNextFocus(''',
'''        fun reset() {
            spinner.setSelection(0, false)
        }

        fun requestFocus(): Boolean = focusView.requestFocus()

        fun setNextFocus(''', "selector requestFocus")

# Tiny handle height constant.
t = replace_once(t,
'''    companion object {
        private const val TAG = "SearchTvSafe"
    }''',
'''    companion object {
        private const val TAG = "SearchTvSafe"
        private const val FILTER_PEEK_DP = 26
    }''', "peek constant")
p.write_text(t)

print("TV filter panel v2 patch applied")

from pathlib import Path
import re

TV = Path('app/src/main/java/com/streamflixreborn/streamflix/fragments/search/SearchTvFragment.kt')
ADV = Path('app/src/main/java/com/streamflixreborn/streamflix/providers/AdvancedSearch.kt')
VM = Path('app/src/main/java/com/streamflixreborn/streamflix/fragments/search/SearchViewModel.kt')

# Start from the already crash-safe/dropdown TV implementation that was tested on device.
# This copies ONLY the TV Search fragment. Mobile source/layouts are deliberately untouched.
import subprocess
subprocess.run(['git','fetch','origin','fix/tv-filter-focus-scroll:refs/remotes/origin/fix/tv-filter-focus-scroll'], check=True)
with TV.open('w', encoding='utf-8') as f:
    subprocess.run(['git','show','origin/fix/tv-filter-focus-scroll:' + str(TV)], check=True, stdout=f)

# ---------- Shared filter model: add optional fields, defaults preserve Mobile behaviour ----------
adv = ADV.read_text(encoding='utf-8')
old_sig = '''    val actorId: String? = null,
    val actorName: String = "",
) {'''
new_sig = '''    val actorId: String? = null,
    val actorName: String = "",
    val directorId: String? = null,
    val directorName: String = "",
    val sort: SortFilter = SortFilter.POPULARITY,
    val platform: PlatformFilter = PlatformFilter.ALL,
) {'''
if old_sig not in adv:
    raise SystemExit('AdvancedSearch signature marker not found')
adv = adv.replace(old_sig, new_sig, 1)

marker = '    enum class ItalianFilter { ALL, YES, NO }\n\n'
insert = '''    enum class ItalianFilter { ALL, YES, NO }

    enum class SortFilter(
        val label: String,
        val movieSort: String,
        val tvSort: String,
    ) {
        POPULARITY("Popolarità", "popularity.desc", "popularity.desc"),
        DATE("Data", "primary_release_date.desc", "first_air_date.desc"),
        RATING("Voto", "vote_average.desc", "vote_average.desc"),
    }

    enum class PlatformFilter(
        val label: String,
        val providerIds: String?,
    ) {
        ALL("Tutte", null),
        NETFLIX("Netflix", "8"),
        DISNEY_PLUS("Disney+", "337"),
        APPLE_TV_PLUS("Apple TV+", "350"),
        PRIME_VIDEO("Prime Video", "119"),
        SKY_NOW("Sky / NOW", "29|39"),
        HBO_MAX("HBO Max", "1899|384"),
    }

'''
if marker not in adv:
    raise SystemExit('ItalianFilter marker not found')
adv = adv.replace(marker, insert, 1)

old_active = '''            !dateTo.isNullOrBlank() ||
            !actorId.isNullOrBlank()
'''
new_active = '''            !dateTo.isNullOrBlank() ||
            !actorId.isNullOrBlank() ||
            !directorId.isNullOrBlank() ||
            platform != PlatformFilter.ALL ||
            sort != SortFilter.POPULARITY
'''
if old_active not in adv:
    raise SystemExit('isActive marker not found')
adv = adv.replace(old_active, new_active, 1)
ADV.write_text(adv, encoding='utf-8')

# ---------- ViewModel: director suggestions + sort/platform/director filtering ----------
vm = VM.read_text(encoding='utf-8')
vm = vm.replace(
'''    private val _actorSuggestions = MutableStateFlow<List<People>>(emptyList())
    val actorSuggestions: Flow<List<People>> = _actorSuggestions
''',
'''    private val _actorSuggestions = MutableStateFlow<List<People>>(emptyList())
    val actorSuggestions: Flow<List<People>> = _actorSuggestions
    private val _directorSuggestions = MutableStateFlow<List<People>>(emptyList())
    val directorSuggestions: Flow<List<People>> = _directorSuggestions
''', 1)

vm = vm.replace(
'''    var selectedActorName: String = ""
        private set

    private var page = 1
''',
'''    var selectedActorName: String = ""
        private set
    var selectedDirectorId: String? = null
        private set
    var selectedDirectorName: String = ""
        private set

    private var page = 1
''', 1)

vm = vm.replace(
'''        selectedActorName = ""
        _actorSuggestions.value = emptyList()
        executeSearch(query, advancedFilters, 1)
''',
'''        selectedActorName = ""
        selectedDirectorId = null
        selectedDirectorName = ""
        _actorSuggestions.value = emptyList()
        _directorSuggestions.value = emptyList()
        executeSearch(query, advancedFilters, 1)
''', 1)

vm = vm.replace(
'''            selectedActorId = advancedFilters.actorId
            selectedActorName = advancedFilters.actorName
            _actorSuggestions.value = emptyList()
            executeSearch(query, advancedFilters, 1)
''',
'''            selectedActorId = advancedFilters.actorId
            selectedActorName = advancedFilters.actorName
            selectedDirectorId = advancedFilters.directorId
            selectedDirectorName = advancedFilters.directorName
            _actorSuggestions.value = emptyList()
            _directorSuggestions.value = emptyList()
            executeSearch(query, advancedFilters, 1)
''', 1)

actor_clear_marker = '''    fun clearActorSuggestions() {
        _actorSuggestions.value = emptyList()
    }

'''
director_block = '''    fun clearActorSuggestions() {
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

    fun searchDirectors(directorQuery: String) = viewModelScope.launch(Dispatchers.IO) {
        val cleanQuery = directorQuery.trim()
        if (cleanQuery.length < 2) {
            _directorSuggestions.value = emptyList()
            return@launch
        }
        try {
            val language = UserPreferences.currentProvider?.language
                ?.takeIf { it.isNotBlank() }
                ?: "it"
            _directorSuggestions.value = TMDb3.Search.multi(
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
            Log.e("SearchViewModel", "searchDirectors: ", e)
            _directorSuggestions.value = emptyList()
        }
    }

'''
if actor_clear_marker not in vm:
    raise SystemExit('actor clear marker not found')
vm = vm.replace(actor_clear_marker, director_block, 1)

# Director means director/crew: Discover TV has no director/crew filter, so when selected
# we deliberately return movie results only rather than showing false TV matches.
vm = vm.replace(
'''        val includeTv = filters.type == AdvancedSearchFilters.ContentType.ALL ||
            filters.type == AdvancedSearchFilters.ContentType.TV ||
            filters.type == AdvancedSearchFilters.ContentType.CARTOON
''',
'''        val includeTv = (filters.type == AdvancedSearchFilters.ContentType.ALL ||
            filters.type == AdvancedSearchFilters.ContentType.TV ||
            filters.type == AdvancedSearchFilters.ContentType.CARTOON) && filters.directorId.isNullOrBlank()
''', 1)

vm = vm.replace(
'''        val selectedGenre = filters.genre
        val actorId = filters.actorId?.takeIf { it.isNotBlank() }

        if (query.isNotBlank()) {
''',
'''        val selectedGenre = filters.genre
        val actorId = filters.actorId?.takeIf { it.isNotBlank() }
        val directorId = filters.directorId?.takeIf { it.isNotBlank() }
        val platformIds = filters.platform.providerIds

        // Text + piattaforma/regista: use filtered Discover pages, then match the text locally.
        // This keeps platform/director filters real instead of pretending Search.multi supports them.
        if (query.isNotBlank() && (platformIds != null || directorId != null)) {
            val cleanTokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            fun textMatches(title: String, overview: String): Boolean {
                val haystack = (title + " " + overview).lowercase()
                return cleanTokens.all { it in haystack }
            }
            val firstTmdbPage = ((page - 1) * 3) + 1
            val pages = firstTmdbPage..(firstTmdbPage + 2)

            val movies = if (includeMovies) pages.map { tmdbPage ->
                async {
                    val params = mutableMapOf(
                        "language" to language,
                        "page" to tmdbPage.toString(),
                        "sort_by" to filters.sort.movieSort,
                        "include_adult" to "false",
                    )
                    if (italianOnly) params["with_original_language"] = "it"
                    actorId?.let { params["with_cast"] = it }
                    directorId?.let { params["with_crew"] = it }
                    platformIds?.let {
                        params["watch_region"] = "IT"
                        params["with_watch_providers"] = it
                        params["with_watch_monetization_types"] = "flatrate"
                    }
                    val genreIds = buildList {
                        if (cartoonOnly) add(16)
                        selectedGenre.movieId?.let { add(it) }
                    }.distinct()
                    if (genreIds.isNotEmpty()) params["with_genres"] = genreIds.joinToString(",")
                    filters.year?.let { params["primary_release_year"] = it.toString() }
                    filters.dateFrom?.takeIf { it.isNotBlank() }?.let { params["primary_release_date.gte"] = it }
                    filters.dateTo?.takeIf { it.isNotBlank() }?.let { params["primary_release_date.lte"] = it }
                    if (filters.sort == AdvancedSearchFilters.SortFilter.RATING) params["vote_count.gte"] = "25"
                    TMDb3.Discover.movie(params).results
                }
            }.awaitAll().flatten() else emptyList()

            val tv = if (includeTv) pages.map { tmdbPage ->
                async {
                    val params = mutableMapOf(
                        "language" to language,
                        "page" to tmdbPage.toString(),
                        "sort_by" to filters.sort.tvSort,
                        "include_adult" to "false",
                    )
                    if (italianOnly) params["with_original_language"] = "it"
                    platformIds?.let {
                        params["watch_region"] = "IT"
                        params["with_watch_providers"] = it
                        params["with_watch_monetization_types"] = "flatrate"
                    }
                    val genreIds = buildList {
                        if (cartoonOnly) add(16)
                        selectedGenre.tvId?.let { add(it) }
                    }.distinct()
                    if (genreIds.isNotEmpty()) params["with_genres"] = genreIds.joinToString(",")
                    filters.year?.let { params["first_air_date_year"] = it.toString() }
                    filters.dateFrom?.takeIf { it.isNotBlank() }?.let { params["first_air_date.gte"] = it }
                    filters.dateTo?.takeIf { it.isNotBlank() }?.let { params["first_air_date.lte"] = it }
                    if (filters.sort == AdvancedSearchFilters.SortFilter.RATING) params["vote_count.gte"] = "25"
                    TMDb3.Discover.tv(params).results
                }
            }.awaitAll().flatten() else emptyList()

            val movieItems = movies.asSequence()
                .filter { !excludeItalian || it.originalLanguage != "it" }
                .filter { textMatches(it.title, it.overview) }
                .map { m -> Movie(
                    id = m.id.toString(), title = m.title, overview = m.overview,
                    released = m.releaseDate, rating = m.voteAverage.toDouble(),
                    poster = m.posterPath?.w500, banner = m.backdropPath?.original,
                ) as AppAdapter.Item }
            val tvItems = tv.asSequence()
                .filter { !excludeItalian || it.originalLanguage != "it" }
                .filter { textMatches(it.name, it.overview) }
                .map { t -> TvShow(
                    id = t.id.toString(), title = t.name, overview = t.overview,
                    released = t.firstAirDate, rating = t.voteAverage.toDouble(),
                    poster = t.posterPath?.w500, banner = t.backdropPath?.original,
                ) as AppAdapter.Item }
            return@coroutineScope sortSearchItems((movieItems + tvItems).distinctBy { it.searchIdentityKey() }.toList(), filters.sort)
        }

        if (query.isNotBlank()) {
''', 1)

# Existing Search.multi branch: preserve relevance for popularity, but honour date/vote sorting locally.
old_distinct = '''            }.distinctBy { it.searchIdentityKey() }
        }

        val movieDeferred = if (includeMovies) async {'''
new_distinct = '''            }.distinctBy { it.searchIdentityKey() }
                .let { sortSearchItems(it, filters.sort) }
        }

        val movieDeferred = if (includeMovies) async {'''
if old_distinct not in vm:
    raise SystemExit('query distinct marker not found')
vm = vm.replace(old_distinct, new_distinct, 1)

# Discover params: sort, platform, director.
vm = vm.replace('''                "sort_by" to "popularity.desc",
                "include_adult" to "false",
            )
            if (italianOnly) params["with_original_language"] = "it"
            actorId?.let { params["with_cast"] = it }
''', '''                "sort_by" to filters.sort.movieSort,
                "include_adult" to "false",
            )
            if (italianOnly) params["with_original_language"] = "it"
            actorId?.let { params["with_cast"] = it }
            directorId?.let { params["with_crew"] = it }
            platformIds?.let {
                params["watch_region"] = "IT"
                params["with_watch_providers"] = it
                params["with_watch_monetization_types"] = "flatrate"
            }
            if (filters.sort == AdvancedSearchFilters.SortFilter.RATING) params["vote_count.gte"] = "25"
''', 1)

# Second sort_by occurrence is TV.
vm = vm.replace('''                "sort_by" to "popularity.desc",
                "include_adult" to "false",
            )
            if (italianOnly) params["with_original_language"] = "it"
            actorId?.let { params["with_cast"] = it }
''', '''                "sort_by" to filters.sort.tvSort,
                "include_adult" to "false",
            )
            if (italianOnly) params["with_original_language"] = "it"
            actorId?.let { params["with_cast"] = it }
            platformIds?.let {
                params["watch_region"] = "IT"
                params["with_watch_providers"] = it
                params["with_watch_monetization_types"] = "flatrate"
            }
            if (filters.sort == AdvancedSearchFilters.SortFilter.RATING) params["vote_count.gte"] = "25"
''', 1)

# Add sort helper before matchesYearFilter.
match_marker = '    private fun matchesYearFilter(date: String?, filters: AdvancedSearchFilters): Boolean {'
sort_helper = '''    private fun sortSearchItems(
        items: List<AppAdapter.Item>,
        sort: AdvancedSearchFilters.SortFilter,
    ): List<AppAdapter.Item> = when (sort) {
        AdvancedSearchFilters.SortFilter.POPULARITY -> items
        AdvancedSearchFilters.SortFilter.DATE -> items.sortedByDescending { item ->
            when (item) {
                is Movie -> item.released.orEmpty()
                is TvShow -> item.released.orEmpty()
                else -> ""
            }
        }
        AdvancedSearchFilters.SortFilter.RATING -> items.sortedByDescending { item ->
            when (item) {
                is Movie -> item.rating
                is TvShow -> item.rating
                else -> 0.0
            }
        }
    }

'''
if match_marker not in vm:
    raise SystemExit('matchesYearFilter marker not found')
vm = vm.replace(match_marker, sort_helper + match_marker, 1)
VM.write_text(vm, encoding='utf-8')

# ---------- TV Fragment only ----------
tv = TV.read_text(encoding='utf-8')
required = ['R.layout.fragment_search_tv_safe', 'private inner class DropdownSelector<T>', 'buildAdvancedFiltersSafely()']
for r in required:
    if r not in tv: raise SystemExit('TV crash-safe marker missing: ' + r)

# State fields.
tv = tv.replace('''    private var suppressActorTextChange = false

    private var typeSelector:''', '''    private var suppressActorTextChange = false
    private var suppressDirectorTextChange = false
    private var filterPanelExpanded = true
    private var focusFirstResultAfterSearch = false
    private var focusActorSuggestionsOnNextResult = false
    private var focusDirectorSuggestionsOnNextResult = false
    private lateinit var filterPeekHandle: TextView

    private var typeSelector:''', 1)

tv = tv.replace('''    private var toSelector: DropdownSelector<Int?>? = null
    private var actorEdit: EditText? = null
''', '''    private var toSelector: DropdownSelector<Int?>? = null
    private var sortSelector: DropdownSelector<AdvancedSearchFilters.SortFilter>? = null
    private var platformSelector: DropdownSelector<AdvancedSearchFilters.PlatformFilter>? = null
    private var actorEdit: EditText? = null
''', 1)

tv = tv.replace('''    private var actorFallbackSuggestions: LinearLayout? = null
    private var btnApplyFilters: TextView? = null
''', '''    private var actorFallbackSuggestions: LinearLayout? = null
    private var directorEdit: EditText? = null
    private var directorSuggestionsGrid: HorizontalGridView? = null
    private var directorFallbackSuggestions: LinearLayout? = null
    private var btnApplyFilters: TextView? = null
''', 1)

# Director adapter.
tv = tv.replace('''    private val actorSuggestionsAdapter by lazy {
        AppAdapter().apply {
            onPeopleClickListener = { people -> selectActor(people) }
        }
    }
''', '''    private val actorSuggestionsAdapter by lazy {
        AppAdapter().apply {
            onPeopleClickListener = { people -> selectActor(people) }
        }
    }

    private val directorSuggestionsAdapter by lazy {
        AppAdapter().apply {
            onPeopleClickListener = { people -> selectDirector(people) }
        }
    }
''', 1)

# Collect director suggestions.
tv = tv.replace('''            collectSearchStateSafely()
            collectActorSuggestionsSafely()
''', '''            collectSearchStateSafely()
            collectActorSuggestionsSafely()
            collectDirectorSuggestionsSafely()
''', 1)

# Destroy cleanup.
tv = tv.replace('''        actorSuggestionsGrid = null
        actorFallbackSuggestions = null
        actorEdit = null
''', '''        actorSuggestionsGrid = null
        actorFallbackSuggestions = null
        directorSuggestionsGrid = null
        directorFallbackSuggestions = null
        actorEdit = null
        directorEdit = null
''', 1)
tv = tv.replace('''        fromSelector = null
        toSelector = null
        btnApplyFilters = null
''', '''        fromSelector = null
        toSelector = null
        sortSelector = null
        platformSelector = null
        btnApplyFilters = null
''', 1)

# Grid focus collapses panel; first row UP goes to tiny handle.
needle = '''            addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected('''
if needle not in tv: raise SystemExit('grid listener marker missing')
tv = tv.replace(needle, '''            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && filterPanelExpanded) setFilterPanelExpanded(false, focusResults = false)
            }
            addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(''', 1)
tv = tv.replace('''                        btnApplyFilters?.let { child?.itemView?.nextFocusUpId = it.id }
''', '''                        if (this@SearchTvFragment::filterPeekHandle.isInitialized) {
                            child?.itemView?.nextFocusUpId = filterPeekHandle.id
                        }
''', 1)

# Collector: when a valid search starts after Apply, immediately collapse to results.
tv = tv.replace('''                                is State.Searching, is State.GlobalSearching -> {
                                    pbSearch.visibility = View.VISIBLE
''', '''                                is State.Searching, is State.GlobalSearching -> {
                                    if (focusFirstResultAfterSearch) {
                                        setFilterPanelExpanded(false, focusResults = true)
                                    }
                                    pbSearch.visibility = View.VISIBLE
''', 1)

# Add director collector after actor collector.
actor_collector_end = '''    private fun buildAdvancedFiltersSafely() {'''
director_collector = '''    private fun collectDirectorSuggestionsSafely() {
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

    private fun buildAdvancedFiltersSafely() {'''
if actor_collector_end not in tv: raise SystemExit('build filters marker missing')
tv = tv.replace(actor_collector_end, director_collector, 1)

# Build panel: tiny handle, remove duplicate global search, move existing search box inside panel.
panel_marker = '''            val panel = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(14))
                setBackgroundResource(R.drawable.bg_filter_panel_tv)
            }
            filterContainer.addView('''
panel_repl = '''            llGlobalSearch.visibility = View.GONE
            isGlobalSearchChecked = false

            if (!this::filterPeekHandle.isInitialized) {
                filterPeekHandle = TextView(requireContext()).apply {
                    id = View.generateViewId()
                    text = "FILTRI"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    gravity = android.view.Gravity.CENTER
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    setBackgroundResource(R.drawable.bg_filter_action_secondary_tv)
                    setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) setFilterPanelExpanded(true, focusResults = false)
                    }
                    setOnClickListener { setFilterPanelExpanded(true, focusResults = false) }
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                setFilterPanelExpanded(true, focusResults = false); true
                            }
                            else -> false
                        }
                    }
                }
                searchHeader.addView(filterPeekHandle, 0, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(28)
                ))
            }

            val panel = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(14))
                setBackgroundResource(R.drawable.bg_filter_panel_tv)
            }
            filterContainer.addView('''
if panel_marker not in tv: raise SystemExit('panel marker missing')
tv = tv.replace(panel_marker, panel_repl, 1)

# After subtitle, reparent the original search box into the filter panel.
subtitle = '''            panel.addView(makeLabel(
                "Combina i criteri: testo, tipo, lingua, anno, genere e attore vengono applicati insieme.",
                12f,
                false,
                Color.rgb(169, 178, 194),
            ))

            val typeValues = listOf('''
subtitle_repl = '''            panel.addView(makeLabel(
                "Combina ricerca e filtri; poi premi Cerca / Applica.",
                12f,
                false,
                Color.rgb(169, 178, 194),
            ))

            val searchBox = etSearch.parent as? View
            (searchBox?.parent as? ViewGroup)?.removeView(searchBox)
            searchBox?.let {
                panel.addView(it, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8); bottomMargin = dp(4) })
            }

            val typeValues = listOf('''
if subtitle not in tv: raise SystemExit('subtitle marker missing')
tv = tv.replace(subtitle, subtitle_repl, 1)

# Add sort + platform selectors after To.
tv = tv.replace('''            fromSelector = makeSelector("Dal", yearValues) { it?.toString() ?: "Tutti" }
            toSelector = makeSelector("Al", yearValues) { it?.toString() ?: "Tutti" }

            val secondRow = LinearLayout(requireContext()).apply {''', '''            fromSelector = makeSelector("Dal", yearValues) { it?.toString() ?: "Tutti" }
            toSelector = makeSelector("Al", yearValues) { it?.toString() ?: "Tutti" }
            sortSelector = makeSelector("Ordina", AdvancedSearchFilters.SortFilter.entries.toList()) { it.label }
            platformSelector = makeSelector("Piattaforma", AdvancedSearchFilters.PlatformFilter.entries.toList()) { it.label }

            val secondRow = LinearLayout(requireContext()).apply {''', 1)

tv = tv.replace('''            addSelectorToRow(secondRow, fromSelector)
            addSelectorToRow(secondRow, toSelector)

            val actorColumn = LinearLayout(requireContext()).apply {''', '''            addSelectorToRow(secondRow, fromSelector)
            addSelectorToRow(secondRow, toSelector)
            addSelectorToRow(secondRow, sortSelector)
            addSelectorToRow(secondRow, platformSelector)

            val peopleRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            panel.addView(peopleRow)

            val actorColumn = LinearLayout(requireContext()).apply {''', 1)

# Actor column belongs to new peopleRow, not secondRow, and weight becomes 1.
tv = tv.replace('''            secondRow.addView(
                actorColumn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply {
                    marginStart = dp(10)
                },
            )''', '''            peopleRow.addView(
                actorColumn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(10)
                },
            )''', 1)

# Explicit actor search should focus the suggestion window when results arrive.
tv = tv.replace('''            actorSearchButton.setOnClickListener { triggerActorSearch() }

            setupActorTextSearch()
            addActorSuggestionsSurface(panel)

            val actions = LinearLayout(requireContext()).apply {''', '''            actorSearchButton.setOnClickListener {
                focusActorSuggestionsOnNextResult = true
                triggerActorSearch()
            }

            val directorColumn = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            peopleRow.addView(
                directorColumn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
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
            }
            directorRow.addView(directorEdit, LinearLayout.LayoutParams(0, dp(46), 1f))
            val directorSearchButton = makeActionButton("Cerca regista", primary = false)
            directorRow.addView(
                directorSearchButton,
                LinearLayout.LayoutParams(dp(142), dp(46)).apply { marginStart = dp(8) },
            )
            directorSearchButton.setOnClickListener {
                focusDirectorSuggestionsOnNextResult = true
                triggerDirectorSearch()
            }

            setupActorTextSearch()
            setupDirectorTextSearch()
            addActorSuggestionsSurface(panel)
            addDirectorSuggestionsSurface(panel)

            val actions = LinearLayout(requireContext()).apply {''', 1)

# Rename primary action and pass both search buttons to focus config.
tv = tv.replace('''            btnApplyFilters = makeActionButton("Applica filtri", primary = true).also { button ->''', '''            btnApplyFilters = makeActionButton("Cerca / Applica filtri", primary = true).also { button ->''', 1)
tv = tv.replace('''                button.setOnClickListener { submitSearch() }
            }

            configureFilterFocus(actorSearchButton)
''', '''                button.setOnClickListener {
                    focusFirstResultAfterSearch = true
                    submitSearch()
                }
            }

            configureFilterFocus(actorSearchButton, directorSearchButton)
''', 1)

# Replace focus configuration wholesale.
pattern = re.compile(r'''    private fun configureFilterFocus\(actorSearchButton: TextView\) \{.*?\n    }\n\n    private fun setupActorTextSearch\(\) \{''', re.S)
focus_block = '''    private fun configureFilterFocus(actorSearchButton: TextView, directorSearchButton: TextView) {
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
        val reset = btnResetFilters ?: return
        val apply = btnApplyFilters ?: return

        filterPeekHandle.nextFocusDownId = etSearch.id
        etSearch.nextFocusRightId = btnSearchClear.id
        etSearch.nextFocusUpId = filterPeekHandle.id
        etSearch.nextFocusDownId = type.viewId
        btnSearchClear.nextFocusLeftId = etSearch.id
        btnSearchClear.nextFocusRightId = btnSearchVoice.id
        btnSearchClear.nextFocusUpId = filterPeekHandle.id
        btnSearchClear.nextFocusDownId = italian.viewId
        btnSearchVoice.nextFocusLeftId = btnSearchClear.id
        btnSearchVoice.nextFocusUpId = filterPeekHandle.id
        btnSearchVoice.nextFocusDownId = genre.viewId

        type.setNextFocus(right = italian.viewId, up = etSearch.id, down = from.viewId)
        italian.setNextFocus(left = type.viewId, right = year.viewId, up = etSearch.id, down = to.viewId)
        year.setNextFocus(left = italian.viewId, right = genre.viewId, up = etSearch.id, down = sort.viewId)
        genre.setNextFocus(left = year.viewId, up = etSearch.id, down = platform.viewId)

        from.setNextFocus(right = to.viewId, up = type.viewId, down = actor.id)
        to.setNextFocus(left = from.viewId, right = sort.viewId, up = italian.viewId, down = actorSearchButton.id)
        sort.setNextFocus(left = to.viewId, right = platform.viewId, up = year.viewId, down = director.id)
        platform.setNextFocus(left = sort.viewId, up = genre.viewId, down = directorSearchButton.id)

        actor.nextFocusRightId = actorSearchButton.id
        actor.nextFocusUpId = from.viewId
        actor.nextFocusDownId = reset.id
        actorSearchButton.nextFocusLeftId = actor.id
        actorSearchButton.nextFocusRightId = director.id
        actorSearchButton.nextFocusUpId = to.viewId
        actorSearchButton.nextFocusDownId = reset.id

        director.nextFocusLeftId = actorSearchButton.id
        director.nextFocusRightId = directorSearchButton.id
        director.nextFocusUpId = sort.viewId
        director.nextFocusDownId = apply.id
        directorSearchButton.nextFocusLeftId = director.id
        directorSearchButton.nextFocusUpId = platform.viewId
        directorSearchButton.nextFocusDownId = apply.id

        reset.nextFocusUpId = actor.id
        reset.nextFocusRightId = apply.id
        reset.nextFocusDownId = vgvSearch.id
        apply.nextFocusUpId = director.id
        apply.nextFocusLeftId = reset.id
        apply.nextFocusDownId = vgvSearch.id
        vgvSearch.nextFocusUpId = filterPeekHandle.id
    }

    private fun setupActorTextSearch() {'''
tv, n = pattern.subn(focus_block, tv, count=1)
if n != 1: raise SystemExit('focus config replacement failed')

# Add director text setup before actor suggestion surface.
marker = '    private fun addActorSuggestionsSurface(panel: LinearLayout) {'
director_setup = '''    private fun setupDirectorTextSearch() {
        val edit = directorEdit ?: return
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (suppressDirectorTextChange) return
                actorSearchJob?.cancel()
                searchViewModel?.clearDirectorSelection()
                val value = s?.toString().orEmpty().trim()
                if (value.length < 2) {
                    searchViewModel?.clearDirectorSuggestions()
                    displayDirectorSuggestions(emptyList())
                    return
                }
                actorSearchJob = viewLifecycleOwner.lifecycleScope.launch {
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
                focusDirectorSuggestionsOnNextResult = true
                triggerDirectorSearch()
                hideKeyboard()
                true
            } else false
        }
    }

    private fun addActorSuggestionsSurface(panel: LinearLayout) {'''
if marker not in tv: raise SystemExit('actor suggestions marker missing')
tv = tv.replace(marker, director_setup, 1)

# Give actor grid popup-like background and focus it on explicit request.
tv = tv.replace('''                setItemSpacing(dp(12))
            }
''', '''                setItemSpacing(dp(12))
                setBackgroundResource(R.drawable.bg_filter_panel_tv)
            }
''', 1)
tv = tv.replace('''                grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            }
''', '''                grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                if (items.isNotEmpty() && focusActorSuggestionsOnNextResult) {
                    focusActorSuggestionsOnNextResult = false
                    grid.post { grid.setSelectedPosition(0); grid.requestFocus() }
                }
            }
''', 1)

# Add director suggestion UI/functions before triggerActorSearch.
trigger_marker = '    private fun triggerActorSearch() {'
director_surface = '''    private fun addDirectorSuggestionsSurface(panel: LinearLayout) {
        try {
            directorSuggestionsGrid = HorizontalGridView(requireContext()).apply {
                id = View.generateViewId()
                visibility = View.GONE
                adapter = directorSuggestionsAdapter
                setItemSpacing(dp(12))
                setBackgroundResource(R.drawable.bg_filter_panel_tv)
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
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
        )
    }

    private fun triggerDirectorSearch() {
        val name = directorEdit?.text?.toString().orEmpty().trim()
        if (name.length < 2) {
            showSafeToast("Scrivi almeno 2 lettere del nome del regista")
            return
        }
        val vm = searchViewModel
        if (vm == null) {
            showSafeToast("Motore di ricerca ancora in inizializzazione")
            return
        }
        vm.searchDirectors(name)
    }

    private fun displayDirectorSuggestions(list: List<People>) {
        if (!isAdded || !this::searchHeader.isInitialized) return
        try {
            val items = list.onEach { it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM }
            directorSuggestionsGrid?.let { grid ->
                directorSuggestionsAdapter.submitList(items)
                grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                if (items.isNotEmpty() && focusDirectorSuggestionsOnNextResult) {
                    focusDirectorSuggestionsOnNextResult = false
                    grid.post { grid.setSelectedPosition(0); grid.requestFocus() }
                }
            }
            directorFallbackSuggestions?.let { fallback ->
                fallback.removeAllViews()
                if (directorSuggestionsGrid == null && items.isNotEmpty()) {
                    fallback.visibility = View.VISIBLE
                    items.take(5).forEach { person ->
                        val chip = makeActionButton(person.name, primary = false)
                        chip.setOnClickListener { selectDirector(person) }
                        fallback.addView(chip, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
                    }
                } else fallback.visibility = View.GONE
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

    private fun triggerActorSearch() {'''
if trigger_marker not in tv: raise SystemExit('trigger actor marker missing')
tv = tv.replace(trigger_marker, director_surface, 1)

# Reset extra fields.
tv = tv.replace('''        fromSelector?.reset()
        toSelector?.reset()
        suppressActorTextChange = true
''', '''        fromSelector?.reset()
        toSelector?.reset()
        sortSelector?.reset()
        platformSelector?.reset()
        suppressActorTextChange = true
''', 1)
tv = tv.replace('''        searchViewModel?.clearActorSuggestions()
        displayActorSuggestions(emptyList())
        searchViewModel?.search(etSearch.text?.toString().orEmpty().trim())
''', '''        searchViewModel?.clearActorSuggestions()
        displayActorSuggestions(emptyList())
        suppressDirectorTextChange = true
        directorEdit?.setText("")
        suppressDirectorTextChange = false
        searchViewModel?.clearDirectorSelection()
        searchViewModel?.clearDirectorSuggestions()
        displayDirectorSuggestions(emptyList())
        searchViewModel?.search(etSearch.text?.toString().orEmpty().trim())
''', 1)

# Current filters: validate director and include all new values.
tv = tv.replace('''        val actorText = actorEdit?.text?.toString().orEmpty().trim()
        if (actorText.isNotBlank() && vm?.selectedActorId == null) {
            triggerActorSearch()
            showSafeToast("Seleziona l'attore dai suggerimenti")
            return null
        }

        return AdvancedSearchFilters(''', '''        val actorText = actorEdit?.text?.toString().orEmpty().trim()
        if (actorText.isNotBlank() && vm?.selectedActorId == null) {
            focusActorSuggestionsOnNextResult = true
            triggerActorSearch()
            showSafeToast("Seleziona l'attore dai suggerimenti")
            return null
        }
        val directorText = directorEdit?.text?.toString().orEmpty().trim()
        if (directorText.isNotBlank() && vm?.selectedDirectorId == null) {
            focusDirectorSuggestionsOnNextResult = true
            triggerDirectorSearch()
            showSafeToast("Seleziona il regista dai suggerimenti")
            return null
        }

        return AdvancedSearchFilters(''', 1)
tv = tv.replace('''            actorId = vm?.selectedActorId,
            actorName = vm?.selectedActorName.orEmpty(),
        )
''', '''            actorId = vm?.selectedActorId,
            actorName = vm?.selectedActorName.orEmpty(),
            directorId = vm?.selectedDirectorId,
            directorName = vm?.selectedDirectorName.orEmpty(),
            sort = sortSelector?.value ?: AdvancedSearchFilters.SortFilter.POPULARITY,
            platform = platformSelector?.value ?: AdvancedSearchFilters.PlatformFilter.ALL,
        )
''', 1)

# Search is now one good integrated search; global switch is removed for TV only.
tv = tv.replace('''        if (isGlobalSearchChecked) {
            if (query.isBlank()) {
                showSafeToast(getString(R.string.search_empty_query))
                return true
            }
            vm.searchGlobal(query, UserPreferences.currentProvider?.language ?: "it")
        } else {
            vm.search(query)
        }
        return true
''', '''        vm.search(query)
        return true
''', 1)

# Replace old scroll/translation system with deterministic expanded/collapsed panel.
pattern_scroll = re.compile(r'''    private fun initializeScrollableHeader\(\) \{.*?\n    private fun focusSearchContent\(\): Boolean \{''', re.S)
new_scroll = '''    private fun initializeScrollableHeader() {
        // TV only: the filter panel has two deterministic states instead of floating over posters.
        vgvSearch.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0 && recyclerView.hasFocus() && filterPanelExpanded) {
                    setFilterPanelExpanded(false, focusResults = false)
                }
            }
        })
    }

    private fun updateGridTopPadding() {
        if (!isAdded || !this::searchHeader.isInitialized) return
        searchHeader.post {
            val top = searchHeader.height + dp(8)
            if (top > 0 && vgvSearch.paddingTop != top) {
                vgvSearch.setPadding(vgvSearch.paddingLeft, top, vgvSearch.paddingRight, vgvSearch.paddingBottom)
            }
        }
    }

    private fun setFilterPanelExpanded(expanded: Boolean, focusResults: Boolean) {
        if (!this::filterPeekHandle.isInitialized) return
        filterPanelExpanded = expanded
        filterContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        filterPeekHandle.text = if (expanded) "FILTRI ▲" else "FILTRI ▼"
        searchHeader.translationY = 0f
        searchHeaderOffset = 0
        searchHeader.post {
            updateGridTopPadding()
            if (focusResults) focusFirstPoster()
        }
    }

    private fun focusFirstPoster() {
        if (appAdapter.itemCount <= 0) {
            focusFirstResultAfterSearch = false
            return
        }
        focusFirstResultAfterSearch = false
        vgvSearch.scrollToPosition(0)
        vgvSearch.post {
            vgvSearch.requestFocus()
            vgvSearch.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun revealSearchHeader() {
        setFilterPanelExpanded(true, focusResults = false)
    }

    private fun focusSearchContent(): Boolean {'''
tv, n = pattern_scroll.subn(new_scroll, tv, count=1)
if n != 1: raise SystemExit('scroll system replacement failed')

# On result display, if Apply requested focus, keep first-result handoff reliable after adapter layout.
tv = tv.replace('''        searchHeader.post { updateGridTopPadding() }
    }

    private fun displayGlobalSearch''', '''        searchHeader.post {
            updateGridTopPadding()
            if (focusFirstResultAfterSearch) setFilterPanelExpanded(false, focusResults = true)
        }
    }

    private fun displayGlobalSearch''', 1)

# Dropdown focus reopening now uses the collapsed-state flag, not translation offsets.
tv = tv.replace('''            focusView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && searchHeaderOffset > 0) revealSearchHeader()
            }
''', '''            focusView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && !filterPanelExpanded) revealSearchHeader()
            }
''', 1)

TV.write_text(tv, encoding='utf-8')
print('Applied TV-only collapsible filter panel v3 + sort/platform/director filters. Mobile UI untouched.')

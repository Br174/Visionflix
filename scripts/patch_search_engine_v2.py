from pathlib import Path
# ViewModel: broaden query while keeping filters cumulative.
p=Path('app/src/main/java/com/streamflixreborn/streamflix/fragments/search/SearchViewModel.kt'); s=p.read_text()
def r(a,b):
 global s
 assert a in s,a[:100]
 s=s.replace(a,b,1)
r('import kotlinx.coroutines.launch\n','import kotlinx.coroutines.launch\nimport java.util.Locale\n')
r('''        if (query.isNotBlank()) {
            val firstTmdbPage = ((page - 1) * 3) + 1
            val searchDeferred = (firstTmdbPage..firstTmdbPage + 2).map { tmdbPage ->
                async {
                    TMDb3.Search.multi(
                        query = query,
                        page = tmdbPage,
                        language = language,
                    ).results
                }
            }
''','''        if (query.isNotBlank()) {
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
''')
s=s.replace('(firstTmdbPage..firstTmdbPage + 2).map { discoverPage ->','(firstDiscoverPage until firstDiscoverPage + 6).map { discoverPage ->')
marker='    fun searchGlobal(query: String, currentLanguage: String) = viewModelScope.launch(Dispatchers.IO) {'
helper='''    private fun buildQueryVariants(rawQuery: String): List<String> {
        val clean = rawQuery.trim().replace(Regex("\\s+"), " ")
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

'''
r(marker,helper+marker)
p.write_text(s)

# AdvancedSearchUi: expose sort/platform to Mobile.
p=Path('app/src/main/java/com/streamflixreborn/streamflix/fragments/search/AdvancedSearchUi.kt'); s=p.read_text()
def q(a,b):
 global s
 assert a in s,a[:100]
 s=s.replace(a,b,1)
q('''        year: Spinner,
        genre: Spinner? = null,
    ) {
''','''        year: Spinner,
        genre: Spinner? = null,
        sort: Spinner? = null,
        platform: Spinner? = null,
    ) {
''')
q('''            year.rootView.findViewById<Spinner>(R.id.et_date_to)?.adapter = yearAdapter(context)
        }
    }
''','''            year.rootView.findViewById<Spinner>(R.id.et_date_to)?.adapter = yearAdapter(context)
        }
        sort?.adapter = compactAdapter(context, AdvancedSearchFilters.SortFilter.values().map { it.label })
        platform?.adapter = compactAdapter(context, AdvancedSearchFilters.PlatformFilter.values().map { it.label })
    }
''')
q('''        yearTo: Spinner,
        genre: Spinner? = null,
    ): AdvancedSearchFilters {
''','''        yearTo: Spinner,
        genre: Spinner? = null,
        sort: Spinner? = null,
        platform: Spinner? = null,
    ): AdvancedSearchFilters {
''')
q('''            dateTo = selectedTo?.let { "%04d-12-31".format(it) },
        )
''','''            dateTo = selectedTo?.let { "%04d-12-31".format(it) },
            sort = sort?.let { AdvancedSearchFilters.SortFilter.values().getOrElse(it.selectedItemPosition) { AdvancedSearchFilters.SortFilter.POPULARITY } } ?: AdvancedSearchFilters.SortFilter.POPULARITY,
            platform = platform?.let { AdvancedSearchFilters.PlatformFilter.values().getOrElse(it.selectedItemPosition) { AdvancedSearchFilters.PlatformFilter.ALL } } ?: AdvancedSearchFilters.PlatformFilter.ALL,
        )
''')
q('''        yearTo: Spinner,
        genre: Spinner? = null,
    ) {
''','''        yearTo: Spinner,
        genre: Spinner? = null,
        sort: Spinner? = null,
        platform: Spinner? = null,
    ) {
''')
q('''        genre?.setSelection(0)
    }
''','''        genre?.setSelection(0)
        sort?.setSelection(0)
        platform?.setSelection(0)
    }
''')
p.write_text(s)
print('search engine patched')

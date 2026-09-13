from pathlib import Path

ROOT = Path('.')
TV = ROOT / 'app/src/main/java/com/streamflixreborn/streamflix/fragments/search/SearchTvFragment.kt'
MOBILE = ROOT / 'app/src/main/java/com/streamflixreborn/streamflix/fragments/search/SearchMobileFragment.kt'
PERSIST = ROOT / 'app/src/main/java/com/streamflixreborn/streamflix/fragments/search/SearchFilterPersistence.kt'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'Missing patch anchor: {label}')
    return text.replace(old, new, 1)

# Shared persistent search/filter state. Shared functionally by TV + Mobile,
# while movement/focus behavior remains platform specific.
PERSIST.write_text(r'''package com.streamflixreborn.streamflix.fragments.search

import android.content.Context
import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters

internal data class SavedSearchFilterState(
    val query: String = "",
    val globalSearch: Boolean = false,
    val type: AdvancedSearchFilters.ContentType = AdvancedSearchFilters.ContentType.ALL,
    val italian: AdvancedSearchFilters.ItalianFilter = AdvancedSearchFilters.ItalianFilter.ALL,
    val year: Int? = null,
    val fromYear: Int? = null,
    val toYear: Int? = null,
    val genre: AdvancedSearchFilters.GenreFilter = AdvancedSearchFilters.GenreFilter.ALL,
    val sort: AdvancedSearchFilters.SortFilter = AdvancedSearchFilters.SortFilter.POPULARITY,
    val platform: AdvancedSearchFilters.PlatformFilter = AdvancedSearchFilters.PlatformFilter.ALL,
    val actorId: String? = null,
    val actorName: String = "",
    val directorId: String? = null,
    val directorName: String = "",
)

internal object SearchFilterPersistence {
    private const val PREFS = "verlezza_search_filters_v1"

    fun load(context: Context): SavedSearchFilterState {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun <T : Enum<T>> enumValue(key: String, values: Array<T>, fallback: T): T {
            val raw = p.getString(key, null) ?: return fallback
            return values.firstOrNull { it.name == raw } ?: fallback
        }
        fun optionalInt(key: String): Int? = if (p.contains(key)) p.getInt(key, 0) else null
        fun optionalString(key: String): String? = p.getString(key, null)?.takeIf { it.isNotBlank() }
        return SavedSearchFilterState(
            query = p.getString("query", "").orEmpty(),
            globalSearch = p.getBoolean("global", false),
            type = enumValue("type", AdvancedSearchFilters.ContentType.values(), AdvancedSearchFilters.ContentType.ALL),
            italian = enumValue("italian", AdvancedSearchFilters.ItalianFilter.values(), AdvancedSearchFilters.ItalianFilter.ALL),
            year = optionalInt("year"),
            fromYear = optionalInt("from_year"),
            toYear = optionalInt("to_year"),
            genre = enumValue("genre", AdvancedSearchFilters.GenreFilter.values(), AdvancedSearchFilters.GenreFilter.ALL),
            sort = enumValue("sort", AdvancedSearchFilters.SortFilter.values(), AdvancedSearchFilters.SortFilter.POPULARITY),
            platform = enumValue("platform", AdvancedSearchFilters.PlatformFilter.values(), AdvancedSearchFilters.PlatformFilter.ALL),
            actorId = optionalString("actor_id"),
            actorName = p.getString("actor_name", "").orEmpty(),
            directorId = optionalString("director_id"),
            directorName = p.getString("director_name", "").orEmpty(),
        )
    }

    fun save(context: Context, state: SavedSearchFilterState) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        e.putString("query", state.query)
        e.putBoolean("global", state.globalSearch)
        e.putString("type", state.type.name)
        e.putString("italian", state.italian.name)
        e.putString("genre", state.genre.name)
        e.putString("sort", state.sort.name)
        e.putString("platform", state.platform.name)
        if (state.year == null) e.remove("year") else e.putInt("year", state.year)
        if (state.fromYear == null) e.remove("from_year") else e.putInt("from_year", state.fromYear)
        if (state.toYear == null) e.remove("to_year") else e.putInt("to_year", state.toYear)
        if (state.actorId.isNullOrBlank()) e.remove("actor_id") else e.putString("actor_id", state.actorId)
        e.putString("actor_name", state.actorName)
        if (state.directorId.isNullOrBlank()) e.remove("director_id") else e.putString("director_id", state.directorId)
        e.putString("director_name", state.directorName)
        e.apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
''')

# ---------------- TV ----------------
s = TV.read_text()
s = replace_once(s,
    '    private var filterHandle: TextView? = null\n',
    '    private var filterFocusSentinel: View? = null\n',
    'TV handle field')
s = replace_once(s,
    '            buildAdvancedFiltersSafely()\n            createViewModelSafely()\n            initializeScrollableHeader()\n',
    '            buildAdvancedFiltersSafely()\n            createViewModelSafely()\n            restorePersistedFiltersSafely()\n            initializeScrollableHeader()\n',
    'TV restore call')
s = replace_once(s,
    '    override fun onDestroyView() {\n',
    '    override fun onPause() {\n        savePersistedFiltersSafely()\n        super.onPause()\n    }\n\n    override fun onDestroyView() {\n',
    'TV onPause')
s = replace_once(s,
    '        filterHandle = null\n',
    '        filterFocusSentinel = null\n',
    'TV sentinel cleanup')
s = replace_once(s,
'''                    if (position in 0 until currentGridColumns.coerceAtLeast(1)) {
                        val target = filterHandle?.id ?: btnApplyFilters?.id
                        if (target != null) child?.itemView?.nextFocusUpId = target
                    }
''',
'''                    if (position in 0 until currentGridColumns.coerceAtLeast(1)) {
                        val target = filterFocusSentinel?.id ?: btnApplyFilters?.id
                        if (target != null) child?.itemView?.nextFocusUpId = target
                        child?.itemView?.setOnKeyListener { _, keyCode, event ->
                            if (event.action == KeyEvent.ACTION_DOWN &&
                                keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                                !filterPanelExpanded) {
                                openFiltersFromResults()
                                true
                            } else false
                        }
                    }
''',
    'TV first-row UP interception')
s = replace_once(s,
    '                setPadding(dp(18), dp(16), dp(18), dp(8))\n',
    '                setPadding(dp(14), dp(10), dp(14), dp(6))\n',
    'TV panel padding')
s = s.replace('setPadding(0, dp(10), 0, 0)', 'setPadding(0, dp(6), 0, 0)')
s = s.replace('setPadding(0, dp(8), 0, 0)', 'setPadding(0, dp(5), 0, 0)')
s = s.replace('dp(136)', 'dp(118)')
s = s.replace('dp(46)', 'dp(42)')
s = s.replace('dp(48)', 'dp(44)')
old_handle = '''            filterHandle = makeActionButton("Filtri", primary = false).apply {
                textSize = 12f
                visibility = View.GONE
                setOnClickListener { openFiltersFromResults() }
                setOnFocusChangeListener { _, hasFocus -> if (hasFocus && !filterPanelExpanded) openFiltersFromResults() }
            }
            filterContainer.addView(
                filterHandle,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(FILTER_PEEK_DP)).apply {
                    topMargin = dp(5)
                },
            )
'''
new_sentinel = '''            // Nessuna barra visibile quando i filtri sono chiusi. Questo piccolo
            // sentinel trasparente serve soltanto al focus D-pad per riaprire il pannello.
            filterFocusSentinel = View(requireContext()).apply {
                id = View.generateViewId()
                isFocusable = true
                isFocusableInTouchMode = true
                alpha = 0f
                visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus && !filterPanelExpanded) openFiltersFromResults()
                }
            }
            filterContainer.addView(
                filterFocusSentinel,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
            )
'''
s = replace_once(s, old_handle, new_sentinel, 'TV remove visible Filtri bar')
s = replace_once(s,
    '        vgvSearch.nextFocusUpId = filterHandle?.id ?: apply.id\n',
    '        vgvSearch.nextFocusUpId = filterFocusSentinel?.id ?: apply.id\n',
    'TV focus target')
s = replace_once(s,
'''        displayActorSuggestions(emptyList())
        displayDirectorSuggestions(emptyList())
        searchViewModel?.search(etSearch.text?.toString().orEmpty().trim())
''',
'''        displayActorSuggestions(emptyList())
        displayDirectorSuggestions(emptyList())
        etSearch.setText("")
        isGlobalSearchChecked = false
        updateGlobalSwitch()
        SearchFilterPersistence.clear(requireContext())
        searchViewModel?.search("")
''',
    'TV reset persistence')
s = replace_once(s,
'''        val filters = currentFilters() ?: return true

        if (filters.isActive) {
''',
'''        val filters = currentFilters() ?: return true
        savePersistedFiltersSafely()

        if (filters.isActive) {
''',
    'TV save on submit')
s = replace_once(s,
'''            vm.searchAdvanced(query, filters)
            collapseFiltersAndFocusResults()
''',
'''            stabilizeStandardResultGrid(query, advanced = true)
            vm.searchAdvanced(query, filters)
            collapseFiltersAndFocusResults()
''',
    'TV stabilize advanced grid')
s = replace_once(s,
'''        } else {
            vm.search(query)
            collapseFiltersAndFocusResults()
        }
''',
'''        } else {
            stabilizeStandardResultGrid(query, advanced = false)
            vm.search(query)
            collapseFiltersAndFocusResults()
        }
''',
    'TV stabilize basic grid')
s = replace_once(s,
'''    private fun updateGlobalSwitch() {
''',
'''    private fun stabilizeStandardResultGrid(query: String, advanced: Boolean) {
        currentGridColumns = if (query.isBlank() && !advanced) 5 else 6
        vgvSearch.setNumColumns(currentGridColumns)
    }

    private fun updateGlobalSwitch() {
''',
    'TV stabilize helper')
s = replace_once(s,
'''        filterPanelBody?.visibility = if (expanded) View.VISIBLE else View.GONE
        filterHandle?.visibility = if (expanded) View.GONE else View.VISIBLE
''',
'''        filterPanelBody?.visibility = if (expanded) View.VISIBLE else View.GONE
        filterFocusSentinel?.visibility = if (expanded) View.GONE else View.VISIBLE
''',
    'TV panel/sentinel visibility')
s = replace_once(s,
'''        searchViewModel?.selectActorFilter(people.id, people.name)
        actorSuggestionsGrid?.visibility = View.GONE
''',
'''        searchViewModel?.selectActorFilter(people.id, people.name)
        savePersistedFiltersSafely()
        actorSuggestionsGrid?.visibility = View.GONE
''',
    'TV actor persistence')
s = replace_once(s,
'''        searchViewModel?.selectDirectorFilter(people.id, people.name)
        directorSuggestionsGrid?.visibility = View.GONE
''',
'''        searchViewModel?.selectDirectorFilter(people.id, people.name)
        savePersistedFiltersSafely()
        directorSuggestionsGrid?.visibility = View.GONE
''',
    'TV director persistence')
insert_before_scroll = '''    private fun initializeScrollableHeader() {
'''
persistence_tv = '''    private fun savePersistedFiltersSafely() {
        if (!isAdded || !this::etSearch.isInitialized) return
        try {
            SearchFilterPersistence.save(
                requireContext(),
                SavedSearchFilterState(
                    query = etSearch.text?.toString().orEmpty(),
                    globalSearch = isGlobalSearchChecked,
                    type = typeSelector?.value ?: AdvancedSearchFilters.ContentType.ALL,
                    italian = italianSelector?.value ?: AdvancedSearchFilters.ItalianFilter.ALL,
                    year = yearSelector?.value,
                    fromYear = fromSelector?.value,
                    toYear = toSelector?.value,
                    genre = genreSelector?.value ?: AdvancedSearchFilters.GenreFilter.ALL,
                    sort = sortSelector?.value ?: AdvancedSearchFilters.SortFilter.POPULARITY,
                    platform = platformSelector?.value ?: AdvancedSearchFilters.PlatformFilter.ALL,
                    actorId = searchViewModel?.selectedActorId,
                    actorName = actorEdit?.text?.toString().orEmpty().trim(),
                    directorId = searchViewModel?.selectedDirectorId,
                    directorName = directorEdit?.text?.toString().orEmpty().trim(),
                ),
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Saving persistent filters failed", error)
        }
    }

    private fun restorePersistedFiltersSafely() {
        if (!isAdded) return
        try {
            val saved = SearchFilterPersistence.load(requireContext())
            etSearch.setText(saved.query)
            isGlobalSearchChecked = saved.globalSearch
            updateGlobalSwitch()
            typeSelector?.selectValue(saved.type)
            italianSelector?.selectValue(saved.italian)
            yearSelector?.selectValue(saved.year)
            fromSelector?.selectValue(saved.fromYear)
            toSelector?.selectValue(saved.toYear)
            genreSelector?.selectValue(saved.genre)
            sortSelector?.selectValue(saved.sort)
            platformSelector?.selectValue(saved.platform)

            suppressActorTextChange = true
            actorEdit?.setText(saved.actorName)
            suppressActorTextChange = false
            suppressDirectorTextChange = true
            directorEdit?.setText(saved.directorName)
            suppressDirectorTextChange = false

            saved.actorId?.takeIf { saved.actorName.isNotBlank() }?.let {
                searchViewModel?.selectActorFilter(it, saved.actorName)
            } ?: run {
                if (saved.actorName.length >= 2) searchViewModel?.searchActors(saved.actorName)
            }
            saved.directorId?.takeIf { saved.directorName.isNotBlank() }?.let {
                searchViewModel?.selectDirectorFilter(it, saved.directorName)
            } ?: run {
                if (saved.directorName.length >= 2) searchViewModel?.searchDirectors(saved.directorName)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Restoring persistent filters failed", error)
        }
    }

'''
s = replace_once(s, insert_before_scroll, persistence_tv + insert_before_scroll, 'TV persistence methods')
s = replace_once(s,
'''        fun reset() {
            spinner.setSelection(0, false)
        }

        fun requestFocus(): Boolean = focusView.requestFocus()
''',
'''        fun reset() {
            spinner.setSelection(0, false)
        }

        fun selectValue(value: T?) {
            val index = values.indexOfFirst { it == value }
            spinner.setSelection(if (index >= 0) index else 0, false)
        }

        fun requestFocus(): Boolean = focusView.requestFocus()
''',
    'TV selector restore')
s = s.replace('        private const val FILTER_PEEK_DP = 28\n', '')
TV.write_text(s)

# ---------------- MOBILE: persistence only, no size/layout movement changes ----------------
m = MOBILE.read_text()
m = replace_once(m,
'''        initializeDirectorSearch()
        initializeScrollableHeader()
''',
'''        initializeDirectorSearch()
        restorePersistedFilters()
        initializeScrollableHeader()
''',
    'Mobile restore call')
m = replace_once(m,
'''    override fun onDestroyView() {
''',
'''    override fun onPause() {
        savePersistedFilters()
        super.onPause()
    }

    override fun onDestroyView() {
''',
    'Mobile onPause')
m = replace_once(m,
'''                viewModel.selectActorFilter(people.id, people.name)
                hideKeyboard()
''',
'''                viewModel.selectActorFilter(people.id, people.name)
                savePersistedFilters()
                hideKeyboard()
''',
    'Mobile actor persistence')
m = replace_once(m,
'''                viewModel.selectDirectorFilter(people.id, people.name)
                hideKeyboard()
''',
'''                viewModel.selectDirectorFilter(people.id, people.name)
                savePersistedFilters()
                hideKeyboard()
''',
    'Mobile director persistence')
m = replace_once(m,
'''            clearActorField()
            clearDirectorField()
            viewModel.search(binding.etSearch.text?.toString().orEmpty())
''',
'''            clearActorField()
            clearDirectorField()
            binding.etSearch.setText("")
            binding.swGlobalSearch.isChecked = false
            SearchFilterPersistence.clear(requireContext())
            viewModel.search("")
''',
    'Mobile reset persistence')
m = replace_once(m,
'''        val filters = baseFilters.copy(
            actorId = viewModel.selectedActorId,
            actorName = viewModel.selectedActorName,
            directorId = viewModel.selectedDirectorId,
            directorName = viewModel.selectedDirectorName,
        )

        if (filters.isActive) {
''',
'''        val filters = baseFilters.copy(
            actorId = viewModel.selectedActorId,
            actorName = viewModel.selectedActorName,
            directorId = viewModel.selectedDirectorId,
            directorName = viewModel.selectedDirectorName,
        )
        savePersistedFilters()

        if (filters.isActive) {
''',
    'Mobile save on submit')
anchor = '''    private fun clearActorField() {
'''
mobile_helpers = '''    private fun savePersistedFilters() {
        if (_binding == null || !isAdded) return
        try {
            fun selectedYear(spinner: android.widget.Spinner): Int? =
                spinner.selectedItem?.toString()?.toIntOrNull()
            SearchFilterPersistence.save(
                requireContext(),
                SavedSearchFilterState(
                    query = binding.etSearch.text?.toString().orEmpty(),
                    globalSearch = binding.swGlobalSearch.isChecked,
                    type = when (binding.spContentType.selectedItemPosition) {
                        1 -> com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.ContentType.MOVIE
                        2 -> com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.ContentType.TV
                        3 -> com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.ContentType.CARTOON
                        else -> com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.ContentType.ALL
                    },
                    italian = when (binding.spItalian.selectedItemPosition) {
                        1 -> com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.ItalianFilter.YES
                        2 -> com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.ItalianFilter.NO
                        else -> com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.ItalianFilter.ALL
                    },
                    year = selectedYear(binding.spYear),
                    fromYear = selectedYear(binding.etDateFrom),
                    toYear = selectedYear(binding.etDateTo),
                    genre = com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.GenreFilter.values()
                        .getOrElse(binding.spGenre.selectedItemPosition) { com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.GenreFilter.ALL },
                    sort = com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.SortFilter.values()
                        .getOrElse(binding.spSort.selectedItemPosition) { com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.SortFilter.POPULARITY },
                    platform = com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.PlatformFilter.values()
                        .getOrElse(binding.spPlatform.selectedItemPosition) { com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.PlatformFilter.ALL },
                    actorId = viewModel.selectedActorId,
                    actorName = binding.etActorSearch.text?.toString().orEmpty().trim(),
                    directorId = viewModel.selectedDirectorId,
                    directorName = binding.etDirectorSearch.text?.toString().orEmpty().trim(),
                ),
            )
        } catch (_: Throwable) {
        }
    }

    private fun restorePersistedFilters() {
        if (_binding == null || !isAdded) return
        val saved = SearchFilterPersistence.load(requireContext())
        binding.etSearch.setText(saved.query)
        binding.swGlobalSearch.isChecked = saved.globalSearch
        binding.spContentType.setSelection(
            when (saved.type) {
                com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.ContentType.MOVIE -> 1
                com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.ContentType.TV -> 2
                com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.ContentType.CARTOON -> 3
                else -> 0
            },
            false,
        )
        binding.spItalian.setSelection(
            when (saved.italian) {
                com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.ItalianFilter.YES -> 1
                com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.ItalianFilter.NO -> 2
                else -> 0
            },
            false,
        )
        fun selectYear(spinner: android.widget.Spinner, year: Int?) {
            if (year == null) {
                spinner.setSelection(0, false)
                return
            }
            val index = (0 until spinner.count).firstOrNull {
                spinner.getItemAtPosition(it)?.toString()?.toIntOrNull() == year
            } ?: 0
            spinner.setSelection(index, false)
        }
        selectYear(binding.spYear, saved.year)
        selectYear(binding.etDateFrom, saved.fromYear)
        selectYear(binding.etDateTo, saved.toYear)
        binding.spGenre.setSelection(saved.genre.ordinal.coerceAtMost(binding.spGenre.count - 1), false)
        binding.spSort.setSelection(saved.sort.ordinal.coerceAtMost(binding.spSort.count - 1), false)
        binding.spPlatform.setSelection(saved.platform.ordinal.coerceAtMost(binding.spPlatform.count - 1), false)

        suppressActorTextChange = true
        binding.etActorSearch.setText(saved.actorName)
        suppressActorTextChange = false
        suppressDirectorTextChange = true
        binding.etDirectorSearch.setText(saved.directorName)
        suppressDirectorTextChange = false
        saved.actorId?.takeIf { saved.actorName.isNotBlank() }?.let {
            viewModel.selectActorFilter(it, saved.actorName)
        } ?: run {
            if (saved.actorName.length >= 2) viewModel.searchActors(saved.actorName)
        }
        saved.directorId?.takeIf { saved.directorName.isNotBlank() }?.let {
            viewModel.selectDirectorFilter(it, saved.directorName)
        } ?: run {
            if (saved.directorName.length >= 2) viewModel.searchDirectors(saved.directorName)
        }
    }

'''
m = replace_once(m, anchor, mobile_helpers + anchor, 'Mobile persistence methods')
MOBILE.write_text(m)

print('TV panel/persistence patch applied')

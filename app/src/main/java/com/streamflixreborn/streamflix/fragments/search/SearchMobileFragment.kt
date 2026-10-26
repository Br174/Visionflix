package com.streamflixreborn.streamflix.fragments.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentSearchMobileBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.VoiceRecognitionHelper
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.hideKeyboard
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false
    private var _binding: FragmentSearchMobileBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { SearchViewModel(database) }
    private var appAdapter = AppAdapter()
    private lateinit var voiceHelper: VoiceRecognitionHelper
    private var actorSearchJob: Job? = null
    private var directorSearchJob: Job? = null
    private var suppressActorTextChange: Boolean = false
    private var suppressDirectorTextChange: Boolean = false
    private val selectedGenres = linkedSetOf<com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.GenreFilter>()

    private val actorSuggestionsAdapter by lazy {
        AppAdapter().apply {
            onPeopleClickListener = { people ->
                suppressActorTextChange = true
                binding.etActorSearch.setText(people.name)
                binding.etActorSearch.setSelection(binding.etActorSearch.text?.length ?: 0)
                suppressActorTextChange = false

                viewModel.selectActorFilter(people.id, people.name)
                savePersistedFilters()
                hideKeyboard()
                binding.rvActorSuggestions.visibility = View.GONE
                binding.searchHeaderMobile.post { updateGridTopPadding() }
            }
        }
    }

    private val directorSuggestionsAdapter by lazy {
        AppAdapter().apply {
            onPeopleClickListener = { people ->
                suppressDirectorTextChange = true
                binding.etDirectorSearch.setText(people.name)
                binding.etDirectorSearch.setSelection(binding.etDirectorSearch.text?.length ?: 0)
                suppressDirectorTextChange = false
                viewModel.selectDirectorFilter(people.id, people.name)
                savePersistedFilters()
                hideKeyboard()
                binding.rvDirectorSuggestions.visibility = View.GONE
                binding.searchHeaderMobile.post { updateGridTopPadding() }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeSearch()
        initializeAdvancedFilters()
        initializeFilterDrawer()
        initializeActorSearch()
        initializeDirectorSearch()
        restorePersistedFilters()
        initializeScrollableHeader()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is State.Searching, is State.GlobalSearching -> {
                        binding.isLoading.apply {
                            root.visibility = View.VISIBLE
                            pbIsLoading.visibility = View.VISIBLE
                            gIsLoadingRetry.visibility = View.GONE
                        }
                        appAdapter.isLoading = false
                        appAdapter.setOnLoadMoreListener(null)
                    }
                    is State.SearchingMore -> appAdapter.isLoading = true
                    is State.SuccessSearching -> {
                        displaySearch(state.results, state.hasMore)
                        appAdapter.isLoading = false
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is State.SuccessGlobalSearching -> {
                        displayGlobalSearch(state.catalog.posters, state.hasMore)
                        appAdapter.isLoading = false
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is State.FailedSearching -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.clear_cache_done_409),
                                Toast.LENGTH_SHORT,
                            ).show()
                            retryCurrentSearch()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener { retryCurrentSearch() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    Toast.makeText(
                                        requireContext(),
                                        getString(R.string.clear_cache_done),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    retryCurrentSearch()
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
                            }
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.actorSuggestions
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { displayActorSuggestions(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.directorSuggestions
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { displayDirectorSuggestions(it) }
        }
    }

    override fun onPause() {
        savePersistedFilters()
        super.onPause()
    }

    override fun onDestroyView() {
        actorSearchJob?.cancel()
        directorSearchJob?.cancel()
        if (::voiceHelper.isInitialized) voiceHelper.stopRecognition()
        super.onDestroyView()
        _binding = null
    }

    private fun initializeAdvancedFilters() {
        AdvancedSearchUi.setup(
            requireContext(),
            binding.spContentType,
            binding.spItalian,
            binding.spYear,
            binding.spGenre,
            binding.spSort,
            binding.spPlatform,
            binding.spCountry,
        )

        binding.spGenre.setOnLongClickListener { showMultiGenrePicker(); true }
        binding.btnApplyFilters.setOnClickListener { submitSearch() }
        binding.btnResetFilters.setOnClickListener {
            AdvancedSearchUi.reset(
                binding.spContentType,
                binding.spItalian,
                binding.spYear,
                binding.etDateFrom,
                binding.etDateTo,
                binding.spGenre,
                binding.spSort,
                binding.spPlatform,
                binding.spCountry,
            )
            selectedGenres.clear()
            clearActorField()
            clearDirectorField()
            binding.etSearch.setText("")
            binding.swGlobalSearch.isChecked = false
            SearchFilterPersistence.clear(requireContext())
            viewModel.search("")
        }
    }

    private fun initializeActorSearch() {
        binding.rvActorSuggestions.apply {
            layoutManager = LinearLayoutManager(
                requireContext(),
                LinearLayoutManager.HORIZONTAL,
                false,
            )
            adapter = actorSuggestionsAdapter
            addItemDecoration(SpacingItemDecoration(8.dp(requireContext())))
        }

        binding.etActorSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val actorQuery = binding.etActorSearch.text?.toString().orEmpty().trim()
                if (actorQuery.length >= 2 && viewModel.selectedActorId == null) {
                    viewModel.searchActors(actorQuery)
                }
                hideKeyboard()
                true
            } else {
                false
            }
        }

        binding.etActorSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (suppressActorTextChange) return
                actorSearchJob?.cancel()
                viewModel.clearActorSelection()

                val actorQuery = s?.toString().orEmpty().trim()
                if (actorQuery.length < 2) {
                    viewModel.clearActorSuggestions()
                    return
                }

                actorSearchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(280)
                    viewModel.searchActors(actorQuery)
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }

    private fun initializeDirectorSearch() {
        binding.rvDirectorSuggestions.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = directorSuggestionsAdapter
            addItemDecoration(SpacingItemDecoration(8.dp(requireContext())))
        }
        binding.etDirectorSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val q = binding.etDirectorSearch.text?.toString().orEmpty().trim()
                if (q.length >= 2 && viewModel.selectedDirectorId == null) viewModel.searchDirectors(q)
                hideKeyboard(); true
            } else false
        }
        binding.etDirectorSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(v: Editable?) {
                if (suppressDirectorTextChange) return
                directorSearchJob?.cancel(); viewModel.clearDirectorSelection()
                val q = v?.toString().orEmpty().trim()
                if (q.length < 2) { viewModel.clearDirectorSuggestions(); return }
                directorSearchJob = viewLifecycleOwner.lifecycleScope.launch { delay(280); viewModel.searchDirectors(q) }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }

    // Only the visible side arrow may open or close this compact Mobile drawer.
    private fun initializeFilterDrawer() {
        val h = (resources.displayMetrics.heightPixels * 0.44f).toInt()
            .coerceAtMost(330.dp(requireContext())).coerceAtLeast(190.dp(requireContext()))
        binding.scrollAdvancedFilters.layoutParams =
            binding.scrollAdvancedFilters.layoutParams.apply { height = h }
        binding.scrollAdvancedFilters.visibility = View.GONE
        binding.btnFilterToggle.apply {
            text = "⌄"
            contentDescription = "Apri i filtri"
            setOnClickListener {
                val expanded = binding.scrollAdvancedFilters.visibility != View.VISIBLE
                binding.scrollAdvancedFilters.visibility = if (expanded) View.VISIBLE else View.GONE
                text = if (expanded) "⌃" else "⌄"
                contentDescription = if (expanded) "Chiudi i filtri" else "Apri i filtri"
                binding.searchHeaderMobile.post { updateGridTopPadding() }
            }
        }
    }

    private fun initializeScrollableHeader() {
        binding.searchHeaderMobile.post { updateGridTopPadding() }
        binding.searchHeaderMobile.translationY = 0f
    }

    private fun updateGridTopPadding() {
        if (_binding == null) return
        val top = binding.searchHeaderMobile.height + 8.dp(requireContext())
        if (binding.rvSearch.paddingTop != top) {
            binding.rvSearch.setPadding(binding.rvSearch.paddingLeft,
                top, binding.rvSearch.paddingRight, binding.rvSearch.paddingBottom)
        }
    }

    private fun savePersistedFilters() {
        if (_binding == null || !isAdded) return
        try {
            fun selectedYear(spinner: android.widget.Spinner): Int? =
                spinner.selectedItem?.toString()?.toIntOrNull()
            SearchFilterPersistence.save(
                requireContext(),
                SavedSearchFilterState(
                    query = binding.etSearch.text?.toString().orEmpty(),
                    globalSearch = false,
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
                    country = com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.CountryFilter.entries
                        .getOrElse(binding.spCountry.selectedItemPosition) { com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.CountryFilter.ALL },
                    genres = selectedGenres.toSet(),
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
        binding.swGlobalSearch.isChecked = false
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
        binding.spCountry.setSelection(saved.country.ordinal.coerceAtMost(binding.spCountry.count - 1), false)
        selectedGenres.clear()
        selectedGenres.addAll(saved.genres)

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

    private fun clearActorField() {
        actorSearchJob?.cancel()
        suppressActorTextChange = true
        binding.etActorSearch.setText("")
        suppressActorTextChange = false
        viewModel.clearActorSelection()
        binding.rvActorSuggestions.visibility = View.GONE
        binding.searchHeaderMobile.post { updateGridTopPadding() }
    }

    private fun clearDirectorField() {
        directorSearchJob?.cancel()
        suppressDirectorTextChange = true
        binding.etDirectorSearch.setText("")
        suppressDirectorTextChange = false
        viewModel.clearDirectorSelection()
        binding.rvDirectorSuggestions.visibility = View.GONE
        binding.searchHeaderMobile.post { updateGridTopPadding() }
    }

    private fun showMultiGenrePicker() {
        val options = com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.GenreFilter.entries
            .filterNot { it == com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.GenreFilter.ALL }
        val selected = selectedGenres.ifEmpty {
            com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.GenreFilter.entries
                .getOrNull(binding.spGenre.selectedItemPosition)
                ?.takeIf { it != com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.GenreFilter.ALL }
                ?.let { setOf(it) } ?: emptySet()
        }.toMutableSet()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Generi multipli")
            .setMultiChoiceItems(options.map { it.label }.toTypedArray(),
                options.map { it in selected }.toBooleanArray()) { _, index, checked ->
                if (checked) selected.add(options[index]) else selected.remove(options[index])
            }
            .setPositiveButton("Applica") { _, _ ->
                selectedGenres.clear()
                selectedGenres.addAll(selected)
                if (selectedGenres.isNotEmpty()) binding.spGenre.setSelection(0)
                Toast.makeText(requireContext(), "Generi selezionati: ${selectedGenres.size}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun submitSearch(): Boolean {
        val query = binding.etSearch.text?.toString().orEmpty().trim()
        hideKeyboard()

        val baseFilters = try {
            AdvancedSearchUi.read(
                binding.spContentType,
                binding.spItalian,
                binding.spYear,
                binding.etDateFrom,
                binding.etDateTo,
                binding.spGenre,
                binding.spSort,
                binding.spPlatform,
                binding.spCountry,
            )
        } catch (e: IllegalArgumentException) {
            Toast.makeText(
                requireContext(),
                e.message ?: "Filtro non valido",
                Toast.LENGTH_SHORT,
            ).show()
            return true
        }

        val actorText = binding.etActorSearch.text?.toString().orEmpty().trim()
        if (actorText.isNotBlank() && viewModel.selectedActorId == null) {
            if (actorText.length >= 2) viewModel.searchActors(actorText)
            Toast.makeText(
                requireContext(),
                "Seleziona l'attore dai suggerimenti",
                Toast.LENGTH_SHORT,
            ).show()
            return true
        }

        val directorText = binding.etDirectorSearch.text?.toString().orEmpty().trim()
        if (directorText.isNotBlank() && viewModel.selectedDirectorId == null) {
            if (directorText.length >= 2) viewModel.searchDirectors(directorText)
            Toast.makeText(requireContext(), "Seleziona il regista dai suggerimenti", Toast.LENGTH_SHORT).show()
            return true
        }
        val filters = baseFilters.copy(
            actorId = viewModel.selectedActorId,
            actorName = viewModel.selectedActorName,
            directorId = viewModel.selectedDirectorId,
            directorName = viewModel.selectedDirectorName,
            // Single-genre selection must not silently inherit a previous
            // multi-genre selection kept in memory.
            genres = if (baseFilters.genre ==
                com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.GenreFilter.ALL)
                selectedGenres.toSet() else emptySet(),
        )
        savePersistedFilters()

        if (binding.swGlobalSearch.isChecked) {
            if (query.isBlank() && !filters.isActive) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.search_empty_query),
                    Toast.LENGTH_SHORT,
                ).show()
                return true
            }
            val currentLanguage = UserPreferences.currentProvider?.language ?: "it"
            viewModel.searchGlobal(query, currentLanguage, filters)
        } else {
            if (filters.isActive) viewModel.searchAdvanced(query, filters) else viewModel.search(query)
        }
        return true
    }

    private fun retryCurrentSearch() {
        if (viewModel.advancedFilters.isActive) {
            viewModel.searchAdvanced(viewModel.query, viewModel.advancedFilters)
        } else {
            viewModel.search(viewModel.query)
        }
    }

    private fun initializeSearch() {
        val isIptv = UserPreferences.currentProvider is IptvProvider
        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        binding.etSearch.hint = getString(hintStringRes)

        binding.etSearch.apply {
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener submitSearch()
                false
            }

            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (s.isNullOrBlank()) {
                        val currentIsIptv = UserPreferences.currentProvider is IptvProvider
                        val res = if (currentIsIptv) {
                            R.string.search_input_hint_iptv
                        } else {
                            R.string.search_input_hint
                        }
                        binding.etSearch.hint = getString(res)
                    }
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            })
        }

        val blink = AlphaAnimation(1f, 0.3f).apply {
            duration = 500
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }

        voiceHelper = VoiceRecognitionHelper(
            fragment = this,
            onResult = { query ->
                binding.btnSearchVoice.clearAnimation()
                binding.etSearch.setText(query)
                submitSearch()
            },
            onError = { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                binding.btnSearchVoice.clearAnimation()
                binding.etSearch.hint = getString(R.string.search_input_hint)
            },
            onListeningStateChanged = {
                binding.btnSearchVoice.startAnimation(blink)
                binding.etSearch.hint = getString(R.string.voice_prompt)
            },
        )

        binding.btnSearchVoice.apply {
            visibility = if (voiceHelper.isAvailable()) View.VISIBLE else View.GONE
            setOnClickListener {
                if (!voiceHelper.isListening) voiceHelper.startWithPermissionCheck()
            }
        }

        binding.btnSearchClear.setOnClickListener {
            binding.etSearch.setText("")
            binding.etSearch.hint = getString(R.string.search_input_hint)
            submitSearch()
        }

        binding.rvSearch.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(SpacingItemDecoration(10.dp(requireContext())))
        }
    }

    private fun displayActorSuggestions(list: List<People>) {
        if (_binding == null) return
        val items = list.onEach { it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM }
        actorSuggestionsAdapter.submitList(items)
        binding.rvActorSuggestions.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        binding.searchHeaderMobile.post { updateGridTopPadding() }
    }

    private fun displayDirectorSuggestions(list: List<People>) {
        if (_binding == null) return
        val items = list.onEach { it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM }
        directorSuggestionsAdapter.submitList(items)
        binding.rvDirectorSuggestions.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        binding.searchHeaderMobile.post { updateGridTopPadding() }
    }

    private fun displaySearch(list: List<AppAdapter.Item>, hasMore: Boolean) {
        appAdapter.submitList(list.onEach {
            when (it) {
                is Genre -> it.itemType = AppAdapter.Type.GENRE_GRID_MOBILE_ITEM
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
                is People -> it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
            }
        })

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMore() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    private fun displayGlobalSearch(posters: List<AppAdapter.Item>, hasMore: Boolean) {
        // LAB152: a single native mobile poster wall, without provider names or duplicate cards.
        appAdapter.submitList(posters.onEach { item ->
            when (item) {
                is Movie -> item.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                is TvShow -> item.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
            }
        })
        if (hasMore) appAdapter.setOnLoadMoreListener { viewModel.loadMoreGlobal() }
        else appAdapter.setOnLoadMoreListener(null)
    }

}

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
    private var searchHeaderOffset: Int = 0

    private val actorSuggestionsAdapter by lazy {
        AppAdapter().apply {
            onPeopleClickListener = { people ->
                suppressActorTextChange = true
                binding.etActorSearch.setText(people.name)
                binding.etActorSearch.setSelection(binding.etActorSearch.text?.length ?: 0)
                suppressActorTextChange = false

                viewModel.selectActorFilter(people.id, people.name)
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
        initializeActorSearch()
        initializeDirectorSearch()
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
                        displayGlobalSearch(state.providerResults)
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
        )

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
            )
            clearActorField()
            clearDirectorField()
            viewModel.search(binding.etSearch.text?.toString().orEmpty())
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

    private fun initializeScrollableHeader() {
        binding.searchHeaderMobile.post { updateGridTopPadding() }

        binding.rvSearch.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (_binding == null) return
                syncHeaderToGrid(recyclerView)
            }
        })
    }

    private fun updateGridTopPadding() {
        if (_binding == null) return
        val extra = 8.dp(requireContext())
        val topPadding = binding.searchHeaderMobile.height + extra
        if (binding.rvSearch.paddingTop != topPadding) {
            binding.rvSearch.setPadding(
                binding.rvSearch.paddingLeft,
                topPadding,
                binding.rvSearch.paddingRight,
                binding.rvSearch.paddingBottom,
            )
        }
        syncHeaderToGrid(binding.rvSearch)
    }

    private fun syncHeaderToGrid(recyclerView: RecyclerView) {
        if (_binding == null) return
        val maxOffset = binding.searchHeaderMobile.height
        if (maxOffset <= 0) return

        val offset = recyclerView.computeVerticalScrollOffset().coerceIn(0, maxOffset)
        searchHeaderOffset = offset
        binding.searchHeaderMobile.translationY = -offset.toFloat()
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
        )

        if (filters.isActive) {
            binding.swGlobalSearch.isChecked = false
            if (UserPreferences.currentProvider !is TmdbProvider) {
                val language = UserPreferences.currentProvider?.language
                    ?.takeIf { it.isNotBlank() }
                    ?: "it"
                UserPreferences.currentProvider = TmdbProvider(language)
            }
            viewModel.searchAdvanced(query, filters)
            return true
        }

        if (binding.swGlobalSearch.isChecked) {
            if (query.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.search_empty_query),
                    Toast.LENGTH_SHORT,
                ).show()
                return true
            }
            val currentLanguage = UserPreferences.currentProvider?.language ?: "it"
            viewModel.searchGlobal(query, currentLanguage)
        } else {
            viewModel.search(query)
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

        if (hasMore && (viewModel.query.isNotEmpty() || viewModel.advancedFilters.isActive)) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMore() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    private fun displayGlobalSearch(providerResults: List<ProviderResult>) {
        val allItems = mutableListOf<AppAdapter.Item>()

        providerResults.forEach { providerResult ->
            val headerTitle = when (val state = providerResult.state) {
                is ProviderResult.State.Loading ->
                    "${providerResult.provider.name} - ${getString(R.string.searching)}"
                is ProviderResult.State.Error ->
                    "${providerResult.provider.name} - ${getString(R.string.search_error)}"
                is ProviderResult.State.Success -> {
                    val count = state.results.size
                    val resultText = if (count == 1) {
                        getString(R.string.result)
                    } else {
                        getString(R.string.results)
                    }
                    "${providerResult.provider.name} - $count $resultText"
                }
            }

            allItems.add(
                Category(name = headerTitle, list = emptyList()).apply {
                    itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
                }
            )

            if (providerResult.state is ProviderResult.State.Success) {
                allItems.addAll(providerResult.state.results.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
                    }
                })
            }
        }

        appAdapter.submitList(allItems)
        appAdapter.setOnLoadMoreListener(null)
    }
}

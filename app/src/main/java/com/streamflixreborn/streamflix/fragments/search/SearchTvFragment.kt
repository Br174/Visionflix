package com.streamflixreborn.streamflix.fragments.search

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.SearchEditText
import androidx.leanback.widget.VerticalGridView
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.ProviderSelection
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.VoiceRecognitionHelper
import com.streamflixreborn.streamflix.utils.hideKeyboard
import java.util.Calendar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchTvFragment : Fragment() {

    private lateinit var rootView: View
    private lateinit var vgvSearch: VerticalGridView
    private lateinit var pbSearch: ProgressBar
    private lateinit var searchHeader: LinearLayout
    private lateinit var etSearch: SearchEditText
    private lateinit var btnSearchClear: ImageView
    private lateinit var btnSearchVoice: ImageView
    private lateinit var llGlobalSearch: LinearLayout
    private lateinit var ivGlobalSearchSwitch: ImageView
    private lateinit var filterContainer: LinearLayout

    private var searchViewModel: SearchViewModel? = null
    private var voiceHelper: VoiceRecognitionHelper? = null
    private var actorSearchJob: Job? = null
    private var directorSearchJob: Job? = null
    private var isGlobalSearchChecked = false
    private var currentGridColumns = 1
    private var suppressActorTextChange = false
    private var suppressDirectorTextChange = false
    private var filterPanelExpanded = true
    private var pendingFocusFirstResult = false

    private var typeSelector: DropdownSelector<AdvancedSearchFilters.ContentType>? = null
    private var italianSelector: DropdownSelector<AdvancedSearchFilters.ItalianFilter>? = null
    private var yearSelector: DropdownSelector<Int?>? = null
    private var genreSelector: DropdownSelector<AdvancedSearchFilters.GenreFilter>? = null
    private val selectedGenres = linkedSetOf<AdvancedSearchFilters.GenreFilter>()
    private var fromSelector: DropdownSelector<Int?>? = null
    private var toSelector: DropdownSelector<Int?>? = null
    private var sortSelector: DropdownSelector<AdvancedSearchFilters.SortFilter>? = null
    private var platformSelector: DropdownSelector<AdvancedSearchFilters.PlatformFilter>? = null
    private var countrySelector: DropdownSelector<AdvancedSearchFilters.CountryFilter>? = null
    private var actorEdit: EditText? = null
    private var directorEdit: EditText? = null
    private var actorSuggestionsGrid: HorizontalGridView? = null
    private var directorSuggestionsGrid: HorizontalGridView? = null
    private var actorFallbackSuggestions: LinearLayout? = null
    private var directorFallbackSuggestions: LinearLayout? = null
    private var filterPanelBody: LinearLayout? = null
    private var btnApplyFilters: TextView? = null
    private var btnResetFilters: TextView? = null
    private var filterFocusSentinel: View? = null

    private val appAdapter by lazy {
        AppAdapter().apply {
            onMovieClickListener = { movie ->
                switchProviderIfNeeded(movie.providerName, movie.providerEndpoint)
                findNavController().navigate(
                    SearchTvFragmentDirections.actionSearchToMovie(id = movie.id)
                )
            }
            onTvShowClickListener = { tvShow ->
                switchProviderIfNeeded(tvShow.providerName, tvShow.providerEndpoint)
                findNavController().navigate(
                    SearchTvFragmentDirections.actionSearchToTvShow(
                        id = tvShow.id,
                        poster = tvShow.poster,
                        banner = tvShow.banner,
                    )
                )
            }
        }
    }

    private val actorSuggestionsAdapter by lazy {
        AppAdapter().apply {
            onPeopleClickListener = { people -> selectActor(people) }
        }
    }

    private val directorSuggestionsAdapter by lazy {
        AppAdapter().apply {
            onPeopleClickListener = { people -> selectDirector(people) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // Mantiene la scocca crash-safe introdotta dopo il crash della ricerca TV.
        // Non reinserire qui il vecchio layout complesso con filtri/Spinner statici:
        // i controlli avanzati devono continuare a nascere solo dopo il primo frame.
        return inflater.inflate(R.layout.fragment_search_tv_safe, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootView = view

        try {
            bindBaseViews(view)
            initializeBaseSearch()
        } catch (error: Throwable) {
            Log.e(TAG, "Base TV search shell failed", error)
            showSafeToast("Cerca: apertura base non riuscita")
            return
        }

        // Mostra prima la pagina stabile, poi inizializza i sottosistemi separatamente.
        // Database/ViewModel, filtri e voce NON devono tornare nel percorso di inflation.
        view.post {
            if (!isAdded || viewLifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                return@post
            }
            buildAdvancedFiltersSafely()
            createViewModelSafely()
            restorePersistedFiltersSafely()
            initializeScrollableHeader()
            updateGridTopPadding()
            etSearch.requestFocus()
        }
    }

    override fun onPause() {
        savePersistedFiltersSafely()
        super.onPause()
    }

    override fun onDestroyView() {
        actorSearchJob?.cancel()
        directorSearchJob?.cancel()
        voiceHelper?.stopRecognition()
        voiceHelper = null
        actorSuggestionsGrid = null
        directorSuggestionsGrid = null
        actorFallbackSuggestions = null
        directorFallbackSuggestions = null
        actorEdit = null
        directorEdit = null
        typeSelector = null
        italianSelector = null
        yearSelector = null
        genreSelector = null
        fromSelector = null
        toSelector = null
        sortSelector = null
        platformSelector = null
        countrySelector = null
        filterPanelBody = null
        btnApplyFilters = null
        btnResetFilters = null
        filterFocusSentinel = null
        super.onDestroyView()
    }

    private fun bindBaseViews(view: View) {
        vgvSearch = view.findViewById(R.id.vgv_search_safe)
        pbSearch = view.findViewById(R.id.pb_search_safe)
        searchHeader = view.findViewById(R.id.search_header_safe)
        etSearch = view.findViewById(R.id.et_search_safe)
        btnSearchClear = view.findViewById(R.id.btn_search_clear_safe)
        btnSearchVoice = view.findViewById(R.id.btn_search_voice_safe)
        llGlobalSearch = view.findViewById(R.id.ll_global_search_safe)
        ivGlobalSearchSwitch = view.findViewById(R.id.iv_global_search_switch_safe)
        filterContainer = view.findViewById(R.id.filter_container_safe)
    }

    private fun initializeBaseSearch() {
        val isIptv = UserPreferences.currentProvider is IptvProvider
        etSearch.hint = getString(
            if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        )

        llGlobalSearch.setOnClickListener {
            isGlobalSearchChecked = !isGlobalSearchChecked
            updateGlobalSwitch()
        }

        etSearch.setOnEditorActionListener { _, actionId, event ->
            val submitAction = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_NULL
            val submitKey = event?.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            if (submitAction || submitKey) submitSearch() else false
        }

        etSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_SEARCH -> submitSearch()
                KeyEvent.KEYCODE_BACK -> focusSearchContent()
                else -> false
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrBlank()) {
                    val hint = if (UserPreferences.currentProvider is IptvProvider) {
                        R.string.search_input_hint_iptv
                    } else {
                        R.string.search_input_hint
                    }
                    etSearch.hint = getString(hint)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        btnSearchClear.setOnClickListener {
            etSearch.setText("")
            searchViewModel?.clearActorSelection()
            submitSearch()
        }

        // Il sottosistema vocale non viene costruito entrando nella pagina.
        btnSearchVoice.setOnClickListener { startVoiceSearch() }

        vgvSearch.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.search_spacing).toInt())
            addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(
                    parent: RecyclerView,
                    child: RecyclerView.ViewHolder?,
                    position: Int,
                    subposition: Int,
                ) {
                    if (position in 0 until currentGridColumns.coerceAtLeast(1)) {
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
                }
            })
        }
    }

    private fun createViewModelSafely() {
        if (searchViewModel != null) return
        try {
            val database = AppDatabase.getInstance(requireContext())
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (!modelClass.isAssignableFrom(SearchViewModel::class.java)) {
                        throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
                    }
                    return SearchViewModel(database) as T
                }
            }
            searchViewModel = ViewModelProvider(this, factory)[SearchViewModel::class.java]
            collectSearchStateSafely()
            collectActorSuggestionsSafely()
            collectDirectorSuggestionsSafely()
        } catch (error: Throwable) {
            Log.e(TAG, "Search ViewModel creation failed", error)
            showSafeToast("Cerca aperta, ma il motore di ricerca non è disponibile")
        }
    }

    private fun collectSearchStateSafely() {
        val vm = searchViewModel ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                vm.state
                    .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                    .collect { state ->
                        try {
                            when (state) {
                                is State.Searching, is State.GlobalSearching -> {
                                    pbSearch.visibility = View.VISIBLE
                                    appAdapter.isLoading = false
                                    appAdapter.setOnLoadMoreListener(null)
                                }
                                is State.SearchingMore -> appAdapter.isLoading = true
                                is State.SuccessSearching -> {
                                    pbSearch.visibility = View.GONE
                                    appAdapter.isLoading = false
                                    displaySearch(state.results, state.hasMore)
                                }
                                is State.SuccessGlobalSearching -> {
                                    pbSearch.visibility = View.GONE
                                    appAdapter.isLoading = false
                                    displayGlobalSearch(state.providerResults)
                                }
                                is State.FailedSearching -> {
                                    pbSearch.visibility = View.GONE
                                    appAdapter.isLoading = false
                                    showSafeToast(
                                        state.error.message?.takeIf { it.isNotBlank() }
                                            ?: "Ricerca non riuscita"
                                    )
                                }
                            }
                        } catch (uiError: Throwable) {
                            Log.e(TAG, "Rendering search state failed", uiError)
                            pbSearch.visibility = View.GONE
                            showSafeToast("Cerca resta aperta: errore nella visualizzazione dei risultati")
                        }
                    }
            } catch (collectorError: Throwable) {
                Log.e(TAG, "Search state collector failed", collectorError)
                pbSearch.visibility = View.GONE
                showSafeToast("Cerca resta aperta: errore del motore risultati")
            }
        }
    }

    private fun collectActorSuggestionsSafely() {
        val vm = searchViewModel ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                vm.actorSuggestions
                    .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                    .collect { displayActorSuggestions(it) }
            } catch (error: Throwable) {
                Log.e(TAG, "Actor suggestions collector failed", error)
            }
        }
    }

    private fun collectDirectorSuggestionsSafely() {
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

    private fun buildAdvancedFiltersSafely() {
        if (filterContainer.childCount > 0) return
        try {
            val panel = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(6))
                setBackgroundResource(R.drawable.bg_filter_panel_tv)
            }
            filterPanelBody = panel
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

            panel.addView(makeLabel("Filtri avanzati", 18f, true).apply { setPadding(0, dp(6), 0, 0) })
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
                "Lingua originale",
                listOf(
                    AdvancedSearchFilters.ItalianFilter.ALL,
                    AdvancedSearchFilters.ItalianFilter.YES,
                    AdvancedSearchFilters.ItalianFilter.NO,
                ),
            ) {
                when (it) {
                    AdvancedSearchFilters.ItalianFilter.ALL -> "Tutte"
                    AdvancedSearchFilters.ItalianFilter.YES -> "Italiana"
                    AdvancedSearchFilters.ItalianFilter.NO -> "Non italiana"
                }
            }
            val currentYear = Calendar.getInstance().get(Calendar.YEAR).coerceAtLeast(2026)
            val yearValues: List<Int?> = listOf<Int?>(null) + (currentYear downTo 1900).map { it }
            yearSelector = makeSelector("Anno", yearValues) { it?.toString() ?: "Tutti" }
            genreSelector = makeSelector("Genere", AdvancedSearchFilters.GenreFilter.entries.toList()) { it.label }
            genreSelector?.setLongPressAction { showMultiGenrePicker() }

            val firstRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
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
                setPadding(0, dp(5), 0, 0)
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
            actorRow.addView(actorEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
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
                setPadding(0, dp(5), 0, 0)
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
            directorRow.addView(directorEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
            setupDirectorTextSearch()
            addDirectorSuggestionsSurface(panel)

            countrySelector = makeSelector("Paese di produzione", AdvancedSearchFilters.CountryFilter.entries.toList()) { it.label }
            val countryRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, 0)
            }
            panel.addView(countryRow)
            addSelectorToRow(countryRow, countrySelector)

            val actions = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                setPadding(0, dp(6), 0, 0)
            }
            panel.addView(actions)
            btnResetFilters = makeActionButton("Azzera", primary = false).also { button ->
                actions.addView(
                    button,
                    LinearLayout.LayoutParams(dp(150), dp(44)).apply { marginEnd = dp(10) },
                )
                button.setOnClickListener { resetFilters() }
                button.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) revealSearchHeader() }
            }
            btnApplyFilters = makeActionButton("Applica filtri", primary = true).also { button ->
                actions.addView(button, LinearLayout.LayoutParams(dp(190), dp(44)))
                button.setOnClickListener { submitSearch() }
                button.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) revealSearchHeader() }
            }

            // Nessuna barra visibile quando i filtri sono chiusi. Questo piccolo
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
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)),
            )
            showSafeToast("Cerca è aperta; i filtri avanzati non sono stati caricati")
        }
    }

    private fun configureFilterFocus() {
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
        genre.setNextFocus(left = year.viewId, up = llGlobalSearch.id, down = actor.id)

        from.setNextFocus(right = to.viewId, up = type.viewId, down = sort.viewId)
        to.setNextFocus(left = from.viewId, right = actor.id, up = italian.viewId, down = platform.viewId)
        actor.nextFocusLeftId = to.viewId
        actor.nextFocusUpId = year.viewId
        actor.nextFocusDownId = director.id

        sort.setNextFocus(right = platform.viewId, up = from.viewId, down = reset.id)
        platform.setNextFocus(left = sort.viewId, right = director.id, up = to.viewId, down = apply.id)
        director.nextFocusLeftId = platform.viewId
        director.nextFocusUpId = actor.id
        director.nextFocusDownId = apply.id

        reset.nextFocusUpId = sort.viewId
        reset.nextFocusRightId = apply.id
        reset.nextFocusDownId = vgvSearch.id
        apply.nextFocusUpId = director.id
        apply.nextFocusLeftId = reset.id
        apply.nextFocusDownId = vgvSearch.id
        vgvSearch.nextFocusUpId = filterFocusSentinel?.id ?: apply.id
    }

    private fun setupActorTextSearch() {
        val edit = actorEdit ?: return
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (suppressActorTextChange) return
                actorSearchJob?.cancel()
                searchViewModel?.clearActorSelection()
                val value = s?.toString().orEmpty().trim()
                if (value.length < 2) {
                    searchViewModel?.clearActorSuggestions()
                    displayActorSuggestions(emptyList())
                    return
                }
                actorSearchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    searchViewModel?.searchActors(value)
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
                triggerActorSearch()
                hideKeyboard()
                true
            } else false
        }
    }

    private fun addActorSuggestionsSurface(panel: LinearLayout) {
        // HorizontalGridView è opzionale: se quel componente non è disponibile sul firmware,
        // viene usato automaticamente un elenco testuale semplice senza chiudere Cerca.
        try {
            actorSuggestionsGrid = HorizontalGridView(requireContext()).apply {
                id = View.generateViewId()
                visibility = View.GONE
                adapter = actorSuggestionsAdapter
                setItemSpacing(dp(12))
            }
            panel.addView(
                actorSuggestionsGrid,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118)).apply {
                    topMargin = dp(8)
                },
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Horizontal actor grid unavailable, using fallback", error)
            actorSuggestionsGrid = null
        }

        actorFallbackSuggestions = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        panel.addView(
            actorFallbackSuggestions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
    }

    private fun setupDirectorTextSearch() {
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
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118)).apply { topMargin = dp(8) },
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

    private fun triggerActorSearch() {
        val actorName = actorEdit?.text?.toString().orEmpty().trim()
        if (actorName.length < 2) {
            showSafeToast("Scrivi almeno 2 lettere del nome dell'attore")
            return
        }
        val vm = searchViewModel
        if (vm == null) {
            showSafeToast("Motore di ricerca ancora in inizializzazione")
            return
        }
        vm.searchActors(actorName)
    }

    private fun displayActorSuggestions(list: List<People>) {
        if (!isAdded || !this::searchHeader.isInitialized) return
        try {
            val items = list.onEach { it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM }
            actorSuggestionsGrid?.let { grid ->
                actorSuggestionsAdapter.submitList(items)
                grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                if (items.isNotEmpty()) {
                    actorEdit?.nextFocusDownId = grid.id
                    grid.nextFocusUpId = actorEdit?.id ?: View.NO_ID
                    grid.nextFocusDownId = sortSelector?.viewId ?: View.NO_ID
                } else {
                    actorEdit?.nextFocusDownId = directorEdit?.id ?: View.NO_ID
                }
            }

            actorFallbackSuggestions?.let { fallback ->
                fallback.removeAllViews()
                if (actorSuggestionsGrid == null && items.isNotEmpty()) {
                    fallback.visibility = View.VISIBLE
                    items.take(5).forEach { person ->
                        val chip = makeActionButton(person.name, primary = false)
                        chip.setOnClickListener { selectActor(person) }
                        fallback.addView(
                            chip,
                            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                                marginEnd = dp(6)
                            },
                        )
                    }
                } else {
                    fallback.visibility = View.GONE
                }
            }
            searchHeader.post { updateGridTopPadding() }
        } catch (error: Throwable) {
            Log.e(TAG, "Displaying actor suggestions failed", error)
        }
    }

    private fun selectActor(people: People) {
        suppressActorTextChange = true
        actorEdit?.setText(people.name)
        actorEdit?.setSelection(actorEdit?.text?.length ?: 0)
        suppressActorTextChange = false
        searchViewModel?.selectActorFilter(people.id, people.name)
        savePersistedFiltersSafely()
        actorSuggestionsGrid?.visibility = View.GONE
        actorFallbackSuggestions?.visibility = View.GONE
        sortSelector?.requestFocus()
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
                    grid.nextFocusUpId = directorEdit?.id ?: View.NO_ID
                    grid.nextFocusDownId = btnApplyFilters?.id ?: View.NO_ID
                } else {
                    directorEdit?.nextFocusDownId = btnApplyFilters?.id ?: View.NO_ID
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
        savePersistedFiltersSafely()
        directorSuggestionsGrid?.visibility = View.GONE
        directorFallbackSuggestions?.visibility = View.GONE
        btnApplyFilters?.requestFocus()
        hideKeyboard()
        searchHeader.post { updateGridTopPadding() }
    }

    private fun resetFilters() {
        typeSelector?.reset()
        italianSelector?.reset()
        yearSelector?.reset()
        genreSelector?.reset()
        selectedGenres.clear()
        fromSelector?.reset()
        toSelector?.reset()
        sortSelector?.reset()
        platformSelector?.reset()
        countrySelector?.reset()
        suppressActorTextChange = true
        actorEdit?.setText("")
        suppressActorTextChange = false
        suppressDirectorTextChange = true
        directorEdit?.setText("")
        suppressDirectorTextChange = false
        searchViewModel?.clearActorSelection()
        searchViewModel?.clearActorSuggestions()
        searchViewModel?.clearDirectorSelection()
        searchViewModel?.clearDirectorSuggestions()
        displayActorSuggestions(emptyList())
        displayDirectorSuggestions(emptyList())
        etSearch.setText("")
        isGlobalSearchChecked = false
        updateGlobalSwitch()
        SearchFilterPersistence.clear(requireContext())
        searchViewModel?.search("")
    }

    private fun showMultiGenrePicker() {
        if (!isAdded) return
        val options = AdvancedSearchFilters.GenreFilter.entries.filterNot { it == AdvancedSearchFilters.GenreFilter.ALL }
        val initial = selectedGenres.ifEmpty {
            genreSelector?.value?.takeIf { it != AdvancedSearchFilters.GenreFilter.ALL }
                ?.let { setOf(it) } ?: emptySet()
        }
        val picks = initial.toMutableSet()
        val labels = options.map { it.label }.toTypedArray()
        val checked = options.map { it in initial }.toBooleanArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Generi multipli")
            .setMultiChoiceItems(labels, checked) { _, index, selected ->
                if (selected) picks.add(options[index]) else picks.remove(options[index])
            }
            .setPositiveButton("Applica") { _, _ ->
                selectedGenres.clear()
                selectedGenres.addAll(picks)
                if (selectedGenres.isNotEmpty()) genreSelector?.selectValue(AdvancedSearchFilters.GenreFilter.ALL)
                showSafeToast("Generi selezionati: ${selectedGenres.size}")
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun currentFilters(): AdvancedSearchFilters? {
        val from = fromSelector?.value
        val to = toSelector?.value
        if (from != null && to != null && from > to) {
            showSafeToast("L'anno Dal non può essere successivo all'anno Al")
            return null
        }

        val vm = searchViewModel
        val actorText = actorEdit?.text?.toString().orEmpty().trim()
        if (actorText.isNotBlank() && vm?.selectedActorId == null) {
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

        return AdvancedSearchFilters(
            type = typeSelector?.value ?: AdvancedSearchFilters.ContentType.ALL,
            italian = italianSelector?.value ?: AdvancedSearchFilters.ItalianFilter.ALL,
            genre = genreSelector?.value ?: AdvancedSearchFilters.GenreFilter.ALL,
            genres = selectedGenres.toSet(),
            year = yearSelector?.value,
            dateFrom = from?.let { "%04d-01-01".format(it) },
            dateTo = to?.let { "%04d-12-31".format(it) },
            actorId = vm?.selectedActorId,
            actorName = vm?.selectedActorName.orEmpty(),
            directorId = vm?.selectedDirectorId,
            directorName = vm?.selectedDirectorName.orEmpty(),
            sort = sortSelector?.value ?: AdvancedSearchFilters.SortFilter.POPULARITY,
            platform = platformSelector?.value ?: AdvancedSearchFilters.PlatformFilter.ALL,
            country = countrySelector?.value ?: AdvancedSearchFilters.CountryFilter.ALL,
        )
    }

    private fun submitSearch(): Boolean {
        val vm = searchViewModel
        if (vm == null) {
            showSafeToast("Motore di ricerca ancora in inizializzazione")
            return true
        }

        val query = etSearch.text?.toString().orEmpty().trim()
        hideKeyboard()
        val filters = currentFilters() ?: return true
        savePersistedFiltersSafely()

        if (isGlobalSearchChecked) {
            if (query.isBlank() && !filters.isActive) {
                showSafeToast(getString(R.string.search_empty_query))
                return true
            }
            vm.searchGlobal(query, UserPreferences.currentProvider?.language ?: "it", filters)
            collapseFiltersAndFocusResults()
        } else {
            stabilizeStandardResultGrid(query, advanced = filters.isActive)
            if (filters.isActive) vm.searchAdvanced(query, filters) else vm.search(query)
            collapseFiltersAndFocusResults()
        }
        return true
    }

    private fun stabilizeStandardResultGrid(query: String, advanced: Boolean) {
        currentGridColumns = if (query.isBlank() && !advanced) 5 else 6
        vgvSearch.setNumColumns(currentGridColumns)
    }

    private fun updateGlobalSwitch() {
        ivGlobalSearchSwitch.setImageResource(
            if (isGlobalSearchChecked) R.drawable.ic_switch_on else R.drawable.ic_switch_off
        )
    }

    private fun savePersistedFiltersSafely() {
        if (!isAdded || !this::etSearch.isInitialized) return
        try {
            SearchFilterPersistence.save(
                requireContext(),
                SavedSearchFilterState(
                    query = etSearch.text?.toString().orEmpty(),
                    globalSearch = false,
                    type = typeSelector?.value ?: AdvancedSearchFilters.ContentType.ALL,
                    italian = italianSelector?.value ?: AdvancedSearchFilters.ItalianFilter.ALL,
                    year = yearSelector?.value,
                    fromYear = fromSelector?.value,
                    toYear = toSelector?.value,
                    genre = genreSelector?.value ?: AdvancedSearchFilters.GenreFilter.ALL,
                    sort = sortSelector?.value ?: AdvancedSearchFilters.SortFilter.POPULARITY,
                    platform = platformSelector?.value ?: AdvancedSearchFilters.PlatformFilter.ALL,
                    country = countrySelector?.value ?: AdvancedSearchFilters.CountryFilter.ALL,
                    genres = selectedGenres.toSet(),
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
            isGlobalSearchChecked = false
            updateGlobalSwitch()
            typeSelector?.selectValue(saved.type)
            italianSelector?.selectValue(saved.italian)
            yearSelector?.selectValue(saved.year)
            fromSelector?.selectValue(saved.fromYear)
            toSelector?.selectValue(saved.toYear)
            genreSelector?.selectValue(saved.genre)
            sortSelector?.selectValue(saved.sort)
            platformSelector?.selectValue(saved.platform)
            countrySelector?.selectValue(saved.country)
            selectedGenres.clear()
            selectedGenres.addAll(saved.genres)

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

    private fun initializeScrollableHeader() {
        vgvSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && filterPanelExpanded) setFilterPanelExpanded(false, true)
        }
        vgvSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                !filterPanelExpanded && vgvSearch.selectedPosition in 0 until currentGridColumns.coerceAtLeast(1)) {
                openFiltersFromResults(); true
            } else false
        }
    }

    private fun updateGridTopPadding() {
        if (!isAdded || !this::searchHeader.isInitialized) return
        searchHeader.post {
            val top = searchHeader.height + dp(10)
            if (top > 0 && vgvSearch.paddingTop != top) vgvSearch.setPadding(
                vgvSearch.paddingLeft, top, vgvSearch.paddingRight, vgvSearch.paddingBottom)
        }
    }

    private fun setFilterPanelExpanded(expanded: Boolean, animate: Boolean) {
        if (!this::searchHeader.isInitialized) return
        filterPanelExpanded = expanded
        searchHeader.animate().cancel(); searchHeader.translationY = 0f
        filterPanelBody?.visibility = if (expanded) View.VISIBLE else View.GONE
        filterFocusSentinel?.visibility = if (expanded) View.GONE else View.VISIBLE
        searchHeader.requestLayout()
        searchHeader.post { updateGridTopPadding(); if (!expanded && pendingFocusFirstResult) focusFirstResultWhenReady() }
    }

    private fun openFiltersFromResults() {
        setFilterPanelExpanded(true, false)
        searchHeader.post { btnApplyFilters?.requestFocus() ?: etSearch.requestFocus() }
    }

    private fun collapseFiltersAndFocusResults() {
        pendingFocusFirstResult = true
        setFilterPanelExpanded(false, true)
        focusFirstResultWhenReady()
    }

    private fun focusFirstResultWhenReady() {
        if (appAdapter.items.isEmpty()) return
        vgvSearch.scrollToPosition(0)
        vgvSearch.setSelectedPosition(0)
        vgvSearch.post {
            if (vgvSearch.requestFocus()) pendingFocusFirstResult = false
        }
    }

    private fun revealSearchHeader() {
        setFilterPanelExpanded(true, true)
    }

    private fun focusSearchContent(): Boolean {
        actorSuggestionsGrid?.let {
            if (it.visibility == View.VISIBLE && actorSuggestionsAdapter.itemCount > 0) {
                return it.requestFocus()
            }
        }
        if (appAdapter.itemCount > 0) return vgvSearch.requestFocus()
        actorEdit?.let { if (it.visibility == View.VISIBLE) return it.requestFocus() }
        btnApplyFilters?.let { return it.requestFocus() }
        return llGlobalSearch.requestFocus()
    }

    private fun displaySearch(list: List<AppAdapter.Item>, hasMore: Boolean) {
        val vm = searchViewModel ?: return
        currentGridColumns = if (vm.query.isEmpty() && !vm.advancedFilters.isActive) 5 else 6
        vgvSearch.setNumColumns(currentGridColumns)
        appAdapter.submitList(list.onEach { item ->
            when (item) {
                is Genre -> item.itemType = AppAdapter.Type.GENRE_GRID_TV_ITEM
                is Movie -> item.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                is TvShow -> item.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
                is People -> item.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
            }
        })
        if (hasMore && (vm.query.isNotEmpty() || vm.advancedFilters.isActive)) {
            appAdapter.setOnLoadMoreListener { vm.loadMore() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
        searchHeader.post {
            updateGridTopPadding()
            if (pendingFocusFirstResult) focusFirstResultWhenReady()
        }
    }

    private fun displayGlobalSearch(providerResults: List<ProviderResult>) {
        val categories = providerResults.map { providerResult ->
            val title = when (val providerState = providerResult.state) {
                is ProviderResult.State.Loading ->
                    "${providerResult.displayName} - ${getString(R.string.searching)}"
                is ProviderResult.State.Error ->
                    "${providerResult.displayName} - ${getString(R.string.search_error)}"
                is ProviderResult.State.Success -> {
                    val count = providerState.results.size
                    val word = if (count == 1) getString(R.string.result) else getString(R.string.results)
                    "${providerResult.displayName} - $count $word"
                }
            }
            val items = (providerResult.state as? ProviderResult.State.Success)?.results?.onEach {
                when (it) {
                    is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                    is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                }
            } ?: emptyList()
            Category(name = title, list = items).apply {
                itemType = AppAdapter.Type.CATEGORY_TV_ITEM
            }
        }
        currentGridColumns = 1
        vgvSearch.setNumColumns(1)
        appAdapter.submitList(categories)
        appAdapter.setOnLoadMoreListener(null)
        searchHeader.post {
            updateGridTopPadding()
            if (pendingFocusFirstResult) focusFirstResultWhenReady()
        }
    }

    private fun startVoiceSearch() {
        try {
            val helper = voiceHelper ?: VoiceRecognitionHelper(
                fragment = this,
                onResult = { query ->
                    if (!isAdded) return@VoiceRecognitionHelper
                    btnSearchVoice.clearAnimation()
                    etSearch.setText(query)
                    submitSearch()
                },
                onError = { message ->
                    if (isAdded) {
                        btnSearchVoice.clearAnimation()
                        showSafeToast(message)
                    }
                },
                onListeningStateChanged = {
                    if (isAdded) {
                        val blink = AlphaAnimation(1f, 0.3f).apply {
                            duration = 500
                            repeatCount = Animation.INFINITE
                            repeatMode = Animation.REVERSE
                        }
                        btnSearchVoice.startAnimation(blink)
                        etSearch.hint = getString(R.string.voice_prompt)
                    }
                },
            ).also { voiceHelper = it }

            if (!helper.isAvailable()) {
                btnSearchVoice.visibility = View.GONE
                return
            }
            if (!helper.isListening) helper.startWithPermissionCheck()
        } catch (error: Throwable) {
            Log.e(TAG, "Voice search unavailable", error)
            btnSearchVoice.clearAnimation()
            btnSearchVoice.visibility = View.GONE
            showSafeToast("Ricerca vocale non disponibile su questo dispositivo")
        }
    }

    private fun switchProviderIfNeeded(providerName: String?, endpoint: String?) {
        val previousName = UserPreferences.currentProvider?.name
        val target = ProviderSelection.select(providerName, endpoint) ?: return
        if (previousName != target.name || !endpoint.isNullOrBlank()) {
            showSafeToast(getString(R.string.switching_to_provider, target.name))
        }
    }

    private fun <T> makeSelector(
        title: String,
        values: List<T>,
        labeler: (T) -> String,
    ): DropdownSelector<T> {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(makeLabel(title, 12f, false, Color.rgb(169, 178, 194)))

        // Stessa struttura grafica del Mobile: cornice, Spinner trasparente e
        // chevron a destra. Viene costruita qui, dopo il primo frame, così non
        // torna nel layout statico che aveva reso fragile l'apertura di Cerca TV.
        val controlFrame = FrameLayout(requireContext()).apply {
            id = View.generateViewId()
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = title
            setBackgroundResource(R.drawable.bg_filter_control_tv)
        }

        val labels = values.map(labeler)
        val spinner = Spinner(requireContext(), Spinner.MODE_DROPDOWN).apply {
            isFocusable = false
            isClickable = false
            background = null
            setPadding(dp(12), 0, dp(30), 0)
            adapter = ArrayAdapter(
                requireContext(),
                R.layout.item_filter_spinner_tv,
                labels,
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setPopupBackgroundDrawable(ColorDrawable(Color.rgb(48, 48, 48)))
            dropDownVerticalOffset = dp(2)
            post {
                if (width > 0) dropDownWidth = width
            }
        }
        controlFrame.addView(
            spinner,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val chevron = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_filter_chevron_down)
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        controlFrame.addView(
            chevron,
            FrameLayout.LayoutParams(dp(18), dp(18)).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                marginEnd = dp(9)
            },
        )

        root.addView(
            controlFrame,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)),
        )
        return DropdownSelector(title, root, controlFrame, spinner, values)
    }

    private fun addSelectorToRow(row: LinearLayout, selector: DropdownSelector<*>?) {
        val control = selector ?: return
        row.addView(
            control.root,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            },
        )
    }

    private fun makeLabel(
        text: String,
        size: Float,
        bold: Boolean,
        color: Int = Color.WHITE,
    ) = TextView(requireContext()).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, if (size < 14f) dp(3) else 0, 0, dp(4))
    }

    private fun makeActionButton(text: String, primary: Boolean): TextView =
        TextView(requireContext()).apply {
            id = View.generateViewId()
            this.text = text
            isFocusable = true
            isClickable = true
            gravity = android.view.Gravity.CENTER
            setTextColor(if (primary) Color.rgb(15, 20, 28) else Color.WHITE)
            textSize = 15f
            setBackgroundResource(
                if (primary) R.drawable.bg_filter_action_primary_tv
                else R.drawable.bg_filter_action_secondary_tv
            )
        }

    private fun showSafeToast(message: String) {
        if (!isAdded || message.isBlank()) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private inner class DropdownSelector<T>(
        private val title: String,
        val root: LinearLayout,
        private val focusView: FrameLayout,
        private val spinner: Spinner,
        private val values: List<T>,
    ) {
        val value: T
            get() {
                val selected = spinner.selectedItemPosition.takeIf { it in values.indices } ?: 0
                return values[selected]
            }
        val viewId: Int get() = focusView.id
        private var onLongPressAction: (() -> Unit)? = null

        fun setLongPressAction(action: () -> Unit) {
            onLongPressAction = action
            focusView.setOnLongClickListener { action(); true }
        }

        init {
            require(values.isNotEmpty())

            focusView.setOnClickListener { openDropdown() }
            focusView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && !filterPanelExpanded) revealSearchHeader()
            }
            focusView.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                if (event.isLongPress && onLongPressAction != null) {
                    onLongPressAction?.invoke()
                    return@setOnKeyListener true
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        openDropdown()
                        true
                    }
                    // Sinistra/destra/su/giù NON cambiano il valore: restano
                    // disponibili alla normale navigazione D-pad tra le caselle.
                    else -> false
                }
            }
        }

        fun reset() {
            spinner.setSelection(0, false)
        }

        fun selectValue(value: T?) {
            val index = values.indexOfFirst { it == value }
            spinner.setSelection(if (index >= 0) index else 0, false)
        }

        fun requestFocus(): Boolean = focusView.requestFocus()

        fun setNextFocus(
            left: Int? = null,
            right: Int? = null,
            up: Int? = null,
            down: Int? = null,
        ) {
            left?.let { focusView.nextFocusLeftId = it }
            right?.let { focusView.nextFocusRightId = it }
            up?.let { focusView.nextFocusUpId = it }
            down?.let { focusView.nextFocusDownId = it }
        }

        private fun openDropdown() {
            if (!isAdded) return
            try {
                spinner.performClick()
            } catch (error: Throwable) {
                Log.e(TAG, "Filter dropdown '$title' failed", error)
                showSafeToast("Impossibile aprire il filtro $title")
            }
        }
    }

    companion object {
        private const val TAG = "SearchTvSafe"
    }
}

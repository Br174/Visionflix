package com.streamflixreborn.streamflix.fragments.search

import android.content.Context
import android.graphics.Color
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private var isGlobalSearchChecked = false
    private var currentGridColumns = 1
    private var searchHeaderOffset = 0
    private var suppressActorTextChange = false

    private var typeSelector: CycleSelector<AdvancedSearchFilters.ContentType>? = null
    private var italianSelector: CycleSelector<AdvancedSearchFilters.ItalianFilter>? = null
    private var yearSelector: CycleSelector<Int?>? = null
    private var genreSelector: CycleSelector<AdvancedSearchFilters.GenreFilter>? = null
    private var fromSelector: CycleSelector<Int?>? = null
    private var toSelector: CycleSelector<Int?>? = null
    private var actorEdit: EditText? = null
    private var actorSuggestionsGrid: HorizontalGridView? = null
    private var actorFallbackSuggestions: LinearLayout? = null
    private var btnApplyFilters: TextView? = null
    private var btnResetFilters: TextView? = null

    private val appAdapter by lazy {
        AppAdapter().apply {
            onMovieClickListener = { movie ->
                switchProviderIfNeeded(movie.providerName)
                findNavController().navigate(
                    SearchTvFragmentDirections.actionSearchToMovie(id = movie.id)
                )
            }
            onTvShowClickListener = { tvShow ->
                switchProviderIfNeeded(tvShow.providerName)
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
            initializeScrollableHeader()
            updateGridTopPadding()
            etSearch.requestFocus()
        }
    }

    override fun onDestroyView() {
        actorSearchJob?.cancel()
        voiceHelper?.stopRecognition()
        voiceHelper = null
        actorSuggestionsGrid = null
        actorFallbackSuggestions = null
        actorEdit = null
        typeSelector = null
        italianSelector = null
        yearSelector = null
        genreSelector = null
        fromSelector = null
        toSelector = null
        btnApplyFilters = null
        btnResetFilters = null
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
                    updateHeaderForSelectedPosition(position)
                    if (position in 0 until currentGridColumns.coerceAtLeast(1)) {
                        btnApplyFilters?.let { child?.itemView?.nextFocusUpId = it.id }
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

    private fun buildAdvancedFiltersSafely() {
        if (filterContainer.childCount > 0) return
        try {
            val panel = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(14))
                setBackgroundResource(R.drawable.bg_filter_panel_tv)
            }
            filterContainer.addView(
                panel,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )

            panel.addView(makeLabel("Filtri avanzati", 18f, true))
            panel.addView(makeLabel(
                "Combina i criteri: testo, tipo, lingua, anno, genere e attore vengono applicati insieme.",
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
                    AdvancedSearchFilters.ContentType.TV -> "Serie TV"
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

            val genres = AdvancedSearchFilters.GenreFilter.entries.toList()
            genreSelector = makeSelector("Genere", genres) { it.label }

            val firstRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
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
                setPadding(0, dp(10), 0, 0)
            }
            panel.addView(secondRow)
            addSelectorToRow(secondRow, fromSelector)
            addSelectorToRow(secondRow, toSelector)

            val actorColumn = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
            }
            secondRow.addView(
                actorColumn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply {
                    marginStart = dp(10)
                },
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
            }
            actorRow.addView(
                actorEdit,
                LinearLayout.LayoutParams(0, dp(46), 1f),
            )

            val actorSearchButton = makeActionButton("Cerca attore", primary = false)
            actorRow.addView(
                actorSearchButton,
                LinearLayout.LayoutParams(dp(142), dp(46)).apply { marginStart = dp(8) },
            )
            actorSearchButton.setOnClickListener { triggerActorSearch() }

            setupActorTextSearch()
            addActorSuggestionsSurface(panel)

            val actions = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                setPadding(0, dp(12), 0, 0)
            }
            panel.addView(actions)

            btnResetFilters = makeActionButton("Azzera", primary = false).also { button ->
                actions.addView(
                    button,
                    LinearLayout.LayoutParams(dp(150), dp(48)).apply { marginEnd = dp(10) },
                )
                button.setOnClickListener { resetFilters() }
            }

            btnApplyFilters = makeActionButton("Applica filtri", primary = true).also { button ->
                actions.addView(button, LinearLayout.LayoutParams(dp(190), dp(48)))
                button.setOnClickListener { submitSearch() }
            }

            configureFilterFocus(actorSearchButton)
            searchHeader.post { updateGridTopPadding() }
        } catch (error: Throwable) {
            Log.e(TAG, "Advanced filter panel failed", error)
            filterContainer.removeAllViews()
            val fallback = makeActionButton("Ricerca base disponibile — filtri non caricati", primary = false)
            fallback.isClickable = false
            filterContainer.addView(
                fallback,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48),
                )
            )
            showSafeToast("Cerca è aperta; i filtri avanzati non sono stati caricati")
        }
    }

    private fun configureFilterFocus(actorSearchButton: TextView) {
        val type = typeSelector ?: return
        val italian = italianSelector ?: return
        val year = yearSelector ?: return
        val genre = genreSelector ?: return
        val from = fromSelector ?: return
        val to = toSelector ?: return
        val actor = actorEdit ?: return
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
        genre.setNextFocus(left = year.viewId, up = llGlobalSearch.id, down = actorSearchButton.id)

        from.setNextFocus(right = to.viewId, up = type.viewId, down = reset.id)
        to.setNextFocus(left = from.viewId, right = actor.id, up = italian.viewId, down = reset.id)

        actor.nextFocusLeftId = to.viewId
        actor.nextFocusRightId = actorSearchButton.id
        actor.nextFocusUpId = year.viewId
        actor.nextFocusDownId = apply.id

        actorSearchButton.nextFocusLeftId = actor.id
        actorSearchButton.nextFocusUpId = genre.viewId
        actorSearchButton.nextFocusDownId = apply.id

        reset.nextFocusUpId = from.viewId
        reset.nextFocusRightId = apply.id
        reset.nextFocusDownId = vgvSearch.id
        apply.nextFocusUpId = actor.id
        apply.nextFocusLeftId = reset.id
        apply.nextFocusDownId = vgvSearch.id
        vgvSearch.nextFocusUpId = apply.id
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
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(136)).apply {
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
        actorSuggestionsGrid?.visibility = View.GONE
        actorFallbackSuggestions?.visibility = View.GONE
        btnApplyFilters?.requestFocus()
        hideKeyboard()
        searchHeader.post { updateGridTopPadding() }
    }

    private fun resetFilters() {
        typeSelector?.reset()
        italianSelector?.reset()
        yearSelector?.reset()
        genreSelector?.reset()
        fromSelector?.reset()
        toSelector?.reset()
        suppressActorTextChange = true
        actorEdit?.setText("")
        suppressActorTextChange = false
        searchViewModel?.clearActorSelection()
        searchViewModel?.clearActorSuggestions()
        displayActorSuggestions(emptyList())
        searchViewModel?.search(etSearch.text?.toString().orEmpty().trim())
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

        return AdvancedSearchFilters(
            type = typeSelector?.value ?: AdvancedSearchFilters.ContentType.ALL,
            italian = italianSelector?.value ?: AdvancedSearchFilters.ItalianFilter.ALL,
            genre = genreSelector?.value ?: AdvancedSearchFilters.GenreFilter.ALL,
            year = yearSelector?.value,
            dateFrom = from?.let { "%04d-01-01".format(it) },
            dateTo = to?.let { "%04d-12-31".format(it) },
            actorId = vm?.selectedActorId,
            actorName = vm?.selectedActorName.orEmpty(),
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

        if (filters.isActive) {
            isGlobalSearchChecked = false
            updateGlobalSwitch()
            if (UserPreferences.currentProvider !is TmdbProvider) {
                val language = UserPreferences.currentProvider?.language
                    ?.takeIf { it.isNotBlank() }
                    ?: "it"
                UserPreferences.currentProvider = TmdbProvider(language)
            }
            vm.searchAdvanced(query, filters)
            return true
        }

        if (isGlobalSearchChecked) {
            if (query.isBlank()) {
                showSafeToast(getString(R.string.search_empty_query))
                return true
            }
            vm.searchGlobal(query, UserPreferences.currentProvider?.language ?: "it")
        } else {
            vm.search(query)
        }
        return true
    }

    private fun updateGlobalSwitch() {
        ivGlobalSearchSwitch.setImageResource(
            if (isGlobalSearchChecked) R.drawable.ic_switch_on else R.drawable.ic_switch_off
        )
    }

    private fun initializeScrollableHeader() {
        vgvSearch.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                try {
                    syncHeaderToGrid(recyclerView)
                } catch (error: Throwable) {
                    Log.w(TAG, "Header scroll sync ignored", error)
                }
            }
        })
    }

    private fun updateGridTopPadding() {
        if (!isAdded || !this::searchHeader.isInitialized) return
        val top = searchHeader.height + dp(12)
        if (top <= 0) return
        if (vgvSearch.paddingTop != top) {
            vgvSearch.setPadding(
                vgvSearch.paddingLeft,
                top,
                vgvSearch.paddingRight,
                vgvSearch.paddingBottom,
            )
        }
        syncHeaderToGrid(vgvSearch)
    }

    private fun syncHeaderToGrid(recyclerView: RecyclerView) {
        val maxOffset = searchHeader.height
        if (maxOffset <= 0 || recyclerView.childCount == 0) {
            setSearchHeaderOffset(0, animate = false)
            return
        }

        var firstChild: View? = null
        var firstPosition = Int.MAX_VALUE
        for (index in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(index)
            val position = recyclerView.getChildAdapterPosition(child)
            if (position != RecyclerView.NO_POSITION && position < firstPosition) {
                firstPosition = position
                firstChild = child
            }
        }

        val offset = if (firstChild == null) {
            0
        } else if (firstPosition < currentGridColumns.coerceAtLeast(1)) {
            (recyclerView.paddingTop - firstChild.top).coerceIn(0, maxOffset)
        } else {
            maxOffset
        }
        setSearchHeaderOffset(offset, animate = false)
    }

    private fun updateHeaderForSelectedPosition(position: Int) {
        if (position == RecyclerView.NO_POSITION || !this::searchHeader.isInitialized) return
        val maxOffset = searchHeader.height
        if (maxOffset <= 0) return
        val firstRowSize = currentGridColumns.coerceAtLeast(1)
        val targetOffset = if (position >= firstRowSize) maxOffset else 0
        setSearchHeaderOffset(targetOffset, animate = true)
    }

    private fun setSearchHeaderOffset(offset: Int, animate: Boolean) {
        if (!this::searchHeader.isInitialized) return
        val maxOffset = searchHeader.height.coerceAtLeast(0)
        val safeOffset = offset.coerceIn(0, maxOffset)
        searchHeaderOffset = safeOffset
        val translation = -safeOffset.toFloat()
        searchHeader.animate().cancel()
        if (animate) {
            searchHeader.animate()
                .translationY(translation)
                .setDuration(140L)
                .start()
        } else {
            searchHeader.translationY = translation
        }
    }

    private fun revealSearchHeader() {
        if (searchHeaderOffset > 0) vgvSearch.scrollToPosition(0)
        setSearchHeaderOffset(0, animate = true)
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
        searchHeader.post { updateGridTopPadding() }
    }

    private fun displayGlobalSearch(providerResults: List<ProviderResult>) {
        val categories = providerResults.map { providerResult ->
            val title = when (val providerState = providerResult.state) {
                is ProviderResult.State.Loading ->
                    "${providerResult.provider.name} - ${getString(R.string.searching)}"
                is ProviderResult.State.Error ->
                    "${providerResult.provider.name} - ${getString(R.string.search_error)}"
                is ProviderResult.State.Success -> {
                    val count = providerState.results.size
                    val word = if (count == 1) getString(R.string.result) else getString(R.string.results)
                    "${providerResult.provider.name} - $count $word"
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
        searchHeader.post { updateGridTopPadding() }
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

    private fun switchProviderIfNeeded(providerName: String?) {
        val targetName = providerName?.takeIf { it.isNotBlank() } ?: return
        if (targetName == UserPreferences.currentProvider?.name) return
        val targetProvider = Provider.providers.keys.find { it.name == targetName } ?: return
        UserPreferences.currentProvider = targetProvider
        showSafeToast(getString(R.string.switching_to_provider, targetName))
    }

    private fun <T> makeSelector(
        title: String,
        values: List<T>,
        labeler: (T) -> String,
    ): CycleSelector<T> {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(makeLabel(title, 12f, false, Color.rgb(169, 178, 194)))
        val valueView = TextView(requireContext()).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            gravity = android.view.Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(12), 0, dp(12), 0)
            setBackgroundResource(R.drawable.bg_filter_control_tv)
        }
        root.addView(
            valueView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)),
        )
        return CycleSelector(title, root, valueView, values, labeler)
    }

    private fun addSelectorToRow(row: LinearLayout, selector: CycleSelector<*>?) {
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

    private inner class CycleSelector<T>(
        private val title: String,
        val root: LinearLayout,
        private val valueView: TextView,
        private val values: List<T>,
        private val labeler: (T) -> String,
    ) {
        private var index = 0
        val value: T get() = values[index]
        val viewId: Int get() = valueView.id

        init {
            require(values.isNotEmpty())
            render()
            valueView.setOnClickListener { showChoices() }
            valueView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && searchHeaderOffset > 0) revealSearchHeader()
            }
            valueView.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        showChoices()
                        true
                    }
                    else -> false
                }
            }
        }

        fun reset() {
            index = 0
            render()
        }

        fun setNextFocus(
            left: Int? = null,
            right: Int? = null,
            up: Int? = null,
            down: Int? = null,
        ) {
            left?.let { valueView.nextFocusLeftId = it }
            right?.let { valueView.nextFocusRightId = it }
            up?.let { valueView.nextFocusUpId = it }
            down?.let { valueView.nextFocusDownId = it }
        }

        private fun showChoices() {
            if (!isAdded) return
            try {
                val labels = values.map(labeler).toTypedArray()
                val dialog = android.app.AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setSingleChoiceItems(labels, index) { openedDialog, which ->
                        index = which
                        render()
                        openedDialog.dismiss()
                    }
                    .setNegativeButton("Annulla", null)
                    .create()

                dialog.setOnShowListener {
                    dialog.listView?.apply {
                        setSelection(index)
                        requestFocus()
                    }
                }
                dialog.setOnDismissListener {
                    if (isAdded) valueView.post { valueView.requestFocus() }
                }
                dialog.show()
            } catch (error: Throwable) {
                Log.e(TAG, "Filter selector '$title' failed", error)
                showSafeToast("Impossibile aprire il filtro $title")
            }
        }

        private fun render() {
            valueView.text = labeler(values[index])
        }
    }

    companion object {
        private const val TAG = "SearchTvSafe"
    }
}

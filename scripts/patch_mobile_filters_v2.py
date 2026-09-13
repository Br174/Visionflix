from pathlib import Path
p=Path('app/src/main/java/com/streamflixreborn/streamflix/fragments/search/SearchMobileFragment.kt'); s=p.read_text()
def r(a,b):
 global s
 assert a in s,a[:100]
 s=s.replace(a,b,1)
r('    private var actorSearchJob: Job? = null\n    private var suppressActorTextChange: Boolean = false\n','    private var actorSearchJob: Job? = null\n    private var directorSearchJob: Job? = null\n    private var suppressActorTextChange: Boolean = false\n    private var suppressDirectorTextChange: Boolean = false\n')
anchor='''    private val actorSuggestionsAdapter by lazy {
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
'''
r(anchor,anchor+'''\n    private val directorSuggestionsAdapter by lazy {
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
''')
r('        initializeActorSearch()\n        initializeScrollableHeader()\n','        initializeActorSearch()\n        initializeDirectorSearch()\n        initializeScrollableHeader()\n')
r('''        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.actorSuggestions
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { displayActorSuggestions(it) }
        }
''','''        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.actorSuggestions
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { displayActorSuggestions(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.directorSuggestions
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { displayDirectorSuggestions(it) }
        }
''')
r('        actorSearchJob?.cancel()\n        if (::voiceHelper.isInitialized) voiceHelper.stopRecognition()\n','        actorSearchJob?.cancel()\n        directorSearchJob?.cancel()\n        if (::voiceHelper.isInitialized) voiceHelper.stopRecognition()\n')
r('''            binding.spYear,
            binding.spGenre,
        )
''','''            binding.spYear,
            binding.spGenre,
            binding.spSort,
            binding.spPlatform,
        )
''')
r('''                binding.etDateTo,
                binding.spGenre,
            )
            clearActorField()
''','''                binding.etDateTo,
                binding.spGenre,
                binding.spSort,
                binding.spPlatform,
            )
            clearActorField()
            clearDirectorField()
''')
marker='    private fun initializeScrollableHeader() {'
r(marker,'''    private fun initializeDirectorSearch() {
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

'''+marker)
marker='    private fun submitSearch(): Boolean {'
r(marker,'''    private fun clearDirectorField() {
        directorSearchJob?.cancel()
        suppressDirectorTextChange = true
        binding.etDirectorSearch.setText("")
        suppressDirectorTextChange = false
        viewModel.clearDirectorSelection()
        binding.rvDirectorSuggestions.visibility = View.GONE
        binding.searchHeaderMobile.post { updateGridTopPadding() }
    }

'''+marker)
# second AdvancedSearchUi.read occurrence
old='''                binding.etDateTo,
                binding.spGenre,
            )
'''
assert old in s
s=s.replace(old,'''                binding.etDateTo,
                binding.spGenre,
                binding.spSort,
                binding.spPlatform,
            )
''',1)
r('''        val filters = baseFilters.copy(
            actorId = viewModel.selectedActorId,
            actorName = viewModel.selectedActorName,
        )
''','''        val directorText = binding.etDirectorSearch.text?.toString().orEmpty().trim()
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
''')
marker='    private fun displaySearch(list: List<AppAdapter.Item>, hasMore: Boolean) {'
r(marker,'''    private fun displayDirectorSuggestions(list: List<People>) {
        if (_binding == null) return
        val items = list.onEach { it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM }
        directorSuggestionsAdapter.submitList(items)
        binding.rvDirectorSuggestions.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        binding.searchHeaderMobile.post { updateGridTopPadding() }
    }

'''+marker)
p.write_text(s)
print('Mobile fragment patched')

from pathlib import Path
p=Path('app/src/main/java/com/streamflixreborn/streamflix/fragments/search/SearchTvFragment.kt')
s=p.read_text()
def r(a,b):
 global s
 assert a in s,a[:80]
 s=s.replace(a,b,1)
r('    private var searchHeaderOffset = 0\n','')
r('    private var actorFallbackSuggestions: LinearLayout? = null\n    private var directorFallbackSuggestions: LinearLayout? = null\n    private var btnActorSearch: TextView? = null\n    private var btnDirectorSearch: TextView? = null\n','    private var actorFallbackSuggestions: LinearLayout? = null\n    private var directorFallbackSuggestions: LinearLayout? = null\n    private var filterPanelBody: LinearLayout? = null\n')
r('        btnActorSearch = null\n        btnDirectorSearch = null\n','        filterPanelBody = null\n')
r('            filterContainer.addView(\n                panel,\n','            filterPanelBody = panel\n            filterContainer.addView(\n                panel,\n')
r('            actorRow.addView(actorEdit, LinearLayout.LayoutParams(0, dp(46), 1f))\n            btnActorSearch = makeActionButton("Cerca attore", primary = false)\n            actorRow.addView(\n                btnActorSearch,\n                LinearLayout.LayoutParams(dp(142), dp(46)).apply { marginStart = dp(8) },\n            )\n            btnActorSearch?.setOnClickListener { triggerActorSearch() }\n            btnActorSearch?.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) revealSearchHeader() }\n','            actorRow.addView(actorEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))\n')
r('            directorRow.addView(directorEdit, LinearLayout.LayoutParams(0, dp(46), 1f))\n            btnDirectorSearch = makeActionButton("Cerca regista", primary = false)\n            directorRow.addView(\n                btnDirectorSearch,\n                LinearLayout.LayoutParams(dp(142), dp(46)).apply { marginStart = dp(8) },\n            )\n            btnDirectorSearch?.setOnClickListener { triggerDirectorSearch() }\n            btnDirectorSearch?.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) revealSearchHeader() }\n','            directorRow.addView(directorEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))\n')
r('            filterHandle = makeActionButton("▲ Filtri", primary = false).apply {\n                textSize = 11f\n                setOnFocusChangeListener { _, hasFocus -> if (hasFocus) setFilterPanelExpanded(true, true) }\n                setOnClickListener { setFilterPanelExpanded(true, true) }\n            }\n            panel.addView(\n                filterHandle,\n','            filterHandle = makeActionButton("Filtri", primary = false).apply {\n                textSize = 12f\n                visibility = View.GONE\n                setOnClickListener { openFiltersFromResults() }\n                setOnFocusChangeListener { _, hasFocus -> if (hasFocus && !filterPanelExpanded) openFiltersFromResults() }\n            }\n            filterContainer.addView(\n                filterHandle,\n')
# focus graph: remove obsolete buttons and retarget
s=s.replace('        val actorSearch = btnActorSearch ?: return\n        val directorSearch = btnDirectorSearch ?: return\n','')
s=s.replace('        genre.setNextFocus(left = year.viewId, up = llGlobalSearch.id, down = actorSearch.id)','        genre.setNextFocus(left = year.viewId, up = llGlobalSearch.id, down = actor.id)')
s=s.replace('        actor.nextFocusRightId = actorSearch.id\n','')
s=s.replace('        actorSearch.nextFocusLeftId = actor.id\n        actorSearch.nextFocusUpId = genre.viewId\n        actorSearch.nextFocusDownId = directorSearch.id\n','')
s=s.replace('        director.nextFocusRightId = directorSearch.id\n','')
s=s.replace('        directorSearch.nextFocusLeftId = director.id\n        directorSearch.nextFocusUpId = actorSearch.id\n        directorSearch.nextFocusDownId = apply.id\n','')
s=s.replace('                    btnActorSearch?.nextFocusDownId = grid.id\n','').replace('                    btnActorSearch?.nextFocusDownId = btnDirectorSearch?.id ?: View.NO_ID\n','').replace('                    btnDirectorSearch?.nextFocusDownId = grid.id\n','').replace('                    btnDirectorSearch?.nextFocusDownId = btnApplyFilters?.id ?: View.NO_ID\n','')
start=s.index('    private fun initializeScrollableHeader() {')
end=s.index('    private fun focusFirstResultWhenReady() {',start)
new='''    private fun initializeScrollableHeader() {
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
        filterHandle?.visibility = if (expanded) View.GONE else View.VISIBLE
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

'''
s=s[:start]+new+s[end:]
s=s.replace('                if (hasFocus && searchHeaderOffset > 0) revealSearchHeader()','                if (hasFocus && !filterPanelExpanded) revealSearchHeader()')
s=s.replace('private const val FILTER_PEEK_DP = 26','private const val FILTER_PEEK_DP = 28')
p.write_text(s)
print('TV panel patched')

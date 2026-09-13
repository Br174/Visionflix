from pathlib import Path
import re

path = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/search/SearchTvFragment.kt")
text = path.read_text(encoding="utf-8")

# Guardrail: this patch is only for the already crash-safe TV search shell.
required = [
    "R.layout.fragment_search_tv_safe",
    "buildAdvancedFiltersSafely()",
    "Database/ViewModel, filtri e voce NON devono tornare nel percorso di inflation.",
    "private inner class CycleSelector<T>",
]
for marker in required:
    if marker not in text:
        raise SystemExit(f"Refusing to patch: missing crash-safe marker: {marker}")

# Imports needed for the same dropdown mechanism already used by Mobile.
text = text.replace(
    "import android.graphics.Color\n",
    "import android.graphics.Color\nimport android.graphics.drawable.ColorDrawable\n",
    1,
)
text = text.replace(
    "import android.widget.EditText\n",
    "import android.widget.ArrayAdapter\nimport android.widget.EditText\n",
    1,
)
text = text.replace(
    "import android.widget.ProgressBar\n",
    "import android.widget.ProgressBar\nimport android.widget.Spinner\n",
    1,
)

# Rename the selector wrapper: the values now live in a real Spinner dropdown,
# but the Spinner itself is still created only after the first frame (crash-safe).
text = text.replace("CycleSelector", "DropdownSelector")

# Match the Mobile wording shown in the reference UI.
text = text.replace(
    'AdvancedSearchFilters.ContentType.TV -> "Serie TV"',
    'AdvancedSearchFilters.ContentType.TV -> "Serie"',
)

# Do not snap-hide the header just because focus enters the second poster row.
# It must move by the same dy as the poster grid, then reappear while scrolling back.
text = text.replace("                    updateHeaderForSelectedPosition(position)\n", "", 1)

old_scroll = '''    private fun initializeScrollableHeader() {
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
'''
new_scroll = '''    private fun initializeScrollableHeader() {
        vgvSearch.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                try {
                    if (!this@SearchTvFragment::searchHeader.isInitialized) return
                    val maxOffset = searchHeader.height
                    if (maxOffset <= 0) return

                    // Il blocco Cerca + filtri si muove dello stesso delta verticale
                    // delle locandine: andando verso i risultati scompare gradualmente;
                    // tornando verso l'alto ricompare gradualmente. In cima torna a zero.
                    val target = (searchHeaderOffset + dy).coerceIn(0, maxOffset)
                    setSearchHeaderOffset(target, animate = false)
                    if (!recyclerView.canScrollVertically(-1)) {
                        setSearchHeaderOffset(0, animate = false)
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "Header scroll sync ignored", error)
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE &&
                    !recyclerView.canScrollVertically(-1)
                ) {
                    setSearchHeaderOffset(0, animate = true)
                }
            }
        })
    }
'''
if old_scroll not in text:
    raise SystemExit("Refusing to patch: old initializeScrollableHeader block not found")
text = text.replace(old_scroll, new_scroll, 1)

old_make = '''    private fun <T> makeSelector(
        title: String,
        values: List<T>,
        labeler: (T) -> String,
    ): DropdownSelector<T> {
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
        return DropdownSelector(title, root, valueView, values, labeler)
    }
'''
new_make = '''    private fun <T> makeSelector(
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
            popupBackgroundDrawable = ColorDrawable(Color.rgb(48, 48, 48))
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
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)),
        )
        return DropdownSelector(title, root, controlFrame, spinner, values)
    }
'''
if old_make not in text:
    raise SystemExit("Refusing to patch: old makeSelector block not found")
text = text.replace(old_make, new_make, 1)

# Replace AlertDialog selector with an anchored Spinner dropdown like Mobile.
pattern = re.compile(
    r'''    private inner class DropdownSelector<T>\(.*?\n    }\n\n    companion object \{''',
    re.S,
)
new_selector = '''    private inner class DropdownSelector<T>(
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

        init {
            require(values.isNotEmpty())

            focusView.setOnClickListener { openDropdown() }
            focusView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && searchHeaderOffset > 0) revealSearchHeader()
            }
            focusView.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
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

    companion object {'''
text, count = pattern.subn(new_selector, text, count=1)
if count != 1:
    raise SystemExit(f"Refusing to patch: DropdownSelector block replacements={count}")

path.write_text(text, encoding="utf-8")
print("Patched TV search: Mobile-style dropdowns + natural hide/reveal scroll; crash-safe shell preserved.")

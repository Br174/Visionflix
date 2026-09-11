package com.streamflixreborn.streamflix.fragments.settings

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.streamflixreborn.streamflix.utils.SourcePreferences
import java.util.Collections
import java.util.WeakHashMap

object SourceSettingsInjector {
    private const val CATEGORY_KEY = "visionflix_source_manager_category"
    private const val IPTV_KEY = "visionflix_source_manager_iptv"
    private const val WEB_KEY = "visionflix_source_manager_web"

    private val installedViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun install(fragment: PreferenceFragmentCompat) {
        val rootView = fragment.view ?: return
        if (installedViews.add(rootView)) {
            rootView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                rootView.post { injectIfRootScreen(fragment) }
            }
        }
        rootView.post { injectIfRootScreen(fragment) }
    }

    private fun injectIfRootScreen(fragment: PreferenceFragmentCompat) {
        if (!fragment.isAdded) return
        val preferenceScreen = fragment.preferenceScreen ?: return

        // The root settings page contains the top-level "Contenuti" screen.
        // Nested pages do not, so the source manager stays on the main page.
        if (fragment.findPreference<PreferenceScreen>("screen_content") == null) return
        if (fragment.findPreference<PreferenceCategory>(CATEGORY_KEY) != null) return

        val context = fragment.requireContext()
        val category = PreferenceCategory(context).apply {
            key = CATEGORY_KEY
            title = "Sorgenti personali"
            order = Int.MAX_VALUE - 20
            isIconSpaceReserved = false
        }

        preferenceScreen.addPreference(category)
        category.addPreference(SourceListPreference(context, SourcePreferences.SourceType.IPTV).apply {
            key = IPTV_KEY
            order = 0
        })
        category.addPreference(SourceListPreference(context, SourcePreferences.SourceType.WEB).apply {
            key = WEB_KEY
            order = 1
        })
    }
}

private class SourceListPreference(
    context: Context,
    private val type: SourcePreferences.SourceType,
) : Preference(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    init {
        isPersistent = false
        isIconSpaceReserved = false
        title = if (type == SourcePreferences.SourceType.IPTV) {
            "Sorgenti IPTV"
        } else {
            "Canali Web / Liste automatiche"
        }
        refreshSummary()
        setOnPreferenceClickListener {
            showManagerDialog()
            true
        }
    }

    private fun refreshSummary() {
        val values = SourcePreferences.get(context, type)
        summary = when {
            values.isEmpty() -> "Nessuna sorgente. Tocca per aggiungerne una."
            values.size == 1 -> "1 sorgente configurata. Tocca per gestirla."
            else -> "${values.size} sorgenti configurate. Tocca per gestirle."
        }
    }

    private fun showManagerDialog() {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(6))
        }

        val help = TextView(context).apply {
            text = if (type == SourcePreferences.SourceType.IPTV) {
                "Incolla un indirizzo M3U/M3U8. Le sorgenti vengono lette nell'ordine mostrato."
            } else {
                "Incolla un sito singolo oppure l'URL di una lista automatica (per esempio Pastebin raw)."
            }
            setPadding(0, 0, 0, dp(10))
        }
        outer.addView(help)

        val addRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(context).apply {
            hint = "https://..."
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addButton = Button(context).apply {
            text = "+ Aggiungi"
            isAllCaps = false
        }
        addRow.addView(input)
        addRow.addView(addButton)
        outer.addView(addRow)

        val separator = TextView(context).apply {
            text = "Sorgenti salvate"
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(6))
        }
        outer.addView(separator)

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(context).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(280),
            )
        }
        outer.addView(scroll)

        fun redraw() {
            listContainer.removeAllViews()
            val values = SourcePreferences.get(context, type)
            if (values.isEmpty()) {
                listContainer.addView(TextView(context).apply {
                    text = "Nessuna sorgente configurata"
                    setPadding(0, dp(12), 0, dp(12))
                })
            } else {
                values.forEachIndexed { index, value ->
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(4), 0, dp(4))
                    }
                    val label = TextView(context).apply {
                        text = "${index + 1}. $value"
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        setPadding(0, 0, dp(8), 0)
                    }
                    val remove = Button(context).apply {
                        text = "Elimina"
                        isAllCaps = false
                        setOnClickListener {
                            SourcePreferences.remove(context, type, value)
                            redraw()
                            refreshSummary()
                        }
                    }
                    row.addView(label)
                    row.addView(remove)
                    listContainer.addView(row)
                }
            }
        }

        redraw()

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(outer)
            .setNegativeButton("Chiudi", null)
            .create()

        addButton.setOnClickListener {
            val value = input.text?.toString().orEmpty().trim()
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                Toast.makeText(context, "Inserisci un indirizzo http:// o https:// valido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val added = SourcePreferences.add(context, type, value)
            if (!added) {
                Toast.makeText(context, "Sorgente già presente", Toast.LENGTH_SHORT).show()
            } else {
                input.setText("")
                redraw()
                refreshSummary()
            }
        }

        dialog.show()
    }
}

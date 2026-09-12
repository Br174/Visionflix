package com.streamflixreborn.streamflix.fragments.search

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters
import com.streamflixreborn.streamflix.utils.UserPreferences

internal object AdvancedSearchUi {
    private val years = (2026 downTo 1900).toList()

    fun setup(
        context: Context,
        contentType: Spinner,
        italian: Spinner,
        year: Spinner,
        genre: Spinner? = null,
    ) {
        if (genre == null) {
            contentType.adapter = mobileAdapter(context, listOf("Tutti", "Film", "Serie", "Cartoni"))
            italian.adapter = mobileAdapter(
                context,
                listOf("Italiani: tutti", "Italiani: sì", "Italiani: no"),
            )
            year.adapter = mobileYearAdapter(context, "Anno: tutti")
            year.rootView.findViewById<Spinner>(R.id.et_date_from)?.adapter =
                mobileYearAdapter(context, "Dal: tutti")
            year.rootView.findViewById<Spinner>(R.id.et_date_to)?.adapter =
                mobileYearAdapter(context, "Al: tutti")
        } else {
            contentType.adapter = compactAdapter(context, listOf("Tutti", "Film", "Serie", "Cartoni"))
            italian.adapter = compactAdapter(context, listOf("Tutti", "Sì", "No"))
            year.adapter = yearAdapter(context)
            genre.adapter = compactAdapter(
                context,
                AdvancedSearchFilters.GenreFilter.values().map { it.label },
            )
            year.rootView.findViewById<Spinner>(R.id.et_date_from)?.adapter = yearAdapter(context)
            year.rootView.findViewById<Spinner>(R.id.et_date_to)?.adapter = yearAdapter(context)
        }
    }

    fun read(
        contentType: Spinner,
        italian: Spinner,
        year: Spinner,
        yearFrom: Spinner,
        yearTo: Spinner,
        genre: Spinner? = null,
    ): AdvancedSearchFilters {
        val selectedYear = selectedYear(year)
        val selectedFrom = selectedYear(yearFrom)
        val selectedTo = selectedYear(yearTo)

        if (selectedFrom != null && selectedTo != null && selectedFrom > selectedTo) {
            throw IllegalArgumentException("L'anno iniziale non può essere successivo all'anno finale")
        }

        val hasRange = selectedFrom != null || selectedTo != null

        val filters = AdvancedSearchFilters(
            type = when (contentType.selectedItemPosition) {
                1 -> AdvancedSearchFilters.ContentType.MOVIE
                2 -> AdvancedSearchFilters.ContentType.TV
                3 -> AdvancedSearchFilters.ContentType.CARTOON
                else -> AdvancedSearchFilters.ContentType.ALL
            },
            italian = when (italian.selectedItemPosition) {
                1 -> AdvancedSearchFilters.ItalianFilter.YES
                2 -> AdvancedSearchFilters.ItalianFilter.NO
                else -> AdvancedSearchFilters.ItalianFilter.ALL
            },
            genre = genre?.let {
                AdvancedSearchFilters.GenreFilter.values()
                    .getOrElse(it.selectedItemPosition) { AdvancedSearchFilters.GenreFilter.ALL }
            } ?: AdvancedSearchFilters.GenreFilter.ALL,
            year = if (hasRange) null else selectedYear,
            dateFrom = selectedFrom?.let { "%04d-01-01".format(it) },
            dateTo = selectedTo?.let { "%04d-12-31".format(it) },
        )

        if (filters.isActive && !hasUsableTmdbKey()) {
            throw IllegalArgumentException(
                "Per usare i filtri inserisci una chiave TMDb valida in Impostazioni → Contenuti → Chiave API TMDb"
            )
        }

        return filters
    }

    fun reset(
        contentType: Spinner,
        italian: Spinner,
        year: Spinner,
        yearFrom: Spinner,
        yearTo: Spinner,
        genre: Spinner? = null,
    ) {
        contentType.setSelection(0)
        italian.setSelection(0)
        year.setSelection(0)
        yearFrom.setSelection(0)
        yearTo.setSelection(0)
        genre?.setSelection(0)
    }

    private fun compactAdapter(
        context: Context,
        values: List<String>,
    ): ArrayAdapter<String> = ArrayAdapter(
        context,
        R.layout.item_filter_spinner_tv,
        values,
    ).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    private fun mobileAdapter(
        context: Context,
        values: List<String>,
    ): ArrayAdapter<String> = ArrayAdapter(
        context,
        android.R.layout.simple_spinner_dropdown_item,
        values,
    )

    private fun yearAdapter(context: Context): ArrayAdapter<String> =
        compactAdapter(context, listOf("Tutti") + years.map { it.toString() })

    private fun mobileYearAdapter(context: Context, firstItem: String): ArrayAdapter<String> =
        mobileAdapter(context, listOf(firstItem) + years.map { it.toString() })

    private fun selectedYear(spinner: Spinner): Int? = spinner.selectedItemPosition
        .takeIf { it > 0 }
        ?.let { years[it - 1] }

    private fun hasUsableTmdbKey(): Boolean {
        if (UserPreferences.tmdbApiKey.isNotBlank()) return true
        val embedded = BuildConfig.TMDB_API_KEY.trim()
        return embedded.isNotBlank() && !embedded.equals("dummy", ignoreCase = true)
    }
}

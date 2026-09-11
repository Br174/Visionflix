package com.streamflixreborn.streamflix.fragments.search

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters

internal object AdvancedSearchUi {
    private val years = (2026 downTo 1900).toList()

    fun setup(
        context: Context,
        contentType: Spinner,
        italian: Spinner,
        year: Spinner,
        yearFrom: Spinner,
        yearTo: Spinner,
    ) {
        contentType.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Tutti", "Film", "Serie", "Cartoni"),
        )
        italian.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Italiani: tutti", "Italiani: sì", "Italiani: no"),
        )
        year.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Anno: tutti") + years.map { it.toString() },
        )
        yearFrom.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Dal: tutti") + years.map { it.toString() },
        )
        yearTo.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Al: tutti") + years.map { it.toString() },
        )
    }

    fun read(
        contentType: Spinner,
        italian: Spinner,
        year: Spinner,
        yearFrom: Spinner,
        yearTo: Spinner,
    ): AdvancedSearchFilters {
        val selectedYear = selectedYear(year)
        val selectedFrom = selectedYear(yearFrom)
        val selectedTo = selectedYear(yearTo)

        if (selectedFrom != null && selectedTo != null && selectedFrom > selectedTo) {
            throw IllegalArgumentException("L'anno iniziale non può essere successivo all'anno finale")
        }

        val hasRange = selectedFrom != null || selectedTo != null

        return AdvancedSearchFilters(
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
            year = if (hasRange) null else selectedYear,
            dateFrom = selectedFrom,
            dateTo = selectedTo,
        )
    }

    fun reset(
        contentType: Spinner,
        italian: Spinner,
        year: Spinner,
        yearFrom: Spinner,
        yearTo: Spinner,
    ) {
        contentType.setSelection(0)
        italian.setSelection(0)
        year.setSelection(0)
        yearFrom.setSelection(0)
        yearTo.setSelection(0)
    }

    private fun selectedYear(spinner: Spinner): Int? = spinner.selectedItemPosition
        .takeIf { it > 0 }
        ?.let { years[it - 1] }
}

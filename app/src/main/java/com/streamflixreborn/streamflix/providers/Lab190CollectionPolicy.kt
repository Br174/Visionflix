package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.utils.TMDb3
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * LAB190: collection evidence for verified TMDb movie IDs.
 * Academy awards from the local MIT archive, TMDb metadata for studios,
 * Italian theatrical release dates, and Christmas keywords.
 */
internal object Lab190CollectionPolicy {
    fun companyIds(collection: AdvancedSearchFilters.CollectionFilter): String? = when (collection) {
        AdvancedSearchFilters.CollectionFilter.PIXAR -> "3"
        AdvancedSearchFilters.CollectionFilter.DISNEY -> "2|3"
        AdvancedSearchFilters.CollectionFilter.DC -> "429|128064"
        AdvancedSearchFilters.CollectionFilter.MARVEL -> "420"
        else -> null
    }

    fun keywordIds(collection: AdvancedSearchFilters.CollectionFilter): String? =
        if (collection == AdvancedSearchFilters.CollectionFilter.CHRISTMAS) "207317" else null

    fun recentCinemaWindow(): Pair<String, String> {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val from = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -100) }
        val to = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 14) }
        return f.format(from.time) to f.format(to.time)
    }

    fun normalize(t: String): String = Normalizer.normalize(t, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")

    private val christmasSetting = setOf(
        "Die Hard", "Batman Returns", "Lethal Weapon", "Eyes Wide Shut",
        "Home Alone", "Gremlins", "Love Actually", "The Holiday",
        "Carol", "The Apartment", "Miracle on 34th Street",
        "It's a Wonderful Life", "The Nightmare Before Christmas"
    ).mapTo(hashSetOf(), ::normalize)

    private fun named(title: String, originals: String, titles: Set<String>): Boolean {
        val normalTitles = titles.mapTo(hashSetOf(), ::normalize)
        return normalize(title) in normalTitles || normalize(originals) in normalTitles
    }

    fun matchesMovie(
        selection: AdvancedSearchFilters.CollectionFilter,
        id: Int, title: String, originalTitle: String, release: String?,
        companies: Set<Int> = emptySet(),
        keywords: Set<Int> = emptySet(),
        theatricalIt: List<String> = emptyList()
    ): Boolean {
        val year = release?.take(4)?.toIntOrNull()
        return when (selection) {
            AdvancedSearchFilters.CollectionFilter.ALL -> true
            AdvancedSearchFilters.CollectionFilter.OSCAR_WINNERS ->
                id in Lab190OscarArchive.principalAwardWinnerMovieIds ||
                (year != null && Lab190OscarArchive.recentPrincipalWinners[year]?.let {
                    named(title, originalTitle, it)
                } == true)
            AdvancedSearchFilters.CollectionFilter.OSCAR_2026 ->
                year == 2025 && named(title, originalTitle, Lab190OscarArchive.nominated2026)
            AdvancedSearchFilters.CollectionFilter.CHRISTMAS ->
                207317 in keywords || normalize(title) in christmasSetting ||
                    normalize(originalTitle) in christmasSetting
            AdvancedSearchFilters.CollectionFilter.PIXAR -> 3 in companies
            AdvancedSearchFilters.CollectionFilter.DISNEY -> 2 in companies || 3 in companies
            AdvancedSearchFilters.CollectionFilter.DC -> 429 in companies || 128064 in companies
            AdvancedSearchFilters.CollectionFilter.MARVEL -> 420 in companies
            AdvancedSearchFilters.CollectionFilter.IN_CINEMA -> {
                val (from, to) = recentCinemaWindow()
                theatricalIt.any { val date = it.take(10)
                    date.length == 10 && date >= from && date <= to }
            }
        }
    }

    fun matchesDetails(selection: AdvancedSearchFilters.CollectionFilter, d: TMDb3.Movie.Detail): Boolean {
        val italianTheatrical = d.releaseDates?.results.orEmpty()
            .filter { it.iso3166.equals("IT", ignoreCase = true) }
            .flatMap { it.releaseDates }
            .filter { it.type == TMDb3.Movie.ReleaseType.THEATRICAL ||
                it.type == TMDb3.Movie.ReleaseType.THEATRICAL_LIMITED }
            .mapNotNull { it.releaseDate }
        return matchesMovie(selection, d.id, d.title, d.originalTitle, d.releaseDate,
            d.productionCompanies.orEmpty().mapTo(hashSetOf()) { it.id },
            d.keywords?.keywords.orEmpty().mapTo(hashSetOf()) { it.id },
            italianTheatrical)
    }
}

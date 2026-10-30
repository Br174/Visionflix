package com.streamflixreborn.streamflix.providers

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Concrete, source-verified collection filters. Thematic IDs are checked
 * against TMDb Discover, and provider availability is verified separately.
 */
internal object Lab190CollectionPolicy {
    private val nomineeTitles2026 = listOf(
        "Bugonia", "F1", "Frankenstein", "Hamnet", "Marty Supreme",
        "The Secret Agent", "Sentimental Value", "Sinners", "Train Dreams",
        "One Battle after Another", "Blue Moon", "If I Had Legs I'd Kick You",
        "Song Sung Blue", "It Was Just an Accident", "Sirat",
        "The Voice of Hind Rajab"
    )
    private val nominated2026 = nomineeTitles2026.map(::normalize).toSet()

    fun curatedTitles(collection: AdvancedSearchFilters.CollectionFilter): List<Pair<Int, String>> =
        when (collection) {
            AdvancedSearchFilters.CollectionFilter.OSCARS_2026 ->
                nomineeTitles2026.map { 2026 to it }
            AdvancedSearchFilters.CollectionFilter.OSCAR_WINNERS ->
                Lab190OscarPrimaryWinners.entries.sortedByDescending { it.first }
            else -> emptyList()
        }

    fun normalize(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "")

    fun isCuratedOscar(
        collection: AdvancedSearchFilters.CollectionFilter,
        original: String, localized: String, releaseYear: Int?,
    ): Boolean {
        val keys = setOf(normalize(original), normalize(localized))
        if (collection == AdvancedSearchFilters.CollectionFilter.OSCARS_2026)
            return (releaseYear == null || releaseYear in 2024..2026) &&
                keys.any { it in nominated2026 }
        if (collection != AdvancedSearchFilters.CollectionFilter.OSCAR_WINNERS) return false
        return Lab190OscarPrimaryWinners.entries.any { (ceremony, title) ->
            (releaseYear == null || releaseYear in (ceremony - 3)..ceremony) &&
                normalize(title) in keys
        }
    }

    fun discoverParams(
        collection: AdvancedSearchFilters.CollectionFilter,
        today: Calendar = Calendar.getInstance(),
    ): Map<String, String> = when (collection) {
        AdvancedSearchFilters.CollectionFilter.IN_CINEMA -> {
            val from = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -75) }
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            mapOf("region" to "IT", "with_release_type" to "2|3",
                "release_date.gte" to fmt.format(from.time),
                "release_date.lte" to fmt.format(today.time))
        }
        AdvancedSearchFilters.CollectionFilter.CHRISTMAS -> mapOf("with_keywords" to "65")
        AdvancedSearchFilters.CollectionFilter.PIXAR -> mapOf("with_companies" to "3")
        AdvancedSearchFilters.CollectionFilter.DISNEY -> mapOf("with_companies" to "2")
        AdvancedSearchFilters.CollectionFilter.DC ->
            mapOf("with_companies" to "9993|429|128064")
        AdvancedSearchFilters.CollectionFilter.MARVEL -> mapOf("with_companies" to "420")
        else -> emptyMap()
    }

    fun isCuratedCollection(collection: AdvancedSearchFilters.CollectionFilter) =
        collection == AdvancedSearchFilters.CollectionFilter.OSCARS_2026 ||
        collection == AdvancedSearchFilters.CollectionFilter.OSCAR_WINNERS

    fun matches(
        collection: AdvancedSearchFilters.CollectionFilter,
        id: Int, original: String, localized: String,
        releaseYear: Int?, discoveredIds: Set<Int>,
    ): Boolean = when (collection) {
        AdvancedSearchFilters.CollectionFilter.ALL -> true
        AdvancedSearchFilters.CollectionFilter.OSCARS_2026,
        AdvancedSearchFilters.CollectionFilter.OSCAR_WINNERS ->
            isCuratedOscar(collection, original, localized, releaseYear)
        else -> id in discoveredIds
    }
}

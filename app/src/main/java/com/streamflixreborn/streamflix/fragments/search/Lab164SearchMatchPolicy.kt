package com.streamflixreborn.streamflix.fragments.search

import kotlin.math.abs
import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters

/**
 * LAB164: single shared rule for metadata matching and sparse filtered pagination.
 * Pure functions allow no-network regression checks before a signed Android build.
 */
internal object Lab164SearchMatchPolicy {
    /**
     * Provider year may refer to a localized release, while TMDb stores the original.
     * Never merge unrelated remakes separated by decades or cross movie/TV boundaries.
     */
    fun matchesMetadata(itemKey: String, metadataKeys: Set<String>): Boolean {
        val kind = itemKey.substringBefore('|')
        val title = itemKey.substringAfter('|', "").substringBeforeLast('|', "")
        val year = itemKey.substringAfterLast('|').toIntOrNull()
        if (title.isBlank() || kind !in setOf("movie", "tv")) return false
        if (itemKey in metadataKeys) return true
        return metadataKeys.any { key ->
            val candidateKind = key.substringBefore('|')
            val candidateTitle = key.substringAfter('|', "").substringBeforeLast('|', "")
            val candidateYear = key.substringAfterLast('|').toIntOrNull()
            val yearMatches = year == null || candidateYear == null ||
                // Several providers report last episode date; TMDb reports first airing.
                if (kind == "tv") year >= candidateYear - 1 && year <= candidateYear + 20
                else abs(year - candidateYear) <= 2
            val titleMatches = candidateTitle == title || (
                candidateTitle.length >= 8 && title.startsWith(candidateTitle) &&
                    title.removePrefix(candidateTitle) in setOf(
                        "film", "filmcompleto", "streaming", "ita", "serietv",
                        "seriecompleta", candidateYear?.toString().orEmpty()
                    )
                )
            candidateKind == kind && titleMatches && yearMatches
        }
    }

    /** A typed title plus a year should not trigger full discovery across TMDb.
     * Keep provider search primary, checking TMDb only when local release years are absent.
     */
    fun providerFirstYearQuery(query: String, filters: AdvancedSearchFilters): Boolean =
        query.isNotBlank() &&
            (filters.year != null || !filters.dateFrom.isNullOrBlank() ||
                !filters.dateTo.isNullOrBlank()) &&
            filters.italian == AdvancedSearchFilters.ItalianFilter.ALL &&
            filters.country == AdvancedSearchFilters.CountryFilter.ALL &&
            filters.platform == AdvancedSearchFilters.PlatformFilter.ALL &&
            filters.effectiveGenres.isEmpty() &&
            filters.type != AdvancedSearchFilters.ContentType.CARTOON &&
            filters.actorId.isNullOrBlank() && filters.directorId.isNullOrBlank()

    /** Recover sparse provider catalog filters with a bounded second window. */
    fun catalogPages(window: Int): IntRange = when(window) {
        1 -> 1..4
        2 -> 5..8
        else -> IntRange.EMPTY
    }

    /** Provider genre names may be in Italian or English. Never infer unverified genres. */
    fun localGenreMatches(name: String, requested: AdvancedSearchFilters.GenreFilter): Boolean {
        val value = name.trim().lowercase()
        val aliases = when(requested) {
            AdvancedSearchFilters.GenreFilter.ALL -> return true
            AdvancedSearchFilters.GenreFilter.COMEDY -> listOf("comedy","commedia")
            AdvancedSearchFilters.GenreFilter.ACTION -> listOf("action","azione")
            AdvancedSearchFilters.GenreFilter.ADVENTURE -> listOf("adventure","avventura")
            AdvancedSearchFilters.GenreFilter.ANIMATION -> listOf("animation","animazione","anime")
            AdvancedSearchFilters.GenreFilter.ROMANCE -> listOf("romance","romant")
            AdvancedSearchFilters.GenreFilter.DRAMA -> listOf("drama","dramma")
            AdvancedSearchFilters.GenreFilter.FANTASY -> listOf("fantasy","fantastico")
            AdvancedSearchFilters.GenreFilter.SCIENCE_FICTION -> listOf("science fiction","sci-fi","fantascienza")
            AdvancedSearchFilters.GenreFilter.CRIME -> listOf("crime","poliziesco")
            AdvancedSearchFilters.GenreFilter.HISTORY -> listOf("history","storico")
            AdvancedSearchFilters.GenreFilter.MYSTERY -> listOf("mystery","mistero")
            AdvancedSearchFilters.GenreFilter.MUSIC -> listOf("music","musicale")
            AdvancedSearchFilters.GenreFilter.WAR -> listOf("war","guerra")
            AdvancedSearchFilters.GenreFilter.DOCUMENTARY -> listOf("documentary","documentario")
            AdvancedSearchFilters.GenreFilter.FAMILY -> listOf("family","famiglia")
            AdvancedSearchFilters.GenreFilter.HORROR -> listOf("horror")
            AdvancedSearchFilters.GenreFilter.WESTERN -> listOf("western")
            AdvancedSearchFilters.GenreFilter.THRILLER -> listOf("thriller")
            AdvancedSearchFilters.GenreFilter.ACTION_ADVENTURE -> listOf("action & adventure","action and adventure","azione e avventura")
            AdvancedSearchFilters.GenreFilter.KIDS -> listOf("kids","bambini")
            AdvancedSearchFilters.GenreFilter.REALITY -> listOf("reality")
        }
        return value.isNotBlank() && aliases.any { value.contains(it) }
    }

    fun canLoadLocalMore(foundNew: Boolean, filtered: Boolean, scannedPage: Int): Boolean =
        foundNew || (filtered && scannedPage < 15)

    fun canLoadGlobalMore(nextPage: Int, consecutiveEmptyPages: Int, filtered: Boolean): Boolean =
        nextPage < 15 && consecutiveEmptyPages < if (filtered) 4 else 2
}

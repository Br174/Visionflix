package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter

data class AdvancedSearchFilters(
    val type: ContentType = ContentType.ALL,
    val italian: ItalianFilter = ItalianFilter.ALL,
    val genre: GenreFilter = GenreFilter.ALL,
    val year: Int? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val actorId: String? = null,
    val actorName: String = "",
    val directorId: String? = null,
    val directorName: String = "",
    val sort: SortFilter = SortFilter.POPULARITY,
    val platform: PlatformFilter = PlatformFilter.ALL,
) {
    enum class ContentType { ALL, MOVIE, TV, CARTOON }
    enum class ItalianFilter { ALL, YES, NO }

    enum class SortFilter(val label: String) {
        POPULARITY("Popolarità"),
        DATE("Data"),
        VOTE("Voto"),
    }

    enum class PlatformFilter(val label: String, val providerIds: String?) {
        ALL("Tutte", null),
        NETFLIX("Netflix", "8"),
        DISNEY_PLUS("Disney+", "337"),
        PRIME_VIDEO("Prime Video", "119"),
        APPLE_TV_PLUS("Apple TV+", "350"),
        SKY_NOW("Sky / NOW", "29|39"),
        HBO_MAX("HBO Max", "1899"),
    }

    enum class GenreFilter(
        val label: String,
        val movieId: Int?,
        val tvId: Int?,
    ) {
        ALL("Tutti", null, null),
        ACTION("Azione", 28, 10759),
        ADVENTURE("Avventura", 12, 10759),
        ANIMATION("Animazione", 16, 16),
        COMEDY("Commedia", 35, 35),
        CRIME("Crime", 80, 80),
        DOCUMENTARY("Documentario", 99, 99),
        DRAMA("Dramma", 18, 18),
        FAMILY("Famiglia", 10751, 10751),
        FANTASY("Fantasy", 14, 10765),
        MYSTERY("Mistero", 9648, 9648),
        SCIENCE_FICTION("Fantascienza", 878, 10765),
        WAR("Guerra", 10752, 10768),
        WESTERN("Western", 37, 37),
    }

    val isActive: Boolean
        get() = type != ContentType.ALL ||
            italian != ItalianFilter.ALL ||
            genre != GenreFilter.ALL ||
            year != null ||
            !dateFrom.isNullOrBlank() ||
            !dateTo.isNullOrBlank() ||
            !actorId.isNullOrBlank() ||
            !directorId.isNullOrBlank() ||
            sort != SortFilter.POPULARITY ||
            platform != PlatformFilter.ALL
}

interface AdvancedSearchProvider {
    suspend fun searchAdvanced(
        query: String,
        filters: AdvancedSearchFilters,
        page: Int = 1,
    ): List<AppAdapter.Item>
}

package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter

data class AdvancedSearchFilters(
    val type: ContentType = ContentType.ALL,
    val italian: ItalianFilter = ItalianFilter.ALL,
    val genre: GenreFilter = GenreFilter.ALL,
    val genres: Set<GenreFilter> = emptySet(), // OR across selected genres; AND with the other filters
    val year: Int? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val actorId: String? = null,
    val actorName: String = "",
    val directorId: String? = null,
    val directorName: String = "",
    val sort: SortFilter = SortFilter.POPULARITY,
    val platform: PlatformFilter = PlatformFilter.ALL,
    val country: CountryFilter = CountryFilter.ALL,
) {
    enum class ContentType { ALL, MOVIE, TV, CARTOON, ANIME }
    // LAB184: preserve persisted enum values while the two-entry UI now offers
    // YES = Italian-dubbed preference (default), NO = original-language option.
    // This selection never changes the independent production-country filter.
    enum class ItalianFilter { ALL, YES, NO }

    enum class SortFilter(val label: String) {
        POPULARITY("Popolarità"),
        DATE("Data"),
        VOTE("Voto"),
        OLDEST("Data crescente"),
    }

    enum class PlatformFilter(val label: String, val providerIds: String?) {
        ALL("Tutte", null),
        NETFLIX("Netflix", "8"),
        DISNEY_PLUS("Disney+", "337"),
        PRIME_VIDEO("Prime Video", "119"),
        APPLE_TV_PLUS("Apple TV+", "350"),
        SKY_NOW("Sky / NOW", "29|39"),
        HBO_MAX("HBO Max / Max", "384|1899"),
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
        ROMANCE("Romantico", 10749, null),
        HORROR("Horror", 27, null),
        THRILLER("Thriller", 53, null),
        HISTORY("Storico", 36, null),
        MUSIC("Musicale", 10402, null),
        ACTION_ADVENTURE("Azione e avventura", null, 10759),
        KIDS("Bambini", null, 10762),
        REALITY("Reality", null, 10764),
    }

    // ISO 3166-1 alpha-2. ALL first, Italy first among concrete countries.
    enum class CountryFilter(val label: String, val isoCode: String?) {
        ALL("Tutti i Paesi", null),
        ITALY("Italia", "IT"),
        USA("Stati Uniti", "US"),
        UK("Regno Unito", "GB"),
        FRANCE("Francia", "FR"),
        GERMANY("Germania", "DE"),
        SPAIN("Spagna", "ES"),
        CANADA("Canada", "CA"),
        AUSTRALIA("Australia", "AU"),
        JAPAN("Giappone", "JP"),
        SOUTH_KOREA("Corea del Sud", "KR"),
        CHINA("Cina", "CN"),
        INDIA("India", "IN"),
        BRAZIL("Brasile", "BR"),
        MEXICO("Messico", "MX"),
        ARGENTINA("Argentina", "AR"),
        TURKEY("Turchia", "TR"),
        SWEDEN("Svezia", "SE"),
        DENMARK("Danimarca", "DK"),
        NORWAY("Norvegia", "NO"),
        NETHERLANDS("Paesi Bassi", "NL"),
        BELGIUM("Belgio", "BE"),
    }

    val effectiveGenres: Set<GenreFilter>
        get() = genres.filterNot { it == GenreFilter.ALL }.toSet()
            .ifEmpty { if (genre == GenreFilter.ALL) emptySet() else setOf(genre) }

    /** Consistent genre matching for TMDb text search and multi-page discovery. */
    fun acceptsGenreIds(genreIds: Collection<Int>, movie: Boolean): Boolean {
        if ((type == ContentType.CARTOON || type == ContentType.ANIME) && 16 !in genreIds) return false
        val requested = effectiveGenres
        if (requested.isEmpty()) return true
        return requested.any { genre ->
            val id = if (movie) genre.movieId else genre.tvId
            id != null && id in genreIds
        }
    }

    val isActive: Boolean
        get() = type != ContentType.ALL ||
            italian != ItalianFilter.ALL ||
            genre != GenreFilter.ALL ||
            genres.isNotEmpty() ||
            year != null ||
            !dateFrom.isNullOrBlank() ||
            !dateTo.isNullOrBlank() ||
            !actorId.isNullOrBlank() ||
            !directorId.isNullOrBlank() ||
            sort != SortFilter.POPULARITY ||
            platform != PlatformFilter.ALL ||
            country != CountryFilter.ALL
}

interface AdvancedSearchProvider {
    suspend fun searchAdvanced(
        query: String,
        filters: AdvancedSearchFilters,
        page: Int = 1,
    ): List<AppAdapter.Item>
}

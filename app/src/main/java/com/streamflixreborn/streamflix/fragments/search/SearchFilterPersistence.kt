package com.streamflixreborn.streamflix.fragments.search

import android.content.Context
import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters

internal data class SavedSearchFilterState(
    val query: String = "",
    val globalSearch: Boolean = false,
    val type: AdvancedSearchFilters.ContentType = AdvancedSearchFilters.ContentType.ALL,
    val italian: AdvancedSearchFilters.ItalianFilter = AdvancedSearchFilters.ItalianFilter.ALL,
    val year: Int? = null,
    val fromYear: Int? = null,
    val toYear: Int? = null,
    val genre: AdvancedSearchFilters.GenreFilter = AdvancedSearchFilters.GenreFilter.ALL,
    val sort: AdvancedSearchFilters.SortFilter = AdvancedSearchFilters.SortFilter.POPULARITY,
    val platform: AdvancedSearchFilters.PlatformFilter = AdvancedSearchFilters.PlatformFilter.ALL,
    val actorId: String? = null,
    val actorName: String = "",
    val directorId: String? = null,
    val directorName: String = "",
)

internal object SearchFilterPersistence {
    private const val PREFS = "verlezza_search_filters_v1"

    fun load(context: Context): SavedSearchFilterState {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun <T : Enum<T>> enumValue(key: String, values: Array<T>, fallback: T): T {
            val raw = p.getString(key, null) ?: return fallback
            return values.firstOrNull { it.name == raw } ?: fallback
        }
        fun optionalInt(key: String): Int? = if (p.contains(key)) p.getInt(key, 0) else null
        fun optionalString(key: String): String? = p.getString(key, null)?.takeIf { it.isNotBlank() }
        return SavedSearchFilterState(
            query = p.getString("query", "").orEmpty(),
            globalSearch = p.getBoolean("global", false),
            type = enumValue("type", AdvancedSearchFilters.ContentType.values(), AdvancedSearchFilters.ContentType.ALL),
            italian = enumValue("italian", AdvancedSearchFilters.ItalianFilter.values(), AdvancedSearchFilters.ItalianFilter.ALL),
            year = optionalInt("year"),
            fromYear = optionalInt("from_year"),
            toYear = optionalInt("to_year"),
            genre = enumValue("genre", AdvancedSearchFilters.GenreFilter.values(), AdvancedSearchFilters.GenreFilter.ALL),
            sort = enumValue("sort", AdvancedSearchFilters.SortFilter.values(), AdvancedSearchFilters.SortFilter.POPULARITY),
            platform = enumValue("platform", AdvancedSearchFilters.PlatformFilter.values(), AdvancedSearchFilters.PlatformFilter.ALL),
            actorId = optionalString("actor_id"),
            actorName = p.getString("actor_name", "").orEmpty(),
            directorId = optionalString("director_id"),
            directorName = p.getString("director_name", "").orEmpty(),
        )
    }

    fun save(context: Context, state: SavedSearchFilterState) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        e.putString("query", state.query)
        e.putBoolean("global", state.globalSearch)
        e.putString("type", state.type.name)
        e.putString("italian", state.italian.name)
        e.putString("genre", state.genre.name)
        e.putString("sort", state.sort.name)
        e.putString("platform", state.platform.name)
        if (state.year == null) e.remove("year") else e.putInt("year", state.year)
        if (state.fromYear == null) e.remove("from_year") else e.putInt("from_year", state.fromYear)
        if (state.toYear == null) e.remove("to_year") else e.putInt("to_year", state.toYear)
        if (state.actorId.isNullOrBlank()) e.remove("actor_id") else e.putString("actor_id", state.actorId)
        e.putString("actor_name", state.actorName)
        if (state.directorId.isNullOrBlank()) e.remove("director_id") else e.putString("director_id", state.directorId)
        e.putString("director_name", state.directorName)
        e.apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

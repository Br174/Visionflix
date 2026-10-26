package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters

/**
 * Native genre routing: use Streamflix provider categories, NOT the missing
 * genres field on thin title/poster cards. A genre route is proof of category.
 */
internal object Lab171NativeGenrePolicy {
    fun matchingNativeIds(
        available: List<Genre>,
        requested: Set<AdvancedSearchFilters.GenreFilter>,
    ): List<String> = available.asSequence()
        .filter { genre ->
            requested.any { wanted ->
                Lab164SearchMatchPolicy.localGenreMatches(genre.name, wanted)
            }
        }
        .map { it.id.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}

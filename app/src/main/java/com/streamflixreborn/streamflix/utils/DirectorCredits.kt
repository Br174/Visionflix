package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.TMDb3.original

/**
 * One canonical director role for the poster row and person filmography.
 * Only TMDb credits explicitly marked Director are promoted to this role.
 * Source-only names are retained by the existing director text fallback.
 */
internal object DirectorCredits {
    private const val PERSON_PREFIX = "tmdb-director:"
    private const val MOVIE_PREFIX = "tmdb-directed-movie:"

    fun directorPersonId(id: String): Int? =
        id.takeIf { it.startsWith(PERSON_PREFIX) }
            ?.removePrefix(PERSON_PREFIX)?.toIntOrNull()?.takeIf { it > 0 }

    fun directedMovieId(id: String): Int? =
        id.takeIf { it.startsWith(MOVIE_PREFIX) }
            ?.removePrefix(MOVIE_PREFIX)?.toIntOrNull()?.takeIf { it > 0 }

    fun directorTiles(crew: List<TMDb3.Crew>?): List<People> =
        crew.orEmpty().asSequence()
            .filter { it.job.equals("Director", ignoreCase = true) }
            .filter { it.id > 0 && it.name.isNotBlank() }
            .distinctBy { it.id }
            .map {
                People(
                    id = PERSON_PREFIX + it.id,
                    name = it.name,
                    image = it.profilePath?.w500,
                )
            }.toList()

    fun reconcileWithSource(
        sourceDirectors: List<People>,
        verifiedDirectors: List<People>,
    ): List<People> {
        if (sourceDirectors.isEmpty()) return verifiedDirectors
        // A fuzzy movie-title match must not relabel an unrelated film's crew.
        val sourceNames = sourceDirectors.map { it.name.trim().lowercase(java.util.Locale.ROOT) }
            .filter { it.isNotBlank() }.toSet()
        if (sourceNames.isEmpty()) return emptyList()
        return verifiedDirectors.filter {
            it.name.trim().lowercase(java.util.Locale.ROOT) in sourceNames
        }
    }

    fun castFirst(directors: List<People>, actors: List<People>): List<People> {
        val verified = directors.filter { directorPersonId(it.id) != null }
            .distinctBy { directorPersonId(it.id) }
        if (verified.isEmpty()) return actors
        val directorIds = verified.mapNotNull { directorPersonId(it.id) }.toSet()
        // Preserve the actors' original billing order; avoid dual-role duplicates.
        return verified + actors.filterNot { actor ->
            actor.id.toIntOrNull()?.let(directorIds::contains) == true
        }
    }

    fun directedMovies(credits: List<TMDb3.Person.Credit.Movie>?): List<Movie> =
        credits.orEmpty().asSequence()
            .filter { it.job.equals("Director", ignoreCase = true) && it.id > 0 }
            .filter { !it.adult }
            .distinctBy { it.id }
            .map {
                Movie(
                    id = MOVIE_PREFIX + it.id,
                    title = it.title?.takeIf(String::isNotBlank)
                        ?: it.originalTitle.orEmpty(),
                    overview = it.overview,
                    released = it.releaseDate?.takeIf { date -> date.isNotBlank() },
                    rating = it.voteAverage.toDouble(),
                    poster = it.posterPath?.w500,
                    banner = it.backdropPath?.original,
                )
            }
            .filter { it.title.isNotBlank() }
            .sortedByDescending { it.released?.timeInMillis ?: Long.MIN_VALUE }
            .toList()
}

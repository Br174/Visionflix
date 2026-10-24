package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import org.junit.Assert.*
import org.junit.Test

/** LAB152: offline catalog contracts. No network or provider availability is assumed. */
class Lab152GlobalCatalogTest {
    private fun film(id: String, title: String, year: String, provider: String,
                     poster: String? = null): Movie =
        Movie(id = id, title = title, released = "$year-01-01", poster = poster).apply {
            providerName = provider
        }
    private fun tv(id: String, title: String, year: String, provider: String): TvShow =
        TvShow(id = id, title = title, released = "$year-01-01").apply {
            providerName = provider
        }
    private fun catalog(vararg groups: List<AppAdapter.Item>) =
        GlobalCatalogMerger.mergeItems(groups.toList())

    @Test fun sameMovieAcrossProvidersHasOnePosterButTwoSources() {
        val a = film("p1", "Interstellar", "2014", "A")
        val b = film("p2", "Interstellar", "2014", "B")
        val merged = catalog(listOf(a), listOf(b))
        assertEquals(1, merged.posters.size)
        assertEquals(2, merged.alternatives.values.single().size)
    }

    @Test fun movieAndTvWithSameTitleNeverMerge() {
        val merged = catalog(listOf(film("m", "Fargo", "1996", "A")),
            listOf(tv("t", "Fargo", "1996", "B")))
        assertEquals(2, merged.posters.size)
    }

    @Test fun remakesFromDifferentYearsAreNotCollapsed() {
        val merged = catalog(listOf(film("a", "Dune", "1984", "A")),
            listOf(film("b", "Dune", "2021", "B")))
        assertEquals(2, merged.posters.size)
    }

    @Test fun accentAndPunctuationDifferencesNormalize() {
        val merged = catalog(listOf(film("a", "L'Été", "2001", "A")),
            listOf(film("b", "L ete", "2001", "B")))
        assertEquals(1, merged.posters.size)
    }

    @Test fun absentYearAndImdbDoesNotRiskFalseMerge() {
        val a = Movie(id = "a", title = "Unknown").apply { providerName = "A" }
        val b = Movie(id = "b", title = "Unknown").apply { providerName = "B" }
        assertEquals(2, catalog(listOf(a), listOf(b)).posters.size)
    }

    @Test fun realImdbIdentityWorksWithoutReleaseDate() {
        val a = Movie(id = "a", title = "Example", imdbId = "tt1234567").apply { providerName = "A" }
        val b = Movie(id = "b", title = "Example", imdbId = "tt1234567").apply { providerName = "B" }
        assertEquals(1, catalog(listOf(a), listOf(b)).posters.size)
    }

    @Test fun highestConfidencePosterWins() {
        val a = film("a", "Title", "2020", "A")
        val b = film("b", "Title", "2020", "B", "https://images.test/w500/example.jpg")
        assertSame(b, catalog(listOf(a), listOf(b)).posters.single())
    }

    @Test fun marginalPosterChangesDoNotDisruptPreviousWall() {
        val a = film("a", "Title", "2020", "A", "https://images.test/w500/a.jpg")
        val before = catalog(listOf(a))
        val b = film("b", "Title", "2020", "B", "https://images.test/original/b.jpg")
        val after = GlobalCatalogMerger.mergeItems(listOf(listOf(a), listOf(b)), before)
        assertSame(a, after.posters.single())
    }

    @Test fun laterProvidersAppendWithoutReorderingDisplayedPosters() {
        val first = film("a", "Alpha", "2020", "A")
        val later = film("b", "Beta", "2021", "B")
        val prior = catalog(listOf(later))
        val updated = GlobalCatalogMerger.mergeItems(listOf(listOf(first), listOf(later)), prior)
        assertEquals(listOf("Beta", "Alpha"),
            updated.posters.filterIsInstance<Movie>().map { it.title })
    }

    @Test fun duplicateResponseFromSameSourceIsNotDoubleCounted() {
        val a = film("a", "Film", "2020", "A")
        val merged = catalog(listOf(a, a, a))
        assertEquals(1, merged.posters.size)
        assertEquals(1, merged.alternatives.values.single().size)
    }

    @Test fun manySourcesStillProduceOneUniquePosterPerFilm() {
        val groups = (1..10).map { providerIndex ->
            (1..50).map { film("m$providerIndex:$it", "Film $it", "2020", "P$providerIndex") as AppAdapter.Item }
        }
        val merged = GlobalCatalogMerger.mergeItems(groups)
        assertEquals(50, merged.posters.size)
        assertEquals(500, merged.alternatives.values.sumOf { it.size })
    }
}

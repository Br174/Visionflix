package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.People
import org.junit.Assert.*
import org.junit.Test

/** Offline regression tests for LAB158: a director is never an acting credit. */
class DirectorCreditsTest {
    private fun crew(id: Int, job: String, name: String = "Persona $id") =
        TMDb3.Crew(id = id, creditId = "credit-$id", name = name, job = job)

    private fun movieCredit(
        id: Int,
        job: String?,
        title: String,
        date: String? = "2020-01-01",
        adult: Boolean = false,
    ) = TMDb3.Person.Credit.Movie(
        posterPath = null,
        overview = "Descrizione",
        releaseDate = date,
        genreIds = emptyList(),
        id = id,
        originalLanguage = "it",
        title = title,
        backdropPath = null,
        popularity = 0f,
        voteAverage = 0f,
        voteCount = 0,
        job = job,
        adult = adult,
    )

    @Test fun onlyVerifiedDirectorsAppearInTheDirectorSlot() {
        val tiles = DirectorCredits.directorTiles(
            listOf(
                crew(10, "Producer"),
                crew(30, "Director", "Regista Uno"),
                crew(30, "director", "Regista Uno"),
                crew(31, "Actor"),
                crew(32, "Director", "Regista Due"),
            )
        )
        assertEquals(listOf("Regista Uno", "Regista Due"), tiles.map { it.name })
        assertEquals(listOf(30, 32), tiles.mapNotNull { DirectorCredits.directorPersonId(it.id) })
    }

    @Test fun directorGoesFirstAndActorsKeepBillingOrder() {
        val director = DirectorCredits.directorTiles(listOf(crew(50, "Director"))).single()
        val actors = listOf(People("101", "Primo"), People("102", "Secondo"), People("103", "Terzo"))
        assertEquals(listOf("Persona 50", "Primo", "Secondo", "Terzo"),
            DirectorCredits.castFirst(listOf(director), actors).map { it.name })
        assertEquals(listOf("Primo", "Secondo", "Terzo"), actors.map { it.name })
    }

    @Test fun performerWhoAlsoDirectedAppearsOnlyOnce() {
        val director = DirectorCredits.directorTiles(listOf(crew(50, "Director"))).single()
        val actors = listOf(People("50", "Anche attore"), People("102", "Altra attrice"))
        assertEquals(listOf("Persona 50", "Altra attrice"),
            DirectorCredits.castFirst(listOf(director), actors).map { it.name })
    }

    @Test fun fallbackDoesNotPromoteAnUnverifiedProviderPerson() {
        val sourceDirector = People("provider-person-123", "Non verificato")
        val actors = listOf(People("1", "Attore"))
        assertEquals(listOf("Attore"),
            DirectorCredits.castFirst(listOf(sourceDirector), actors).map { it.name })
    }

    @Test fun onlyDirectorJobIsAcceptedAndRepeatedMoviesAreDeduplicated() {
        val credits = listOf(
            movieCredit(1, "Actor", "Recitato"),
            movieCredit(2, "Director", "Diretto 2020", "2020-01-01"),
            movieCredit(2, "Director", "Diretto 2020", "2020-01-01"),
            movieCredit(3, "director", "Diretto 2024", "2024-01-01"),
            movieCredit(4, "Writer", "Sceneggiatura"),
            movieCredit(5, "Director", "Solo adulti", adult = true),
        )
        val result = DirectorCredits.directedMovies(credits)
        assertEquals(listOf("Diretto 2024", "Diretto 2020"), result.map { it.title })
        assertEquals(listOf(3, 2), result.mapNotNull { DirectorCredits.directedMovieId(it.id) })
    }

    @Test fun conflictingSourceDirectorNamesCannotBeMislabeled() {
        val source = listOf(People("provider-9", "Vero regista"))
        val unrelated = DirectorCredits.directorTiles(
            listOf(crew(13, "Director", "Altro regista"))
        )
        assertTrue(DirectorCredits.reconcileWithSource(source, unrelated).isEmpty())
        assertEquals("Vero regista",
            DirectorCredits.reconcileWithSource(source,
                DirectorCredits.directorTiles(listOf(crew(14, "Director", "Vero regista")))
            ).single().name)
        assertEquals(unrelated, DirectorCredits.reconcileWithSource(emptyList(), unrelated))
    }

    @Test fun mismatchedImdbOrReleaseYearNeverPromotesWrongDirector() {
        assertFalse(DirectorCredits.matchesSourceMovie("tt1234567", "tt7654321", 2020, 2020))
        assertFalse(DirectorCredits.matchesSourceMovie("tt1234567", null, 2020, 2020))
        assertFalse(DirectorCredits.matchesSourceMovie(null, null, 2020, 2024))
        assertTrue(DirectorCredits.matchesSourceMovie("tt1234567", "tt1234567", 2020, 2021))
        assertTrue(DirectorCredits.matchesSourceMovie(null, null, 2020, 2020))
    }

    @Test fun directorOnlyCastAndNoDirectorFallbackRemainStable() {
        val actors = listOf(People("601", "Primo attore"), People("602", "Secondo attore"))
        assertEquals(actors, DirectorCredits.castFirst(emptyList(), actors))
        val directors = DirectorCredits.directorTiles(
            listOf(crew(700, "Director", "Prima regista"), crew(701, "Director", "Secondo regista"))
        )
        assertEquals(listOf("Prima regista", "Secondo regista"),
            DirectorCredits.castFirst(directors, emptyList()).map { it.name })
        assertEquals(emptyList<People>(), DirectorCredits.directorTiles(null))
    }

    @Test fun nestedTmdbFilmActorFilmAndSeriesKeepTheirCatalogIdentity() {
        val actorId = DirectorCredits.actorIdentity(135)
        assertEquals(135, DirectorCredits.actorPersonId(actorId))
        assertNull(DirectorCredits.actorPersonId("135"))
        assertNull(DirectorCredits.actorPersonId("tmdb-actor:0"))
        assertNull(DirectorCredits.actorPersonId("tmdb-actor:abc"))

        val source = listOf(
            com.streamflixreborn.streamflix.models.Movie(id = "902", title = "Film da attore"),
            com.streamflixreborn.streamflix.models.TvShow(id = "903", title = "Serie da attore"),
        )
        val routed = DirectorCredits.preserveCatalogFilmography(source)
        fun ids(list: List<com.streamflixreborn.streamflix.models.Show>): List<String> =
            list.map { item ->
                when (item) {
                    is com.streamflixreborn.streamflix.models.Movie -> item.id
                    is com.streamflixreborn.streamflix.models.TvShow -> item.id
                }
            }
        val routedIds = ids(routed)
        assertEquals(listOf("tmdb-movie:902", "tmdb-tv:903"), routedIds)
        assertEquals(902, DirectorCredits.catalogMovieId(routedIds[0]))
        assertEquals(903, DirectorCredits.catalogTvId(routedIds[1]))
        assertEquals(listOf("902", "903"), ids(source))
        assertNull(DirectorCredits.catalogMovieId("tmdb-movie:oops"))
        assertNull(DirectorCredits.catalogTvId("903"))
    }

    @Test fun malformedAndUnverifiedIdsAreRejected() {
        assertNull(DirectorCredits.directorPersonId("director-abc"))
        assertNull(DirectorCredits.directorPersonId("tmdb-director:0"))
        assertNull(DirectorCredits.directorPersonId("tmdb-director:abc"))
        assertNull(DirectorCredits.directedMovieId("tmdb-directed-movie:abc"))
        assertNull(DirectorCredits.directedMovieId("23"))
        assertTrue(DirectorCredits.directedMovies(null).isEmpty())
    }
}

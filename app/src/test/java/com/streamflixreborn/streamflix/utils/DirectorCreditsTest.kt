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

    @Test fun malformedAndUnverifiedIdsAreRejected() {
        assertNull(DirectorCredits.directorPersonId("director-abc"))
        assertNull(DirectorCredits.directorPersonId("tmdb-director:0"))
        assertNull(DirectorCredits.directorPersonId("tmdb-director:abc"))
        assertNull(DirectorCredits.directedMovieId("tmdb-directed-movie:abc"))
        assertNull(DirectorCredits.directedMovieId("23"))
        assertTrue(DirectorCredits.directedMovies(null).isEmpty())
    }
}

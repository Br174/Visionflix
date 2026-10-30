package com.streamflixreborn.streamflix.providers

import org.junit.Assert.*
import org.junit.Test

/** Source rules, not mock green smoke: exact selected metadata contracts. */
class Lab190CollectionPolicyTest {
    @Test fun eightVisibleChoicesPlusResetChoice() {
        assertEquals(9, AdvancedSearchFilters.CollectionFilter.entries.size)
        assertEquals("Al cinema", AdvancedSearchFilters.CollectionFilter.IN_CINEMA.label)
        assertEquals("Nomination Oscar 2026", AdvancedSearchFilters.CollectionFilter.OSCARS_2026.label)
        assertEquals("Natale", AdvancedSearchFilters.CollectionFilter.CHRISTMAS.label)
        assertFalse(AdvancedSearchFilters().isActive)
        assertTrue(AdvancedSearchFilters(collection =
            AdvancedSearchFilters.CollectionFilter.OSCARS_2026).isActive)
    }

    @Test fun productionCompaniesAreNotGuessedFromTitles() {
        assertEquals("3", Lab190CollectionPolicy.discoverParams(
            AdvancedSearchFilters.CollectionFilter.PIXAR)["with_companies"])
        assertEquals("2", Lab190CollectionPolicy.discoverParams(
            AdvancedSearchFilters.CollectionFilter.DISNEY)["with_companies"])
        assertEquals("420", Lab190CollectionPolicy.discoverParams(
            AdvancedSearchFilters.CollectionFilter.MARVEL)["with_companies"])
        assertEquals("9993|429|128064", Lab190CollectionPolicy.discoverParams(
            AdvancedSearchFilters.CollectionFilter.DC)["with_companies"])
    }

    @Test fun christmasIsOneThemeAndCinemaHasItalianReleaseRange() {
        assertEquals("65", Lab190CollectionPolicy.discoverParams(
            AdvancedSearchFilters.CollectionFilter.CHRISTMAS)["with_keywords"])
        val params = Lab190CollectionPolicy.discoverParams(
            AdvancedSearchFilters.CollectionFilter.IN_CINEMA)
        assertEquals("IT", params["region"])
        assertEquals("2|3", params["with_release_type"])
        assertNotNull(params["release_date.gte"])
        assertNotNull(params["release_date.lte"])
    }

    @Test fun oscars2026HasDocumentedNomineesNotRandom2026Movies() {
        assertTrue(Lab190CollectionPolicy.isCuratedOscar(
            AdvancedSearchFilters.CollectionFilter.OSCARS_2026, "Hamnet", "Hamnet", 2025))
        assertTrue(Lab190CollectionPolicy.isCuratedOscar(
            AdvancedSearchFilters.CollectionFilter.OSCARS_2026, "Sinners", "I peccatori", 2025))
        assertFalse(Lab190CollectionPolicy.isCuratedOscar(
            AdvancedSearchFilters.CollectionFilter.OSCARS_2026, "Dune Part Two", "", 2024))
    }

    @Test fun historicalWinnersSpanNearlyACentury() {
        val winners = Lab190OscarPrimaryWinners.entries
        assertTrue(winners.size >= 300)
        assertTrue(winners.any { (year, name) -> year == 1929 && name == "Wings" })
        assertTrue(winners.any { (year, name) -> year == 2026 &&
            name == "One Battle after Another" })
        assertTrue(Lab190CollectionPolicy.isCuratedOscar(
            AdvancedSearchFilters.CollectionFilter.OSCAR_WINNERS, "Anora", "Anora", 2024))
        assertFalse(Lab190CollectionPolicy.isCuratedOscar(
            AdvancedSearchFilters.CollectionFilter.OSCAR_WINNERS, "Anora", "Anora", 1960))
    }

    @Test fun noUnverifiedDiscoverIdMayPassCollections() {
        assertFalse(Lab190CollectionPolicy.matches(
            AdvancedSearchFilters.CollectionFilter.CHRISTMAS, 123, "Example",
            "Example", 2023, emptySet()))
        assertTrue(Lab190CollectionPolicy.matches(
            AdvancedSearchFilters.CollectionFilter.CHRISTMAS, 123, "Example",
            "Example", 2023, setOf(123)))
        assertTrue(Lab190CollectionPolicy.matches(
            AdvancedSearchFilters.CollectionFilter.ALL, 123, "Example",
            "Example", 2023, emptySet()))
    }
}

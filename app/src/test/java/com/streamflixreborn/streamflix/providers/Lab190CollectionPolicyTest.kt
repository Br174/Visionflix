package com.streamflixreborn.streamflix.providers

import org.junit.Assert.*
import org.junit.Test
import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.CollectionFilter as C
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Lab190CollectionPolicyTest {
    private val p = Lab190CollectionPolicy

    @Test fun eightNamedCollectionsAndAllChoice() {
        assertEquals(9, C.entries.size)
        val names = C.entries.map { it.label }
        assertEquals(listOf("Tutte le collezioni", "Al cinema", "Nomination Oscar 2026",
            "Oscar", "Natale", "Pixar", "Disney", "DC", "Marvel"), names)
        assertTrue(AdvancedSearchFilters(collection = C.CHRISTMAS).isActive)
    }

    @Test fun oscarNamesAreEvidenceBasedAndYearBound() {
        assertTrue(p.matchesMovie(C.OSCAR_WINNERS, 872585, "Oppenheimer", "Oppenheimer", "2023-07-19"))
        assertTrue(p.matchesMovie(C.OSCAR_WINNERS, 0, "Hamnet", "Hamnet", "2025-11-21"))
        assertFalse(p.matchesMovie(C.OSCAR_WINNERS, 0, "Hamnet", "Hamnet", "2010-01-01"))
        assertTrue(p.matchesMovie(C.OSCAR_2026, 1, "Bugonia", "Bugonia", "2025-10-01"))
        assertFalse(p.matchesMovie(C.OSCAR_2026, 1, "Bugonia", "Bugonia", "2024-10-01"))
    }

    @Test fun studioIdsDoNotInventBrandMembership() {
        assertTrue(p.matchesMovie(C.PIXAR, 1, "Test", "Test", "2020", setOf(3)))
        assertTrue(p.matchesMovie(C.DISNEY, 1, "Test", "Test", "2020", setOf(2)))
        assertTrue(p.matchesMovie(C.DISNEY, 1, "Test", "Test", "2020", setOf(3)))
        assertTrue(p.matchesMovie(C.DC, 1, "Test", "Test", "2020", setOf(429)))
        assertTrue(p.matchesMovie(C.MARVEL, 1, "Test", "Test", "2020", setOf(420)))
        assertFalse(p.matchesMovie(C.MARVEL, 1, "Test", "Test", "2020", setOf(429)))
    }

    @Test fun christmasSettingWorksWithoutTitleChristmas() {
        assertTrue(p.matchesMovie(C.CHRISTMAS, 7, "Die Hard", "Die Hard", "1988"))
        assertTrue(p.matchesMovie(C.CHRISTMAS, 8, "Other title", "Other title", "2014",
            keywords = setOf(207317)))
        assertFalse(p.matchesMovie(C.CHRISTMAS, 9, "A random summer film", "Test", "2014"))
    }

    @Test fun cinemaRequiresVerifiedItalyTheatricalDates() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        assertTrue(p.matchesMovie(C.IN_CINEMA, 17, "Test", "Test", today,
            theatricalIt = listOf(today)))
        assertFalse(p.matchesMovie(C.IN_CINEMA, 17, "Test", "Test", today))
        assertFalse(p.matchesMovie(C.IN_CINEMA, 17, "Test", "Test", today,
            theatricalIt = listOf("2000-01-01")))
    }

    @Test fun sourceMetadataGuardPreservesOtherFilters() {
        val f = AdvancedSearchFilters(collection = C.PIXAR,
            year = 2020, country = AdvancedSearchFilters.CountryFilter.ITALY)
        assertEquals(2020, f.year)
        assertEquals(AdvancedSearchFilters.CountryFilter.ITALY, f.country)
        assertTrue(f.isActive)
    }
}

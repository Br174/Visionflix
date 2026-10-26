package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.GenreFilter
import org.junit.Assert.*
import org.junit.Test

class Lab171NativeGenrePolicyTest {
    @Test fun westernCardsWithoutGenreFieldsUseSourceCategory() {
        val native = listOf(
            Genre(id="western-genre", name="Western"),
            Genre(id="comedy-genre", name="Commedia"),
        )
        assertEquals(listOf("western-genre"),
            Lab171NativeGenrePolicy.matchingNativeIds(native, setOf(GenreFilter.WESTERN)))
        // A provider can return just (title, poster) from getGenre: category
        // choice itself verifies the requested genre.
    }

    @Test fun multilingualNativeGenresAndOrFilters() {
        val native = listOf(
            Genre(id="action-id", name="Azione"),
            Genre(id="romance-id", name="Romantico"),
            Genre(id="west-id", name="Western"),
        )
        assertEquals(listOf("action-id", "west-id"),
            Lab171NativeGenrePolicy.matchingNativeIds(
                native, setOf(GenreFilter.ACTION, GenreFilter.WESTERN)))
        assertEquals(listOf("romance-id"),
            Lab171NativeGenrePolicy.matchingNativeIds(native, setOf(GenreFilter.ROMANCE)))
    }

    @Test fun doNotInventNativeCategoryWhenProviderDoesNotAdvertiseIt() {
        assertTrue(Lab171NativeGenrePolicy.matchingNativeIds(
            listOf(Genre(id="action", name="Azione")),
            setOf(GenreFilter.WESTERN)).isEmpty())
    }
}

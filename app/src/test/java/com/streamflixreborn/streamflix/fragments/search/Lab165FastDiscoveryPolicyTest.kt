package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.providers.AdvancedSearchFilters.SortFilter
import org.junit.Assert.*
import org.junit.Test

class Lab165FastDiscoveryPolicyTest {
    private fun select(sort: SortFilter, query: String = "", actor: String? = null,
        director: String? = null, page: Int = 1, lookup: Boolean = true) =
        Lab165FastDiscoveryPolicy.metadataSort(sort, query, actor, director, page, lookup)

    @Test fun firstPageNoTextUsesAvailablePopularityCandidatesForEveryDisplaySort() {
        for (sort in SortFilter.entries) {
            assertEquals(SortFilter.POPULARITY, select(sort))
        }
    }

    @Test fun directTmdbResultsKeepTrueRequestedChronologicalOrder() {
        assertEquals(SortFilter.DATE, select(SortFilter.DATE, lookup = false))
        assertEquals(SortFilter.OLDEST, select(SortFilter.OLDEST, lookup = false))
        assertEquals(SortFilter.VOTE, select(SortFilter.VOTE, lookup = false))
    }

    @Test fun laterPagesExploreChosenChronologicalOrVoteOrder() {
        assertEquals(SortFilter.DATE, select(SortFilter.DATE, page = 2))
        assertEquals(SortFilter.OLDEST, select(SortFilter.OLDEST, page = 2))
        assertEquals(SortFilter.VOTE, select(SortFilter.VOTE, page = 3))
    }

    @Test fun directorAndActorNestedFlowsAreNeverRerouted() {
        assertEquals(SortFilter.OLDEST, select(SortFilter.OLDEST, director = "512"))
        assertEquals(SortFilter.DATE, select(SortFilter.DATE, actor = "1001"))
        assertEquals(48, Lab165FastDiscoveryPolicy.candidateLimit(48, "", "1001", null, 1))
        assertEquals(48, Lab165FastDiscoveryPolicy.candidateLimit(48, "", null, "512", 1))
    }

    @Test fun freeTextKeepsExistingSearchStrategy() {
        assertEquals(SortFilter.OLDEST, select(SortFilter.OLDEST, query = "francia"))
        assertEquals(24, Lab165FastDiscoveryPolicy.candidateLimit(24, "francia", null, null, 1))
    }

    @Test fun noTextFirstPageLimitsFanOutButDoesNotDropSecondPageOrPeople() {
        assertEquals(12, Lab165FastDiscoveryPolicy.candidateLimit(24, "", null, null, 1))
        assertEquals(12, Lab165FastDiscoveryPolicy.candidateLimit(12, "", null, null, 1))
        assertEquals(32, Lab165FastDiscoveryPolicy.candidateLimit(32, "", null, null, 2))
    }
}

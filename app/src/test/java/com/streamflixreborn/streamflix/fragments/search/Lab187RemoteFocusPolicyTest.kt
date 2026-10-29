package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.fragments.search.Lab187RemoteFocusPolicy.Route as R
import com.streamflixreborn.streamflix.fragments.search.Lab187RemoteFocusPolicy.Action as A
import org.junit.Assert.assertEquals
import org.junit.Test

class Lab187RemoteFocusPolicyTest {
    private val p = Lab187RemoteFocusPolicy

    @Test fun sideArrowOneOkOpensAndFocusesSearch() {
        assertEquals(A.OPEN_AND_FOCUS_SEARCH, p.action(R.SIDE_ARROW_OK, false, true))
        assertEquals(A.OPEN_AND_FOCUS_SEARCH, p.action(R.SIDE_ARROW_OK, true, true))
    }
    @Test fun firstUpFocusesSearchAndSecondUpExpands() {
        assertEquals(A.FOCUS_SEARCH, p.action(R.POSTER_TOP_UP, false, true))
        assertEquals(A.EXPAND_KEEP_SEARCH, p.action(R.SEARCH_UP, false, true))
        assertEquals(A.NATIVE_NAVIGATION, p.action(R.SEARCH_UP, true, true))
    }
    @Test fun downFromFooterClosesAtTheSameTimeAsPosterFocus() {
        assertEquals(A.CLOSE_AND_FOCUS_POSTERS, p.action(R.FOOTER_DOWN, true, true))
        assertEquals(A.CLOSE_AND_FOCUS_POSTERS, p.action(R.FOOTER_DOWN, false, true))
        assertEquals(A.NATIVE_NAVIGATION, p.action(R.FOOTER_DOWN, true, false))
    }
}
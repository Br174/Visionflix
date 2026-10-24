package com.streamflixreborn.streamflix.fragments.search

import org.junit.Assert.*
import org.junit.Test

/** Remote navigation rules: upward/right edge clamps, no accidental escapes. */
class Lab159DrawerFocusPolicyTest {
    @Test fun topIsSealedAndRightCannotEscapeByDpad() {
        assertFalse(Lab159DrawerFocusPolicy.mayExit(Lab159DrawerFocusPolicy.Border.TOP))
        assertFalse(Lab159DrawerFocusPolicy.mayExit(Lab159DrawerFocusPolicy.Border.RIGHT))
        assertFalse(Lab159DrawerFocusPolicy.mayExit(Lab159DrawerFocusPolicy.Border.TOP, true))
    }
    @Test fun sideRailAndPostersRemainAccessible() {
        assertTrue(Lab159DrawerFocusPolicy.mayExit(Lab159DrawerFocusPolicy.Border.LEFT))
        assertTrue(Lab159DrawerFocusPolicy.mayExit(Lab159DrawerFocusPolicy.Border.BOTTOM))
    }
    @Test fun arrowIsOnlyExplicitRightSideEscape() {
        assertTrue(Lab159DrawerFocusPolicy.mayExit(Lab159DrawerFocusPolicy.Border.RIGHT, true))
    }
}

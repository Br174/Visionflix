package com.streamflixreborn.streamflix.fragments.search

import org.junit.Assert.*
import org.junit.Test

class Lab157FocusPolicyTest {
    @Test fun activelyFocusedPosterCanNeverBeOverridden() {
        assertFalse(Lab157FocusPolicy.canRestoreSelection(true, 95, 200))
        assertFalse(Lab157FocusPolicy.canCompleteDeferredFocus(true, true, 95, 95))
    }

    @Test fun explicitReturnFromHeaderCanRestoreAValidRow() {
        assertTrue(Lab157FocusPolicy.canRestoreSelection(false, 95, 200))
        assertTrue(Lab157FocusPolicy.canCompleteDeferredFocus(true, false, 95, 95))
    }

    @Test fun delayedCallbackCannotOverrideLaterNavigation() {
        assertFalse(Lab157FocusPolicy.canCompleteDeferredFocus(true, false, 95, 30))
    }

    @Test fun noFocusWhenViewIsDestroyed() {
        assertFalse(Lab157FocusPolicy.canCompleteDeferredFocus(false, false, 95, 95))
    }

    @Test fun outOfRangeOrEmptyPosterGridCannotRestore() {
        assertFalse(Lab157FocusPolicy.canRestoreSelection(false, -1, 200))
        assertFalse(Lab157FocusPolicy.canRestoreSelection(false, 200, 200))
        assertFalse(Lab157FocusPolicy.canRestoreSelection(false, 0, 0))
    }

    @Test fun firstAndLastPosterRemainSelectable() {
        assertTrue(Lab157FocusPolicy.canRestoreSelection(false, 0, 200))
        assertTrue(Lab157FocusPolicy.canRestoreSelection(false, 199, 200))
    }
}

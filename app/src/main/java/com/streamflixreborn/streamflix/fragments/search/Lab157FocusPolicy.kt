package com.streamflixreborn.streamflix.fragments.search

/**
 * Single rule for deferred search-wall focus restoration.
 * Automatic callbacks never override an already focused poster.
 */
internal object Lab157FocusPolicy {
    fun canRestoreSelection(
        wallHasFocus: Boolean,
        requestedPosition: Int,
        posterCount: Int,
    ): Boolean = !wallHasFocus && requestedPosition >= 0 && requestedPosition < posterCount

    fun canCompleteDeferredFocus(
        isViewActive: Boolean,
        wallHasFocus: Boolean,
        requestedPosition: Int,
        currentPosition: Int,
    ): Boolean = isViewActive && !wallHasFocus && requestedPosition == currentPosition
}

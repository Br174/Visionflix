package com.streamflixreborn.streamflix.fragments.search

/** LAB159 drawer boundary contract. All interactive controls share these exits. */
internal object Lab159DrawerFocusPolicy {
    enum class Border { TOP, RIGHT, LEFT, BOTTOM }

    fun mayExit(border: Border, arrowActivated: Boolean = false): Boolean = when (border) {
        Border.TOP -> false
        Border.RIGHT -> arrowActivated
        Border.LEFT, Border.BOTTOM -> true
    }
}

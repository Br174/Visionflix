package com.streamflixreborn.streamflix.fragments.search

/** Explicit TV DPAD contract: never mutate poster order or filter values. */
internal object Lab187RemoteFocusPolicy {
    enum class Route { SIDE_ARROW_OK, POSTER_TOP_UP, SEARCH_UP, FOOTER_DOWN }
    enum class Action {
        OPEN_AND_FOCUS_SEARCH, FOCUS_SEARCH, EXPAND_KEEP_SEARCH,
        CLOSE_AND_FOCUS_POSTERS, NATIVE_NAVIGATION,
    }
    fun action(route: Route, panelExpanded: Boolean, hasPosters: Boolean): Action =
        when (route) {
            Route.SIDE_ARROW_OK -> Action.OPEN_AND_FOCUS_SEARCH
            Route.POSTER_TOP_UP -> Action.FOCUS_SEARCH
            Route.SEARCH_UP -> if (panelExpanded) Action.NATIVE_NAVIGATION
                else Action.EXPAND_KEEP_SEARCH
            Route.FOOTER_DOWN -> if (hasPosters) Action.CLOSE_AND_FOCUS_POSTERS
                else Action.NATIVE_NAVIGATION
        }
}
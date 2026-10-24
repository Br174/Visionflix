package com.streamflixreborn.streamflix.fragments.search

/**
 * HTTP handling for Search. A legal restriction is never a reason to retry a
 * provider repeatedly, terminate navigation, or attempt to bypass the block.
 */
internal object Lab155SearchSafety {
    fun isRestricted(statusCode: Int): Boolean = statusCode == 451

    fun canRefreshMissingEndpoint(statusCode: Int, endpointManaged: Boolean): Boolean =
        statusCode == 404 && endpointManaged

    fun explainRestrictedProvider(providerName: String): String =
        "$providerName: contenuto non disponibile per restrizioni del provider " +
            "(HTTP 451). Cerca resta aperta. Prova un'altra fonte autorizzata."
}

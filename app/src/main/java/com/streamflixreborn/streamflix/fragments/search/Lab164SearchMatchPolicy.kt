package com.streamflixreborn.streamflix.fragments.search

import kotlin.math.abs

/**
 * LAB164: single shared rule for metadata matching and sparse filtered pagination.
 * Pure functions allow no-network regression checks before a signed Android build.
 */
internal object Lab164SearchMatchPolicy {
    /**
     * Provider year may refer to a localized release, while TMDb stores the original.
     * Never merge unrelated remakes separated by decades or cross movie/TV boundaries.
     */
    fun matchesMetadata(itemKey: String, metadataKeys: Set<String>): Boolean {
        val kind = itemKey.substringBefore('|')
        val title = itemKey.substringAfter('|', "").substringBeforeLast('|', "")
        val year = itemKey.substringAfterLast('|').toIntOrNull()
        if (title.isBlank() || kind !in setOf("movie", "tv")) return false
        if (itemKey in metadataKeys) return true
        return metadataKeys.any { key ->
            val candidateKind = key.substringBefore('|')
            val candidateTitle = key.substringAfter('|', "").substringBeforeLast('|', "")
            val candidateYear = key.substringAfterLast('|').toIntOrNull()
            candidateKind == kind && candidateTitle == title &&
                (year == null || candidateYear == null ||
                    abs(year - candidateYear) <= if (kind == "tv") 3 else 1)
        }
    }

    fun canLoadLocalMore(foundNew: Boolean, filtered: Boolean, scannedPage: Int): Boolean =
        foundNew || (filtered && scannedPage < 15)

    fun canLoadGlobalMore(nextPage: Int, consecutiveEmptyPages: Int, filtered: Boolean): Boolean =
        nextPage < 15 && consecutiveEmptyPages < if (filtered) 4 else 2
}

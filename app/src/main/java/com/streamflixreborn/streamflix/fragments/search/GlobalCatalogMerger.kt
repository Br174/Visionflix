package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/**
 * LAB152: one poster per film/series, independent of the search engine.
 * Keep every real provider candidate for playback routing and diagnostics.
 * Deliberately never merge a movie with a TV show or distinct known years.
 */
object GlobalCatalogMerger {
    data class Catalog(
        val posters: List<AppAdapter.Item>,
        val alternatives: Map<String, List<AppAdapter.Item>>,
    )

    fun merge(results: List<ProviderResult>, previous: Catalog? = null): Catalog {
        val buckets = linkedMapOf<String, MutableList<AppAdapter.Item>>()
        results.forEach { result ->
            val items = (result.state as? ProviderResult.State.Success)?.results ?: return@forEach
            items.forEach { item ->
                if (item !is Movie && item !is TvShow) return@forEach
                val key = identity(item)
                // Do not mistake duplicated responses from the same provider for alternatives.
                val bucket = buckets.getOrPut(key) { mutableListOf() }
                if (bucket.none { candidate ->
                        candidate.javaClass == item.javaClass &&
                            provider(candidate) == provider(item) &&
                            itemId(candidate) == itemId(item)
                    }) bucket.add(item)
            }
        }
        val previousByKey = previous?.posters.orEmpty().associateBy(::identity)
        val existingOrder = previous?.posters.orEmpty()
            .mapIndexed { index, item -> identity(item) to index }.toMap()
        val initialOrder = buckets.keys.withIndex().associate { it.value to it.index }
        val stableKeys = buckets.keys.sortedWith(compareBy<String> { existingOrder[it] ?: Int.MAX_VALUE }
            .thenBy { initialOrder[it] ?: Int.MAX_VALUE })
        val selected = stableKeys.mapNotNull { key ->
            val entries = buckets[key].orEmpty()
            val best = entries.maxByOrNull(::posterQuality) ?: return@mapNotNull null
            val prior = previousByKey[key]
            // Avoid poster swaps and focus jumps for marginal metadata differences.
            val sameSource = entries.firstOrNull {
                itemId(it) == prior?.let(::itemId) && provider(it) == prior?.let(::provider)
            }
            if (sameSource != null && posterQuality(best) - posterQuality(sameSource) < 20) sameSource else best
        }
        return Catalog(selected, buckets.mapValues { it.value.toList() })
    }

    fun identity(item: AppAdapter.Item): String {
        val kind = if (item is Movie) "film" else if (item is TvShow) "series" else "other"
        val title = when (item) {
            is Movie -> item.title
            is TvShow -> item.title
            else -> ""
        }
        val id = itemId(item)
        val imdb = when (item) {
            is Movie -> item.imdbId
            is TvShow -> item.imdbId
            else -> null
        }?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.matches(Regex("tt[0-9]+")) }
        val year = when (item) {
            is Movie -> item.released?.get(Calendar.YEAR)
            is TvShow -> item.released?.get(Calendar.YEAR)
            else -> null
        }
        val normalized = Normalizer.normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        // Prefer title+year for matches across unrelated provider IDs. Missing year:
        // an IMDb ID is safe; otherwise keep separate to prevent collapsing remakes.
        return when {
            normalized.isNotBlank() && year != null -> "$kind:$normalized:$year"
            imdb != null -> "$kind:imdb:$imdb"
            else -> "$kind:unverified:$normalized:${provider(item)}:$id"
        }
    }

    private fun provider(item: AppAdapter.Item): String = when (item) {
        is Movie -> item.providerName.orEmpty()
        is TvShow -> item.providerName.orEmpty()
        else -> ""
    }

    private fun itemId(item: AppAdapter.Item): String = when (item) {
        is Movie -> item.id
        is TvShow -> item.id
        else -> ""
    }

    private fun posterQuality(item: AppAdapter.Item): Int {
        val poster = when (item) {
            is Movie -> item.poster
            is TvShow -> item.poster
            else -> null
        }
        val description = when (item) {
            is Movie -> item.overview
            is TvShow -> item.overview
            else -> null
        }
        val rating = when (item) {
            is Movie -> item.rating
            is TvShow -> item.rating
            else -> null
        }
        val movieQuality = when (item) {
            is Movie -> item.quality
            is TvShow -> item.quality
            else -> null
        }
        // Poster validity and metadata completeness before unverified stream-quality labels.
        var value = 0
        if (poster?.startsWith("https://") == true) value += 100
        else if (poster?.startsWith("http://") == true) value += 80
        if (poster?.contains("w500") == true || poster?.contains("original") == true) value += 12
        if (!description.isNullOrBlank()) value += 10
        if (rating != null && rating > 0.0) value += 5
        if (!movieQuality.isNullOrBlank()) value += 1
        return value
    }
}

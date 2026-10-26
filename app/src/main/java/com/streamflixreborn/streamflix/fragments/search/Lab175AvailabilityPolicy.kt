package com.streamflixreborn.streamflix.fragments.search

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * LAB175: catalogue presence is provider-owned. TMDb supplies the metadata
 * predicate, but cannot invent a playable provider item.
 */
internal object Lab175AvailabilityPolicy {
    data class Identity(val kind: String, val title: String, val year: Int?)
    data class Source(val provider: String, val endpoint: String, val id: String, val identity: Identity)

    fun normalizeTitle(raw: String): String = Normalizer.normalize(
        Lab174FilteredSourcePolicy.cleanProviderTitle(raw),
        Normalizer.Form.NFD,
    ).replace(Regex("""\p{M}+"""), "")
        .lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), "")

    // Earlier pages from principal TV sources are useful first; all other
    // sources still receive a turn before repeated pages can monopolise work.
    fun prioritySource(name: String): Boolean =
        name in setOf("StreamingCommunity", "CB01", "Adaptive Cinema")

    fun candidate(kind: String, title: String, year: Int?): Identity =
        Identity(kind, normalizeTitle(title), year)

    /** Unknown original dates are not positive availability evidence. */
    fun matches(source: Identity, metadata: Identity): Boolean {
        if (source.kind != metadata.kind || source.title.isBlank() ||
            source.title != metadata.title) return false
        val a = source.year ?: return false
        val b = metadata.year ?: return false
        return if (source.kind == "tv") a in (b - 1)..(b + 20)
            else abs(a - b) <= 1
    }

    fun <T> onlyConfirmed(
        metadata: List<Pair<Identity, T>>, available: List<Identity>,
    ): List<T> = metadata.filter { (id, _) ->
        available.any { matches(it, id) }
    }.map { it.second }

    // An index may grow on its own only while the TV Search surface is active.
    // In a quiet interval do not busy-spin: wake periodically and consult
    // persisted per-source due times without re-scanning each search.
    fun boundedIdleDelayMillis(earliestDue: Long?, now: Long): Long =
        ((earliestDue ?: (now + 15_000L)) - now).coerceIn(1_000L, 15_000L)

    fun progressLabel(titles: Int, reached: Int, total: Int, pages: Int,
                      running: Boolean): String =
        "Catalogo provider: $titles titoli · $reached/$total fonti · " +
        "$pages pagine · " + if (running) "aggiornamento in corso" else "in pausa"

    fun chooseNext(
        candidates: List<Pair<String, Long>>,
        now: Long,
        limit: Int,
    ): List<String> = candidates.filter { (_, due) -> due <= now }
        .sortedWith(compareBy<Pair<String, Long>> { it.second }.thenBy { it.first })
        .take(limit.coerceAtLeast(0)).map { it.first }
}

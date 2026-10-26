package com.streamflixreborn.streamflix.fragments.search

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Lab172NativeYearPolicy
import com.streamflixreborn.streamflix.utils.format
import java.util.Calendar

/**
 * Independent LAB175 availability catalogue. DOES NOT touch the existing
 * provider-specific Room databases, favourites, history or saved films.
 * Records only provider listing evidence, not video playback success.
 */
internal class Lab175AvailabilityIndex(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "lab175_availability_v1.db", null, 1) {

    data class Record(
        val provider: String, val endpoint: String, val sourceId: String,
        val kind: String, val title: String, val year: Int?,
        val poster: String?, val rating: Double?,
    ) {
        val identity: Lab175AvailabilityPolicy.Identity
            get() = Lab175AvailabilityPolicy.candidate(kind, title, year)
    }

    data class SourceKey(val name: String, val endpoint: String = "") {
        val key: String get() = name + "|" + endpoint
    }

    data class Progress(val nextPage: Int, val due: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE availability (
                 provider TEXT NOT NULL,
                 endpoint TEXT NOT NULL,
                 source_id TEXT NOT NULL,
                 kind TEXT NOT NULL,
                 title TEXT NOT NULL,
                 normalized TEXT NOT NULL,
                 year INTEGER,
                 poster TEXT,
                 rating REAL,
                 seen_at INTEGER NOT NULL,
                 PRIMARY KEY(provider, endpoint, kind, source_id)
            )"""
        )
        db.execSQL("CREATE INDEX idx_availability_title ON availability(normalized, kind)")
        db.execSQL(
            """CREATE TABLE progress (
                provider TEXT NOT NULL, endpoint TEXT NOT NULL,
                next_page INTEGER NOT NULL, due INTEGER NOT NULL,
                PRIMARY KEY(provider, endpoint)
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema v1: never delete or migrate existing app/user databases.
        if (oldVersion != newVersion) throw IllegalStateException(
            "Unsupported availability index schema, not deleting user data"
        )
    }

    @Synchronized fun progress(source: SourceKey): Progress {
        readableDatabase.query(
            "progress", arrayOf("next_page", "due"),
            "provider=? AND endpoint=?", arrayOf(source.name, source.endpoint),
            null, null, null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) Progress(cursor.getInt(0), cursor.getLong(1))
            else Progress(1, 0)
        }
    }

    @Synchronized fun chooseSources(
        sources: List<SourceKey>, now: Long, limit: Int,
    ): List<Pair<SourceKey, Int>> {
        val progress = sources.associateWith { progress(it) }
        // Initial priority goes to the user's principal Italian movie sources.
        // Afterwards select unseen providers before fetching a second page
        // from already-visited providers, preventing permanent starvation.
        return progress.entries.asSequence()
            .filter { it.value.due <= now }
            .sortedWith(compareBy<Map.Entry<SourceKey, Progress>> { entry ->
                when {
                    entry.value.nextPage == 1 &&
                        Lab175AvailabilityPolicy.prioritySource(entry.key.name) -> 0
                    entry.value.nextPage == 1 -> 1
                    else -> 2
                }
            }.thenBy { it.value.due }.thenBy { it.key.key })
            .take(limit.coerceAtLeast(0))
            .map { it.key to it.value.nextPage }
            .toList()
    }

    /**
     * One already-fetched native provider page. Empty/error pages are retried
     * later; nonempty pages advance one step. No destructive pruning.
     */
    @Synchronized fun recordPage(
        source: SourceKey, requestedPage: Int,
        cards: List<AppAdapter.Item>, now: Long,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            cards.forEach { item ->
                val id: String
                val title: String
                val kind: String
                val year: Int?
                val poster: String?
                val rating: Double?
                when (item) {
                    is Movie -> {
                        id = item.id; title = item.title; kind = "movie"
                        year = item.released?.get(Calendar.YEAR)
                            ?: Lab172NativeYearPolicy.fromTitle(title)?.toIntOrNull()
                        poster = item.poster; rating = item.rating
                    }
                    is TvShow -> {
                        id = item.id; title = item.title; kind = "tv"
                        year = item.released?.get(Calendar.YEAR)
                            ?: Lab172NativeYearPolicy.fromTitle(title)?.toIntOrNull()
                        poster = item.poster; rating = item.rating
                    }
                    else -> return@forEach
                }
                if (id.isBlank() || title.isBlank()) return@forEach
                val normalized = Lab175AvailabilityPolicy.normalizeTitle(title)
                if (normalized.isBlank()) return@forEach
                db.insertWithOnConflict("availability", null, ContentValues().apply {
                    put("provider", source.name)
                    put("endpoint", source.endpoint)
                    put("source_id", id)
                    put("kind", kind)
                    put("title", title)
                    put("normalized", normalized)
                    if (year != null) put("year", year) else putNull("year")
                    put("poster", poster)
                    if (rating != null) put("rating", rating) else putNull("rating")
                    put("seen_at", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val nextPage = if (cards.isEmpty()) requestedPage else requestedPage + 1
            // An empty page might be a temporary provider failure: retry.
            // A successful page yields its slot to the providers not yet
            // indexed. Revisit later, never on each search/sort click.
            val delay = if (cards.isEmpty()) 30L * 60 * 1000 else 5L * 60 * 1000
            db.insertWithOnConflict("progress", null, ContentValues().apply {
                put("provider", source.name)
                put("endpoint", source.endpoint)
                put("next_page", nextPage)
                put("due", now + delay)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized fun markFailure(source: SourceKey, page: Int, now: Long) {
        writableDatabase.insertWithOnConflict(
            "progress", null, ContentValues().apply {
                put("provider", source.name); put("endpoint", source.endpoint)
                put("next_page", page); put("due", now + 30L * 60 * 1000)
            }, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Indexed title lookup, bounded by TMDb's already-filtered result window. */
    @Synchronized fun findMatches(metadata: List<AppAdapter.Item>): List<AppAdapter.Item> {
        if (metadata.isEmpty()) return emptyList()
        val candidates = metadata.mapNotNull { item ->
            when (item) {
                is Movie -> item to Lab175AvailabilityPolicy.candidate(
                    "movie", item.title, item.released?.get(Calendar.YEAR)
                )
                is TvShow -> item to Lab175AvailabilityPolicy.candidate(
                    "tv", item.title, item.released?.get(Calendar.YEAR)
                )
                else -> null
            }
        }
        val keys = candidates.map { it.second.title }.filter { it.isNotBlank() }
            .distinct()
        if (keys.isEmpty()) return emptyList()
        val indexed = mutableListOf<Record>()
        keys.chunked(250).forEach { group ->
            val args = group.toTypedArray()
            // Do not claim ancient, possibly removed provider listings as
            // currently available. The source is kept for non-destructive
            // re-indexing, but cannot authorize new displayed posters.
            val freshAfter = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            val where = "normalized IN (" + List(group.size) { "?" }.joinToString(",") +
                ") AND seen_at >= ?"
            readableDatabase.query(
                "availability",
                arrayOf("provider", "endpoint", "source_id", "kind", "title",
                    "year", "poster", "rating"),
                where, args + freshAfter.toString(), null, null, null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    indexed.add(Record(
                        cursor.getString(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3),
                        cursor.getString(4),
                        if (cursor.isNull(5)) null else cursor.getInt(5),
                        if (cursor.isNull(6)) null else cursor.getString(6),
                        if (cursor.isNull(7)) null else cursor.getDouble(7),
                    ))
                }
            }
        }
        val byTitle = indexed.groupBy { it.identity.title }
        return candidates.flatMap { (tmdb, identity) ->
            byTitle[identity.title].orEmpty().filter { record ->
                Lab175AvailabilityPolicy.matches(record.identity, identity)
            }.map { source ->
                when (tmdb) {
                    is Movie -> Movie(
                        id = source.sourceId, title = source.title,
                        released = tmdb.released?.format("yyyy-MM-dd"),
                        poster = source.poster ?: tmdb.poster,
                        rating = source.rating ?: tmdb.rating,
                        providerName = source.provider,
                        providerEndpoint = source.endpoint.ifBlank { null },
                    ) as AppAdapter.Item
                    is TvShow -> TvShow(
                        id = source.sourceId, title = source.title,
                        released = tmdb.released?.format("yyyy-MM-dd"),
                        poster = source.poster ?: tmdb.poster,
                        rating = source.rating ?: tmdb.rating,
                        providerName = source.provider,
                        providerEndpoint = source.endpoint.ifBlank { null },
                    ) as AppAdapter.Item
                    else -> error("Unreachable media type")
                }
            }
        }.distinctBy { item ->
            when (item) {
                is Movie -> "movie|" + item.providerName + "|" + item.id
                is TvShow -> "tv|" + item.providerName + "|" + item.id
                else -> ""
            }
        }
    }
}

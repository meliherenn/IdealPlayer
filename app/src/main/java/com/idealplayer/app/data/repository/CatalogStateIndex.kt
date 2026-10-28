package com.idealplayer.app.data.repository

import android.database.sqlite.SQLiteDatabase
import com.idealplayer.app.core.common.isUsableArtworkUrl
import com.idealplayer.app.core.database.ChannelEntity
import com.idealplayer.app.core.database.EpisodeEntity
import com.idealplayer.app.core.database.MovieEntity
import com.idealplayer.app.core.database.SeriesEntity
import com.idealplayer.app.core.security.SecretEnvelopeCodec
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Disk-backed refresh state. Neither old catalogs nor claimed identities accumulate in the heap. */
internal class CatalogStateIndex(
    private val db: SQLiteDatabase,
    private val codec: SecretEnvelopeCodec,
    private val lock: Any = Any()
) {
    // The random salt exists only for this import. Secret-bearing natural keys never reach disk.
    private val salt = ByteArray(32).also(SecureRandom()::nextBytes)
    private val json = Json

    init {
        synchronized(lock) {
            db.execSQL("CREATE TABLE prior_state (kind TEXT NOT NULL, source_id INTEGER NOT NULL, lookup TEXT NOT NULL, payload TEXT NOT NULL, claimed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(kind, source_id))")
            db.execSQL("CREATE INDEX prior_state_lookup ON prior_state(kind, lookup, claimed)")
            db.execSQL("CREATE TABLE catalog_artwork (kind TEXT NOT NULL, lookup TEXT NOT NULL, slot TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(kind, lookup, slot))")
        }
    }

    fun rememberChannels(rows: List<ChannelEntity>) = remember("channel", rows, ChannelEntity.serializer(), { it.id }, { it.stableContentKey() })
    fun rememberMovies(rows: List<MovieEntity>) = remember("movie", rows, MovieEntity.serializer(), { it.id }, { it.stableContentKey() })
    fun rememberSeries(rows: List<SeriesEntity>) = remember("series", rows, SeriesEntity.serializer(), { it.id }, { it.stableContentKey() })
    fun rememberEpisodes(rows: List<EpisodeEntity>) = remember("episode", rows, EpisodeEntity.serializer(), { it.id }, { it.stableContentKey() })

    fun mergeChannels(rows: List<ChannelEntity>): List<ChannelEntity> = rows
        .withPreservedChannelState(claim("channel", rows.map { it.stableContentKey() }, ChannelEntity.serializer()), inheritArtwork = false)
        .map { row ->
            if (isUsableArtworkUrl(row.logoUrl)) row else row.copy(
                logoUrl = row.artworkLookupKeys().firstNotNullOfOrNull { artwork("channel", it, "poster") } ?: row.logoUrl
            )
        }

    fun mergeMovies(rows: List<MovieEntity>, syncTime: Long): List<MovieEntity> = rows
        .withPreservedMovieState(claim("movie", rows.map { it.stableContentKey() }, MovieEntity.serializer()), syncTime, inheritArtwork = false)
        .map { row -> row.copy(
            posterUrl = resolveArtwork("movie", row.name, row.year, "poster", row.posterUrl),
            backdropUrl = resolveArtwork("movie", row.name, row.year, "backdrop", row.backdropUrl)
        ) }

    fun mergeSeries(rows: List<SeriesEntity>, syncTime: Long): List<SeriesEntity> = rows
        .withPreservedSeriesState(claim("series", rows.map { it.stableContentKey() }, SeriesEntity.serializer()), syncTime, inheritArtwork = false)
        .map { row -> row.copy(
            posterUrl = resolveArtwork("series", row.name, row.year, "poster", row.posterUrl),
            backdropUrl = resolveArtwork("series", row.name, row.year, "backdrop", row.backdropUrl)
        ) }

    fun mergeEpisodes(rows: List<EpisodeEntity>): List<EpisodeEntity> = rows.withPreservedEpisodeState(
        claim("episode", rows.map { it.stableContentKey() }, EpisodeEntity.serializer())
    )

    fun episodePage(afterId: Long, limit: Int): List<EpisodeEntity> = db.rawQuery(
        "SELECT payload FROM prior_state WHERE kind = 'episode' AND source_id > ? ORDER BY source_id LIMIT ?",
        arrayOf(afterId.toString(), limit.toString())
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(json.decodeFromString(EpisodeEntity.serializer(), codec.decryptOrEmpty(cursor.getString(0))))
    } }

    fun channelArtwork(rows: List<ChannelEntity>) = transaction {
        rows.forEach { row -> row.artworkLookupKeys().forEach { putArtwork("channel", it, "poster", row.logoUrl) } }
    }

    fun movieArtwork(rows: List<MovieEntity>) = transaction {
        rows.forEach { row -> catalogArtworkKey(row.name, row.year)?.let {
            putArtwork("movie", it, "poster", row.posterUrl)
            putArtwork("movie", it, "backdrop", row.backdropUrl)
        } }
    }

    fun seriesArtwork(rows: List<SeriesEntity>) = transaction {
        rows.forEach { row -> catalogArtworkKey(row.name, row.year)?.let {
            putArtwork("series", it, "poster", row.posterUrl)
            putArtwork("series", it, "backdrop", row.backdropUrl)
        } }
    }

    private fun <T> remember(kind: String, rows: List<T>, serializer: KSerializer<T>, id: (T) -> Long, key: (T) -> String) = transaction {
        db.compileStatement("INSERT OR REPLACE INTO prior_state(kind, source_id, lookup, payload) VALUES (?, ?, ?, ?)").use { stmt ->
            rows.forEach { row ->
                stmt.bindString(1, kind)
                stmt.bindLong(2, id(row))
                stmt.bindString(3, digest(key(row)))
                stmt.bindString(4, codec.encrypt(json.encodeToString(serializer, row)))
                stmt.executeInsert()
                stmt.clearBindings()
            }
        }
    }

    private fun <T> claim(kind: String, keys: List<String>, serializer: KSerializer<T>): Map<String, T> = transaction {
        buildMap {
            keys.distinct().forEach { key ->
                val lookup = digest(key)
                val previous = db.rawQuery(
                    "SELECT payload FROM prior_state WHERE kind = ? AND lookup = ? AND claimed = 0 ORDER BY source_id DESC LIMIT 1",
                    arrayOf(kind, lookup)
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                if (previous != null) {
                    put(key, json.decodeFromString(serializer, codec.decryptOrEmpty(previous)))
                    // A duplicate incoming key on a later page must not overwrite the first row's ID.
                    db.execSQL("UPDATE prior_state SET claimed = 1 WHERE kind = ? AND lookup = ?", arrayOf(kind, lookup))
                }
            }
        }
    }

    private fun putArtwork(kind: String, key: String, slot: String, value: String) {
        if (isUsableArtworkUrl(value)) db.execSQL(
            "INSERT OR IGNORE INTO catalog_artwork VALUES (?, ?, ?, ?)", arrayOf(kind, digest(key), slot, codec.encrypt(value))
        )
    }

    private fun artwork(kind: String, key: String, slot: String): String? = db.rawQuery(
        "SELECT value FROM catalog_artwork WHERE kind = ? AND lookup = ? AND slot = ?", arrayOf(kind, digest(key), slot)
    ).use { if (it.moveToFirst()) codec.decryptOrEmpty(it.getString(0)).takeIf(::isUsableArtworkUrl) else null }

    private fun resolveArtwork(kind: String, name: String, year: Int, slot: String, current: String): String =
        if (isUsableArtworkUrl(current)) current else catalogArtworkKey(name, year)?.let { artwork(kind, it, slot) } ?: current

    private fun digest(key: String): String = MessageDigest.getInstance("SHA-256").run {
        update(salt)
        java.util.Base64.getEncoder().encodeToString(digest(key.toByteArray(Charsets.UTF_8)))
    }

    private inline fun <T> transaction(block: () -> T): T = synchronized(lock) {
        db.beginTransaction()
        try { return block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
}

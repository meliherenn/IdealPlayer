package com.idealplayer.app.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.idealplayer.app.core.database.CategoryEntity
import com.idealplayer.app.core.database.ChannelEntity
import com.idealplayer.app.core.database.EpisodeEntity
import com.idealplayer.app.core.database.MovieEntity
import com.idealplayer.app.core.database.SeriesEntity
import com.idealplayer.app.core.security.SecretEnvelopeCodec
import timber.log.Timber
import java.io.Closeable
import java.io.File

/**
 * Isolated SQLite database in application cache for staging large catalog imports
 * (e.g. 20,000+ items).
 *
 * Stores source secrets as envelopes with an import-scoped, memory-only key, including
 * in files left behind by process death. Reads decrypt only the requested bounded page;
 * the repository applies the permanent Keystore envelope when publishing to Room.
 * Callers control batch sizes and publish to Room in their own transaction. This store
 * does not bound upstream parser/merge memory or make publication atomic on its own.
 */
class CatalogStagingStore(
    context: Context,
    val playlistId: Long
) : Closeable {

    // Per-record hardware Keystore calls while consuming an HTTP response can exhaust
    // its call timeout on real devices. The disposable cache never needs a durable key.
    private val secretCodec = SecretEnvelopeCodec.forTransientStorage()

    private val dbFile: File = File(
        context.cacheDir,
        "catalog_staging_${playlistId}_${System.currentTimeMillis()}_${(0..9999).random()}.db"
    )
    private val dbLock = Any()
    private var db: SQLiteDatabase? = null
    private val lockFile = File(dbFile.path + ".lock")
    private val lockHandle: java.io.RandomAccessFile
    private val fileLock: java.nio.channels.FileLock
    internal val stateIndex by lazy { CatalogStateIndex(requireDb(), secretCodec, dbLock) }

    init {
        cleanupAbandoned(context.cacheDir)
        val parent = dbFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        lockHandle = java.io.RandomAccessFile(lockFile, "rw")
        fileLock = lockHandle.channel.lock()
        db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        createSchema()
    }

    private fun requireDb(): SQLiteDatabase =
        db ?: throw IllegalStateException("CatalogStagingStore is closed or discarded")

    private fun createSchema() {
        val database = requireDb()
        database.execSQL("PRAGMA synchronous = OFF")

        database.execSQL("""
            CREATE TABLE IF NOT EXISTS channels (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                streamId INTEGER NOT NULL,
                name TEXT NOT NULL,
                logoUrl TEXT NOT NULL,
                groupTitle TEXT NOT NULL,
                streamUrl TEXT NOT NULL,
                epgChannelId TEXT NOT NULL,
                catchupSource TEXT NOT NULL,
                sortOrder INTEGER NOT NULL
            )
        """.trimIndent())

        database.execSQL("""
            CREATE TABLE IF NOT EXISTS movies (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                streamId INTEGER NOT NULL,
                name TEXT NOT NULL,
                posterUrl TEXT NOT NULL,
                backdropUrl TEXT NOT NULL,
                streamUrl TEXT NOT NULL,
                genre TEXT NOT NULL,
                plot TEXT NOT NULL,
                castMembers TEXT NOT NULL,
                director TEXT NOT NULL,
                releaseDate TEXT NOT NULL,
                year INTEGER NOT NULL,
                duration INTEGER NOT NULL,
                rating REAL NOT NULL,
                imdbId TEXT NOT NULL,
                tmdbId INTEGER NOT NULL,
                containerExtension TEXT NOT NULL,
                categoryId INTEGER NOT NULL,
                categoryName TEXT NOT NULL,
                addedAt INTEGER NOT NULL,
                sourceOrder INTEGER NOT NULL
            )
        """.trimIndent())

        database.execSQL("""
            CREATE TABLE IF NOT EXISTS series (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                seriesId INTEGER NOT NULL,
                name TEXT NOT NULL,
                posterUrl TEXT NOT NULL,
                backdropUrl TEXT NOT NULL,
                genre TEXT NOT NULL,
                plot TEXT NOT NULL,
                castMembers TEXT NOT NULL,
                director TEXT NOT NULL,
                releaseDate TEXT NOT NULL,
                year INTEGER NOT NULL,
                rating REAL NOT NULL,
                imdbId TEXT NOT NULL,
                tmdbId INTEGER NOT NULL,
                categoryId INTEGER NOT NULL,
                categoryName TEXT NOT NULL,
                seasonCount INTEGER NOT NULL,
                episodeCount INTEGER NOT NULL,
                episodesRefreshedAt INTEGER NOT NULL,
                addedAt INTEGER NOT NULL,
                sourceOrder INTEGER NOT NULL
            )
        """.trimIndent())

        database.execSQL("""
            CREATE TABLE IF NOT EXISTS episodes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                seriesName TEXT NOT NULL,
                seriesId INTEGER NOT NULL,
                seasonNumber INTEGER NOT NULL,
                episodeNumber INTEGER NOT NULL,
                name TEXT NOT NULL,
                plot TEXT NOT NULL,
                posterUrl TEXT NOT NULL,
                streamUrl TEXT NOT NULL,
                duration INTEGER NOT NULL,
                rating REAL NOT NULL,
                containerExtension TEXT NOT NULL,
                sourceEpisodeId TEXT NOT NULL
            )
        """.trimIndent())

        database.execSQL("""
            CREATE TABLE IF NOT EXISTS categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                categoryId INTEGER NOT NULL,
                name TEXT NOT NULL,
                contentType TEXT NOT NULL,
                parentId INTEGER NOT NULL,
                itemCount INTEGER NOT NULL
            )
        """.trimIndent())

        database.execSQL("""
            CREATE TABLE IF NOT EXISTS epg_urls (
                url TEXT PRIMARY KEY
            )
        """.trimIndent())

        database.execSQL("CREATE INDEX IF NOT EXISTS idx_episodes_seriesName ON episodes(seriesName)")
        database.execSQL("CREATE TABLE m3u_series_counts (name TEXT PRIMARY KEY, episodes INTEGER NOT NULL)")
    }

    fun nextEpisodeIndex(seriesName: String): Int = synchronized(dbLock) {
        val database = requireDb()
        val previous = database.rawQuery("SELECT episodes FROM m3u_series_counts WHERE name = ?", arrayOf(seriesName)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        database.execSQL("INSERT OR REPLACE INTO m3u_series_counts VALUES (?, ?)", arrayOf<Any>(seriesName, previous + 1))
        previous
    }

    fun insertChannels(channels: List<ChannelEntity>) = synchronized(dbLock) {
        if (channels.isEmpty()) return
        stateIndex.channelArtwork(channels)
        val database = requireDb()
        database.beginTransaction()
        try {
            val stmt: SQLiteStatement = database.compileStatement("""
                INSERT INTO channels (streamId, name, logoUrl, groupTitle, streamUrl, epgChannelId, catchupSource, sortOrder)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent())
            for (ch in channels) {
                stmt.bindLong(1, ch.streamId.toLong())
                stmt.bindString(2, ch.name)
                stmt.bindString(3, ch.logoUrl)
                stmt.bindString(4, ch.groupTitle)
                stmt.bindString(5, secretCodec.encrypt(ch.streamUrl))
                stmt.bindString(6, ch.epgChannelId)
                stmt.bindString(7, secretCodec.encrypt(ch.catchupSource))
                stmt.bindLong(8, ch.sortOrder.toLong())
                stmt.executeInsert()
                stmt.clearBindings()
            }
            stmt.close()
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun insertMovies(movies: List<MovieEntity>) = synchronized(dbLock) {
        if (movies.isEmpty()) return
        stateIndex.movieArtwork(movies)
        val database = requireDb()
        database.beginTransaction()
        try {
            val stmt: SQLiteStatement = database.compileStatement("""
                INSERT INTO movies (streamId, name, posterUrl, backdropUrl, streamUrl, genre, plot, castMembers,
                    director, releaseDate, year, duration, rating, imdbId, tmdbId, containerExtension,
                    categoryId, categoryName, addedAt, sourceOrder)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent())
            for (m in movies) {
                stmt.bindLong(1, m.streamId.toLong())
                stmt.bindString(2, m.name)
                stmt.bindString(3, m.posterUrl)
                stmt.bindString(4, m.backdropUrl)
                stmt.bindString(5, secretCodec.encrypt(m.streamUrl))
                stmt.bindString(6, m.genre)
                stmt.bindString(7, m.plot)
                stmt.bindString(8, m.cast)
                stmt.bindString(9, m.director)
                stmt.bindString(10, m.releaseDate)
                stmt.bindLong(11, m.year.toLong())
                stmt.bindLong(12, m.duration.toLong())
                stmt.bindDouble(13, m.rating)
                stmt.bindString(14, m.imdbId)
                stmt.bindLong(15, m.tmdbId.toLong())
                stmt.bindString(16, m.containerExtension)
                stmt.bindLong(17, m.categoryId.toLong())
                stmt.bindString(18, m.categoryName)
                stmt.bindLong(19, m.addedAt)
                stmt.bindLong(20, m.sourceOrder.toLong())
                stmt.executeInsert()
                stmt.clearBindings()
            }
            stmt.close()
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun insertSeries(series: List<SeriesEntity>) = synchronized(dbLock) {
        if (series.isEmpty()) return
        stateIndex.seriesArtwork(series)
        val database = requireDb()
        database.beginTransaction()
        try {
            val stmt: SQLiteStatement = database.compileStatement("""
                INSERT INTO series (seriesId, name, posterUrl, backdropUrl, genre, plot, castMembers,
                    director, releaseDate, year, rating, imdbId, tmdbId, categoryId, categoryName,
                    seasonCount, episodeCount, episodesRefreshedAt, addedAt, sourceOrder)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent())
            for (s in series) {
                stmt.bindLong(1, s.seriesId.toLong())
                stmt.bindString(2, s.name)
                stmt.bindString(3, s.posterUrl)
                stmt.bindString(4, s.backdropUrl)
                stmt.bindString(5, s.genre)
                stmt.bindString(6, s.plot)
                stmt.bindString(7, s.cast)
                stmt.bindString(8, s.director)
                stmt.bindString(9, s.releaseDate)
                stmt.bindLong(10, s.year.toLong())
                stmt.bindDouble(11, s.rating)
                stmt.bindString(12, s.imdbId)
                stmt.bindLong(13, s.tmdbId.toLong())
                stmt.bindLong(14, s.categoryId.toLong())
                stmt.bindString(15, s.categoryName)
                stmt.bindLong(16, s.seasonCount.toLong())
                stmt.bindLong(17, s.episodeCount.toLong())
                stmt.bindLong(18, s.episodesRefreshedAt)
                stmt.bindLong(19, s.addedAt)
                stmt.bindLong(20, s.sourceOrder.toLong())
                stmt.executeInsert()
                stmt.clearBindings()
            }
            stmt.close()
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun insertEpisodes(episodes: List<EpisodeEntity>, seriesName: String = "") = synchronized(dbLock) {
        if (episodes.isEmpty()) return
        val database = requireDb()
        database.beginTransaction()
        try {
            val stmt: SQLiteStatement = database.compileStatement("""
                INSERT INTO episodes (seriesName, seriesId, seasonNumber, episodeNumber, name,
                    plot, posterUrl, streamUrl, duration, rating, containerExtension, sourceEpisodeId)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent())
            for (ep in episodes) {
                stmt.bindString(1, seriesName)
                stmt.bindLong(2, ep.seriesId)
                stmt.bindLong(3, ep.seasonNumber.toLong())
                stmt.bindLong(4, ep.episodeNumber.toLong())
                stmt.bindString(5, ep.name)
                stmt.bindString(6, ep.plot)
                stmt.bindString(7, ep.posterUrl)
                stmt.bindString(8, secretCodec.encrypt(ep.streamUrl))
                stmt.bindLong(9, ep.duration.toLong())
                stmt.bindDouble(10, ep.rating)
                stmt.bindString(11, ep.containerExtension)
                stmt.bindString(12, ep.sourceEpisodeId)
                stmt.executeInsert()
                stmt.clearBindings()
            }
            stmt.close()
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun insertCategories(categories: List<CategoryEntity>) = synchronized(dbLock) {
        if (categories.isEmpty()) return
        val database = requireDb()
        database.beginTransaction()
        try {
            val stmt: SQLiteStatement = database.compileStatement("""
                INSERT INTO categories (categoryId, name, contentType, parentId, itemCount)
                VALUES (?, ?, ?, ?, ?)
            """.trimIndent())
            for (cat in categories) {
                stmt.bindLong(1, cat.categoryId.toLong())
                stmt.bindString(2, cat.name)
                stmt.bindString(3, cat.contentType)
                stmt.bindLong(4, cat.parentId.toLong())
                stmt.bindLong(5, cat.itemCount.toLong())
                stmt.executeInsert()
                stmt.clearBindings()
            }
            stmt.close()
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun insertEpgUrls(urls: List<String>) = synchronized(dbLock) {
        if (urls.isEmpty()) return
        val database = requireDb()
        database.beginTransaction()
        try {
            val stmt: SQLiteStatement = database.compileStatement("INSERT OR IGNORE INTO epg_urls (url) VALUES (?)")
            for (url in urls) {
                if (url.isNotBlank()) {
                    stmt.bindString(1, secretCodec.encrypt(url.trim()))
                    stmt.executeInsert()
                    stmt.clearBindings()
                }
            }
            stmt.close()
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun channelCount(): Int = queryCount("channels")
    fun movieCount(): Int = queryCount("movies")
    fun seriesCount(): Int = queryCount("series")
    fun episodeCount(): Int = queryCount("episodes")
    fun categoryCount(): Int = queryCount("categories")

    fun totalItems(): Int = channelCount() + movieCount() + seriesCount() + episodeCount() + categoryCount()

    fun getEpgUrls(): List<String> {
        val database = requireDb()
        val result = mutableListOf<String>()
        database.rawQuery("SELECT url FROM epg_urls", null).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(secretCodec.decryptOrEmpty(cursor.getString(0)))
            }
        }
        return result
    }

    private fun queryCount(tableName: String): Int {
        val database = requireDb()
        return database.rawQuery("SELECT COUNT(*) FROM $tableName", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun readChannels(limit: Int, offset: Int): List<ChannelEntity> {
        val database = requireDb()
        val list = mutableListOf<ChannelEntity>()
        database.rawQuery("SELECT streamId, name, logoUrl, groupTitle, streamUrl, epgChannelId, catchupSource, sortOrder FROM channels ORDER BY id LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString())).use { c ->
            while (c.moveToNext()) {
                list.add(ChannelEntity(
                    playlistId = playlistId,
                    streamId = c.getInt(0),
                    name = c.getString(1),
                    logoUrl = c.getString(2),
                    groupTitle = c.getString(3),
                    streamUrl = secretCodec.decryptOrEmpty(c.getString(4)),
                    epgChannelId = c.getString(5),
                    catchupSource = secretCodec.decryptOrEmpty(c.getString(6)),
                    sortOrder = c.getInt(7)
                ))
            }
        }
        return list
    }

    fun readMovies(limit: Int, offset: Int): List<MovieEntity> {
        val database = requireDb()
        val list = mutableListOf<MovieEntity>()
        database.rawQuery("""
            SELECT streamId, name, posterUrl, backdropUrl, streamUrl, genre, plot, castMembers,
                director, releaseDate, year, duration, rating, imdbId, tmdbId, containerExtension,
                categoryId, categoryName, addedAt, sourceOrder
            FROM movies ORDER BY id LIMIT ? OFFSET ?
        """.trimIndent(), arrayOf(limit.toString(), offset.toString())).use { c ->
            while (c.moveToNext()) {
                list.add(MovieEntity(
                    playlistId = playlistId,
                    streamId = c.getInt(0),
                    name = c.getString(1),
                    posterUrl = c.getString(2),
                    backdropUrl = c.getString(3),
                    streamUrl = secretCodec.decryptOrEmpty(c.getString(4)),
                    genre = c.getString(5),
                    plot = c.getString(6),
                    cast = c.getString(7),
                    director = c.getString(8),
                    releaseDate = c.getString(9),
                    year = c.getInt(10),
                    duration = c.getInt(11),
                    rating = c.getDouble(12),
                    imdbId = c.getString(13),
                    tmdbId = c.getInt(14),
                    containerExtension = c.getString(15),
                    categoryId = c.getInt(16),
                    categoryName = c.getString(17),
                    addedAt = c.getLong(18),
                    sourceOrder = c.getInt(19)
                ))
            }
        }
        return list
    }

    fun readSeries(limit: Int, offset: Int): List<SeriesEntity> {
        val database = requireDb()
        val list = mutableListOf<SeriesEntity>()
        database.rawQuery("""
            SELECT seriesId, name, posterUrl, backdropUrl, genre, plot, castMembers,
                director, releaseDate, year, rating, imdbId, tmdbId, categoryId, categoryName,
                seasonCount, episodeCount, episodesRefreshedAt, addedAt, sourceOrder
            FROM series ORDER BY id LIMIT ? OFFSET ?
        """.trimIndent(), arrayOf(limit.toString(), offset.toString())).use { c ->
            while (c.moveToNext()) {
                list.add(SeriesEntity(
                    playlistId = playlistId,
                    seriesId = c.getInt(0),
                    name = c.getString(1),
                    posterUrl = c.getString(2),
                    backdropUrl = c.getString(3),
                    genre = c.getString(4),
                    plot = c.getString(5),
                    cast = c.getString(6),
                    director = c.getString(7),
                    releaseDate = c.getString(8),
                    year = c.getInt(9),
                    rating = c.getDouble(10),
                    imdbId = c.getString(11),
                    tmdbId = c.getInt(12),
                    categoryId = c.getInt(13),
                    categoryName = c.getString(14),
                    seasonCount = c.getInt(15),
                    episodeCount = c.getInt(16),
                    episodesRefreshedAt = c.getLong(17),
                    addedAt = c.getLong(18),
                    sourceOrder = c.getInt(19)
                ))
            }
        }
        return list
    }

    fun readEpisodes(limit: Int, offset: Int): List<EpisodeEntity> {
        val database = requireDb()
        val list = mutableListOf<EpisodeEntity>()
        database.rawQuery("""
            SELECT seriesId, seasonNumber, episodeNumber, name,
                plot, posterUrl, streamUrl, duration, rating, containerExtension, sourceEpisodeId
            FROM episodes ORDER BY id LIMIT ? OFFSET ?
        """.trimIndent(), arrayOf(limit.toString(), offset.toString())).use { c ->
            while (c.moveToNext()) {
                list.add(EpisodeEntity(
                    seriesId = c.getLong(0),
                    seasonNumber = c.getInt(1),
                    episodeNumber = c.getInt(2),
                    name = c.getString(3),
                    plot = c.getString(4),
                    posterUrl = c.getString(5),
                    streamUrl = secretCodec.decryptOrEmpty(c.getString(6)),
                    duration = c.getInt(7),
                    rating = c.getDouble(8),
                    containerExtension = c.getString(9),
                    sourceEpisodeId = c.getString(10)
                ))
            }
        }
        return list
    }

    fun readM3uEpisodesWithSeriesName(limit: Int, offset: Int): List<Pair<String, EpisodeEntity>> {
        val database = requireDb()
        val list = mutableListOf<Pair<String, EpisodeEntity>>()
        database.rawQuery("""
            SELECT seriesName, seasonNumber, episodeNumber, name,
                plot, posterUrl, streamUrl, duration, rating, containerExtension, sourceEpisodeId
            FROM episodes WHERE seriesName != '' ORDER BY id LIMIT ? OFFSET ?
        """.trimIndent(), arrayOf(limit.toString(), offset.toString())).use { c ->
            while (c.moveToNext()) {
                val seriesName = c.getString(0)
                val episode = EpisodeEntity(
                    seriesId = 0L,
                    seasonNumber = c.getInt(1),
                    episodeNumber = c.getInt(2),
                    name = c.getString(3),
                    plot = c.getString(4),
                    posterUrl = c.getString(5),
                    streamUrl = secretCodec.decryptOrEmpty(c.getString(6)),
                    duration = c.getInt(7),
                    rating = c.getDouble(8),
                    containerExtension = c.getString(9),
                    sourceEpisodeId = c.getString(10)
                )
                list.add(seriesName to episode)
            }
        }
        return list
    }

    fun readCategories(limit: Int, offset: Int): List<CategoryEntity> {
        val database = requireDb()
        val list = mutableListOf<CategoryEntity>()
        database.rawQuery("""
            SELECT categoryId, name, contentType, parentId, itemCount
            FROM categories ORDER BY id LIMIT ? OFFSET ?
        """.trimIndent(), arrayOf(limit.toString(), offset.toString())).use { c ->
            while (c.moveToNext()) {
                list.add(CategoryEntity(
                    playlistId = playlistId,
                    categoryId = c.getInt(0),
                    name = c.getString(1),
                    contentType = c.getString(2),
                    parentId = c.getInt(3),
                    itemCount = c.getInt(4)
                ))
            }
        }
        return list
    }

    fun discard() {
        close()
        try {
            SQLiteDatabase.deleteDatabase(dbFile)
            lockFile.delete()
        } catch (e: Exception) {
            Timber.w("Failed to delete staging db file: %s", e.message)
        }
    }

    fun diskSizeBytes(): Long = if (dbFile.exists()) dbFile.length() else 0L

    override fun close() {
        if (db == null) return
        try {
            db?.close()
        } catch (e: Exception) {
            // ignore
        } finally {
            db = null
            if (fileLock.isValid) fileLock.release()
            lockHandle.close()
        }
    }

    companion object {
        /** Locked imports are never removed; scan work is capped on every new import. */
        internal fun cleanupAbandoned(cacheDir: File) {
            if (!cacheDir.isDirectory) return
            java.nio.file.Files.newDirectoryStream(cacheDir.toPath(), "catalog_staging_*.db").use { paths ->
                var inspected = 0
                for (path in paths) {
                    if (inspected++ == 32) break
                    val candidate = path.toFile()
                    val lock = File(candidate.path + ".lock")
                    try {
                        java.io.RandomAccessFile(lock, "rw").use { handle ->
                            val acquired = handle.channel.tryLock() ?: return@use
                            acquired.use { SQLiteDatabase.deleteDatabase(candidate) }
                        }
                        if (!candidate.exists()) lock.delete()
                    } catch (_: java.nio.channels.OverlappingFileLockException) {
                        // Another active import in this process owns the file.
                    } catch (_: java.io.IOException) {
                        // A later import can retry a file that is temporarily unavailable.
                    }
                }
            }
        }
    }
}

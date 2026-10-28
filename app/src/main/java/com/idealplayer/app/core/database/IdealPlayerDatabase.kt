package com.idealplayer.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        CategoryEntity::class,
        EpgProgramEntity::class,
        EpgSyncStateEntity::class,
        WatchHistoryEntity::class,
        FavoriteEntity::class,
        MetadataCacheEntity::class
    ],
    version = 9,
    exportSchema = true
)
abstract class IdealPlayerDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun movieDao(): MovieDao
    abstract fun seriesDao(): SeriesDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun categoryDao(): CategoryDao
    abstract fun epgDao(): EpgDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun metadataCacheDao(): MetadataCacheDao

    companion object {
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Preserve every row and its stable ID; only add access paths for TV paging.
                db.execSQL("CREATE INDEX IF NOT EXISTS index_channels_playlistId_sortOrder_id ON channels (playlistId, sortOrder, id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_channels_playlistId_groupTitle_sortOrder_id ON channels (playlistId, groupTitle, sortOrder, id)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN sourceRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE series ADD COLUMN episodesRefreshedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN sourceEpisodeId TEXT NOT NULL DEFAULT ''")
                // v7 guide rows have no provable owner. Only this disposable cache is rebuilt;
                // playlists, catalog IDs, credentials, favorites and progress remain untouched.
                db.execSQL("DROP TABLE epg_programs")
                db.execSQL("""CREATE TABLE IF NOT EXISTS epg_programs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    playlistId INTEGER NOT NULL, channelId TEXT NOT NULL, title TEXT NOT NULL,
                    description TEXT NOT NULL, startTime INTEGER NOT NULL, endTime INTEGER NOT NULL,
                    posterUrl TEXT NOT NULL, genre TEXT NOT NULL,
                    FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )""")
                db.execSQL("CREATE INDEX index_epg_programs_playlistId ON epg_programs (playlistId)")
                db.execSQL("CREATE INDEX index_epg_programs_playlistId_channelId_endTime ON epg_programs (playlistId, channelId, endTime)")
                db.execSQL("CREATE UNIQUE INDEX index_epg_programs_playlistId_channelId_startTime ON epg_programs (playlistId, channelId, startTime)")
                db.execSQL("CREATE INDEX index_epg_programs_playlistId_endTime ON epg_programs (playlistId, endTime)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS epg_sync_state (
                    playlistId INTEGER NOT NULL PRIMARY KEY,
                    lastSuccessAt INTEGER NOT NULL, lastAttemptAt INTEGER NOT NULL, failure TEXT,
                    FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )""")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE metadata_cache ADD COLUMN language TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE metadata_cache ADD COLUMN tagline TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE metadata_cache ADD COLUMN trailerUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("DROP INDEX IF EXISTS index_metadata_cache_title_year")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_metadata_cache_title_year_language ON metadata_cache (title, year, language)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add stream health check column to channels (defaults to online)
                db.execSQL("ALTER TABLE channels ADD COLUMN isOnline INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Composite indices for EPG query performance
                db.execSQL("CREATE INDEX IF NOT EXISTS index_epg_programs_channelId_endTime ON epg_programs (channelId, endTime)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_epg_programs_channelId_startTime ON epg_programs (channelId, startTime)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_epg_programs_endTime ON epg_programs (endTime)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN addedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE movies ADD COLUMN sourceOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE series ADD COLUMN addedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE series ADD COLUMN sourceOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE movies SET sourceOrder = CASE WHEN id > 2147483647 THEN 2147483647 ELSE id END")
                db.execSQL("UPDATE series SET sourceOrder = CASE WHEN id > 2147483647 THEN 2147483647 ELSE id END")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_movies_playlistId_addedAt_sourceOrder ON movies (playlistId, addedAt, sourceOrder)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_series_playlistId_addedAt_sourceOrder ON series (playlistId, addedAt, sourceOrder)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN epgUrl TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Historically epg_programs had an auto-generated primary key and no unique
                // constraint, so every EPG sync appended fresh duplicate rows for the same
                // (channelId, startTime) slot. Collapse existing duplicates keeping the most
                // recently inserted copy, then enforce the natural key with a unique index so
                // future OnConflictStrategy.REPLACE inserts actually overwrite in place.
                db.execSQL(
                    "DELETE FROM epg_programs WHERE id NOT IN (" +
                        "SELECT MAX(id) FROM epg_programs GROUP BY channelId, startTime)"
                )
                db.execSQL("DROP INDEX IF EXISTS index_epg_programs_channelId_startTime")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_epg_programs_channelId_startTime " +
                        "ON epg_programs (channelId, startTime)"
                )
            }
        }
    }
}

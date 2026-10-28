package com.idealplayer.app.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side schema and retention proof for the first migration backed by exported schemas.
 *
 * Versions 1–5 predate schema export, so the JVM migration tests cover their SQL postconditions.
 * Version 6 is the earliest database that MigrationTestHelper can construct and validate against
 * Room's generated version 7 schema.
 */
@RunWith(AndroidJUnit4::class)
class IdealPlayerDatabaseMigrationInstrumentedTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IdealPlayerDatabase::class.java
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "idealplayer-migration-6-7-test"

    @Before
    fun removeStaleDatabase() {
        context.deleteDatabase(databaseName)
    }

    @After
    fun removeTestDatabase() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migration_6_7_validates_schema_and_retains_user_data() {
        migrationHelper.createDatabase(databaseName, 6).use { version6 ->
            version6.insertVersion6UserData()
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            7,
            true,
            IdealPlayerDatabase.MIGRATION_6_7
        ).use { version7 ->
            version7.query(
                "SELECT name, type, isActive FROM playlists WHERE id = 1"
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("Synthetic playlist")
                assertThat(cursor.getString(1)).isEqualTo("M3U_URL")
                assertThat(cursor.getInt(2)).isEqualTo(1)
            }

            version7.query(
                "SELECT name, isFavorite, lastWatched FROM channels WHERE id = 10"
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("Synthetic channel")
                assertThat(cursor.getInt(1)).isEqualTo(1)
                assertThat(cursor.getLong(2)).isEqualTo(1_234L)
            }

            assertSingleRetainedRow(version7, "favorites", "contentId = 10 AND contentType = 'LIVE'")
            assertSingleRetainedRow(version7, "watch_history", "contentId = 10 AND contentType = 'LIVE'")

            version7.query(
                "SELECT id, title FROM epg_programs " +
                    "WHERE channelId = 'synthetic-channel' AND startTime = 1000"
            ).use { cursor ->
                assertThat(cursor.count).isEqualTo(1)
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(2L)
                assertThat(cursor.getString(1)).isEqualTo("Fresh programme")
            }

            // The validated v7 unique index must make replacement idempotent for later syncs.
            version7.execSQL(
                "INSERT OR REPLACE INTO epg_programs " +
                    "(channelId, title, description, startTime, endTime, posterUrl, genre) " +
                    "VALUES ('synthetic-channel', 'Replacement', '', 1000, 2500, '', '')"
            )
            version7.query(
                "SELECT COUNT(*), title FROM epg_programs " +
                    "WHERE channelId = 'synthetic-channel' AND startTime = 1000"
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(1)
                assertThat(cursor.getString(1)).isEqualTo("Replacement")
            }
        }
    }

    private fun SupportSQLiteDatabase.insertVersion6UserData() {
        execSQL(
            "INSERT INTO playlists " +
                "(id, name, type, url, serverUrl, username, password, filePath, epgUrl, " +
                "lastUpdated, lastUsed, isActive, channelCount, movieCount, seriesCount) VALUES " +
                "(1, 'Synthetic playlist', 'M3U_URL', 'fixture://playlist', '', '', '', '', '', " +
                "100, 200, 1, 1, 0, 0)"
        )
        execSQL(
            "INSERT INTO channels " +
                "(id, playlistId, streamId, name, logoUrl, groupTitle, streamUrl, " +
                "epgChannelId, catchupSource, isFavorite, lastWatched, sortOrder, isOnline) VALUES " +
                "(10, 1, 100, 'Synthetic channel', '', 'Test', 'fixture://stream', " +
                "'synthetic-channel', '', 1, 1234, 0, 1)"
        )
        execSQL(
            "INSERT INTO favorites " +
                "(id, contentId, contentType, title, posterUrl, streamUrl, timestamp) VALUES " +
                "(20, 10, 'LIVE', 'Synthetic channel', '', 'fixture://stream', 1234)"
        )
        execSQL(
            "INSERT INTO watch_history " +
                "(id, contentId, contentType, title, posterUrl, streamUrl, position, " +
                "totalDuration, progress, timestamp, seasonNumber, episodeNumber, seriesName) VALUES " +
                "(30, 10, 'LIVE', 'Synthetic channel', '', 'fixture://stream', 0, " +
                "0, 0.0, 1234, 0, 0, '')"
        )
        execSQL(
            "INSERT INTO epg_programs " +
                "(id, channelId, title, description, startTime, endTime, posterUrl, genre) VALUES " +
                "(1, 'synthetic-channel', 'Stale programme', '', 1000, 2000, '', ''), " +
                "(2, 'synthetic-channel', 'Fresh programme', '', 1000, 2100, '', ''), " +
                "(3, 'synthetic-channel', 'Later programme', '', 2100, 3000, '', '')"
        )
    }

    private fun assertSingleRetainedRow(
        database: SupportSQLiteDatabase,
        table: String,
        predicate: String
    ) {
        database.query("SELECT COUNT(*) FROM $table WHERE $predicate").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
    }
}

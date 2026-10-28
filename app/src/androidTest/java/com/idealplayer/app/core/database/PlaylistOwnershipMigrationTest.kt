package com.idealplayer.app.core.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.*
import org.junit.runner.RunWith

/** Runs the exported schemas on Android SQLite and compares every original user-data column. */
@RunWith(AndroidJUnit4::class)
class PlaylistOwnershipMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), IdealPlayerDatabase::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "playlist-ownership-migration"
    private val tables = listOf("playlists", "channels", "movies", "series", "episodes", "favorites", "watch_history", "categories", "metadata_cache")
    @Before fun before() { context.deleteDatabase(name) }
    @After fun after() { context.deleteDatabase(name) }

    @Test fun version6_to_version9_preserves_user_data() = migrate(6)
    @Test fun version7_to_version9_preserves_user_data() = migrate(7)

    private fun migrate(version: Int) {
        val original = helper.createDatabase(name, version).use { db ->
            tables.forEach { table ->
                val values = ContentValues()
                // Populate all old columns so retention covers credentials, metadata and progress.
                db.query("PRAGMA table_info(`$table`)").use { cursor ->
                    while (cursor.moveToNext()) {
                        val column = cursor.getString(1)
                        when (cursor.getString(2)) {
                            "INTEGER" -> values.put(column, if (column == "id" || column == "playlistId" || column == "seriesId") 1L else 37L)
                            "REAL" -> values.put(column, 0.37)
                            else -> values.put(column, "synthetic-$column")
                        }
                    }
                }
                db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
            }
            db.execSQL("INSERT INTO epg_programs (channelId,title,description,startTime,endTime,posterUrl,genre) VALUES ('ambiguous-id','Old cache','',1,2,'','')")
            tables.associateWith { db.snapshot(it) }
        }
        helper.runMigrationsAndValidate(name, 9, true, IdealPlayerDatabase.MIGRATION_6_7, IdealPlayerDatabase.MIGRATION_7_8, IdealPlayerDatabase.MIGRATION_8_9).use { db ->
            original.forEach { (table, values) ->
                val migrated = db.snapshot(table)
                values.forEach { (column, value) -> com.google.common.truth.Truth.assertWithMessage("$table.$column").that(migrated[column]).isEqualTo(value) }
            }
            assertThat(db.snapshot("playlists")["sourceRevision"]).isEqualTo("0")
            assertThat(db.snapshot("series")["episodesRefreshedAt"]).isEqualTo("0")
            assertThat(db.snapshot("episodes")["sourceEpisodeId"]).isEqualTo("")
            for (table in listOf("epg_programs", "epg_sync_state")) {
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    cursor.moveToFirst(); assertThat(cursor.getInt(0)).isEqualTo(0)
                }
            }
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("INSERT INTO epg_programs (playlistId,channelId,title,description,startTime,endTime,posterUrl,genre) VALUES (1,'same-id','Owned','',1,2,'','')")
            db.execSQL("INSERT INTO epg_sync_state (playlistId,lastSuccessAt,lastAttemptAt,failure) VALUES (1,42,42,NULL)")
            db.execSQL("DELETE FROM playlists WHERE id=1")
            db.query("SELECT (SELECT COUNT(*) FROM epg_programs)+(SELECT COUNT(*) FROM epg_sync_state)").use { cursor ->
                cursor.moveToFirst(); assertThat(cursor.getInt(0)).isEqualTo(0)
            }
        }
    }

    private fun SupportSQLiteDatabase.snapshot(table: String): Map<String, String?> =
        query("SELECT * FROM `$table`").use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
            cursor.moveToFirst()
            cursor.columnNames.mapIndexed { index, column -> column to cursor.getString(index) }.toMap()
        }
}

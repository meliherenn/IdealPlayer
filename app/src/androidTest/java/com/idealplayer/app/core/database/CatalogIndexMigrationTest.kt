package com.idealplayer.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogIndexMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), IdealPlayerDatabase::class.java)
    private val name = "catalog-index-migration"
    @After fun cleanup() { InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name) }

    @Test fun version8_to9_preserves_channel_data_and_avoids_temporary_sort() {
        helper.createDatabase(name, 8).use { db ->
            db.execSQL("INSERT INTO channels (id,playlistId,streamId,name,logoUrl,groupTitle,streamUrl,epgChannelId,catchupSource,isFavorite,lastWatched,sortOrder,isOnline) VALUES (41,7,19,'Fixture','','Group','enc:v2:fixture','guide','enc:v2:catchup',1,12345,17,1)")
        }
        helper.runMigrationsAndValidate(name, 9, true, IdealPlayerDatabase.MIGRATION_8_9).use { db ->
            db.query("SELECT id,streamUrl,catchupSource,isFavorite,lastWatched FROM channels").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(41)
                assertThat(c.getString(1)).isEqualTo("enc:v2:fixture")
                assertThat(c.getString(2)).isEqualTo("enc:v2:catchup")
                assertThat(c.getInt(3)).isEqualTo(1)
                assertThat(c.getLong(4)).isEqualTo(12345)
            }
            for (filter in listOf("playlistId = 7", "playlistId = 7 AND groupTitle = 'Group'")) {
                db.query("EXPLAIN QUERY PLAN SELECT id,name FROM channels WHERE $filter ORDER BY sortOrder,id LIMIT 60 OFFSET 5000").use { c ->
                    val details = buildList { while (c.moveToNext()) add(c.getString(3)) }.joinToString()
                    assertThat(details).doesNotContain("TEMP B-TREE")
                    assertThat(details).contains("index_channels_playlistId_")
                }
            }
        }
    }
}

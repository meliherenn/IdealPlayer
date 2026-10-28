package com.idealplayer.app.benchmark

import androidx.room.withTransaction
import com.idealplayer.app.core.database.*
import com.idealplayer.app.core.security.SecretEnvelopeCodec
import com.idealplayer.app.data.repository.ContentRepository
import com.idealplayer.app.ui.catalog.CatalogSortOption
import org.json.JSONArray
import org.json.JSONObject
import kotlin.system.measureNanoTime

/** Isolated, synthetic 20k-row fixture. Never compiled into a Play artifact. */
internal suspend fun measureR5Catalog(
    db: IdealPlayerDatabase, repository: ContentRepository, codec: SecretEnvelopeCodec
): JSONObject {
    db.clearAllTables()
    val secret = codec.encrypt("https://fixture.invalid.test/" + "x".repeat(512))
    db.withTransaction {
        db.playlistDao().insert(PlaylistEntity(id = 9001, name = "R5 synthetic", type = "M3U_URL", isActive = true))
        for (start in 0 until 10_000 step 250) {
            db.channelDao().insertAll((start until start + 250).map { i ->
                ChannelEntity(id = i + 1L, playlistId = 9001, name = "Channel %05d".format(java.util.Locale.ROOT, i),
                    streamUrl = secret, catchupSource = secret, groupTitle = "Group ${i / 100}", sortOrder = i)
            })
            db.movieDao().insertAll((start until start + 250).map { i ->
                MovieEntity(id = i + 1L, playlistId = 9001, name = "Movie %05d".format(java.util.Locale.ROOT, i),
                    streamUrl = secret, plot = "Synthetic plot", categoryName = "Group ${i / 100}")
            })
        }
    }
    val result = JSONObject().put("rows", 20_000).put("page_size", 60)
    val cases = JSONObject()
    for (table in listOf(CatalogTable.CHANNELS, CatalogTable.MOVIES)) {
        for (offset in listOf(0, 5_000)) {
            suspend fun readPage(): Int = if (table == CatalogTable.CHANNELS) {
                repository.getChannelsPage(9001, null, 60, offset).items.also { rows ->
                    check(rows.all { it.streamUrl.isEmpty() && it.playbackAvailable && it.supportsCatchup })
                }.size
            } else {
                repository.getMoviesPage(9001, null, CatalogSortOption.TITLE, 60, offset).items.also { rows ->
                    check(rows.all { it.streamUrl.isEmpty() && it.playbackAvailable })
                }.size
            }
            repeat(3) { check(readPage() == 60) }
            val samples = (0 until 30).map { measureNanoTime { check(readPage() == 60) } / 1_000_000.0 }
            val query = catalogQuery(table, 9001, null, null, limit = 60, offset = offset)
            val plan = db.openHelper.readableDatabase.query(
                androidx.sqlite.db.SimpleSQLiteQuery("EXPLAIN QUERY PLAN " + query.sql, arrayOf(9001, 60, offset))
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(3)) } }
            var bytes = 0L
            val columns = db.openHelper.readableDatabase.query(query).use { c ->
                val names = c.columnNames.toList()
                while (c.moveToNext()) for (i in 0 until c.columnCount) {
                    bytes += c.getString(i)?.toByteArray(Charsets.UTF_8)?.size ?: 0
                }
                names
            }
            cases.put("${table.sqlName}_$offset", JSONObject()
                .put("samples_ms", JSONArray(samples)).put("p50_ms", samples.sorted()[14])
                .put("p95_ms", samples.sorted()[28]).put("cursor_value_utf8_bytes", bytes)
                .put("columns", JSONArray(columns)).put("query_plan", JSONArray(plan)))
        }
    }
    return result.put("cases", cases).put("java_heap_used_bytes", Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() })
}

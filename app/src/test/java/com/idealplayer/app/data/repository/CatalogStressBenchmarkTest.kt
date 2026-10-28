package com.idealplayer.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.common.Resource
import com.idealplayer.app.core.database.*
import com.idealplayer.app.core.model.*
import com.idealplayer.app.ui.catalog.CatalogSortOption
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CatalogStressBenchmarkTest {
    private lateinit var f: RepositorySecurityFixture
    private val db get() = f.database
    @Before fun before() { f = RepositorySecurityFixture(recordSecrets = false) }
    @After fun after() { f.close() }

    @Test fun `benchmark 20k catalog uses production sync refresh and every SQL page`() = runBlocking<Unit> {
        val file = File(f.directory, "catalog.m3u")
        file.bufferedWriter().use { out ->
            out.appendLine("#EXTM3U")
            repeat(10_000) { index ->
                out.appendLine("#EXTINF:-1 tvg-id=\"live-$index\" group-title=\"Live\",Synthetic Live $index")
                out.appendLine("https://streams.invalid.test/live/$index.m3u8")
                out.appendLine("#EXTINF:-1 group-title=\"Movies\",Synthetic Movie $index")
                out.appendLine("https://streams.invalid.test/movie/user/pass/$index.mp4")
            }
        }
        val playlist = Playlist(id = 1, name = "Synthetic", type = PlaylistType.M3U_FILE, filePath = file.path)
        db.playlistDao().insert(playlist.toSecureEntity(f.codec))
        val repository = f.playlists()
        val importMs = measureTimeMillis {
            assertThat(repository.syncPlaylist(playlist)).isInstanceOf(Resource.Success::class.java)
        }
        val movie = db.movieDao().getRefreshPage(1, 0, 1).single()
        db.movieDao().setFavorite(movie.id, true)
        db.movieDao().updateProgress(movie.id, 1234, 9876)
        val refreshMs = measureTimeMillis {
            assertThat(repository.syncPlaylist(playlist)).isInstanceOf(Resource.Success::class.java)
        }
        assertThat(db.channelDao().countByPlaylist(1)).isEqualTo(10_000)
        assertThat(db.movieDao().countByPlaylist(1)).isEqualTo(10_000)
        assertThat(db.movieDao().getById(movie.id)!!.lastPosition).isEqualTo(1234)
        assertThat(db.movieDao().getById(movie.id)!!.isFavorite).isTrue()

        var firstPageMs = 0L
        val channelIds = mutableSetOf<Long>()
        val movieIds = mutableSetOf<Long>()
        var offset = 0
        while (offset < 10_000) {
            val elapsed = measureTimeMillis {
                val channels = f.content.getChannelsPage(1, null, 60, offset)
                val movies = f.content.getMoviesPage(1, null, CatalogSortOption.TITLE, 60, offset)
                assertThat(channels.totalCount).isEqualTo(10_000)
                assertThat(movies.totalCount).isEqualTo(10_000)
                channels.items.forEach { assertThat(channelIds.add(it.id)).isTrue() }
                movies.items.forEach { assertThat(movieIds.add(it.id)).isTrue() }
                offset += channels.items.size
            }
            if (offset == 60) firstPageMs = elapsed
        }
        assertThat(channelIds).hasSize(10_000)
        assertThat(movieIds).hasSize(10_000)
        val searchMs = measureTimeMillis {
            assertThat(f.content.searchMoviesNow(1, "Movie 5555")).isNotEmpty()
            assertThat(f.content.searchChannelsNow(1, "Live 5555")).isNotEmpty()
        }
        val warmPageMs = measureTimeMillis { f.content.getMoviesPage(1, null, CatalogSortOption.TITLE, 60, 5000) }
        assertThat(warmPageMs).isLessThan(100L)
        assertThat(firstPageMs).isLessThan(150L)
        println("PRODUCTION_CATALOG entries=20000 import_ms=$importMs refresh_ms=$refreshMs first_two_pages_ms=$firstPageMs warm_movie_page_ms=$warmPageMs sql_search_ms=$searchMs")
        println("MEMORY_SCOPE=host_JVM_not_device_PSS; disk_pages=" + db.openHelper.readableDatabase.query("PRAGMA page_count").use { it.moveToFirst(); it.getLong(0) })
    }

    @Test fun `benchmark 100k unique XMLTV programmes uses production network transaction`() = runBlocking<Unit> {
        db.playlistDao().insert(PlaylistEntity(id = 1, name = "Synthetic", type = "M3U_URL", isActive = true))
        val channels = (0 until 100).map { ChannelEntity(
            playlistId = 1, name = "Synthetic $it", epgChannelId = "synthetic-$it", streamUrl = ""
        ) }
        db.channelDao().insertAll(channels)
        val file = File(f.directory, "epg.xml")
        val start = (System.currentTimeMillis() / 3_600_000L) * 3_600_000L
        val format = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        file.bufferedWriter().use { out ->
            out.appendLine("<tv>")
            repeat(100_000) { index ->
                val time = start + index / 100 * 3_600_000L
                out.appendLine("<programme channel=\"synthetic-${index % 100}\" start=\"${format.format(Instant.ofEpochMilli(time))}\" stop=\"${format.format(Instant.ofEpochMilli(time + 3_600_000))}\"><title>Program $index</title></programme>")
            }
            out.appendLine("</tv>")
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body(object : ResponseBody() {
                    private val input = file.source().buffer()
                    override fun contentType() = null
                    override fun contentLength() = file.length()
                    override fun source() = input
                }).build()
        }.build()
        val repository = f.epg(client)
        val importMs = measureTimeMillis {
            assertThat(repository.fetchAndSave("https://example.test/epg.xml", expectedActivePlaylistId = 1))
                .isEqualTo(EpgSyncResult.Success(100_000))
        }
        val saved = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM epg_programs WHERE playlistId = 1").use {
            it.moveToFirst(); it.getInt(0)
        }
        assertThat(saved).isEqualTo(100_000)
        val guideMs = measureTimeMillis {
            val rows = repository.getGuideProgramsForChannels(
                db.channelDao().getRefreshPage(1, 0, 60).map { it.toCatalogModel() }, start, start + 4 * 3_600_000L
            )
            assertThat(rows).hasSize(60)
            rows.values.forEach { assertThat(it).hasSize(4) }
        }
        println("PRODUCTION_XMLTV unique_programmes=$saved import_ms=$importMs guide_60_ms=$guideMs source_bytes=${file.length()}")
        assertThat(guideMs).isLessThan(100L)
    }

    @Test fun `abandoned staging cleanup preserves active imports and published database`() = runBlocking<Unit> {
        val context = io.mockk.mockk<android.content.Context> { io.mockk.every { cacheDir } returns f.directory }
        db.movieDao().insertAll(listOf(MovieEntity(id = 1, playlistId = 1, name = "Original", streamUrl = "")))
        val active = CatalogStagingStore(context, 1)
        active.insertMovies(listOf(MovieEntity(playlistId = 1, name = "Pending", streamUrl = "https://example.test/private")))
        val abandoned = CatalogStagingStore(context, 2)
        abandoned.insertMovies(listOf(MovieEntity(playlistId = 2, name = "Abandoned", streamUrl = "")))
        abandoned.close()
        CatalogStagingStore.cleanupAbandoned(f.directory)
        assertThat(active.movieCount()).isEqualTo(1)
        assertThat(db.movieDao().getById(1)!!.name).isEqualTo("Original")
        assertThat(f.directory.listFiles()!!.count { it.extension == "db" }).isEqualTo(1)
        active.discard()
        abandoned.discard()
    }
}

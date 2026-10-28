package com.idealplayer.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.common.Resource
import com.idealplayer.app.core.connected.*
import com.idealplayer.app.core.database.*
import com.idealplayer.app.core.model.*
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaylistSecurityTest {
    private lateinit var fixture: RepositorySecurityFixture
    private val db get() = fixture.database
    @Before fun before() { fixture = RepositorySecurityFixture() }
    @After fun after() { fixture.close() }

    @Test fun `HTTP 200 invalid snapshots preserve every catalog and user row`() = runBlocking<Unit> {
        val playlist = seed()
        val before = snapshot(playlist.id)
        val invalid = listOf(
            "<html>sign in</html>", "{\"error\":\"synthetic-token\"}", "", "#EXTM3U\n",
            "#EXTM3U\n#EXTINF:-1,Incomplete", valid("New") + "#EXTINF:-1,Truncated\n",
            valid("New") + "https://<html>/invalid\n"
        )
        for (body in invalid) {
            val repository = fixture.playlists(client { body.toResponseBody() })
            assertThat(repository.syncPlaylist(playlist)).isInstanceOf(Resource.Error::class.java)
            assertThat(snapshot(playlist.id)).isEqualTo(before)
        }
    }

    @Test fun `transport truncation and unknown length oversize preserve every row`() = runBlocking<Unit> {
        val playlist = seed()
        val before = snapshot(playlist.id)
        val truncated = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = object : ForwardingSource(Buffer().writeUtf8(valid("New"))) {
                var first = true
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (!first) throw IOException("synthetic transport truncation")
                    first = false
                    return super.read(sink, byteCount)
                }
            }.buffer()
        }
        assertThat(fixture.playlists(client { truncated }).syncPlaylist(playlist)).isInstanceOf(Resource.Error::class.java)
        assertThat(snapshot(playlist.id)).isEqualTo(before)
        val tooLarge = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = Buffer().writeUtf8(valid("New") + "#".repeat(10 * 1024 * 1024))
        }
        assertThat(fixture.playlists(client { tooLarge }).syncPlaylist(playlist)).isInstanceOf(Resource.Error::class.java)
        assertThat(snapshot(playlist.id)).isEqualTo(before)
    }

    @Test fun `cancelling an active source read never commits its valid prefix`() = runBlocking<Unit> {
        val playlist = seed()
        val before = snapshot(playlist.id)
        lateinit var cancelRead: () -> Unit
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = object : ForwardingSource(Buffer().writeUtf8(valid("New"))) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    cancelRead()
                    return super.read(sink, byteCount)
                }
            }.buffer()
        }
        val repository = fixture.playlists(client { body })
        val job = launch(start = CoroutineStart.LAZY) { repository.syncPlaylist(playlist) }
        cancelRead = { job.cancel() }
        job.start()
        job.join()
        assertThat(job.isCancelled).isTrue()
        assertThat(snapshot(playlist.id)).isEqualTo(before)
    }

    @Test fun `same name imports retain independent encrypted sources when A is synced again`() = runBlocking<Unit> {
        val repository = fixture.playlists()
        val firstPath = fixture.files.create(valid("First"))
        val secondPath = fixture.files.create(valid("Second"))
        val firstId = repository.savePlaylist(Playlist(name = "Import", type = PlaylistType.M3U_FILE, filePath = firstPath))
        val secondId = repository.savePlaylist(Playlist(name = "Import", type = PlaylistType.M3U_FILE, filePath = secondPath))
        val first = repository.getPlaylistById(firstId)!!
        val second = repository.getPlaylistById(secondId)!!
        assertThat(first.filePath).isNotEqualTo(second.filePath)
        assertThat(File(first.filePath).readText()).doesNotContain("synthetic-secret")
        assertThat(fixture.files.open(first.filePath).bufferedReader().use { it.readText() }).isEqualTo(valid("First"))
        assertThat(repository.syncPlaylist(first)).isInstanceOf(Resource.Success::class.java)
        assertThat(repository.syncPlaylist(second)).isInstanceOf(Resource.Success::class.java)
        assertThat(repository.syncPlaylist(first)).isInstanceOf(Resource.Success::class.java)
        assertThat(db.channelDao().getByPlaylistSnapshot(firstId).single().name).isEqualTo("First")
        assertThat(db.channelDao().getByPlaylistSnapshot(secondId).single().name).isEqualTo("Second")
        // Shared references are retained until the last row is removed.
        val sharedId = repository.savePlaylist(first.copy(id = 0, name = "Shared"))
        repository.deletePlaylist(first)
        assertThat(File(first.filePath).exists()).isTrue()
        repository.deletePlaylist(repository.getPlaylistById(sharedId)!!)
        assertThat(File(first.filePath).exists()).isFalse()
        assertThat(File(second.filePath).exists()).isTrue()
        repository.deletePlaylist(second)
        assertThat(File(second.filePath).exists()).isFalse()
    }

    @Test fun `owned files refuse plaintext envelopes and remove files on cleanup`() {
        assertThat(runCatching { fixture.files.create("enc:v1:not-a-playlist") }.isFailure).isTrue()
        assertThat(fixture.directory.walk().filter { it.isFile }.toList()).isEmpty()
        val path = fixture.files.create(valid("First"))
        assertThat(fixture.files.owns(path)).isTrue()
        fixture.files.delete(path)
        assertThat(File(path).exists()).isFalse()
    }

    @Test fun `delete cannot remove a legacy external file or a SAF document`() = runBlocking<Unit> {
        val repository = fixture.playlists()
        val legacy = File(fixture.directory, "legacy.m3u").apply { writeText(valid("Legacy")) }
        for (path in listOf(legacy.absolutePath, "content://example.test/document/123")) {
            val playlist = Playlist(name = "External", type = PlaylistType.M3U_FILE, filePath = path)
            val id = repository.savePlaylist(playlist)
            repository.deletePlaylist(playlist.copy(id = id))
            assertThat(legacy.exists()).isTrue()
        }
    }

    @Test fun `disabled remote setup never opens or polls the network including duplicate polls`() = runBlocking<Unit> {
        val client = mockk<OkHttpClient>()
        val connected = ConnectedSetupRepository(fixture.context, client, fixture.playlists(), fixture.settings, fixture.files, fixture.parser)
        assertThat(connected.isEnabled).isFalse()
        assertThat(runCatching { connected.createPairingSession() }.isFailure).isTrue()
        assertThat(connected.openPairing(CODE)).isFalse()
        repeat(2) { assertThat(connected.pollPairing(CODE)).isNull() }
        verify(exactly = 0) { client.newCall(any()) }
    }

    private suspend fun seed(): Playlist {
        val id = db.playlistDao().insert(PlaylistEntity(name = "Existing", type = "M3U_URL", url = "https://example.test/playlist.m3u", channelCount = 1, movieCount = 1, seriesCount = 1))
        db.channelDao().insertAll(listOf(ChannelEntity(id = 11, playlistId = id, name = "Original", groupTitle = "Kids", streamUrl = "https://example.test/live", isFavorite = true, lastWatched = 99)))
        db.movieDao().insertAll(listOf(MovieEntity(id = 12, playlistId = id, name = "Movie", streamUrl = "https://example.test/movie", lastPosition = 123, isFavorite = true)))
        db.seriesDao().insertAll(listOf(SeriesEntity(id = 13, playlistId = id, name = "Series", lastWatchedEpisodeId = 14)))
        db.episodeDao().insertAll(listOf(EpisodeEntity(id = 14, seriesId = 13, seasonNumber = 1, episodeNumber = 1, name = "Episode", streamUrl = "https://example.test/episode", lastPosition = 456)))
        db.categoryDao().insertAll(listOf(CategoryEntity(playlistId = id, name = "Kids", contentType = "LIVE")))
        db.favoriteDao().insert(FavoriteEntity(contentId = 11, contentType = "LIVE", title = "Original"))
        db.watchHistoryDao().insert(WatchHistoryEntity(contentId = 12, contentType = "MOVIE", title = "Movie", streamUrl = "https://example.test/movie", position = 123))
        return fixture.playlists().getPlaylistById(id)!!
    }

    private suspend fun snapshot(id: Long): List<Any?> = listOf(
        db.playlistDao().getById(id), db.channelDao().getByPlaylistSnapshot(id), db.movieDao().getByPlaylistSnapshot(id),
        db.seriesDao().getByPlaylistSnapshot(id), db.episodeDao().getBySeries(13).first(),
        db.categoryDao().getByType(id, "LIVE").first(), db.favoriteDao().getAllSnapshot(), db.watchHistoryDao().getAllSnapshot()
    )

    private fun client(body: () -> ResponseBody) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body()).build()
    }.build()
    private fun filePayload(content: String) = Json.encodeToJsonElement(ConnectedSetupPayload(1, "connected-idealplayer", CODE,
        ConnectedPlaylistInfo(name = "Import", type = "file", fileName = "same.m3u", fileContent = content, epgAutoSync = false))).jsonObject
    private fun valid(name: String) = "#EXTM3U\n#EXTINF:-1 group-title=\"Kids\",$name\nhttps://example.test/$name?token=synthetic-secret\n"
    companion object { const val CODE = "A7K9Q2BC" }
}

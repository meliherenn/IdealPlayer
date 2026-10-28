package com.idealplayer.app.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.common.Resource
import com.idealplayer.app.core.database.*
import com.idealplayer.app.core.model.Playlist
import com.idealplayer.app.core.model.PlaylistType
import com.idealplayer.app.data.parser.XtreamContentSection
import com.idealplayer.app.data.parser.XtreamStreamingSink
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CatalogStagingRegressionTest {
    private lateinit var f: RepositorySecurityFixture
    private val db get() = f.database
    @Before fun before() { f = RepositorySecurityFixture() }
    @After fun after() { f.close() }

    @Test fun `staging protects source secrets on disk even before discard`() {
        val context = mockk<Context> { every { cacheDir } returns f.directory }
        val source = "https://example.test/synthetic-secret/stream"
        val staging = CatalogStagingStore(context, 1)
        try {
            staging.insertChannels(listOf(ChannelEntity(playlistId = 1, name = "Channel", streamUrl = source, catchupSource = "$source/catchup")))
            staging.insertMovies(listOf(MovieEntity(playlistId = 1, name = "Movie", streamUrl = source)))
            staging.insertEpisodes(listOf(EpisodeEntity(seriesId = 1, seasonNumber = 1, episodeNumber = 1, name = "Episode", streamUrl = source)), "Show")
            staging.insertEpgUrls(listOf("$source/epg"))
            assertThat(staging.readChannels(1, 0).single().streamUrl).isEqualTo(source)
            assertThat(staging.readChannels(1, 0).single().catchupSource).isEqualTo("$source/catchup")
            assertThat(staging.readMovies(1, 0).single().streamUrl).isEqualTo(source)
            assertThat(staging.readEpisodes(1, 0).single().streamUrl).isEqualTo(source)
            assertThat(staging.readM3uEpisodesWithSeriesName(1, 0).single().second.streamUrl).isEqualTo(source)
            assertThat(staging.getEpgUrls()).containsExactly("$source/epg")
            staging.close()
            val files = f.directory.listFiles()!!.filter { it.isFile }
            assertThat(files).isNotEmpty()
            files.forEach { assertThat(it.readBytes().toString(Charsets.ISO_8859_1)).doesNotContain("synthetic-secret") }
        } finally {
            staging.discard()
        }
        assertThat(f.directory.listFiles()!!.filter { it.name.startsWith("catalog_staging_") }).isEmpty()
    }

    @Test fun `live only Xtream refresh preserves unsupported movies series episodes and categories`() = runBlocking<Unit> {
        val playlist = Playlist(id = 1, name = "Synthetic", type = PlaylistType.XTREAM_CODES,
            serverUrl = "https://example.test", username = "user", password = "synthetic-secret")
        db.playlistDao().insert(playlist.toSecureEntity(f.codec))
        val oldLiveUrl = f.codec.encrypt("https://example.test/old-live")
        db.channelDao().insertAll(listOf(ChannelEntity(id = 50, playlistId = 1, streamId = 7,
            name = "Previous live", streamUrl = oldLiveUrl, isFavorite = true, lastWatched = 123)))
        val movie = MovieEntity(id = 10, playlistId = 1, name = "Keep movie", streamUrl = f.codec.encrypt("https://example.test/movie"), isFavorite = true, lastPosition = 123)
        val series = SeriesEntity(id = 20, playlistId = 1, name = "Keep series", lastWatchedEpisodeId = 30)
        val episode = EpisodeEntity(id = 30, seriesId = 20, seasonNumber = 1, episodeNumber = 1, name = "Keep episode", streamUrl = f.codec.encrypt("https://example.test/episode"), lastPosition = 456)
        val categories = listOf(CategoryEntity(id = 40, playlistId = 1, name = "Movies", contentType = "MOVIE"),
            CategoryEntity(id = 41, playlistId = 1, name = "Series", contentType = "SERIES"))
        db.movieDao().insertAll(listOf(movie))
        db.seriesDao().insertAll(listOf(series))
        db.episodeDao().insertAll(listOf(episode))
        db.categoryDao().insertAll(categories)
        coEvery { f.xtream.loadContentStreaming(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<XtreamStreamingSink>(4).onChannels(listOf(ChannelEntity(playlistId = 1, streamId = 7,
                name = "New live", streamUrl = "https://example.test/live/user/synthetic-secret/7")))
            setOf(XtreamContentSection.LIVE)
        }
        assertThat(f.playlists().syncPlaylist(playlist)).isInstanceOf(Resource.Success::class.java)
        assertThat(db.movieDao().getById(10)).isEqualTo(movie)
        assertThat(db.seriesDao().getById(20)).isEqualTo(series)
        assertThat(db.episodeDao().getById(30)).isEqualTo(episode)
        assertThat(db.categoryDao().getByType(1, "MOVIE").first()).containsExactly(categories[0])
        assertThat(db.categoryDao().getByType(1, "SERIES").first()).containsExactly(categories[1])
        assertThat(db.channelDao().getByPlaylistSnapshot(1).single().streamUrl).startsWith("enc:v2:")
        val live = db.channelDao().getById(50)!!
        assertThat(live.isFavorite).isTrue()
        assertThat(live.lastWatched).isEqualTo(123)
        assertThat(f.codec.decryptOrEmpty(live.streamUrl)).isEqualTo("https://example.test/live/user/synthetic-secret/7")
        verify(exactly = 0) { f.codec.decryptOrEmpty(oldLiveUrl) }
    }

    @Test fun `M3U refresh removes deleted episodes and preserves surviving playback state`() = runBlocking<Unit> {
        val playlist = Playlist(id = 1, name = "Synthetic", type = PlaylistType.M3U_URL, url = "https://example.test/list.m3u")
        db.playlistDao().insert(playlist.toSecureEntity(f.codec))
        fun document(vararg episodes: Int) = "#EXTM3U\n" + episodes.joinToString("") {
            "#EXTINF:-1 group-title=\"Series\",Example Show S01E0$it\nhttps://example.test/episode$it\n"
        }
        var body = document(1, 2)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val repository = f.playlists(client)
        assertThat(repository.syncPlaylist(playlist)).isInstanceOf(Resource.Success::class.java)
        val show = db.seriesDao().getByPlaylistSnapshot(1).single()
        val first = db.episodeDao().getBySeries(show.id).first().first { it.episodeNumber == 1 }
        db.episodeDao().updateProgress(first.id, 123, 1000)
        body = document(1)
        assertThat(repository.syncPlaylist(playlist)).isInstanceOf(Resource.Success::class.java)
        val survivor = db.episodeDao().getBySeries(show.id).first().single()
        assertThat(survivor.id).isEqualTo(first.id)
        assertThat(survivor.lastPosition).isEqualTo(123)
        assertThat(f.codec.decryptOrEmpty(survivor.streamUrl)).isEqualTo("https://example.test/episode1")
        assertThat(db.seriesDao().getById(show.id)!!.episodeCount).isEqualTo(1)
    }

    @Test fun `state claims span batches and a late publication failure rolls back the old catalog`() = runBlocking<Unit> {
        val playlist = Playlist(id = 1, name = "Synthetic", type = PlaylistType.M3U_URL, url = "https://example.test/list.m3u")
        db.playlistDao().insert(playlist.toSecureEntity(f.codec))
        db.movieDao().insertAll(listOf(MovieEntity(id = 50, playlistId = 1, streamId = 7, name = "Old",
            streamUrl = f.codec.encrypt("https://example.test/movie/u/p/7.mp4"), isFavorite = true, lastPosition = 123)))
        val body = buildString {
            appendLine("#EXTM3U")
            repeat(501) { index ->
                appendLine("#EXTINF:-1 group-title=\"Movies\",Movie $index")
                appendLine("https://example.test/movie/u/p/${if (index == 500) 7 else index + 7}.mp4")
            }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_late_movie BEFORE INSERT ON movies WHEN NEW.name = 'Movie 500' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        assertThat(f.playlists(client).syncPlaylist(playlist)).isInstanceOf(Resource.Error::class.java)
        assertThat(db.movieDao().getByPlaylistSnapshot(1).single().name).isEqualTo("Old")
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_late_movie")
        assertThat(f.playlists(client).syncPlaylist(playlist)).isInstanceOf(Resource.Success::class.java)
        assertThat(db.movieDao().countByPlaylist(1)).isEqualTo(501)
        assertThat(db.movieDao().getById(50)!!.name).isEqualTo("Movie 0")
        assertThat(db.movieDao().getById(50)!!.lastPosition).isEqualTo(123)
    }

    @Test fun `many interleaved M3U series keep episode numbering across global batch flushes`() = runBlocking<Unit> {
        val file = java.io.File(f.directory, "shows.m3u")
        file.bufferedWriter().use { out ->
            out.appendLine("#EXTM3U")
            repeat(2) { episode -> repeat(501) { show ->
                out.appendLine("#EXTINF:-1 group-title=\"Series\",Show $show S01E0${episode + 1}")
                out.appendLine("https://example.test/series/u/p/${show * 2 + episode}.mkv")
            } }
        }
        val playlist = Playlist(id = 1, name = "Synthetic", type = PlaylistType.M3U_FILE, filePath = file.path)
        db.playlistDao().insert(playlist.toSecureEntity(f.codec))
        assertThat(f.playlists().syncPlaylist(playlist)).isInstanceOf(Resource.Success::class.java)
        val shows = db.seriesDao().getByPlaylistSnapshot(1)
        assertThat(shows).hasSize(501)
        shows.forEach { show ->
            assertThat(show.episodeCount).isEqualTo(2)
            assertThat(show.seasonCount).isEqualTo(1)
            assertThat(db.episodeDao().getBySeriesSnapshot(show.id).map { it.episodeNumber }).containsExactly(1, 2).inOrder()
        }
    }
}

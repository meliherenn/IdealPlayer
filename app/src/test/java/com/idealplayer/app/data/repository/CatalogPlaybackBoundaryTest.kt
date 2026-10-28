package com.idealplayer.app.data.repository

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.database.*
import com.idealplayer.app.core.model.*
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CatalogPlaybackBoundaryTest {
    private lateinit var f: RepositorySecurityFixture
    private val db get() = f.database
    @Before fun before() = runBlocking<Unit> {
        f = RepositorySecurityFixture()
        db.playlistDao().insert(PlaylistEntity(id = 1, name = "Synthetic", type = "XTREAM_CODES",
            serverUrl = f.codec.encrypt("https://example.test/base"), username = f.codec.encrypt("u/?#% ç"),
            password = f.codec.encrypt("p/?#% ş"), isActive = true))
        db.channelDao().insertAll(listOf(ChannelEntity(id = 10, playlistId = 1, name = "Live", streamId = 42,
            streamUrl = f.codec.encrypt("https://example.test/live"), isFavorite = true, lastWatched = 100)))
        db.movieDao().insertAll(listOf(MovieEntity(id = 20, playlistId = 1, name = "Movie", streamUrl = f.codec.encrypt("https://example.test/movie"), isFavorite = true)))
        db.seriesDao().insertAll(listOf(SeriesEntity(id = 30, playlistId = 1, name = "Series")))
        db.episodeDao().insertAll(listOf(EpisodeEntity(id = 40, seriesId = 30, name = "Episode", seasonNumber = 1,
            episodeNumber = 1, streamUrl = f.codec.encrypt("https://example.test/episode"))))
        clearMocks(f.codec, answers = false)
        Unit
    }
    @After fun after() { f.close() }

    @Test fun `catalog detail search and favorites never decrypt or expose source material`() = runBlocking<Unit> {
        val channels = f.content.getChannels(1).first() + f.content.getFavoriteChannels(1).first() +
            f.content.searchChannelsNow(1, "Live") + listOfNotNull(f.content.getChannel(10))
        assertThat(channels).isNotEmpty()
        channels.forEach { assertThat(it.streamUrl).isEmpty(); assertThat(it.catchupSource).isEmpty(); assertThat(it.playbackAvailable).isTrue() }
        val movies = f.content.getMovies(1).first() + f.content.getFavoriteMovies(1).first() + listOfNotNull(f.content.getMovie(20))
        movies.forEach { assertThat(it.streamUrl).isEmpty(); assertThat(it.playbackAvailable).isTrue() }
        assertThat(f.content.getEpisode(40)!!.streamUrl).isEmpty()
        assertThat(f.content.getEpisodes(30).first().single().streamUrl).isEmpty()
        verify(exactly = 0) { f.codec.decryptOrEmpty(any()) }
        verify(exactly = 0) { f.codec.decode(any()) }
    }

    @Test fun `resolver decrypts only the selected source off Main and constructs the past stream`() = runBlocking<Unit> {
        every { f.codec.decryptOrEmpty(any()) } answers {
            assertThat(Looper.myLooper()).isNotEqualTo(Looper.getMainLooper())
            callOriginal()
        }
        val resolver = f.resolver()
        assertThat(resolver.resolve(20, ContentType.MOVIE)?.url).isEqualTo("https://example.test/movie")
        verify(exactly = 1) { f.codec.decryptOrEmpty(any()) }
        val start = 1_700_000_000_000L
        val past = resolver.resolve(10, ContentType.LIVE, CatchupWindow(start, start + 3_600_000))!!
        assertThat(past.url).isNotEqualTo(resolver.resolve(10, ContentType.LIVE)!!.url)
        assertThat(past.url.toHttpUrl().pathSegments).containsExactly("base", "timeshift", "u/?#% ç", "p/?#% ş", "60", "2023-11-14:22-13", "42.ts").inOrder()
        assertThat(past.url.toHttpUrl().query).isNull()
        assertThat(past.url.toHttpUrl().fragment).isNull()
    }

    @Test fun `unsupported catchup missing identities and locked content never fall back to live`() = runBlocking<Unit> {
        db.playlistDao().update(db.playlistDao().getById(1)!!.copy(type = "M3U_URL"))
        val resolver = f.resolver()
        val window = CatchupWindow(1_700_000_000_000, 1_700_003_600_000)
        assertThat(resolver.resolve(10, ContentType.LIVE, window)).isNull()
        assertThat(resolver.resolve(999, ContentType.MOVIE)).isNull()
        f.parental.setPin("1234")
        f.parental.setChildLock(true, f.parental.authorize("1234"))
        clearMocks(f.codec, answers = false)
        assertThat(resolver.resolve(20, ContentType.MOVIE)).isNull()
        verify(exactly = 0) { f.codec.decryptOrEmpty(any()) }
    }
}

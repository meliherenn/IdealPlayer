package com.idealplayer.app.data.repository

import android.os.Looper
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.database.*
import com.idealplayer.app.core.model.ContentType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContentAccessRepositoryTest {
    private lateinit var fixture: RepositorySecurityFixture
    private val db get() = fixture.database
    private val content get() = fixture.content
    @Before fun before() = runBlocking<Unit> {
        fixture = RepositorySecurityFixture()
        db.playlistDao().insert(PlaylistEntity(id = 1, name = "Synthetic", type = "M3U_URL"))
        for ((base, category) in listOf(10L to "Kids", 20L to "Restricted")) {
            db.channelDao().insertAll(listOf(ChannelEntity(id = base + 1, playlistId = 1, name = "Sample channel", groupTitle = category, streamUrl = "https://example.test/live/$base", isFavorite = true, lastWatched = 100)))
            db.movieDao().insertAll(listOf(MovieEntity(id = base + 2, playlistId = 1, name = "Sample movie", categoryName = category, streamUrl = "https://example.test/movie/$base", isFavorite = true, lastPosition = 30, totalDuration = 100, lastWatched = 100)))
            db.seriesDao().insertAll(listOf(SeriesEntity(id = base + 3, playlistId = 1, name = "Sample series", categoryName = category, isFavorite = true, lastWatchedEpisodeId = base + 4)))
            db.episodeDao().insertAll(listOf(EpisodeEntity(id = base + 4, seriesId = base + 3, name = "Episode", seasonNumber = 1, episodeNumber = 1, streamUrl = "https://example.test/series/$base", lastPosition = 30, totalDuration = 100, lastWatched = 100)))
            for ((offset, type) in listOf(1 to "LIVE", 2 to "MOVIE", 3 to "SERIES")) {
                db.favoriteDao().insert(FavoriteEntity(contentId = base + offset, contentType = type, title = "Favorite"))
                db.watchHistoryDao().insert(WatchHistoryEntity(contentId = base + if (type == "SERIES") 4 else offset, contentType = type, title = "History", streamUrl = "https://example.test/history/$base", progress = 0.3f, position = 30, totalDuration = 100))
            }
        }
        fixture.parental.setPin("1234")
        val grant = fixture.parental.authorize("1234")
        fixture.parental.setCategoryAllowed("Kids", true, grant)
        fixture.parental.setChildLock(true, grant)
        Unit
    }
    @After fun after() { fixture.close() }

    @Test fun `browse search favorites recent and resume lists all apply the policy`() = runBlocking<Unit> {
        assertThat(content.getChannels(1).first().map { it.id }).containsExactly(11L)
        assertThat(content.getRecentlyWatchedChannels(1).first().map { it.id }).containsExactly(11L)
        assertThat(content.getFavoriteChannels(1).first().map { it.id }).containsExactly(11L)
        assertThat(content.getChannelsByGroup(1, "Restricted").first()).isEmpty()
        assertThat(content.getMovies(1).first().map { it.id }).containsExactly(12L)
        assertThat(content.getFavoriteMovies(1).first().map { it.id }).containsExactly(12L)
        assertThat(content.getContinueWatchingMovies(1).first().map { it.id }).containsExactly(12L)
        assertThat(content.getSeries(1).first().map { it.id }).containsExactly(13L)
        assertThat(content.getFavoriteSeries(1).first().map { it.id }).containsExactly(13L)
        assertThat(content.getChannelGroups(1).first()).containsExactly("Kids")
        assertThat(content.getMovieCategories(1).first()).containsExactly("Kids")
        assertThat(content.getSeriesCategories(1).first()).containsExactly("Kids")
        assertThat(content.searchChannelsNow(1, "Sample").map { it.id }).containsExactly(11L)
        assertThat(content.searchMoviesNow(1, "Sample").map { it.id }).containsExactly(12L)
        assertThat(content.searchSeriesNow(1, "Sample").map { it.id }).containsExactly(13L)
        assertThat(content.searchChannels(1, "Sample").first().map { it.id }).containsExactly(11L)
        assertThat(content.searchMovies(1, "Sample").first().map { it.id }).containsExactly(12L)
        assertThat(content.searchSeries(1, "Sample").first().map { it.id }).containsExactly(13L)
        assertThat(content.getAllFavorites().first().map { it.contentId }).containsExactly(11L, 12L, 13L)
        assertThat(content.getWatchHistory().first().map { it.contentId }).containsExactly(11L, 12L, 14L)
        assertThat(content.getContinueWatching().first().map { it.contentId }).containsExactly(11L, 12L, 14L)
    }

    @Test fun `direct identities and episodes cannot bypass a locked parent category`() = runBlocking<Unit> {
        assertThat(content.getChannel(21)).isNull()
        assertThat(content.getMovie(22)).isNull()
        assertThat(content.getSeriesById(23)).isNull()
        assertThat(content.getEpisode(24)).isNull()
        assertThat(content.getEpisodes(23).first()).isEmpty()
        assertThat(content.getEpisodesBySeason(23, 1).first()).isEmpty()
        assertThat(content.getSeasons(23).first()).isEmpty()
        assertThat(content.getLastWatchedEpisode(23)).isNull()
        assertThat(content.getNextEpisode(23, 1, 0)).isNull()
        assertThat(content.getSeriesResumeEpisode(23)).isNull()
        assertThat(content.getSeriesResumeEpisode(13)?.id).isEqualTo(14L)
        for ((id, type) in listOf(21L to "LIVE", 22L to "MOVIE", 24L to "SERIES", 0L to "LIVE", 999L to "MOVIE")) {
            assertThat(content.canPlay(id, type)).isFalse()
        }
        for ((id, type) in listOf(11L to "LIVE", 12L to "MOVIE", 14L to "SERIES")) {
            assertThat(content.canPlay(id, type)).isTrue()
        }
        assertThat(fixture.access.allows(23, ContentType.SERIES)).isFalse()
    }

    @Test fun `open browse flow removes content as soon as the allow list is revoked`() = runBlocking<Unit> {
        content.getChannels(1).test {
            assertThat(awaitItem().map { it.id }).containsExactly(11L)
            fixture.parental.setCategoryAllowed("Kids", false, fixture.parental.authorize("1234"))
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `category changes invalidate stored favorite history and playback access`() = runBlocking<Unit> {
        content.observeContentAccess(12, ContentType.MOVIE).test {
            assertThat(awaitItem()).isTrue()
            db.movieDao().insertAll(listOf(db.movieDao().getById(12)!!.copy(categoryName = "Restricted")))
            assertThat(awaitItem()).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(content.getAllFavorites().first().map { it.contentId }).doesNotContain(12L)
        assertThat(content.getContinueWatching().first().map { it.contentId }).doesNotContain(12L)
        // Corrupt/stale resume references never transfer permission to another series.
        db.seriesDao().insertAll(listOf(db.seriesDao().getById(13)!!.copy(lastWatchedEpisodeId = 24)))
        assertThat(content.getSeriesResumeEpisode(13)?.id).isEqualTo(14L)
    }

    @Test fun `unlock restores access while missing category stays blocked under lock`() = runBlocking<Unit> {
        db.channelDao().insertAll(listOf(ChannelEntity(id = 31, playlistId = 1, name = "Unknown", streamUrl = "https://example.test/unknown")))
        assertThat(content.getChannel(31)).isNull()
        fixture.parental.setChildLock(false, fixture.parental.authorize("1234"))
        assertThat(content.getChannels(1).first()).hasSize(3)
        assertThat(content.getEpisode(24)).isNotNull()
    }
}

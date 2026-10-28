package com.idealplayer.app.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.datastore.*
import com.idealplayer.app.core.model.*
import com.idealplayer.app.data.repository.*
import com.idealplayer.app.ui.home.HomeViewModel
import com.idealplayer.app.ui.movies.MoviesViewModel
import com.idealplayer.app.ui.series.SeriesViewModel
import com.idealplayer.app.ui.tv.TvFavoritesViewModel
import com.idealplayer.app.ui.live.LiveTvViewModel
import com.idealplayer.app.ui.guide.TvGuideViewModel
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActivePlaylistSubscriptionsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val active = MutableStateFlow<PlaylistSummary?>(null)
    private val playlists = mockk<PlaylistRepository>(relaxed = true)
    private val content = mockk<ContentRepository>(relaxed = true)
    private val epg = mockk<EpgRepository>(relaxed = true)
    private val settings = mockk<SettingsDataStore> { every { settings } returns flowOf(AppSettings()) }
    private val movieRows = (1L..2L).associateWith { MutableStateFlow(listOf(Movie(id = it, playlistId = it, name = "Movie $it", streamUrl = ""))) }
    private val seriesRows = (1L..2L).associateWith { MutableStateFlow(listOf(Series(id = it, playlistId = it, name = "Series $it"))) }
    private val channelRows = (1L..2L).associateWith { MutableStateFlow(listOf(Channel(id = it, playlistId = it, name = "Channel $it", streamUrl = ""))) }
    private val collectors = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    private fun <T> tracked(id: Long, flow: Flow<T>) = flow.onStart { collectors.merge(id, 1, Int::plus) }
        .onCompletion { collectors.merge(id, -1, Int::plus) }

    @Before fun before() {
        Dispatchers.setMain(dispatcher)
        every { playlists.getActivePlaylistSummary() } returns active
        every { content.accessChanges } returns flowOf(com.idealplayer.app.core.security.ParentalAccessState(ready = true, enabled = false))
        every { content.catalogAccessPolicy } returns MutableStateFlow(com.idealplayer.app.core.security.ParentalAccessState(ready = true, enabled = false))
        coEvery { playlists.ensureEpgSynced(any<Long>(), any()) } returns false
        coEvery { content.backfillMissingMovieArtwork(any(), any()) } returns 0
        coEvery { content.backfillMissingSeriesArtwork(any(), any()) } returns 0
        every { content.getContinueWatching(any()) } returns flowOf(emptyList())
        for (id in 1L..2L) {
            every { content.getMoviePreview(id) } returns tracked(id, movieRows.getValue(id))
            every { content.getRecentMovies(id) } returns tracked(id, movieRows.getValue(id))
            every { content.getLatestAddedMovies(id) } returns tracked(id, movieRows.getValue(id))
            every { content.getFavoriteMovies(id) } returns tracked(id, movieRows.getValue(id))
            every { content.getSeriesPreview(id) } returns tracked(id, seriesRows.getValue(id))
            every { content.getLatestAddedSeries(id) } returns tracked(id, seriesRows.getValue(id))
            every { content.getFavoriteSeries(id) } returns tracked(id, seriesRows.getValue(id))
            every { content.getChannels(id) } returns tracked(id, channelRows.getValue(id))
            every { content.getFavoriteChannels(id) } returns tracked(id, channelRows.getValue(id))
            every { content.getRecentlyWatchedChannels(id) } returns tracked(id, channelRows.getValue(id))
            // Room observers remain subscribed; finite flows complete during a page emission.
            every { content.getMovieCategories(id) } returns tracked(id, MutableStateFlow(listOf("Category $id")))
            every { content.getSeriesCategories(id) } returns tracked(id, MutableStateFlow(listOf("Category $id")))
            every { content.getChannelGroups(id) } returns tracked(id, MutableStateFlow(listOf("Category $id")))
            every { content.getMovieCategoryCounts(id) } returns tracked(id, MutableStateFlow(mapOf("Category $id" to 1)))
            every { content.getSeriesCategoryCounts(id) } returns tracked(id, MutableStateFlow(mapOf("Category $id" to 1)))
            every { content.getChannelGroupCounts(id) } returns tracked(id, MutableStateFlow(mapOf("Category $id" to 1)))
            coEvery { content.getMoviesPage(id, any(), any(), any(), any()) } answers { CatalogPage(movieRows.getValue(id).value, movieRows.getValue(id).value.size) }
            coEvery { content.getMovieFilteredCount(id, any()) } answers { movieRows.getValue(id).value.size }
            coEvery { content.getSeriesPage(id, any(), any(), any(), any()) } answers { CatalogPage(seriesRows.getValue(id).value, seriesRows.getValue(id).value.size) }
            coEvery { content.getChannelsPage(id, any(), any(), any(), any()) } answers { CatalogPage(channelRows.getValue(id).value, channelRows.getValue(id).value.size) }
            coEvery { content.getSeriesFilteredCount(id, any()) } answers { seriesRows.getValue(id).value.size }
            every { epg.observeSyncState(id) } returns flowOf(null)
        }
        coEvery { epg.getGuideProgramsForChannels(any(), any(), any()) } returns emptyMap()
        coEvery { epg.getCurrentProgramsForChannels(any()) } returns emptyMap()
    }
    @After fun after() { stores.forEach(ViewModelStore::clear); Dispatchers.resetMain() }

    @Test fun `Home movies series favorites live and guide each own one cancellable source tree`() = runTest(dispatcher) {
        val screens: List<Pair<ViewModel, Flow<List<Long>>>> = listOf(
            HomeViewModel(playlists, content, settings).let { it to it.state.map { state -> state.allMovies.map(Movie::id) } },
            MoviesViewModel(playlists, content).let { it to it.state.map { state -> state.movies.map(Movie::id) } },
            SeriesViewModel(playlists, content).let { it to it.state.map { state -> state.series.map(Series::id) } },
            TvFavoritesViewModel(playlists, content).let { it to it.state.map { state -> state.movies.map(Movie::id) } },
            LiveTvViewModel(playlists, content, epg).let { it to it.state.map { state -> state.channels.map(Channel::id) } },
            TvGuideViewModel(playlists, content, epg).let { it to it.state.map { state -> state.rows.map { row -> row.channel.id } } }
        )
        screens.forEach { (vm, _) -> stores += ViewModelStore().also { it.put("test", vm) } }
        suspend fun awaitRows(expected: List<Long>) = withContext(Dispatchers.Default) {
            // The ViewModels deliberately use real IO for artwork/EPG. A virtual timeout can
            // expire while those children are merely returning to the test dispatcher.
            withTimeout(10_000) {
                screens.forEach { (_, rows) -> rows.first { it == expected } }
            }
        }
        fun source(id: Long, revision: Long = 0) = Playlist(id = id, name = "Source $id", type = PlaylistType.M3U_URL, sourceRevision = revision).toSummary()
        active.value = source(1); awaitRows(listOf(1))
        val subscriptions = collectors[1]!!
        assertThat(subscriptions).isGreaterThan(0)
        active.value = source(2); awaitRows(listOf(2))
        assertThat(collectors[1]).isEqualTo(0)
        assertThat(collectors[2]).isEqualTo(subscriptions)
        movieRows.getValue(1).value = listOf(Movie(id = 91, playlistId = 1, name = "Late A", streamUrl = ""))
        screens.forEach { (_, rows) -> assertThat(rows.first()).containsExactly(2L) }
        movieRows.getValue(1).value = listOf(Movie(id = 1, playlistId = 1, name = "A again", streamUrl = ""))
        active.value = source(1, 1); awaitRows(listOf(1))
        assertThat(collectors[2]).isEqualTo(0)
        assertThat(collectors[1]).isEqualTo(subscriptions)
        active.value = source(1, 2); awaitRows(listOf(1))
        active.value = null; awaitRows(emptyList())
        assertThat(collectors.values.sum()).isEqualTo(0)
    }
}

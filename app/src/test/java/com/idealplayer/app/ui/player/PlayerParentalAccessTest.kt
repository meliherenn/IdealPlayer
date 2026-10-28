package com.idealplayer.app.ui.player

import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.datastore.AppSettings
import com.idealplayer.app.core.datastore.SettingsDataStore
import com.idealplayer.app.core.model.Channel
import com.idealplayer.app.core.player.*
import com.idealplayer.app.core.security.ParentalAccessState
import com.idealplayer.app.data.repository.ContentRepository
import com.idealplayer.app.data.repository.PlaylistRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerParentalAccessTest {
    private val store = ViewModelStore()
    private val policy = MutableStateFlow(ParentalAccessState(ready = true, isPinSet = true, enabled = true))
    private val player = mockk<PlayerManager>(relaxed = true)
    private val content = mockk<ContentRepository>(relaxed = true)
    private val bridge = mockk<TvMediaSessionBridge>(relaxed = true)
    private lateinit var viewModel: PlayerViewModel

    @Before fun before() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { player.isOwner(any()) } returns true
        every { player.state } returns MutableStateFlow(PlayerState())
        every { player.diagnostics } returns MutableStateFlow(PlaybackDiagnostics())
        every { player.sessionState } returns MutableStateFlow(PlaybackSessionState.Idle)
        every { player.metrics } returns MutableStateFlow(PlaybackMetricsSnapshot())
        every { content.accessChanges } returns policy
        coEvery { content.canPlay(any(), any()) } returns false
        coEvery { content.getMovie(any()) } returns null
        coEvery { content.getEpisode(any()) } returns null
        coEvery { content.getChannel(any()) } returns null
        val settings = mockk<SettingsDataStore> { every { settings } returns flowOf(AppSettings()) }
        val playlists = mockk<PlaylistRepository> { every { getActivePlaylistSummary() } returns flowOf(null) }
        val retry = mockk<StreamRetryManager>(relaxed = true) { every { state } returns MutableStateFlow(RetryState()) }
        val sleep = mockk<SleepTimerManager>(relaxed = true) {
            every { state } returns MutableStateFlow(SleepTimerState())
            every { availableOptions } returns listOf(15, 30)
        }
        viewModel = PlayerViewModel(player, settings, content, playlists, retry, sleep, mockk(relaxed = true), bridge, mockk(relaxed = true), mockk(relaxed = true))
        store.put("player", viewModel)
    }
    @After fun after() { store.clear(); Dispatchers.resetMain() }

    @Test fun `raw route retry and play pause cannot start restricted movie`() = runBlocking<Unit> {
        viewModel.init("https://example.test/restricted.mp4", "Restricted", 100L, 22L, "MOVIE", "")
        viewModel.retryCurrent()
        viewModel.togglePlayPause()
        coVerify(exactly = 0) { player.play(any(), any(), any(), any()) }
        coVerify(exactly = 0) { player.resume(any()) }
        coVerify(atLeast = 1) { player.stop(any()) }
        verify { player.reportPlaybackFailure(PlaybackFailure.Authorization, any()) }
        assertThat(viewModel.session.value.movie).isNull()
        assertThat(viewModel.session.value.title).isEmpty()
    }

    @Test fun `TV transport resume and a forged cached channel still use stored identity`() = runBlocking<Unit> {
        val onPlay = slot<() -> Unit>()
        every { bridge.activate(any(), any(), capture(onPlay), any(), any(), any()) } just Runs
        viewModel.configureTvPlayback(true)
        viewModel.init("https://example.test/restricted.ts", "Restricted", 0, 21, "LIVE", "Kids")
        viewModel.playChannel(Channel(id = 21, playlistId = 1, name = "Forged category", groupTitle = "Kids", streamUrl = "https://example.test/restricted.ts"))
        onPlay.captured()
        coVerify(exactly = 0) { player.play(any(), any(), any(), any()) }
        coVerify(exactly = 0) { player.resume(any()) }
        coVerify { content.canPlay(21, "LIVE") }
    }
}

package com.idealplayer.app.ui.player

import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.datastore.AppSettings
import com.idealplayer.app.core.datastore.SettingsDataStore
import com.idealplayer.app.core.model.Movie
import com.idealplayer.app.core.player.*
import com.idealplayer.app.data.repository.ContentRepository
import com.idealplayer.app.data.repository.PlaylistRepository
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val player = mockk<PlayerManager>(relaxed = true)
    private val state = MutableStateFlow(PlayerState())
    private val metrics = MutableStateFlow(PlaybackMetricsSnapshot())
    private val writer = mockk<PlaybackProgressWriter>()
    private val checkpoint = CompletableDeferred<Boolean>()
    private val played = CompletableDeferred<Unit>()
    private lateinit var viewModel: PlayerViewModel
    private lateinit var sleep: SleepTimerManager
    private val resolver = mockk<com.idealplayer.app.data.repository.PlaybackSourceResolver>()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { player.isOwner(any()) } returns true
        every { player.state } returns state
        every { player.diagnostics } returns MutableStateFlow(PlaybackDiagnostics())
        every { player.sessionState } returns MutableStateFlow(PlaybackSessionState.Idle)
        every { player.metrics } returns metrics
        every { writer.enqueue(any()) } returns checkpoint
        coEvery { player.play(any(), any(), any(), any()) } coAnswers {
            metrics.value = PlaybackMetricsSnapshot(requestId = 1)
            state.value = PlayerState(requestId = 1, playbackState = PlaybackState.PLAYING,
                isPlaying = true, isPlaybackConfirmed = true, currentPosition = 42_000, duration = 120_000)
            played.complete(Unit)
        }
        val settings = mockk<SettingsDataStore> { every { settings } returns flowOf(AppSettings()) }
        val content = mockk<ContentRepository>(relaxed = true) {
            every { accessChanges } returns emptyFlow()
            coEvery { canPlay(any(), any()) } returns true
            coEvery { getMovie(22) } returns Movie(id = 22, playlistId = 1, name = "Fixture", streamUrl = "https://example.test/movie.mp4")
        }
        val playlists = mockk<PlaylistRepository> { every { getActivePlaylistSummary() } returns flowOf(null) }
        val retry = mockk<StreamRetryManager>(relaxed = true) { every { state } returns MutableStateFlow(RetryState()) }
        sleep = mockk<SleepTimerManager>(relaxed = true) {
            every { state } returns MutableStateFlow(SleepTimerState())
            every { availableOptions } returns listOf(15)
        }
        viewModel = PlayerViewModel(player, settings, content, playlists, retry, sleep, mockk(relaxed = true), mockk(relaxed = true), writer, resolver)
        store.put("player", viewModel)
    }

    @After fun cleanup() {
        checkpoint.complete(true)
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun `clearing a departing handoff owner keeps shared sleep timer and engine`() {
        every { player.isHandoffPendingFrom(any()) } returns true
        store.clear()
        dispatcher.scheduler.runCurrent()
        verify(exactly = 0) { sleep.cancel(); player.release(any()) }
    }

    private suspend fun startMovie() {
        viewModel.init("https://example.test/movie.mp4", "Fixture", 12_000, 22, "MOVIE", "")
        played.await()
    }

    private fun lifecycleTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            block()
        } finally {
            store.clear()
            runCurrent()
        }
    }

    @Test fun `typed catchup uses its resolved past URL with VOD retry and keeps the time window`() = lifecycleTest {
        val window = com.idealplayer.app.core.model.CatchupWindow(1_700_000_000_000, 1_700_003_600_000)
        coEvery { resolver.resolve(41, com.idealplayer.app.core.model.ContentType.LIVE, window) } returns
            com.idealplayer.app.data.repository.ResolvedPlaybackSource("https://example.test/past.mp4", "Past programme")
        viewModel.init("", "", 12_000, 41, "LIVE", "", window.startTimeMs, window.endTimeMs)
        played.await()
        runCurrent()
        assertThat(viewModel.session.value.isCatchup).isTrue()
        coVerify(exactly = 1) { resolver.resolve(41, com.idealplayer.app.core.model.ContentType.LIVE, window) }
        coVerify { player.play("https://example.test/past.mp4", 12_000, PlaybackProfile.VOD, any()) }
        clearMocks(player, answers = false, recordedCalls = true)
        viewModel.retryCurrent()
        runCurrent()
        coVerify { player.play("https://example.test/past.mp4", 42_000, PlaybackProfile.VOD, any()) }
        coVerify(exactly = 0) { player.play(any(), any(), PlaybackProfile.LIVE, any()) }
    }

    @Test fun `exit waits for immutable checkpoint before release and navigation`() = lifecycleTest {
        startMovie()
        runCurrent()
        var navigated = false
        viewModel.exitPlayer { navigated = true }
        runCurrent()
        assertThat(navigated).isFalse()
        coVerify(exactly = 0) { player.releaseAndAwait(any()) }
        verify { writer.enqueue(match { it.contentId == 22L && it.position == 42_000L && it.confirmed }) }
        state.value = PlayerState()
        checkpoint.complete(true)
        runCurrent()
        coVerify(exactly = 1) { player.releaseAndAwait(any()) }
        assertThat(navigated).isTrue()
    }

    @Test fun `TV Home stops playback and blocks queued retry until visible`() = lifecycleTest {
        startMovie()
        runCurrent()
        clearMocks(player, answers = false, recordedCalls = true)
        viewModel.onPlaybackHidden(PlaybackVisibility(isTv = true, isVisible = false))
        viewModel.retryCurrent()
        runCurrent()
        coVerify(exactly = 1) { player.stop(any()) }
        coVerify(exactly = 0) { player.play(any(), any(), any(), any()) }
        state.value = PlayerState()
        checkpoint.complete(true)
        viewModel.onPlaybackVisible()
        runCurrent()
        coVerify { player.play(any(), 42_000, PlaybackProfile.VOD, any()) }
    }

    @Test fun `valid mobile PiP and rotation retain the active request`() = lifecycleTest {
        startMovie()
        runCurrent()
        clearMocks(player, answers = false, recordedCalls = true)
        viewModel.onPlaybackHidden(PlaybackVisibility(false, false, true))
        viewModel.onPlaybackHidden(PlaybackVisibility(false, false, isChangingConfigurations = true), ownerRemoved = true)
        runCurrent()
        coVerify(exactly = 0) { player.stop(any()) }
        coVerify(exactly = 0) { player.releaseAndAwait(any()) }
    }

    @Test fun `inline removal releases even though its navigation ViewModel remains alive`() = lifecycleTest {
        startMovie()
        runCurrent()
        checkpoint.complete(true)
        viewModel.onPlaybackHidden(PlaybackVisibility(false, true), ownerRemoved = true)
        runCurrent()
        coVerify { player.stop(any()) }
        coVerify { player.releaseAndAwait(any()) }
        verify { sleep.cancel() }
        assertThat(viewModel.playerReady.value).isFalse()
    }

    @Test fun `sleep expiry while hidden prevents automatic resume on return`() = lifecycleTest {
        startMovie()
        runCurrent()
        val expiry = slot<() -> Unit>()
        every { sleep.start(any(), capture(expiry)) } just Runs
        viewModel.setSleepTimer(15)
        clearMocks(player, answers = false, recordedCalls = true)
        viewModel.onPlaybackHidden(PlaybackVisibility(true, false))
        checkpoint.complete(true)
        runCurrent()

        expiry.captured()
        viewModel.onPlaybackVisible()
        runCurrent()

        coVerify(exactly = 0) { player.play(any(), any(), any(), any()) }
        coVerify(exactly = 1) { player.pause(any()) }
    }

    @Test fun `sleep expiry cancels a visibility resume waiting for its checkpoint`() = lifecycleTest {
        startMovie()
        runCurrent()
        val expiry = slot<() -> Unit>()
        every { sleep.start(any(), capture(expiry)) } just Runs
        viewModel.setSleepTimer(15)
        clearMocks(player, answers = false, recordedCalls = true)
        viewModel.onPlaybackHidden(PlaybackVisibility(true, false))
        runCurrent()
        viewModel.onPlaybackVisible()
        runCurrent()

        expiry.captured()
        checkpoint.complete(true)
        runCurrent()

        coVerify(exactly = 0) { player.play(any(), any(), any(), any()) }
    }

    @Test fun `HTTP retry preserves VOD position after engine clears timeline`() = lifecycleTest {
        startMovie()
        runCurrent()
        clearMocks(player, answers = false, recordedCalls = true)
        state.value = PlayerState(requestId = 1, playbackState = PlaybackState.ERROR, errorMessage = "HTTP_STATUS 503")
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        coVerify { player.play(any(), 42_000, PlaybackProfile.VOD, any()) }
    }

    @Test fun `external subtitles survive M3uParser through PlayerViewModel resolvePlaybackCandidates to ExoPlayer`() = lifecycleTest {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 subtitles="https://media.example.test/subs/movie,part1.tr.srt" group-title="Movies",Synthetic Movie
            #EXTVLCOPT:sub-file=https://media.example.test/subs/movie;part2.en.vtt
            #EXTVLCOPT:http-cookie=auth_token=secret123
            https://media.example.test/movie/1.mkv
        """.trimIndent()
        val parsed = com.idealplayer.app.data.parser.M3uParser().parseText(m3u, 1L)
        val movieUrl = parsed.movies.single().streamUrl

        val movieCandidates = viewModel.resolvePlaybackCandidates(movieUrl, "MOVIE")
        val resolvedMovieSource = parsePlaybackSource(movieCandidates.single())
        assertThat(resolvedMovieSource.url).isEqualTo("https://media.example.test/movie/1.mkv")
        assertThat(resolvedMovieSource.headers["Cookie"]).isEqualTo("auth_token=secret123")
        assertThat(resolvedMovieSource.subtitleUrls).containsExactly(
            "https://media.example.test/subs/movie,part1.tr.srt",
            "https://media.example.test/subs/movie;part2.en.vtt"
        ).inOrder()

        val seriesUrl = withPlaybackHeaders(
            rawUrl = "https://media.example.test/series/user/pass/101",
            additionalHeaders = mapOf("Referer" to "https://portal.example.test"),
            additionalSubtitles = listOf("https://media.example.test/subs/ep1.tr.srt")
        )
        val seriesCandidates = viewModel.resolvePlaybackCandidates(seriesUrl, "SERIES")
        assertThat(seriesCandidates).isNotEmpty()
        seriesCandidates.forEach { candidate ->
            val candidateSource = parsePlaybackSource(candidate)
            assertThat(candidateSource.headers["Referer"]).isEqualTo("https://portal.example.test")
            assertThat(candidateSource.subtitleUrls).containsExactly("https://media.example.test/subs/ep1.tr.srt")
        }
    }

    @Test fun `fullscreen adopts working TS fallback without stopping or retrying preferred HLS`() = lifecycleTest {
        val hls = "https://example.test/live/user/pass/7.m3u8"
        val ts = "https://example.test/live/user/pass/7.ts"
        every { player.canResumeHandoff(any(), any(), any()) } answers { firstArg<String>() == ts }
        assertThat(viewModel.resolvePlaybackCandidates(hls, "LIVE").first()).isEqualTo(hls)
        viewModel.init(hls, "Fixture", 0L, 7L, "LIVE", "")
        played.await()
        runCurrent()
        coVerify(exactly = 0) { player.stop(any()); player.play(hls, any(), any(), any()) }
        coVerify(exactly = 1) { player.play(ts, 0L, PlaybackProfile.LIVE, any()) }
    }

    @Test fun `live channel dwell timer suppresses rapid zaps and records channel after 15 seconds`() = lifecycleTest {
        val channel11Ready = CompletableDeferred<Unit>()
        val content = mockk<ContentRepository>(relaxed = true) {
            every { accessChanges } returns emptyFlow()
            coEvery { canPlay(any(), any()) } returns true
            coEvery { getChannel(any()) } answers {
                val id = firstArg<Long>()
                if (id == 11L) channel11Ready.complete(Unit)
                com.idealplayer.app.core.model.Channel(id = id, playlistId = 1, name = "Ch $id", streamUrl = "")
            }
        }
        val settings = mockk<SettingsDataStore> {
            every { settings } returns flowOf(AppSettings(rememberLastChannel = true, preferredLiveStreamFormat = "TS"))
        }
        val playlists = mockk<PlaylistRepository> { every { getActivePlaylistSummary() } returns flowOf(null) }
        val retry = mockk<StreamRetryManager>(relaxed = true) { every { state } returns MutableStateFlow(RetryState()) }
        coEvery { resolver.resolve(11L, com.idealplayer.app.core.model.ContentType.LIVE, null) } returns
            com.idealplayer.app.data.repository.ResolvedPlaybackSource("https://example.test/live/u/p/11.m3u8", "Ch 11")

        val vm = PlayerViewModel(player, settings, content, playlists, retry, sleep, mockk(relaxed = true), mockk(relaxed = true), writer, resolver)
        store.put("player-dwell", vm)

        // Start channel 10: with preferredLiveStreamFormat = TS, .ts candidate should be tried first!
        vm.init("https://example.test/live/u/p/10.m3u8", "Ch 10", 0L, 10L, "LIVE", "")
        played.await()
        advanceTimeBy(1_000L)
        runCurrent()
        coVerify { player.play("https://example.test/live/u/p/10.ts", 0L, PlaybackProfile.LIVE, any()) }

        // Advance only 5 seconds (< 15s dwell timer) and zap to channel 11
        advanceTimeBy(5_000L)
        runCurrent()
        coVerify(exactly = 0) { content.updateChannelLastWatched(10L) }

        val secondPlayed = CompletableDeferred<Unit>()
        coEvery { player.play("https://example.test/live/u/p/11.ts", any(), any(), any()) } coAnswers {
            metrics.value = PlaybackMetricsSnapshot(requestId = 2)
            state.value = PlayerState(requestId = 2, playbackState = PlaybackState.PLAYING,
                isPlaying = true, isPlaybackConfirmed = true)
            secondPlayed.complete(Unit)
        }
        vm.playChannel(com.idealplayer.app.core.model.Channel(id = 11L, playlistId = 1L, name = "Ch 11", streamUrl = ""))
        advanceTimeBy(50L)
        runCurrent()
        secondPlayed.await()
        advanceTimeBy(1_000L)
        runCurrent()
        // Wait for the real Dispatchers.IO refreshSessionContext call to finish so scheduleLiveHistoryDwell is launched
        channel11Ready.await()
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()
        // Channel 10 was left before 15s; Channel 11 is only at 10s
        coVerify(exactly = 0) { content.updateChannelLastWatched(10L) }
        coVerify(exactly = 0) { content.updateChannelLastWatched(11L) }

        // Paused wall-clock time must not mark this channel as watched.
        state.value = state.value.copy(isPlaying = false, playbackState = PlaybackState.PAUSED)
        runCurrent()
        advanceTimeBy(30_000L)
        runCurrent()
        coVerify(exactly = 0) { content.updateChannelLastWatched(11L) }
        state.value = state.value.copy(isPlaying = true, playbackState = PlaybackState.PLAYING)
        runCurrent()
        advanceTimeBy(14_999L)
        runCurrent()
        coVerify(exactly = 0) { content.updateChannelLastWatched(11L) }
        advanceTimeBy(1L)
        runCurrent()
        coVerify(exactly = 0) { content.updateChannelLastWatched(10L) }
        coVerify(exactly = 1) { content.updateChannelLastWatched(11L) }
    }
}

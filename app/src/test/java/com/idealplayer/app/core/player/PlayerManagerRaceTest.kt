package com.idealplayer.app.core.player

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.datastore.AppSettings
import com.idealplayer.app.core.datastore.SettingsDataStore
import com.idealplayer.app.core.model.PlayerEngineType
import io.mockk.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
class PlayerManagerRaceTest {
    private val dispatcher = StandardTestDispatcher()
    private val exo = mockk<ExoPlayerEngine>(relaxed = true)
    private val vlc = mockk<VlcPlayerEngine>(relaxed = true)
    private val releaseVlc = mockk<() -> Unit>(relaxed = true)
    private val exoState = MutableStateFlow(PlayerState())
    private val vlcState = MutableStateFlow(PlayerState())
    private val settings = mockk<SettingsDataStore>(relaxed = true)
    private val captions = MutableStateFlow(defaultSystemCaptionPreferences())
    private lateinit var manager: PlayerManager
    private val owner = Any()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { settings.settings } returns flowOf(AppSettings())
        coEvery { settings.getPlaybackEngineProfile(any()) } returns null
        every { exo.isAvailable() } returns true
        every { exo.state } returns exoState
        every { vlc.state } returns vlcState
        every { vlc.prepareRelease() } returns releaseVlc
        every { exo.engineName } returns "ExoPlayer"
        every { vlc.engineName } returns "VLC"
        every { exo.play(any(), any(), any(), any()) } answers {
            exoState.value = PlayerState(requestId = arg(3), playbackState = PlaybackState.BUFFERING, currentPosition = arg(1))
        }
        every { vlc.play(any(), any(), any(), any()) } answers {
            vlcState.value = PlayerState(requestId = arg(3), playbackState = PlaybackState.BUFFERING, currentPosition = arg(1))
        }
        manager = PlayerManager(Provider { exo }, Provider { vlc }, settings, mockk {
            every { snapshot() } answers { captions.value }
            every { changes() } returns captions
        })
        manager.claimOwnership(owner)
    }

    @After fun cleanup() {
        try {
            runTest(dispatcher) {
                // VLC destruction runs on real IO. Draining virtual tasks alone can
                // reset Main while that release is still dispatching its completion.
                if (::manager.isInitialized) manager.release()?.join()
                runCurrent()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `preview fullscreen and return preserve the same live request without release or prepare`() = runTest(dispatcher) {
        manager.play("https://example.test/live.ts", profile = PlaybackProfile.LIVE, owner = owner)
        runCurrent()
        val requestId = exoState.value.requestId
        assertThat(manager.prepareSeamlessHandoff(owner)).isTrue()
        val fullscreen = Any()
        clearMocks(exo, answers = false, recordedCalls = true)
        manager.claimOwnership(fullscreen)
        manager.initializeWithSettings(fullscreen)
        assertThat(manager.canResumeHandoff("https://example.test/live.ts", PlaybackProfile.LIVE, fullscreen)).isTrue()
        manager.play("https://example.test/live.ts", profile = PlaybackProfile.LIVE, owner = fullscreen)
        manager.stop(owner) // Late disposal of the old surface owner.
        assertThat(manager.prepareReturnHandoff(fullscreen)).isTrue()
        manager.claimOwnership(owner)
        manager.initializeWithSettings(owner)
        manager.play("https://example.test/live.ts", profile = PlaybackProfile.LIVE, owner = owner)
        runCurrent()
        verify(exactly = 0) { exo.release(); exo.stop(); exo.initialize(); exo.play(any(), any(), any(), any()) }
        assertThat(exoState.value.requestId).isEqualTo(requestId)
    }

    @Test fun `VLC handoff preserves native player and media request`() = runTest(dispatcher) {
        every { settings.settings } returns flowOf(AppSettings(playerEngine = PlayerEngineType.VLC.name))
        manager.play("https://example.test/live.ts", profile = PlaybackProfile.LIVE, owner = owner)
        runCurrent()
        val requestId = vlcState.value.requestId
        assertThat(manager.prepareSeamlessHandoff(owner)).isTrue()
        clearMocks(vlc, answers = false, recordedCalls = true)
        val destination = Any()
        manager.claimOwnership(destination)
        manager.initializeWithSettings(destination)
        manager.play("https://example.test/live.ts", profile = PlaybackProfile.LIVE, owner = destination)
        runCurrent()
        verify(exactly = 0) { vlc.initialize(); vlc.play(any(), any(), any(), any()); vlc.prepareRelease() }
        assertThat(vlcState.value.requestId).isEqualTo(requestId)
    }

    @Test fun `abandoned handoff keeps visible preview but releases hidden owner`() = runTest(dispatcher) {
        manager.play("https://example.test/live.ts", profile = PlaybackProfile.LIVE, owner = owner)
        runCurrent()
        manager.prepareSeamlessHandoff(owner)
        advanceTimeBy(5_001L)
        runCurrent()
        assertThat(manager.getEngine()).isSameInstanceAs(exo)
        manager.prepareSeamlessHandoff(owner)
        manager.markHandoffSourceHidden(owner)
        advanceTimeBy(5_001L)
        runCurrent()
        assertThat(manager.getEngine()).isNull()
    }

    @Test fun `handoff cannot suppress a different content request or be consumed by a third owner`() = runTest(dispatcher) {
        manager.play("https://example.test/first.ts", profile = PlaybackProfile.LIVE, owner = owner)
        runCurrent()
        manager.prepareSeamlessHandoff(owner)
        val fullscreen = Any()
        manager.claimOwnership(fullscreen)
        manager.initializeWithSettings(fullscreen)
        assertThat(manager.canResumeHandoff("https://example.test/second.ts", PlaybackProfile.LIVE, fullscreen)).isFalse()
        manager.play("https://example.test/second.ts", profile = PlaybackProfile.LIVE, owner = fullscreen)
        runCurrent()
        assertThat(manager.prepareReturnHandoff(fullscreen)).isFalse()
        verify { exo.play("https://example.test/second.ts", 0L, PlaybackProfile.LIVE, any()) }
        manager.prepareSeamlessHandoff(fullscreen)
        manager.claimOwnership(Any())
        val third = Any()
        manager.claimOwnership(third)
        assertThat(manager.canResumeHandoff("https://example.test/second.ts", PlaybackProfile.LIVE, third)).isFalse()
    }

    @Test fun `fifty queued zaps only start newest request and ignore stale confirmation`() = runTest(dispatcher) {
        val settingsGate = CompletableDeferred<Unit>()
        coEvery { settings.getPlaybackEngineProfile(any()) } coAnswers { settingsGate.await(); null }
        repeat(50) { index -> launch { manager.play("https://example.test/channel-$index.ts", profile = PlaybackProfile.LIVE, owner = owner) } }
        runCurrent()
        settingsGate.complete(Unit)
        runCurrent()
        verify(exactly = 1) { exo.play(any(), any(), any(), any()) }
        verify { exo.play("https://example.test/channel-49.ts", 0, PlaybackProfile.LIVE, any()) }
        val request = exoState.value.requestId
        exoState.value = PlayerState(requestId = request - 1, isPlaying = true, isPlaybackConfirmed = true)
        runCurrent()
        assertThat(manager.state.value.isPlaybackConfirmed).isFalse()
        exoState.value = PlayerState(requestId = request, playbackState = PlaybackState.PLAYING, isPlaying = true, isPlaybackConfirmed = true)
        runCurrent()
        assertThat(manager.sessionState.value).isEqualTo(PlaybackSessionState.Confirmed(request))
    }

    @Test fun `old route cleanup cannot release newer owner`() = runTest(dispatcher) {
        manager.play("https://example.test/first.mp4", owner = owner)
        val nextOwner = Any()
        manager.claimOwnership(nextOwner)
        manager.play("https://example.test/second.mp4", owner = nextOwner)
        clearMocks(exo, answers = false, recordedCalls = true)
        manager.release(owner)
        manager.stop(owner)
        manager.play("https://example.test/stale.mp4", owner = owner)
        runCurrent()
        verify(exactly = 0) { exo.release() }
        verify(exactly = 0) { exo.stop() }
        verify(exactly = 0) { exo.play(any(), any(), any(), any()) }
        assertThat(manager.getEngine()).isSameInstanceAs(exo)
    }

    @Test fun `fallback releases primary first and retains VOD resume position`() = runTest(dispatcher) {
        manager.play("https://example.test/movie.mp4", 42_000, PlaybackProfile.VOD, owner)
        val request = exoState.value.requestId
        exoState.value = PlayerState(requestId = request, playbackState = PlaybackState.ERROR, errorMessage = "decoder failure")
        runCurrent()
        verifyOrder {
            exo.stop()
            exo.release()
            vlc.initialize()
            vlc.play("https://example.test/movie.mp4", 42_000, PlaybackProfile.VOD, request)
        }
        exoState.value = PlayerState(requestId = request, isPlaybackConfirmed = true)
        runCurrent()
        assertThat(manager.state.value.isPlaybackConfirmed).isFalse()
    }

    @Test fun `VLC release retires views on main and destroys native handles off main`() = runTest(dispatcher) {
        every { settings.settings } returns flowOf(AppSettings(playerEngine = PlayerEngineType.VLC.name))
        manager.play("https://example.test/retire.mkv", owner = owner)
        runCurrent()
        clearMocks(vlc, releaseVlc, answers = false, recordedCalls = true)
        val mainThread = Thread.currentThread()
        val preparationThread = AtomicReference<Thread>()
        val destructionThread = AtomicReference<Thread>()
        every { vlc.prepareRelease() } answers {
            preparationThread.set(Thread.currentThread())
            releaseVlc
        }
        every { releaseVlc.invoke() } answers { destructionThread.set(Thread.currentThread()) }

        manager.releaseAndAwait(owner)

        verifyOrder { vlc.prepareRelease(); releaseVlc.invoke() }
        verify(exactly = 0) { vlc.stop(); vlc.detachSurface(); vlc.release() }
        assertThat(preparationThread.get()).isSameInstanceAs(mainThread)
        assertThat(destructionThread.get()).isNotNull()
        assertThat(destructionThread.get()).isNotSameInstanceAs(mainThread)
        assertThat(manager.getEngine()).isNull()
    }

    @Test fun `cancelled release finishes native cleanup and clears published engine`() = runTest(dispatcher) {
        every { settings.settings } returns flowOf(AppSettings(playerEngine = PlayerEngineType.VLC.name))
        manager.play("https://example.test/cancel-release.mkv", owner = owner)
        runCurrent()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        every { releaseVlc.invoke() } answers {
            entered.countDown()
            check(proceed.await(5, TimeUnit.SECONDS)) { "Synthetic cleanup was not released" }
            finished.countDown()
        }
        val cleanup = manager.release(owner)!!
        try {
            runCurrent()
            withContext(Dispatchers.IO) {
                check(entered.await(5, TimeUnit.SECONDS)) { "Native cleanup did not start" }
            }
            cleanup.cancel()
        } finally {
            proceed.countDown()
            cleanup.join()
        }
        assertThat(finished.count).isEqualTo(0L)
        verify(exactly = 1) { releaseVlc.invoke() }
        assertThat(manager.getEngine()).isNull()
        assertThat(manager.activeEngineName.value).isNull()
        assertThat(manager.state.value.playbackState).isEqualTo(PlaybackState.IDLE)
    }

    @Test fun `Media3 follows system caption changes without restarting playback`() = runTest(dispatcher) {
        assertCaptionChanges(PlayerEngineType.EXOPLAYER)
    }

    @Test fun `VLC follows system caption changes without restarting playback`() = runTest(dispatcher) {
        assertCaptionChanges(PlayerEngineType.VLC)
    }

    private suspend fun assertCaptionChanges(type: PlayerEngineType) {
        every { settings.settings } returns flowOf(AppSettings(playerEngine = type.name))
        captions.value = captions.value.copy(enabled = true, language = "tr")
        manager.play("https://example.test/captions.mkv", owner = owner)
        dispatcher.scheduler.runCurrent()
        clearMocks(exo, vlc, answers = false)

        captions.value = captions.value.copy(language = "en")
        dispatcher.scheduler.runCurrent()
        if (type == PlayerEngineType.VLC) verify(exactly = 1) { vlc.setPreferredSubtitleLanguage("en") }
        else verify(exactly = 1) { exo.setPreferredSubtitleLanguage("en") }

        captions.value = captions.value.copy(enabled = false)
        dispatcher.scheduler.runCurrent()
        if (type == PlayerEngineType.VLC) verify(exactly = 1) { vlc.disableSubtitles() }
        else verify(exactly = 1) { exo.disableSubtitles() }
        verify(exactly = 0) { exo.play(any(), any(), any(), any()); vlc.play(any(), any(), any(), any()) }
        verify(exactly = 0) { exo.initialize(); vlc.initialize(); exo.release(); releaseVlc.invoke() }
        verify(exactly = 0) { exo.setPreferredAudioLanguage(any()); vlc.setPreferredAudioLanguage(any()) }

        clearMocks(exo, vlc, answers = false)
        captions.value = captions.value.copy(fontScale = 2f, foregroundColor = 0xFFFFFF00.toInt())
        dispatcher.scheduler.runCurrent()
        verify(exactly = 0) { exo.setPreferredSubtitleLanguage(any()); vlc.setPreferredSubtitleLanguage(any()) }
        verify(exactly = 0) { exo.disableSubtitles(); vlc.disableSubtitles() }
    }

    @Test fun `explicit app subtitle language is independent of system caption changes`() = runTest(dispatcher) {
        every { settings.settings } returns flowOf(AppSettings(defaultSubtitleLanguage = "eng"))
        manager.play("https://example.test/explicit.mkv", owner = owner)
        runCurrent()
        clearMocks(exo, answers = false)
        captions.value = captions.value.copy(enabled = true, language = "tr")
        runCurrent()
        captions.value = captions.value.copy(enabled = false)
        runCurrent()
        verify(exactly = 0) { exo.setPreferredSubtitleLanguage(any()); exo.disableSubtitles() }
    }

    @Test fun `manual track and off choices survive caption events until the next content`() = runTest(dispatcher) {
        captions.value = captions.value.copy(enabled = true, language = "tr")
        manager.play("https://example.test/manual.mkv", owner = owner)
        exoState.value = exoState.value.copy(subtitleTracks = listOf(TrackInfo(0, "English", "en")))
        runCurrent()
        manager.selectSubtitleTrack(0, owner)
        verify { exo.selectSubtitleTrack(0) }
        clearMocks(exo, answers = false)
        captions.value = captions.value.copy(enabled = false, language = "en")
        runCurrent()
        verify(exactly = 0) { exo.setPreferredSubtitleLanguage(any()); exo.disableSubtitles() }

        manager.disableSubtitles(owner)
        clearMocks(exo, answers = false)
        captions.value = captions.value.copy(enabled = true)
        runCurrent()
        verify(exactly = 0) { exo.setPreferredSubtitleLanguage(any()) }
        manager.play("https://example.test/next.mkv", owner = owner)
        runCurrent()
        verify { exo.setPreferredSubtitleLanguage("en") }
    }

    @Test fun `release stops observing captions and the next session subscribes again`() = runTest(dispatcher) {
        manager.play("https://example.test/first.mkv", owner = owner)
        runCurrent()
        assertThat(captions.subscriptionCount.value).isEqualTo(1)
        manager.release(owner)?.join()
        runCurrent()
        assertThat(captions.subscriptionCount.value).isEqualTo(0)
        clearMocks(exo, vlc, answers = false)
        captions.value = captions.value.copy(enabled = true, language = "tr")
        runCurrent()
        verify(exactly = 0) { exo.setPreferredSubtitleLanguage(any()); vlc.setPreferredSubtitleLanguage(any()) }
        manager.play("https://example.test/new-session.mkv", owner = owner)
        runCurrent()
        assertThat(captions.subscriptionCount.value).isEqualTo(1)
        verify { exo.setPreferredSubtitleLanguage("tr") }
    }

    @Test fun `system audio language comes from device locale rather than caption language`() = runTest(dispatcher) {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            every { settings.settings } returns flowOf(AppSettings(defaultAudioLanguage = "system"))
            captions.value = captions.value.copy(enabled = true, language = "tr")
            manager.play("https://example.test/languages.mkv", owner = owner)
            runCurrent()
            verify { exo.setPreferredAudioLanguage("en"); exo.setPreferredSubtitleLanguage("tr") }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test fun `audio description updates preserve manual audio choice until new content`() = runTest(dispatcher) {
        manager.play("https://example.test/audio.mkv", owner = owner)
        runCurrent()
        clearMocks(exo, answers = false)
        captions.value = captions.value.copy(audioDescriptionRequested = true)
        runCurrent()
        verify { exo.setAudioDescriptionEnabled(true) }
        verify(exactly = 0) { exo.play(any(), any(), any(), any()) }
        exoState.value = exoState.value.copy(audioTracks = listOf(TrackInfo(4, "Standard", "en")))
        runCurrent()
        manager.selectAudioTrack(4, owner)
        clearMocks(exo, answers = false)
        captions.value = captions.value.copy(audioDescriptionRequested = false)
        runCurrent()
        verify(exactly = 0) { exo.setAudioDescriptionEnabled(any()); exo.setPreferredAudioLanguage(any()) }
        manager.play("https://example.test/next.mkv", owner = owner)
        runCurrent()
        verify { exo.setAudioDescriptionEnabled(false); exo.setPreferredAudioLanguage(any()) }
    }

    @Test fun `paused VLC defers style replacement until resume and preserves VOD request position`() = runTest(dispatcher) {
        every { settings.settings } returns flowOf(AppSettings(playerEngine = "VLC"))
        manager.play("https://example.test/style.mkv", owner = owner)
        vlcState.value = vlcState.value.copy(playbackState = PlaybackState.PAUSED, currentPosition = 42_000)
        runCurrent()
        var pendingAppearance = false
        every { vlc.requiresSubtitleAppearanceRebuild(any()) } answers { pendingAppearance }
        every { vlc.setSubtitleAppearance(any()) } answers { pendingAppearance = false }
        clearMocks(vlc, answers = false)
        pendingAppearance = true
        captions.value = captions.value.copy(fontScale = 2f)
        runCurrent()
        verify(exactly = 0) { releaseVlc.invoke(); vlc.initialize(); vlc.play(any(), any(), any(), any()) }
        val request = vlcState.value.requestId
        manager.resume(owner)
        runCurrent()
        verifyOrder { releaseVlc.invoke(); vlc.setSubtitleAppearance(match { it.fontScale == 2f });
            vlc.initialize(); vlc.play("https://example.test/style.mkv", 42_000, PlaybackProfile.VOD, request) }
        verify { vlc.resume() }
        verify(exactly = 0) { vlc.pause() }
        assertThat(manager.state.value.requestId).isEqualTo(request)
    }
}

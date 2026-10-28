package com.idealplayer.app.core.player

import android.content.Context
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.slot
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@androidx.annotation.OptIn(UnstableApi::class)
class ExoReleaseBoundaryTest {
    @Test fun staleSurfaceCallbacksCannotChangeReplacementOrReleasedState() {
        val engine = engine()
        val oldCallback = slot<SurfaceHolder.Callback>()
        val currentCallback = slot<SurfaceHolder.Callback>()
        fun view(callback: io.mockk.CapturingSlot<SurfaceHolder.Callback>): Pair<PlayerView, SurfaceHolder> {
            val surface = mockk<Surface>(relaxed = true)
            every { surface.isValid } returns true
            val holder = mockk<SurfaceHolder>(relaxed = true)
            every { holder.surface } returns surface
            every { holder.addCallback(capture(callback)) } returns Unit
            // A failed unregister is the worst case: the retired callback can still fire.
            every { holder.removeCallback(any()) } throws IllegalStateException("Synthetic detach failure")
            val surfaceView = mockk<SurfaceView>(relaxed = true)
            every { surfaceView.holder } returns holder
            every { surfaceView.width } returns 640
            every { surfaceView.height } returns 360
            val playerView = mockk<PlayerView>(relaxed = true)
            every { playerView.videoSurfaceView } returns surfaceView
            return playerView to holder
        }
        val (oldView, oldHolder) = view(oldCallback)
        val (currentView, currentHolder) = view(currentCallback)
        engine.attachPlayerView(oldView)
        shadowOf(Looper.getMainLooper()).idle()
        engine.attachPlayerView(currentView)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(engine.state.value.isSurfaceReady).isTrue()

        oldCallback.captured.surfaceDestroyed(oldHolder)
        assertThat(engine.state.value.isSurfaceReady).isTrue()

        engine.release()
        shadowOf(Looper.getMainLooper()).idle()
        currentCallback.captured.surfaceCreated(currentHolder)
        oldCallback.captured.surfaceChanged(oldHolder, 0, 640, 360)
        assertThat(engine.state.value.isSurfaceReady).isFalse()
        assertDetached(engine)
    }

    @Test fun failedStopStillReleasesTheNativePlayer() {
        val engine = engine()
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.stop() } throws IllegalStateException("Synthetic stop failure")
        set(engine, "player", player)

        engine.release()
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 1) { player.clearMediaItems(); player.release() }
        assertDetached(engine)
    }

    @Test fun failedListenerViewAndNativeCleanupStillRetiresOwnedReferences() {
        val engine = engine()
        val player = mockk<ExoPlayer>(relaxed = true)
        val listener = mockk<Player.Listener>(relaxed = true)
        val view = mockk<PlayerView>(relaxed = true)
        every { player.removeListener(listener) } throws IllegalStateException("Synthetic listener failure")
        every { view.player = null } throws IllegalStateException("Synthetic view failure")
        every { player.release() } throws IllegalStateException("Synthetic release failure")
        set(engine, "player", player)
        set(engine, "playerListener", listener)
        set(engine, "playerView", view)

        engine.release()
        shadowOf(Looper.getMainLooper()).idle()
        engine.release()
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 1) { player.stop(); player.release() }
        assertDetached(engine)
    }

    @Test fun behindLiveWindowErrorRecoversInPlaceWithoutOverridingPlayWhenReady() {
        val engine = engine()
        val player = mockk<ExoPlayer>(relaxed = true)
        val listenerSlot = slot<Player.Listener>()
        every { player.addListener(capture(listenerSlot)) } returns Unit
        every { player.playerError } returns null
        every { player.playbackState } returns Player.STATE_BUFFERING
        set(engine, "player", player)
        set(engine, "surfaceReady", true)
        set(engine, "hasVideoTrack", true)
        set(engine, "firstFrameRendered", true)
        val install = ExoPlayerEngine::class.java.getDeclaredMethod("installPlayerListener", ExoPlayer::class.java)
            .apply { isAccessible = true }
        install.invoke(engine, player)

        val error = androidx.media3.common.PlaybackException(
            "Behind live window",
            null,
            androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
        )
        listenerSlot.captured.onPlayerError(error)

        verify(exactly = 1) {
            player.seekToDefaultPosition()
            player.prepare()
        }
        verify(exactly = 0) { player.playWhenReady = any() }
        assertThat(engine.state.value.playbackState).isEqualTo(PlaybackState.BUFFERING)
        assertThat(engine.state.value.hasRenderedFirstFrame).isFalse()
    }

    @Test fun externalSubtitlesAreAttachedToMediaItemConfiguration() {
        val engine = engine()
        val source = parsePlaybackSource(
            withPlaybackHeaders(
                rawUrl = "https://media.example.test/movie/1.mkv",
                additionalHeaders = emptyMap(),
                additionalSubtitles = listOf(
                    "https://media.example.test/subs/movie.tr.srt",
                    "https://media.example.test/subs/movie.en.vtt"
                )
            )
        )
        val mediaItem = engine.buildMediaItem(source, PlaybackProfile.VOD)
        val subtitles = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()

        assertThat(subtitles).hasSize(2)
        assertThat(subtitles[0].uri.toString()).isEqualTo("https://media.example.test/subs/movie.tr.srt")
        assertThat(subtitles[0].mimeType).isEqualTo(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
        assertThat(subtitles[0].language).isEqualTo("tur")
        assertThat(subtitles[0].selectionFlags).isEqualTo(0)
        assertThat(subtitles[1].uri.toString()).isEqualTo("https://media.example.test/subs/movie.en.vtt")
        assertThat(subtitles[1].mimeType).isEqualTo(androidx.media3.common.MimeTypes.TEXT_VTT)
        assertThat(subtitles[1].language).isEqualTo("eng")
    }

    @Test fun defaultRenderersKeepPlatformFirstAndPreferFfmpegWhenRequested() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val handler = android.os.Handler(Looper.getMainLooper())
        val videoListener = mockk<androidx.media3.exoplayer.video.VideoRendererEventListener>(relaxed = true)
        val audioListener = mockk<androidx.media3.exoplayer.audio.AudioRendererEventListener>(relaxed = true)
        val textOutput = mockk<androidx.media3.exoplayer.text.TextOutput>(relaxed = true)
        val metadataOutput = mockk<androidx.media3.exoplayer.metadata.MetadataOutput>(relaxed = true)

        val defaultFactory = IdealPlayerRenderersFactory(
            context = context,
            preferHardwareVideoDecoding = true,
            preferFfmpegAudioDecoding = false
        )
        val defaultAudioRenderers = defaultFactory.createRenderers(
            handler, videoListener, audioListener, textOutput, metadataOutput
        ).filter { it.trackType == androidx.media3.common.C.TRACK_TYPE_AUDIO }
        assertThat(defaultAudioRenderers.size).isAtLeast(2)
        assertThat(defaultAudioRenderers.first().javaClass.simpleName).isEqualTo("MediaCodecAudioRenderer")
        assertThat(defaultAudioRenderers.last().javaClass.simpleName).isEqualTo("FfmpegAudioRenderer")

        val preferredFactory = IdealPlayerRenderersFactory(
            context = context,
            preferHardwareVideoDecoding = true,
            preferFfmpegAudioDecoding = true
        )
        val preferredAudioRenderers = preferredFactory.createRenderers(
            handler, videoListener, audioListener, textOutput, metadataOutput
        ).filter { it.trackType == androidx.media3.common.C.TRACK_TYPE_AUDIO }
        assertThat(preferredAudioRenderers.first().javaClass.simpleName).isEqualTo("FfmpegAudioRenderer")
    }

    private fun engine() = ExoPlayerEngine(
        ApplicationProvider.getApplicationContext<Context>(), OkHttpClient()
    )

    private fun set(engine: ExoPlayerEngine, name: String, value: Any) {
        ExoPlayerEngine::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(engine, value)
    }

    private fun assertDetached(engine: ExoPlayerEngine) {
        for (name in listOf("player", "playerListener", "playerView", "surfaceBinding", "trackSelector")) {
            assertThat(ExoPlayerEngine::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(engine)).isNull()
        }
        assertThat(engine.playerInstance.value).isNull()
        assertThat(engine.state.value.playbackState).isEqualTo(PlaybackState.IDLE)
    }
}

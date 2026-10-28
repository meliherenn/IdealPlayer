package com.idealplayer.app.core.player

import android.content.Context
import android.media.AudioManager
import android.view.SurfaceView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VlcReleaseBoundaryTest {
    @Test fun retiringNativePlayerIsUnreachableBeforeBackgroundDestruction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = VlcPlayerEngine(context)
        val player = mockk<MediaPlayer>(relaxed = true)
        val library = mockk<LibVLC>(relaxed = true)
        fun setNative(name: String, value: Any) {
            VlcPlayerEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(engine, value)
        }
        setNative("mediaPlayer", player)
        setNative("libVlc", library)

        val destroy = engine.prepareRelease()
        // Compose can deliver a pending surface update while IO cleanup is blocked.
        engine.attachSurface(SurfaceView(context))
        engine.updateSurfaceSize(640, 360)
        engine.detachSurface()
        assertThat(engine.state.value.playbackState).isEqualTo(PlaybackState.IDLE)
        // Any accidental native time/length call above fails on this JVM without LibVLC JNI.
        verify(exactly = 0) { player.release(); library.release() }

        // A delayed cleanup must own only its retired native objects, never a newer session.
        val replacement = mockk<MediaPlayer>(relaxed = true)
        setNative("mediaPlayer", replacement)
        destroy()
        destroy() // Repeated cleanup must not decrement native refcounts twice.
        val retained = VlcPlayerEngine::class.java.getDeclaredField("mediaPlayer")
            .apply { isAccessible = true }.get(engine)
        assertThat(retained).isSameInstanceAs(replacement)
        verify(exactly = 1) { player.release(); library.release() }
        verify(exactly = 0) { replacement.release() }
    }

    @Test fun nativeListenerFailureDoesNotSkipRemainingCleanup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = VlcPlayerEngine(context)
        val player = mockk<MediaPlayer>(relaxed = true)
        val library = mockk<LibVLC>(relaxed = true)
        every { player.setEventListener(null) } throws IllegalStateException("Synthetic listener failure")
        every { player.stop() } throws IllegalStateException("Synthetic stop failure")
        every { player.release() } throws IllegalStateException("Synthetic player release failure")
        VlcPlayerEngine::class.java.getDeclaredField("mediaPlayer")
            .apply { isAccessible = true }.set(engine, player)
        VlcPlayerEngine::class.java.getDeclaredField("libVlc")
            .apply { isAccessible = true }.set(engine, library)

        val destroy = engine.prepareRelease()
        assertThat(engine.state.value.playbackState).isEqualTo(PlaybackState.IDLE)
        for (name in listOf("mediaPlayer", "libVlc", "currentSurfaceView")) {
            assertThat(VlcPlayerEngine::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(engine)).isNull()
        }
        destroy()
        destroy()
        verify(exactly = 1) { player.release(); library.release() }
    }

    @Test fun failedAudioFocusAbandonmentStillUnregistersReceiverAndReleasesNativeHandles() {
        val context = mockk<Context>(relaxed = true)
        val audio = mockk<AudioManager>(relaxed = true)
        every { context.getSystemService(AudioManager::class.java) } returns audio
        every { audio.abandonAudioFocusRequest(any()) } throws IllegalStateException("Synthetic audio service failure")
        val engine = VlcPlayerEngine(context)
        val player = mockk<MediaPlayer>(relaxed = true)
        val library = mockk<LibVLC>(relaxed = true)
        for ((name, value) in listOf("mediaPlayer" to player, "libVlc" to library,
                                    "hasAudioFocus" to true, "noisyReceiverRegistered" to true)) {
            VlcPlayerEngine::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(engine, value)
        }

        engine.prepareRelease().invoke()

        verify(exactly = 1) { context.unregisterReceiver(any()); player.release(); library.release() }
        for (name in listOf("hasAudioFocus", "noisyReceiverRegistered")) {
            assertThat(VlcPlayerEngine::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.getBoolean(engine)).isFalse()
        }
    }
}

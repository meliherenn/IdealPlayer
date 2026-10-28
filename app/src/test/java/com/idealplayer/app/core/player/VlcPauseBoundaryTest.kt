package com.idealplayer.app.core.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.videolan.libvlc.MediaPlayer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VlcPauseBoundaryTest {
    private val engine = VlcPlayerEngine(ApplicationProvider.getApplicationContext<Context>())

    @Test fun queuedOpeningAndBufferingCannotUndoPause() {
        engine.pause()
        deliver(MediaPlayer.Event.Opening)
        deliver(MediaPlayer.Event.Buffering)
        assertThat(engine.state.value.playbackState).isEqualTo(PlaybackState.PAUSED)
        assertThat(engine.state.value.isPlaying).isFalse()
    }

    @Test fun queuedPlayingCannotPublishPlaybackAfterPause() {
        engine.pause()
        deliver(MediaPlayer.Event.Playing)
        assertThat(engine.state.value.playbackState).isEqualTo(PlaybackState.PAUSED)
        assertThat(engine.state.value.isPlaybackConfirmed).isFalse()
    }

    @Test fun openingStillPublishesBufferingForAnActiveRequest() {
        field("playWhenReady", true)
        deliver(MediaPlayer.Event.Opening)
        assertThat(engine.state.value.playbackState).isEqualTo(PlaybackState.BUFFERING)
        engine.pause()
        deliver(MediaPlayer.Event.Buffering)
        assertThat(engine.state.value.playbackState).isEqualTo(PlaybackState.PAUSED)
    }

    private fun field(name: String, value: Any) {
        VlcPlayerEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(engine, value)
    }

    private fun deliver(type: Int) {
        val event = object : MediaPlayer.Event(type, 25f) {}
        VlcPlayerEngine::class.java.getDeclaredMethod("handleVlcEvent", MediaPlayer.Event::class.java)
            .apply { isAccessible = true }.invoke(engine, event)
    }
}

package com.idealplayer.app.core.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.ILibVLC
import org.videolan.libvlc.interfaces.IMedia

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class VlcVideoDimensionsTest {
    private val engine = VlcPlayerEngine(ApplicationProvider.getApplicationContext<Context>())
    private val player = mockk<NativeFreePlayer>(relaxed = true)
    private var track: IMedia.VideoTrack? = null

    @Before fun setUp() {
        field("mediaPlayer", player)
        every { player.currentVideoTrack } answers { track }
        every { player.media } returns null
        every { player.audioTracks } returns null
        every { player.spuTracks } returns null
    }

    @Test fun selectedTrackPublishesDimensionsAfterAnEarlyVout() {
        deliver(MediaPlayer.Event.Vout)
        assertThat(engine.state.value.videoWidth).isEqualTo(0)
        track = video(1920, 1080)
        deliver(MediaPlayer.Event.ESSelected)
        assertDimensions(1920, 1080)
        // Metadata alone must not claim successful playback or a rendered frame.
        assertThat(engine.state.value.isPlaybackConfirmed).isFalse()
        assertThat(engine.state.value.hasRenderedFirstFrame).isFalse()
    }

    @Test fun progressRetriesDimensionsWithoutAnotherTrackEvent() = runTest {
        field("scope", backgroundScope)
        field("playWhenReady", true)
        deliver(MediaPlayer.Event.Playing)
        runCurrent()
        assertDimensions(0, 0)
        track = video(1920, 1080)
        advanceTimeBy(500)
        runCurrent()
        assertDimensions(1920, 1080)
        engine.stop()
    }

    @Test fun emptyNativeSnapshotsPreserveSizeButValidTrackChangesReplaceIt() {
        track = video(1920, 1080)
        deliver(MediaPlayer.Event.ESSelected)
        track = null
        deliver(MediaPlayer.Event.Vout)
        assertDimensions(1920, 1080)
        track = video(0, 0)
        deliver(MediaPlayer.Event.ESSelected)
        assertDimensions(1920, 1080)
        track = video(1280, 720)
        deliver(MediaPlayer.Event.ESSelected)
        assertDimensions(1280, 720)
    }

    @Test fun stoppingClearsDimensionsBeforeTheNextSource() {
        track = video(1920, 1080)
        deliver(MediaPlayer.Event.ESSelected)
        engine.stop()
        assertDimensions(0, 0)
        assertThat(engine.state.value.isPlaybackConfirmed).isFalse()
    }

    private fun assertDimensions(width: Int, height: Int) {
        assertThat(engine.state.value.videoWidth).isEqualTo(width)
        assertThat(engine.state.value.videoHeight).isEqualTo(height)
    }

    private fun video(width: Int, height: Int) = IMedia.VideoTrack(
        "h264", "h264", 0, 0, 0, 0, 1_000_000, "", "",
        height, width, 1, 1, 24, 1, 0, 0
    )

    private fun field(name: String, value: Any) {
        VlcPlayerEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(engine, value)
    }

    private fun deliver(type: Int) {
        val event = object : MediaPlayer.Event(type, 25f) {}
        VlcPlayerEngine::class.java.getDeclaredMethod("handleVlcEvent", MediaPlayer.Event::class.java)
            .apply { isAccessible = true }.invoke(engine, event)
    }

    // MockK cannot intercept JNI methods. Override only the native getters used
    // by state publication; mock allocation deliberately skips this constructor.
    private open class NativeFreePlayer(library: ILibVLC) : MediaPlayer(library) {
        override fun getTime(): Long = 0L
        override fun getLength(): Long = 0L
        override fun isPlaying(): Boolean = false
        override fun isSeekable(): Boolean = false
        override fun pause() = Unit
    }
}

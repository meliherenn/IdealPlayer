package com.idealplayer.app.core.player

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/** Exercises the bundled Java/JNI boundary, not display or audio-output acceptance. */
class NativeLiveTrackSnapshotTest {
    @Test fun earlyEmptyEnumerationDoesNotHideTracksDiscoveredDuringPlayback() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open("caption-fixture.mkv").use { it.readBytes() }
        CaptionFixtureServer(bytes).use { fixture ->
            withContext(Dispatchers.Main) {
                val library = LibVLC(instrumentation.targetContext, arrayListOf("--vout=dummy", "--aout=adummy"))
                val player = MediaPlayer(library)
                val media = Media(library, Uri.parse(fixture.url))
                try {
                    media.setHWDecoderEnabled(false, false)
                    // Reproduce the premature snapshot made by an early ES event.
                    assertThat(media.trackCount).isEqualTo(0)
                    player.media = media
                    player.play()
                    withTimeout(15_000) {
                        while (player.currentVideoTrack?.let { it.width == 640 && it.height == 360 } != true) {
                            delay(100)
                        }
                    }
                    assertThat(media.trackCount).isGreaterThan(0)
                } finally {
                    player.stop()
                    player.release()
                    media.release()
                    library.release()
                }
            }
        }
    }
}

package com.idealplayer.app.ui.player

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.player.FrameRateMatchingMode
import com.idealplayer.app.core.player.PlaybackProfile
import org.junit.Test

class FrameRateMatchingTest {

    @Test
    fun `auto uses seamless switching for live and full matching for vod`() {
        assertThat(
            resolveFrameRateRequest(
                FrameRateMatchingMode.AUTO,
                PlaybackProfile.LIVE,
                playbackActive = true,
                videoFps = "25.000 fps"
            )
        ).isEqualTo(FrameRateRequest(25f, FrameRateChangeStrategy.SEAMLESS_ONLY))
        assertThat(
            resolveFrameRateRequest(
                FrameRateMatchingMode.AUTO,
                PlaybackProfile.VOD,
                playbackActive = true,
                videoFps = "23.976"
            )
        ).isEqualTo(FrameRateRequest(23.976f, FrameRateChangeStrategy.ALWAYS))
    }

    @Test
    fun `off pause and invalid metadata reset the surface preference`() {
        assertThat(
            resolveFrameRateRequest(
                FrameRateMatchingMode.OFF,
                PlaybackProfile.VOD,
                playbackActive = true,
                videoFps = "24"
            )
        ).isEqualTo(FrameRateRequest(0f, FrameRateChangeStrategy.RESET))
        assertThat(
            resolveFrameRateRequest(
                FrameRateMatchingMode.ALWAYS,
                PlaybackProfile.VOD,
                playbackActive = false,
                videoFps = "24"
            )
        ).isEqualTo(FrameRateRequest(0f, FrameRateChangeStrategy.RESET))
        assertThat(
            resolveFrameRateRequest(
                FrameRateMatchingMode.AUTO,
                PlaybackProfile.VOD,
                playbackActive = true,
                videoFps = "unknown"
            )
        ).isEqualTo(FrameRateRequest(0f, FrameRateChangeStrategy.RESET))
    }
}

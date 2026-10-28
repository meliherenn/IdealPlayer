package com.idealplayer.app.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackSessionTest {

    @Test
    fun `session progresses through surface ready first frame and confirmation`() {
        val requestId = 42L

        assertThat(resolvePlaybackSessionState(requestId, PlayerState(), PlaybackRecoveryState(), false))
            .isEqualTo(PlaybackSessionState.Preparing(requestId))
        assertThat(
            resolvePlaybackSessionState(
                requestId,
                PlayerState(isSurfaceReady = true),
                PlaybackRecoveryState(),
                false
            )
        ).isEqualTo(PlaybackSessionState.SurfaceAttached(requestId))
        assertThat(
            resolvePlaybackSessionState(
                requestId,
                PlayerState(
                    playbackState = PlaybackState.PLAYING,
                    isSurfaceReady = true,
                    hasVideoTrack = true
                ),
                PlaybackRecoveryState(),
                false
            )
        ).isEqualTo(PlaybackSessionState.Ready(requestId))
        assertThat(
            resolvePlaybackSessionState(
                requestId,
                PlayerState(hasRenderedFirstFrame = true),
                PlaybackRecoveryState(),
                false
            )
        ).isEqualTo(PlaybackSessionState.FirstFrame(requestId))
        assertThat(
            resolvePlaybackSessionState(
                requestId,
                PlayerState(isPlaybackConfirmed = true),
                PlaybackRecoveryState(),
                true
            )
        ).isEqualTo(PlaybackSessionState.Confirmed(requestId))
    }

    @Test
    fun `buffering records whether playback had already been confirmed`() {
        val beforeFirstFrame = resolvePlaybackSessionState(
            requestId = 7L,
            state = PlayerState(playbackState = PlaybackState.BUFFERING),
            recovery = PlaybackRecoveryState(),
            hadConfirmedPlayback = false
        )
        val rebuffer = resolvePlaybackSessionState(
            requestId = 7L,
            state = PlayerState(playbackState = PlaybackState.BUFFERING),
            recovery = PlaybackRecoveryState(),
            hadConfirmedPlayback = true
        )

        assertThat(beforeFirstFrame).isEqualTo(PlaybackSessionState.Buffering(7L, false))
        assertThat(rebuffer).isEqualTo(PlaybackSessionState.Buffering(7L, true))
    }

    @Test
    fun `failures are typed without retaining provider text`() {
        assertThat(classifyPlaybackFailure("HTTP 403 at secret provider"))
            .isEqualTo(PlaybackFailure.Authorization)
        assertThat(classifyPlaybackFailure("404 not found"))
            .isEqualTo(PlaybackFailure.NotFound)
        assertThat(classifyPlaybackFailure("socket timed out"))
            .isEqualTo(PlaybackFailure.Timeout)
        assertThat(classifyPlaybackFailure("decoder init failed for HEVC"))
            .isEqualTo(PlaybackFailure.Decoder)
        assertThat(classifyPlaybackFailure("unsupported container"))
            .isEqualTo(PlaybackFailure.UnsupportedSource)
        assertThat(classifyPlaybackFailure("DNS lookup failed"))
            .isEqualTo(PlaybackFailure.Network)
        assertThat(
            classifyPlaybackFailure(
                message = null,
                state = PlayerState(hasVideoTrack = true, isSurfaceReady = false)
            )
        ).isEqualTo(PlaybackFailure.SurfaceLost)
    }

    @Test
    fun `synthetic codec failure matrix routes video and audio families to decoder recovery`() {
        val syntheticFailures = listOf(
            "AVC1 initialization failed",
            "H.264 profile is unavailable",
            "HEVC output format rejected",
            "HVC1 initialization failed",
            "AV01 hardware path unavailable",
            "MPEG-2 video initialization failed",
            "MP4A audio initialization failed",
            "AAC sink configuration failed",
            "AC-3 audio initialization failed",
            "EAC3 passthrough unavailable",
            "Opus audio initialization failed"
        )

        syntheticFailures.forEach { failure ->
            assertThat(classifyPlaybackFailure(failure))
                .isEqualTo(PlaybackFailure.Decoder)
        }
        assertThat(classifyPlaybackFailure("unsupported Matroska container"))
            .isEqualTo(PlaybackFailure.UnsupportedSource)
    }

    @Test
    fun `metrics track startup live zap rebuffer and fallback locally`() {
        var clock = 0L
        val tracker = PlaybackMetricsTracker { clock }

        tracker.begin(requestId = 9L, profile = PlaybackProfile.LIVE)
        clock = 25L
        tracker.observe(
            requestId = 9L,
            state = PlayerState(isSurfaceReady = true),
            engineName = "ExoPlayer",
            hadConfirmedPlayback = false
        )
        clock = 80L
        tracker.observe(
            requestId = 9L,
            state = PlayerState(hasRenderedFirstFrame = true),
            engineName = "ExoPlayer",
            hadConfirmedPlayback = false
        )
        clock = 120L
        val confirmed = tracker.observe(
            requestId = 9L,
            state = PlayerState(
                playbackState = PlaybackState.PLAYING,
                isPlaybackConfirmed = true
            ),
            engineName = "ExoPlayer",
            hadConfirmedPlayback = false
        )
        clock = 150L
        val rebuffered = tracker.observe(
            requestId = 9L,
            state = PlayerState(playbackState = PlaybackState.BUFFERING),
            engineName = "ExoPlayer",
            hadConfirmedPlayback = true
        )
        val recovered = tracker.markFallback("ExoPlayer", PlaybackFailure.Decoder)

        assertThat(confirmed.surfaceAttachedAfterMs).isEqualTo(25L)
        assertThat(confirmed.firstFrameAfterMs).isEqualTo(80L)
        assertThat(confirmed.confirmedAfterMs).isEqualTo(120L)
        assertThat(confirmed.zapAfterMs).isEqualTo(120L)
        assertThat(rebuffered.rebufferCount).isEqualTo(1)
        assertThat(recovered.automaticFallbackUsed).isTrue()
        assertThat(recovered.failureCategory).isEqualTo("decoder")
        assertThat(recovered.toString()).doesNotContain("http")
    }

    @Test
    fun `recovery and terminal states have precedence over raw readiness`() {
        val recovery = PlaybackRecoveryState(
            isRecovering = true,
            fromEngine = "ExoPlayer",
            toEngine = "VLC",
            lastErrorCategory = "decoder"
        )
        assertThat(
            resolvePlaybackSessionState(3L, PlayerState(isSurfaceReady = true), recovery, false)
        ).isEqualTo(
            PlaybackSessionState.Recovering(
                requestId = 3L,
                fromEngine = "ExoPlayer",
                toEngine = "VLC",
                failure = PlaybackFailure.Decoder
            )
        )
        assertThat(
            resolvePlaybackSessionState(
                3L,
                PlayerState(playbackState = PlaybackState.ENDED),
                PlaybackRecoveryState(),
                true
            )
        ).isEqualTo(PlaybackSessionState.Ended(3L))
    }
}

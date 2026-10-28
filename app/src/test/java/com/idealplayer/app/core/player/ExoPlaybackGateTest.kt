package com.idealplayer.app.core.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExoPlaybackGateTest {

    @Test
    fun `ready surface does not consume playback request before tracks are known`() {
        assertThat(
            shouldOpenPlaybackGate(
                hasVideoTrack = false,
                hasAudioTrack = false,
                surfaceReady = true
            )
        ).isFalse()
    }

    @Test
    fun `ready surface starts playback immediately while tracks are being discovered`() {
        assertThat(
            shouldStartPlaybackImmediatelyOnPlay(
                hasVideoTrack = false,
                hasAudioTrack = false,
                surfaceReady = true
            )
        ).isTrue()
        assertThat(
            shouldStartPlaybackImmediatelyOnPlay(
                hasVideoTrack = false,
                hasAudioTrack = false,
                surfaceReady = false
            )
        ).isFalse()
    }

    @Test
    fun `video playback waits for its surface`() {
        assertThat(
            shouldOpenPlaybackGate(
                hasVideoTrack = true,
                hasAudioTrack = false,
                surfaceReady = false
            )
        ).isFalse()
        assertThat(
            shouldOpenPlaybackGate(
                hasVideoTrack = true,
                hasAudioTrack = false,
                surfaceReady = true
            )
        ).isTrue()
    }

    @Test
    fun `audio only playback does not wait for a video surface`() {
        assertThat(
            shouldOpenPlaybackGate(
                hasVideoTrack = false,
                hasAudioTrack = true,
                surfaceReady = false
            )
        ).isTrue()
    }

    @Test
    fun `behind live window error triggers immediate recovery within budget and falls through when exceeded`() {
        assertThat(shouldRecoverBehindLiveWindow(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
            .isTrue()
        assertThat(shouldRecoverBehindLiveWindow(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            .isFalse()

        val timestamps = ArrayDeque<Long>()
        assertThat(shouldRecoverBehindLiveWindow(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, 1_000L, timestamps)).isTrue()
        assertThat(shouldRecoverBehindLiveWindow(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, 5_000L, timestamps)).isTrue()
        assertThat(shouldRecoverBehindLiveWindow(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, 10_000L, timestamps)).isTrue()
        // 4th recovery within 30s window is rejected so normal error / VLC fallback can run
        assertThat(shouldRecoverBehindLiveWindow(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, 15_000L, timestamps)).isFalse()
        // After the 30s window slides past the first timestamp, recovery is allowed again
        assertThat(shouldRecoverBehindLiveWindow(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, 32_000L, timestamps)).isTrue()
    }

    @Test
    fun `subtitle host header isolation only forwards stream headers to matching host`() {
        val streamHost = extractHostLowercase("https://user:pass@stream.example.test:8443/live/1.m3u8")
        assertThat(streamHost).isEqualTo("stream.example.test")
        assertThat(
            shouldForwardStreamHeadersToSubtitleHost(
                streamHost = streamHost,
                subtitleHost = extractHostLowercase("https://stream.example.test/subs/1.srt")
            )
        ).isTrue()
        assertThat(
            shouldForwardStreamHeadersToSubtitleHost(
                streamHost = streamHost,
                subtitleHost = extractHostLowercase("https://thirdparty-subs.example.org/1.srt")
            )
        ).isFalse()
    }

    @Test
    fun `external subtitle mime type and language are inferred accurately`() {
        assertThat(inferSubtitleMimeType("https://media.example.test/subs/movie.tr.srt?token=1"))
            .isEqualTo(MimeTypes.APPLICATION_SUBRIP)
        assertThat(inferSubtitleMimeType("https://media.example.test/subs/movie.en.vtt"))
            .isEqualTo(MimeTypes.TEXT_VTT)
        assertThat(inferSubtitleMimeType("https://media.example.test/subs/movie.ass"))
            .isEqualTo(MimeTypes.TEXT_SSA)
        assertThat(inferSubtitleMimeType("https://media.example.test/subs/movie.ttml"))
            .isEqualTo(MimeTypes.APPLICATION_TTML)

        assertThat(inferSubtitleLanguage("https://media.example.test/subs/movie.tr.srt")).isEqualTo("tur")
        assertThat(inferSubtitleLanguage("https://media.example.test/subs/movie.english.vtt")).isEqualTo("eng")
        assertThat(inferSubtitleLanguage("https://media.example.test/subs/movie.srt")).isNull()
    }
}

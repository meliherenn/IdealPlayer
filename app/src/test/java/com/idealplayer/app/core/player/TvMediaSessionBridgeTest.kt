package com.idealplayer.app.core.player

import android.media.session.PlaybackState as PlatformPlaybackState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvMediaSessionBridgeTest {

    @Test
    fun `platform playback state follows the engine independent state`() {
        assertThat(mediaSessionPlaybackState(PlayerState(playbackState = PlaybackState.BUFFERING)))
            .isEqualTo(PlatformPlaybackState.STATE_BUFFERING)
        assertThat(mediaSessionPlaybackState(PlayerState(playbackState = PlaybackState.PLAYING)))
            .isEqualTo(PlatformPlaybackState.STATE_PLAYING)
        assertThat(mediaSessionPlaybackState(PlayerState(playbackState = PlaybackState.ERROR)))
            .isEqualTo(PlatformPlaybackState.STATE_ERROR)
    }

    @Test
    fun `seek actions are advertised only for a finite seek window`() {
        val liveActions = mediaSessionActions(PlayerState(isSeekable = false, duration = 0L))
        val vodActions = mediaSessionActions(PlayerState(isSeekable = true, duration = 60_000L))

        assertThat(liveActions and PlatformPlaybackState.ACTION_SEEK_TO).isEqualTo(0L)
        assertThat(liveActions and PlatformPlaybackState.ACTION_SKIP_TO_NEXT).isNotEqualTo(0L)
        assertThat(vodActions and PlatformPlaybackState.ACTION_SEEK_TO).isNotEqualTo(0L)
        assertThat(vodActions and PlatformPlaybackState.ACTION_FAST_FORWARD).isNotEqualTo(0L)
    }
}

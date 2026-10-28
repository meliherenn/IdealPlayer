package com.idealplayer.app.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackLifecyclePolicyTest {
    @Test fun `background only keeps mobile PiP running`() {
        assertThat(playbackLifecycleAction(PlaybackVisibility(true, false, true))).isEqualTo(PlaybackLifecycleAction.SUSPEND)
        assertThat(playbackLifecycleAction(PlaybackVisibility(false, false))).isEqualTo(PlaybackLifecycleAction.SUSPEND)
        assertThat(playbackLifecycleAction(PlaybackVisibility(false, false, true))).isEqualTo(PlaybackLifecycleAction.KEEP)
        assertThat(playbackLifecycleAction(PlaybackVisibility(true, true))).isEqualTo(PlaybackLifecycleAction.KEEP)
    }

    @Test fun `inline removal releases but activity recreation keeps its request`() {
        assertThat(playbackLifecycleAction(PlaybackVisibility(false, true), ownerRemoved = true)).isEqualTo(PlaybackLifecycleAction.RELEASE)
        assertThat(playbackLifecycleAction(PlaybackVisibility(false, false, isChangingConfigurations = true), true)).isEqualTo(PlaybackLifecycleAction.KEEP)
    }

    @Test fun `VOD retry uses last known position after a zeroed engine error`() {
        assertThat(playbackRetryPosition(PlaybackProfile.VOD, 0, 54_000)).isEqualTo(54_000)
        assertThat(playbackRetryPosition(PlaybackProfile.VOD, 31_000, 54_000)).isEqualTo(31_000)
        assertThat(playbackRetryPosition(PlaybackProfile.LIVE, 31_000, 54_000)).isEqualTo(0)
    }
}

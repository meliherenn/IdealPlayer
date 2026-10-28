package com.idealplayer.app.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackWindowPolicyTest {
    @Test
    fun onlyTvLocksItsActivityToLandscape() {
        assertThat(PlaybackWindowPolicy.shouldLockLandscape(isTv = true)).isTrue()
        assertThat(PlaybackWindowPolicy.shouldLockLandscape(isTv = false)).isFalse()
    }

    @Test
    fun pictureInPictureRequiresPlatformFeatureAndMobileUi() {
        assertThat(
            PlaybackWindowPolicy.supportsPictureInPicture(
                hasPictureInPictureFeature = true,
                isTv = false
            )
        ).isTrue()
        assertThat(
            PlaybackWindowPolicy.supportsPictureInPicture(
                hasPictureInPictureFeature = false,
                isTv = false
            )
        ).isFalse()
        assertThat(
            PlaybackWindowPolicy.supportsPictureInPicture(
                hasPictureInPictureFeature = true,
                isTv = true
            )
        ).isFalse()
    }

    @Test
    fun preAndroid12UserLeaveEntersPipOnlyForActiveMobilePlayback() {
        assertThat(
            PlaybackWindowPolicy.shouldEnterPictureInPictureOnUserLeave(
                isPlayerScreenActive = true,
                isTv = false,
                sdkInt = 30
            )
        ).isTrue()
        assertThat(
            PlaybackWindowPolicy.shouldEnterPictureInPictureOnUserLeave(
                isPlayerScreenActive = false,
                isTv = false,
                sdkInt = 30
            )
        ).isFalse()
        assertThat(
            PlaybackWindowPolicy.shouldEnterPictureInPictureOnUserLeave(
                isPlayerScreenActive = true,
                isTv = true,
                sdkInt = 30
            )
        ).isFalse()
    }

    @Test
    fun android12AndNewerUsesAutoEnterInsteadOfUserLeaveCallback() {
        assertThat(
            PlaybackWindowPolicy.shouldEnterPictureInPictureOnUserLeave(
                isPlayerScreenActive = true,
                isTv = false,
                sdkInt = 31
            )
        ).isFalse()
        assertThat(
            PlaybackWindowPolicy.shouldEnterPictureInPictureOnUserLeave(
                isPlayerScreenActive = true,
                isTv = false,
                sdkInt = 36
            )
        ).isFalse()
    }
}

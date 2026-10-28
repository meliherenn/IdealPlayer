package com.idealplayer.app.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class TrackDecoderSupportTest {
    private fun group(mime: String, support: Int) = Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(mime).build()),
        false, intArrayOf(support), booleanArrayOf(support == C.FORMAT_HANDLED)
    )

    @Test fun missingAudioDecoderRejectsOtherwiseSupportedVideo() {
        val tracks = Tracks(ImmutableList.of(
            group("video/avc", C.FORMAT_HANDLED), group("audio/ac3", C.FORMAT_UNSUPPORTED_SUBTYPE)
        ))
        assertThat(unsupportedPrimaryTrackType(tracks)).isEqualTo(C.TRACK_TYPE_AUDIO)
    }

    @Test fun supportedAlternativeAudioKeepsTheSourcePlayable() {
        val tracks = Tracks(ImmutableList.of(
            group("audio/ac3", C.FORMAT_UNSUPPORTED_SUBTYPE), group("audio/mp4a-latm", C.FORMAT_HANDLED)
        ))
        assertThat(unsupportedPrimaryTrackType(tracks)).isNull()
    }

    @Test fun silentVideoAndAudioOnlySourcesDoNotRequireAnAbsentTrackType() {
        assertThat(unsupportedPrimaryTrackType(Tracks(ImmutableList.of(group("video/avc", C.FORMAT_HANDLED))))).isNull()
        assertThat(unsupportedPrimaryTrackType(Tracks(ImmutableList.of(group("audio/mp4a-latm", C.FORMAT_HANDLED))))).isNull()
        assertThat(unsupportedPrimaryTrackType(Tracks.EMPTY)).isNull()
    }

    @Test fun unavailableVideoDecoderFailsWhileAdvertisedLimitExcessCanBeAttempted() {
        assertThat(unsupportedPrimaryTrackType(Tracks(ImmutableList.of(group("video/av01", C.FORMAT_UNSUPPORTED_SUBTYPE)))))
            .isEqualTo(C.TRACK_TYPE_VIDEO)
        assertThat(unsupportedPrimaryTrackType(Tracks(ImmutableList.of(group("video/av01", C.FORMAT_EXCEEDS_CAPABILITIES)))))
            .isNull()
    }

    @Test fun unsupportedSubtitlesDoNotRejectOtherwisePlayableMedia() {
        val tracks = Tracks(ImmutableList.of(
            group("video/avc", C.FORMAT_HANDLED), group("text/vtt", C.FORMAT_UNSUPPORTED_TYPE)
        ))
        assertThat(unsupportedPrimaryTrackType(tracks)).isNull()
    }
}

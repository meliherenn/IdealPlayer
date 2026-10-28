package com.idealplayer.app.core.player

import android.view.accessibility.CaptioningManager.CaptionStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleAppearanceTest {
    @Test fun `native text options preserve RGB and alpha separately`() {
        val options = SubtitleAppearance(foregroundColor = 0x80FFFF00.toInt(),
            backgroundColor = 0x40203040, edgeType = CaptionStyle.EDGE_TYPE_OUTLINE,
            edgeColor = 0xFF123456.toInt()).vlcOptions()
        assertThat(options).containsAtLeast("--freetype-color=16776960", "--freetype-opacity=128",
            "--freetype-background-color=2109504", "--freetype-background-opacity=64",
            "--freetype-outline-thickness=4", "--freetype-outline-color=1193046",
            "--freetype-outline-opacity=255", "--freetype-shadow-opacity=0")
    }

    @Test fun `large text and malformed scale stay inside renderer bounds`() {
        assertThat(SubtitleAppearance(fontScale = 2f).vlcOptions()).contains("--freetype-rel-fontsize=9")
        assertThat(SubtitleAppearance(fontScale = Float.NaN).vlcOptions())
            .isEqualTo(SubtitleAppearance().vlcOptions())
        assertThat(SubtitleAppearance(fontScale = 100f).vlcOptions())
            .isEqualTo(SubtitleAppearance(fontScale = 2f).vlcOptions())
    }

    @Test fun `description metadata requires an explicit marker`() {
        assertThat("English Audio Description".hasAudioDescriptionMarker()).isTrue()
        assertThat("Türkçe Sesli Betimleme".hasAudioDescriptionMarker()).isTrue()
        assertThat("Standard English".hasAudioDescriptionMarker()).isFalse()
        assertThat("".hasAudioDescriptionMarker()).isFalse()
    }
}

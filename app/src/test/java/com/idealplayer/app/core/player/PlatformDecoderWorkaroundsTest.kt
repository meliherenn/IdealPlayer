package com.idealplayer.app.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlatformDecoderWorkaroundsTest {
    @Test fun excludesCrashingAlacDecoderOnConfirmedDevice() {
        assertThat(isUnsafePlatformDecoder("audio/alac", "c2.qti.alac.sw.decoder", "ishtar", 36))
            .isTrue()
        assertThat(isUnsafePlatformDecoder("audio/alac", "c2.qti.alac.hw.decoder", "ishtar", 36))
            .isTrue()
    }

    @Test fun preservesOtherDevicesOsVersionsAndDecoders() {
        for (api in listOf(26, 35, 37)) {
            assertThat(isUnsafePlatformDecoder("audio/alac", "c2.qti.alac.sw.decoder", "ishtar", api))
                .isFalse()
        }
        assertThat(isUnsafePlatformDecoder("audio/alac", "c2.qti.alac.sw.decoder", "other", 36))
            .isFalse()
        assertThat(isUnsafePlatformDecoder("audio/alac", "c2.android.alac.decoder", "ishtar", 36))
            .isFalse()
        assertThat(isUnsafePlatformDecoder("audio/mp4a-latm", "c2.qti.aac.decoder", "ishtar", 36))
            .isFalse()
        assertThat(isUnsafePlatformDecoder("video/avc", "c2.qti.avc.decoder", "ishtar", 36))
            .isFalse()
    }
}

package com.idealplayer.app.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoilConfigTest {

    @Test
    fun imageMemoryCachePercent_usesCompactCacheForConstrainedDevices() {
        assertThat(imageMemoryCachePercent(isLowRamDevice = true, memoryClassMb = 512))
            .isEqualTo(0.12)
        assertThat(imageMemoryCachePercent(isLowRamDevice = false, memoryClassMb = 256))
            .isEqualTo(0.12)
    }

    @Test
    fun imageMemoryCachePercent_keepsBalancedCacheForLargerDevices() {
        assertThat(imageMemoryCachePercent(isLowRamDevice = false, memoryClassMb = 384))
            .isEqualTo(0.20)
    }

    @Test
    fun imageMemoryCacheMaxSizeBytes_capsLowRamAndRegularDevices() {
        assertThat(imageMemoryCacheMaxSizeBytes(isLowRamDevice = true, memoryClassMb = 512))
            .isEqualTo(24 * 1024 * 1024)
        assertThat(imageMemoryCacheMaxSizeBytes(isLowRamDevice = false, memoryClassMb = 256))
            .isEqualTo(24 * 1024 * 1024)
        assertThat(imageMemoryCacheMaxSizeBytes(isLowRamDevice = false, memoryClassMb = 384))
            .isEqualTo(64 * 1024 * 1024)
    }
}

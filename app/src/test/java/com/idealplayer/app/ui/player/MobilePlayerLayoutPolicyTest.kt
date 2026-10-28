package com.idealplayer.app.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MobilePlayerLayoutPolicyTest {
    @Test
    fun `compact landscape phone keeps controls in stacked layout`() {
        assertThat(MobilePlayerLayoutPolicy.usesExpandedTransport(widthDp = 731f)).isFalse()
    }

    @Test
    fun `expanded transport starts at safe width`() {
        assertThat(MobilePlayerLayoutPolicy.usesExpandedTransport(widthDp = 839.9f)).isFalse()
        assertThat(MobilePlayerLayoutPolicy.usesExpandedTransport(widthDp = 840f)).isTrue()
    }
}

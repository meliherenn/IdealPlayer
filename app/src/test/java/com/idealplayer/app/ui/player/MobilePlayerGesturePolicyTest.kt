package com.idealplayer.app.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MobilePlayerGesturePolicyTest {
    @Test
    fun leftHalfControlsBrightnessAndRightHalfControlsVolume() {
        assertThat(MobilePlayerGesturePolicy.targetFor(startX = 499f, width = 1_000f))
            .isEqualTo(MobilePlayerGestureTarget.BRIGHTNESS)
        assertThat(MobilePlayerGesturePolicy.targetFor(startX = 500f, width = 1_000f))
            .isEqualTo(MobilePlayerGestureTarget.VOLUME)
    }

    @Test
    fun upwardAndDownwardDragsAdjustInExpectedDirection() {
        assertThat(
            MobilePlayerGesturePolicy.adjustedFraction(
                currentFraction = 0.5f,
                dragDeltaY = -250f,
                height = 1_000f
            )
        ).isWithin(0.0001f).of(0.75f)
        assertThat(
            MobilePlayerGesturePolicy.adjustedFraction(
                currentFraction = 0.5f,
                dragDeltaY = 250f,
                height = 1_000f
            )
        ).isWithin(0.0001f).of(0.25f)
    }

    @Test
    fun adjustmentClampsBrightnessAndVolumeBounds() {
        assertThat(
            MobilePlayerGesturePolicy.adjustedFraction(0.9f, -500f, 1_000f)
        ).isEqualTo(1f)
        assertThat(
            MobilePlayerGesturePolicy.adjustedFraction(0.1f, 500f, 1_000f)
        ).isEqualTo(0f)
        assertThat(
            MobilePlayerGesturePolicy.adjustedFraction(0.1f, 500f, 1_000f, minimum = 0.01f)
        ).isEqualTo(0.01f)
    }

    @Test
    fun invalidHeightDoesNotCreateANonFiniteValue() {
        assertThat(
            MobilePlayerGesturePolicy.adjustedFraction(0.4f, -100f, height = 0f)
        ).isEqualTo(0.4f)
    }
}

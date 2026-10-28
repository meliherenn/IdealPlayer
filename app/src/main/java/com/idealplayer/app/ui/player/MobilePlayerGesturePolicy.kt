package com.idealplayer.app.ui.player

internal enum class MobilePlayerGestureTarget {
    BRIGHTNESS,
    VOLUME
}

/** Pure gesture rules shared by the touch player and its regression tests. */
internal object MobilePlayerGesturePolicy {
    fun targetFor(startX: Float, width: Float): MobilePlayerGestureTarget {
        return if (startX < width.coerceAtLeast(0f) / 2f) {
            MobilePlayerGestureTarget.BRIGHTNESS
        } else {
            MobilePlayerGestureTarget.VOLUME
        }
    }

    fun adjustedFraction(
        currentFraction: Float,
        dragDeltaY: Float,
        height: Float,
        minimum: Float = 0f
    ): Float {
        if (!height.isFinite() || height <= 0f) return currentFraction.coerceIn(minimum, 1f)
        val delta = -dragDeltaY / height
        return (currentFraction + delta).coerceIn(minimum, 1f)
    }
}

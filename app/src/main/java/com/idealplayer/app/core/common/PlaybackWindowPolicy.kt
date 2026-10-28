package com.idealplayer.app.core.common

/** Shared mobile/TV window rules kept independent from Activity callbacks for regression tests. */
object PlaybackWindowPolicy {
    fun shouldLockLandscape(isTv: Boolean): Boolean = isTv

    fun supportsPictureInPicture(
        hasPictureInPictureFeature: Boolean,
        isTv: Boolean
    ): Boolean = hasPictureInPictureFeature && !isTv

    fun shouldEnterPictureInPictureOnUserLeave(
        isPlayerScreenActive: Boolean,
        isTv: Boolean,
        sdkInt: Int
    ): Boolean = isPlayerScreenActive && !isTv && sdkInt < 31
}

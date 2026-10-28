package com.idealplayer.app.core.player

/** Only a visible owner or a mobile Activity actually in PiP may run video playback. */
data class PlaybackVisibility(
    val isTv: Boolean,
    val isVisible: Boolean,
    val isInPictureInPicture: Boolean = false,
    val isChangingConfigurations: Boolean = false
)

enum class PlaybackLifecycleAction { KEEP, SUSPEND, RELEASE }

fun playbackLifecycleAction(visibility: PlaybackVisibility, ownerRemoved: Boolean = false): PlaybackLifecycleAction = when {
    visibility.isChangingConfigurations -> PlaybackLifecycleAction.KEEP
    ownerRemoved -> PlaybackLifecycleAction.RELEASE
    visibility.isVisible || (!visibility.isTv && visibility.isInPictureInPicture) -> PlaybackLifecycleAction.KEEP
    else -> PlaybackLifecycleAction.SUSPEND
}

fun playbackRetryPosition(profile: PlaybackProfile, currentPosition: Long, lastPosition: Long): Long =
    if (profile == PlaybackProfile.LIVE) 0L else currentPosition.takeIf { it > 0L } ?: lastPosition.coerceAtLeast(0L)

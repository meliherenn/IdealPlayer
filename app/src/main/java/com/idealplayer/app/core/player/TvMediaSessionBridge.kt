package com.idealplayer.app.core.player

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as PlatformPlaybackState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Activity-scoped TV transport bridge shared by Media3 and VLC through [PlayerManager].
 * It deliberately does not use a playback service: video is stopped when the player route exits.
 */
@Singleton
class TvMediaSessionBridge @Inject constructor(
    @ApplicationContext context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = MediaSession(context, "IdealPlayerTvPlayback")
    private var stateJob: Job? = null
    private var activeOwner: Any? = null

    fun activate(
        playerManager: PlayerManager,
        title: () -> String,
        onPlay: () -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
        owner: Any? = null
    ) {
        activeOwner = owner
        stateJob?.cancel()
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (activeOwner !== owner) return
                onPlay()
            }

            override fun onPause() {
                if (activeOwner !== owner) return
                scope.launch { playerManager.pause(owner) }
            }

            override fun onStop() {
                if (activeOwner !== owner) return
                scope.launch { playerManager.stop(owner) }
            }

            override fun onSeekTo(pos: Long) {
                if (activeOwner !== owner) return
                scope.launch { playerManager.seekTo(pos, owner) }
            }

            override fun onFastForward() {
                if (activeOwner !== owner) return
                scope.launch { playerManager.seekForward(DEFAULT_MEDIA_SEEK_MS, owner) }
            }

            override fun onRewind() {
                if (activeOwner !== owner) return
                scope.launch { playerManager.seekBackward(DEFAULT_MEDIA_SEEK_MS, owner) }
            }

            override fun onSkipToNext() { if (activeOwner === owner) onNext() }

            override fun onSkipToPrevious() { if (activeOwner === owner) onPrevious() }
        })
        session.isActive = true
        stateJob = scope.launch {
            playerManager.state.collectLatest { playerState ->
                session.setPlaybackState(
                    PlatformPlaybackState.Builder()
                        .setActions(mediaSessionActions(playerState))
                        .setState(
                            mediaSessionPlaybackState(playerState),
                            playerState.currentPosition.coerceAtLeast(0L),
                            if (playerState.isPlaying) playerState.playbackSpeed else 0f
                        )
                        .build()
                )
                session.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title().ifBlank { "IdealPlayer" })
                        .putLong(
                            MediaMetadata.METADATA_KEY_DURATION,
                            playerState.duration.takeIf { it > 0L } ?: -1L
                        )
                        .build()
                )
            }
        }
    }

    fun deactivate(owner: Any? = null) {
        if (owner != null && activeOwner !== owner) return
        activeOwner = null
        stateJob?.cancel()
        stateJob = null
        session.setCallback(null)
        session.setPlaybackState(
            PlatformPlaybackState.Builder()
                .setState(PlatformPlaybackState.STATE_STOPPED, 0L, 0f)
                .build()
        )
        session.isActive = false
    }

    private companion object {
        const val DEFAULT_MEDIA_SEEK_MS = 10_000L
    }
}

internal fun mediaSessionPlaybackState(state: PlayerState): Int = when (state.playbackState) {
    PlaybackState.IDLE -> PlatformPlaybackState.STATE_NONE
    PlaybackState.BUFFERING -> PlatformPlaybackState.STATE_BUFFERING
    PlaybackState.PLAYING -> PlatformPlaybackState.STATE_PLAYING
    PlaybackState.PAUSED -> PlatformPlaybackState.STATE_PAUSED
    PlaybackState.ENDED, PlaybackState.STOPPED -> PlatformPlaybackState.STATE_STOPPED
    PlaybackState.ERROR -> PlatformPlaybackState.STATE_ERROR
}

internal fun mediaSessionActions(state: PlayerState): Long {
    var actions = PlatformPlaybackState.ACTION_PLAY or
        PlatformPlaybackState.ACTION_PAUSE or
        PlatformPlaybackState.ACTION_PLAY_PAUSE or
        PlatformPlaybackState.ACTION_STOP or
        PlatformPlaybackState.ACTION_SKIP_TO_NEXT or
        PlatformPlaybackState.ACTION_SKIP_TO_PREVIOUS
    if (state.isSeekable && state.duration > 0L) {
        actions = actions or
            PlatformPlaybackState.ACTION_SEEK_TO or
            PlatformPlaybackState.ACTION_FAST_FORWARD or
            PlatformPlaybackState.ACTION_REWIND
    }
    return actions
}

package com.idealplayer.app.core.player

/** Privacy-safe, engine-independent playback failures exposed to UI and diagnostics. */
sealed interface PlaybackFailure {
    val category: String

    data object Network : PlaybackFailure { override val category = "network" }
    data object Authorization : PlaybackFailure { override val category = "authorization" }
    data object NotFound : PlaybackFailure { override val category = "not_found" }
    data object Decoder : PlaybackFailure { override val category = "decoder" }
    data object UnsupportedSource : PlaybackFailure { override val category = "unsupported_source" }
    data object Timeout : PlaybackFailure { override val category = "timeout" }
    data object SurfaceLost : PlaybackFailure { override val category = "surface_lost" }
    data object Unknown : PlaybackFailure { override val category = "unknown" }
}

/**
 * Playback state machine above the raw engine state. A request id makes stale updates observable
 * and lets UI/tests reason about one logical playback request across an engine fallback.
 */
sealed interface PlaybackSessionState {
    val requestId: Long?

    data object Idle : PlaybackSessionState { override val requestId: Long? = null }
    data class Preparing(override val requestId: Long) : PlaybackSessionState
    data class SurfaceAttached(override val requestId: Long) : PlaybackSessionState
    data class Ready(override val requestId: Long) : PlaybackSessionState
    data class FirstFrame(override val requestId: Long) : PlaybackSessionState
    data class Confirmed(override val requestId: Long) : PlaybackSessionState
    data class Buffering(
        override val requestId: Long,
        val afterConfirmation: Boolean
    ) : PlaybackSessionState
    data class Recovering(
        override val requestId: Long,
        val fromEngine: String,
        val toEngine: String,
        val failure: PlaybackFailure
    ) : PlaybackSessionState
    data class Failed(
        override val requestId: Long,
        val failure: PlaybackFailure
    ) : PlaybackSessionState
    data class Ended(override val requestId: Long) : PlaybackSessionState
}

data class PlaybackMetricsSnapshot(
    val requestId: Long = 0L,
    val profile: PlaybackProfile = PlaybackProfile.VOD,
    val engineName: String = "",
    val surfaceAttachedAfterMs: Long? = null,
    val readyAfterMs: Long? = null,
    val firstFrameAfterMs: Long? = null,
    val confirmedAfterMs: Long? = null,
    val zapAfterMs: Long? = null,
    val rebufferCount: Int = 0,
    val automaticFallbackUsed: Boolean = false,
    val failureCategory: String = ""
)

internal fun classifyPlaybackFailure(
    message: String?,
    state: PlayerState? = null
): PlaybackFailure {
    val normalized = message.orEmpty().lowercase()
    return when {
        normalized.contains("401") || normalized.contains("403") ||
            normalized.contains("unauthorized") || normalized.contains("forbidden") ->
            PlaybackFailure.Authorization

        normalized.contains("404") || normalized.contains("not found") ->
            PlaybackFailure.NotFound

        normalized.contains("timeout") || normalized.contains("timed out") ->
            PlaybackFailure.Timeout

        normalized.contains("surface") ||
            (state?.hasVideoTrack == true && state.isSurfaceReady.not()) ->
            PlaybackFailure.SurfaceLost

        normalized.containsAny(DECODER_FAILURE_TOKENS) -> PlaybackFailure.Decoder

        normalized.contains("unsupported") || normalized.contains("format") ||
            normalized.contains("container") || normalized.contains("source") ->
            PlaybackFailure.UnsupportedSource

        normalized.contains("http") || normalized.contains("network") ||
            normalized.contains("connection") || normalized.contains("dns") ||
            normalized.contains("socket") -> PlaybackFailure.Network

        else -> PlaybackFailure.Unknown
    }
}

private val DECODER_FAILURE_TOKENS = listOf(
    "codec",
    "decoder",
    "avc1",
    "h.264",
    "h264",
    "hevc",
    "hvc1",
    "hev1",
    "h.265",
    "h265",
    "av01",
    "av1",
    "mpeg-2",
    "mpeg2",
    "mp4a",
    "aac",
    "ac-3",
    "ac3",
    "ec-3",
    "eac3",
    "opus"
)

private fun String.containsAny(tokens: Iterable<String>): Boolean = tokens.any(::contains)

internal fun resolvePlaybackSessionState(
    requestId: Long?,
    state: PlayerState,
    recovery: PlaybackRecoveryState,
    hadConfirmedPlayback: Boolean
): PlaybackSessionState {
    if (requestId == null) return PlaybackSessionState.Idle

    if (recovery.isRecovering) {
        return PlaybackSessionState.Recovering(
            requestId = requestId,
            fromEngine = recovery.fromEngine,
            toEngine = recovery.toEngine,
            failure = failureFromCategory(recovery.lastErrorCategory)
        )
    }

    return when {
        state.playbackState == PlaybackState.ERROR -> PlaybackSessionState.Failed(
            requestId = requestId,
            failure = classifyPlaybackFailure(state.errorMessage, state)
        )

        state.playbackState == PlaybackState.ENDED -> PlaybackSessionState.Ended(requestId)
        state.playbackState == PlaybackState.BUFFERING -> PlaybackSessionState.Buffering(
            requestId = requestId,
            afterConfirmation = hadConfirmedPlayback
        )

        state.isPlaybackConfirmed -> PlaybackSessionState.Confirmed(requestId)
        state.hasRenderedFirstFrame -> PlaybackSessionState.FirstFrame(requestId)
        state.playbackState == PlaybackState.PLAYING ||
            state.playbackState == PlaybackState.PAUSED ||
            state.hasVideoTrack || state.hasAudioTrack -> PlaybackSessionState.Ready(requestId)

        state.isSurfaceReady -> PlaybackSessionState.SurfaceAttached(requestId)
        state.playbackState == PlaybackState.IDLE || state.playbackState == PlaybackState.STOPPED ->
            PlaybackSessionState.Preparing(requestId)

        else -> PlaybackSessionState.Preparing(requestId)
    }
}

internal class PlaybackMetricsTracker(
    private val elapsedRealtimeMs: () -> Long
) {
    private var startedAtMs: Long? = null
    private var lastPlaybackState = PlaybackState.IDLE
    private var snapshot = PlaybackMetricsSnapshot()

    fun begin(requestId: Long, profile: PlaybackProfile): PlaybackMetricsSnapshot {
        startedAtMs = elapsedRealtimeMs()
        lastPlaybackState = PlaybackState.IDLE
        snapshot = PlaybackMetricsSnapshot(requestId = requestId, profile = profile)
        return snapshot
    }

    fun observe(
        requestId: Long,
        state: PlayerState,
        engineName: String,
        hadConfirmedPlayback: Boolean
    ): PlaybackMetricsSnapshot {
        val startedAt = startedAtMs
        if (snapshot.requestId != requestId || startedAt == null) return snapshot
        val elapsedMs = (elapsedRealtimeMs() - startedAt).coerceAtLeast(0L)
        val enteredRebuffer = hadConfirmedPlayback &&
            state.playbackState == PlaybackState.BUFFERING &&
            lastPlaybackState != PlaybackState.BUFFERING

        snapshot = snapshot.copy(
            engineName = engineName,
            surfaceAttachedAfterMs = snapshot.surfaceAttachedAfterMs
                ?: elapsedMs.takeIf { state.isSurfaceReady },
            readyAfterMs = snapshot.readyAfterMs ?: elapsedMs.takeIf {
                state.playbackState == PlaybackState.PLAYING ||
                    state.playbackState == PlaybackState.PAUSED ||
                    state.hasVideoTrack || state.hasAudioTrack
            },
            firstFrameAfterMs = snapshot.firstFrameAfterMs
                ?: elapsedMs.takeIf { state.hasRenderedFirstFrame },
            confirmedAfterMs = snapshot.confirmedAfterMs
                ?: elapsedMs.takeIf { state.isPlaybackConfirmed },
            zapAfterMs = snapshot.zapAfterMs
                ?: elapsedMs.takeIf {
                    snapshot.profile == PlaybackProfile.LIVE && state.isPlaybackConfirmed
                },
            rebufferCount = snapshot.rebufferCount + if (enteredRebuffer) 1 else 0,
            failureCategory = if (state.playbackState == PlaybackState.ERROR) {
                classifyPlaybackFailure(state.errorMessage, state).category
            } else {
                snapshot.failureCategory
            }
        )
        lastPlaybackState = state.playbackState
        return snapshot
    }

    fun markFallback(engineName: String, failure: PlaybackFailure): PlaybackMetricsSnapshot {
        snapshot = snapshot.copy(
            engineName = engineName,
            automaticFallbackUsed = true,
            failureCategory = failure.category
        )
        return snapshot
    }

    fun reset(): PlaybackMetricsSnapshot {
        startedAtMs = null
        lastPlaybackState = PlaybackState.IDLE
        snapshot = PlaybackMetricsSnapshot()
        return snapshot
    }
}

private fun failureFromCategory(category: String): PlaybackFailure = when (category) {
    PlaybackFailure.Network.category -> PlaybackFailure.Network
    PlaybackFailure.Authorization.category -> PlaybackFailure.Authorization
    PlaybackFailure.NotFound.category -> PlaybackFailure.NotFound
    PlaybackFailure.Decoder.category -> PlaybackFailure.Decoder
    PlaybackFailure.UnsupportedSource.category, "source" -> PlaybackFailure.UnsupportedSource
    PlaybackFailure.Timeout.category -> PlaybackFailure.Timeout
    PlaybackFailure.SurfaceLost.category -> PlaybackFailure.SurfaceLost
    else -> PlaybackFailure.Unknown
}

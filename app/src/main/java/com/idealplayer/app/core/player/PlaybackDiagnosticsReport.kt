package com.idealplayer.app.core.player

fun buildPlaybackDiagnosticsReport(
    appVersion: String,
    device: String,
    androidSdk: Int,
    contentType: String,
    diagnostics: PlaybackDiagnostics,
    state: PlayerState
): String {
    val bufferAheadMs = (state.bufferedPosition - state.currentPosition).coerceAtLeast(0L)
    val headers = diagnostics.requestHeaderNames
        .sorted()
        .joinToString(",")
        .ifBlank { "none" }
    val recovery = diagnostics.recovery
    val metrics = diagnostics.metrics

    return buildString {
        appendLine("IdealPlayer playback diagnostics")
        appendLine("app=$appVersion")
        appendLine("device=$device sdk=$androidSdk")
        appendLine("content=${contentType.ifBlank { "unknown" }}")
        appendLine("engine=${diagnostics.engineName}")
        appendLine("state=${state.playbackState} confirmed=${state.isPlaybackConfirmed}")
        appendLine("surface=${state.isSurfaceReady} firstFrame=${state.hasRenderedFirstFrame}")
        appendLine("tracks=video:${state.hasVideoTrack},audio:${state.hasAudioTrack}")
        appendLine("video=${state.currentVideoResolution.ifBlank { "unknown" }} codec=${state.currentVideoCodec.ifBlank { "unknown" }} fps=${state.currentVideoFps.ifBlank { "unknown" }}")
        appendLine("networkKbps=${state.networkSpeedKbps}")
        appendLine("bufferAheadMs=$bufferAheadMs")
        appendLine("source=${diagnostics.streamProtocol}:${diagnostics.streamHost}")
        appendLine("requestHeaders=$headers")
        appendLine("autoRecovery=${recovery.automaticFallbackUsed} recovering=${recovery.isRecovering}")
        appendLine("startupMs=${metrics.confirmedAfterMs ?: -1} firstFrameMs=${metrics.firstFrameAfterMs ?: -1}")
        appendLine("zapMs=${metrics.zapAfterMs ?: -1} rebuffers=${metrics.rebufferCount}")
        append("errorCategory=${metrics.failureCategory.ifBlank { recovery.lastErrorCategory }.ifBlank { "none" }}")
    }
}

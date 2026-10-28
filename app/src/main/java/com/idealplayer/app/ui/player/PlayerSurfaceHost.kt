package com.idealplayer.app.ui.player

import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Surface
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.idealplayer.app.core.player.ExoPlayerEngine
import com.idealplayer.app.core.player.FrameRateMatchingMode
import com.idealplayer.app.core.player.PlayerEngine
import com.idealplayer.app.core.player.PlaybackProfile
import com.idealplayer.app.core.player.PlayerState
import com.idealplayer.app.core.player.VlcPlayerEngine
import com.idealplayer.app.core.player.SystemCaptionPreferences
import com.idealplayer.app.core.player.SystemCaptionPreferenceSnapshot

enum class PlayerShellMode {
    MOBILE,
    TV
}

data class PlayerSurfaceHostState(
    val playerReady: Boolean,
    val playbackActive: Boolean,
    val shellMode: PlayerShellMode,
    val playbackProfile: PlaybackProfile = PlaybackProfile.VOD,
    val frameRateMatchingMode: FrameRateMatchingMode = FrameRateMatchingMode.AUTO,
    val useSystemCaptionStyle: Boolean = true,
    val captionTextSizeSp: Float = 18f,
    val captionForegroundColor: Int = 0xFFFFFFFF.toInt(),
    val captionBackgroundColor: Int = 0x80000000.toInt()
)

@Composable
fun PlayerSurfaceHost(
    modifier: Modifier = Modifier,
    playerEngine: PlayerEngine?,
    playerState: PlayerState,
    surfaceState: PlayerSurfaceHostState,
    onSurfaceBoundsChanged: (Rect) -> Unit = {},
    placeholder: @Composable (() -> Unit)? = null
) {
    if (!surfaceState.playerReady || playerEngine == null) {
        placeholder?.invoke()
        return
    }

    key(surfaceState.shellMode, playerEngine) {
        BoxWithConstraints(
            modifier = modifier.onGloballyPositioned { coordinates ->
                onSurfaceBoundsChanged(coordinates.boundsInWindow().toAndroidRect())
            },
            contentAlignment = Alignment.Center
        ) {
            val viewportAspectRatio = if (maxHeight > 0.dp) {
                maxWidth.value / maxHeight.value
            } else {
                null
            }

            when (playerEngine) {
                is ExoPlayerEngine -> {
                    ExoPlayerRenderer(
                        playerEngine = playerEngine,
                        playerState = playerState,
                        surfaceState = surfaceState,
                        viewportAspectRatio = viewportAspectRatio
                    )
                }
                is VlcPlayerEngine -> {
                    VlcRenderer(
                        playerEngine = playerEngine,
                        playerState = playerState,
                        surfaceState = surfaceState,
                        viewportAspectRatio = viewportAspectRatio
                    )
                }
            }
        }
    }
}

private fun ComposeRect.toAndroidRect(): Rect = Rect(
    left.toInt(),
    top.toInt(),
    right.toInt(),
    bottom.toInt()
)

@Composable
@OptIn(UnstableApi::class)
private fun ExoPlayerRenderer(
    playerEngine: ExoPlayerEngine,
    playerState: PlayerState,
    surfaceState: PlayerSurfaceHostState,
    viewportAspectRatio: Float?
) {
    val context = LocalContext.current
    val systemCaption = if (surfaceState.useSystemCaptionStyle) {
        val preferences = remember(context) { SystemCaptionPreferences(context) }
        val changes = remember(preferences) { preferences.changes() }
        val snapshot by changes.collectAsStateWithLifecycle(initialValue = preferences.snapshot())
        snapshot
    } else null
    AndroidView(
        onReset = null,
        onRelease = { view -> playerEngine.clearPlayerView(view) },
        factory = { context ->
            PlayerView(context).apply {
                keepScreenOn = surfaceState.playbackActive
                useController = false
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                if (surfaceState.shellMode == PlayerShellMode.TV) {
                    isFocusable = false
                    isFocusableInTouchMode = false
                }
                playerEngine.attachPlayerView(this)
                applyCaptionStyle(this, surfaceState, systemCaption)
                applyFrameRatePreference(videoSurfaceView, playerState, surfaceState)
            }
        },
        update = { view ->
            view.keepScreenOn = surfaceState.playbackActive
            if (surfaceState.shellMode == PlayerShellMode.TV) {
                view.isFocusable = false
                view.isFocusableInTouchMode = false
            }
            playerEngine.attachPlayerView(view)
            applyCaptionStyle(view, surfaceState, systemCaption)
            applyFrameRatePreference(view.videoSurfaceView, playerState, surfaceState)
        },
        modifier = Modifier.playerViewport(
            targetAspectRatio = playerEngine.getViewportAspectRatio(playerState.aspectRatioMode),
            viewportAspectRatio = viewportAspectRatio
        )
    )
}

@Composable
private fun VlcRenderer(
    playerEngine: VlcPlayerEngine,
    playerState: PlayerState,
    surfaceState: PlayerSurfaceHostState,
    viewportAspectRatio: Float?
) {
    AndroidView(
        onReset = null,
        onRelease = { view -> playerEngine.detachSurface(view) },
        factory = { context ->
            SurfaceView(context).apply {
                keepScreenOn = surfaceState.playbackActive
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                if (surfaceState.shellMode == PlayerShellMode.TV) {
                    isFocusable = false
                    isFocusableInTouchMode = false
                }
                playerEngine.attachSurface(this)
                applyFrameRatePreference(this, playerState, surfaceState)
            }
        },
        update = { surfaceView ->
            surfaceView.keepScreenOn = surfaceState.playbackActive
            if (surfaceState.shellMode == PlayerShellMode.TV) {
                surfaceView.isFocusable = false
                surfaceView.isFocusableInTouchMode = false
            }
            playerEngine.attachSurface(surfaceView)
            playerEngine.updateSurfaceSize(surfaceView.width, surfaceView.height)
            applyFrameRatePreference(surfaceView, playerState, surfaceState)
        },
        modifier = Modifier.playerViewport(
            targetAspectRatio = viewportAspectRatioFor(playerState),
            viewportAspectRatio = viewportAspectRatio
        )
    )
}

@OptIn(UnstableApi::class)
private fun applyCaptionStyle(view: PlayerView, state: PlayerSurfaceHostState, system: SystemCaptionPreferenceSnapshot?) {
    val subtitleView = view.subtitleView ?: return
    if (system != null) {
        subtitleView.setStyle(CaptionStyleCompat(
            system.foregroundColor, system.backgroundColor, system.windowColor,
            system.edgeType, system.edgeColor,
            system.typefaceName?.let { Typeface.create(it, Typeface.NORMAL) }
        ))
        subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * system.fontScale)
    } else {
        subtitleView.setStyle(
            CaptionStyleCompat(
                state.captionForegroundColor,
                state.captionBackgroundColor,
                android.graphics.Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_NONE,
                android.graphics.Color.BLACK,
                null
            )
        )
        subtitleView.setFixedTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            state.captionTextSizeSp.coerceIn(10f, 48f)
        )
    }
}

internal enum class FrameRateChangeStrategy {
    RESET,
    SEAMLESS_ONLY,
    ALWAYS
}

internal data class FrameRateRequest(
    val framesPerSecond: Float,
    val strategy: FrameRateChangeStrategy
)

internal fun resolveFrameRateRequest(
    mode: FrameRateMatchingMode,
    profile: PlaybackProfile,
    playbackActive: Boolean,
    videoFps: String
): FrameRateRequest {
    val fps = Regex("""\d+(?:\.\d+)?""")
        .find(videoFps)
        ?.value
        ?.toFloatOrNull()
        ?.takeIf { it in 1f..240f }
    if (!playbackActive || mode == FrameRateMatchingMode.OFF || fps == null) {
        return FrameRateRequest(0f, FrameRateChangeStrategy.RESET)
    }
    val strategy = when {
        mode == FrameRateMatchingMode.ALWAYS -> FrameRateChangeStrategy.ALWAYS
        profile == PlaybackProfile.VOD -> FrameRateChangeStrategy.ALWAYS
        else -> FrameRateChangeStrategy.SEAMLESS_ONLY
    }
    return FrameRateRequest(fps, strategy)
}

private fun applyFrameRatePreference(
    videoView: android.view.View?,
    playerState: PlayerState,
    surfaceState: PlayerSurfaceHostState
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val surfaceView = videoView as? SurfaceView ?: return
    val surface = surfaceView.holder.surface.takeIf(Surface::isValid) ?: return
    val request = resolveFrameRateRequest(
        mode = surfaceState.frameRateMatchingMode,
        profile = surfaceState.playbackProfile,
        playbackActive = surfaceState.playbackActive,
        videoFps = playerState.currentVideoFps
    )
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val changeStrategy = when (request.strategy) {
                FrameRateChangeStrategy.ALWAYS -> Surface.CHANGE_FRAME_RATE_ALWAYS
                FrameRateChangeStrategy.RESET,
                FrameRateChangeStrategy.SEAMLESS_ONLY -> Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
            }
            surface.setFrameRate(
                request.framesPerSecond,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                changeStrategy
            )
        } else {
            surface.setFrameRate(
                request.framesPerSecond,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
            )
        }
    }
}

private fun viewportAspectRatioFor(playerState: PlayerState): Float? {
    val width = playerState.videoWidth
    val height = playerState.videoHeight
    if (width <= 0 || height <= 0) {
        return null
    }

    return when (playerState.aspectRatioMode) {
        com.idealplayer.app.core.player.AspectRatioMode.FORCE_16_9 -> 16f / 9f
        com.idealplayer.app.core.player.AspectRatioMode.FORCE_4_3 -> 4f / 3f
        else -> width.toFloat() / height.toFloat()
    }
}

internal fun Modifier.playerViewport(
    targetAspectRatio: Float?,
    viewportAspectRatio: Float?
): Modifier {
    if (
        targetAspectRatio == null ||
        viewportAspectRatio == null ||
        targetAspectRatio <= 0f ||
        viewportAspectRatio <= 0f
    ) {
        return fillMaxSize()
    }

    return if (viewportAspectRatio > targetAspectRatio) {
        fillMaxHeight()
            .aspectRatio(targetAspectRatio, matchHeightConstraintsFirst = true)
    } else {
        fillMaxWidth()
            .aspectRatio(targetAspectRatio)
    }
}

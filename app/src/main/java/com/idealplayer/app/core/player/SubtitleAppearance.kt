package com.idealplayer.app.core.player

import android.view.accessibility.CaptioningManager
import kotlin.math.roundToInt

/** Plain text caption appearance; bitmap and authored ASS layout remain decoder-owned. */
data class SubtitleAppearance(
    val fontScale: Float = 1f,
    val foregroundColor: Int = 0xFFFFFFFF.toInt(),
    val backgroundColor: Int = 0xFF000000.toInt(),
    val edgeType: Int = CaptioningManager.CaptionStyle.EDGE_TYPE_NONE,
    val edgeColor: Int = 0xFF000000.toInt(),
    val typefaceName: String? = null
)

internal fun SystemCaptionPreferenceSnapshot.appearance() = SubtitleAppearance(
    fontScale, foregroundColor, backgroundColor, edgeType, edgeColor, typefaceName
)

/** LibVLC 3's freetype renderer takes these options when the native instance is created. */
internal fun SubtitleAppearance.vlcOptions(): List<String> = buildList {
    val scale = fontScale.takeIf(Float::isFinite)?.coerceIn(0.5f, 2f) ?: 1f
    // Match Media3's default fraction of the viewport height as closely as the integer permits.
    add("--freetype-rel-fontsize=${(1f / (0.0533f * scale)).roundToInt().coerceAtLeast(1)}")
    add("--freetype-color=${foregroundColor and 0xFFFFFF}")
    add("--freetype-opacity=${foregroundColor ushr 24}")
    add("--freetype-background-color=${backgroundColor and 0xFFFFFF}")
    add("--freetype-background-opacity=${backgroundColor ushr 24}")
    val outline = edgeType == CaptioningManager.CaptionStyle.EDGE_TYPE_OUTLINE
    val shadow = edgeType in listOf(
        CaptioningManager.CaptionStyle.EDGE_TYPE_DROP_SHADOW,
        CaptioningManager.CaptionStyle.EDGE_TYPE_RAISED,
        CaptioningManager.CaptionStyle.EDGE_TYPE_DEPRESSED
    )
    add("--freetype-outline-thickness=${if (outline) 4 else 0}")
    add("--freetype-outline-color=${edgeColor and 0xFFFFFF}")
    add("--freetype-outline-opacity=${if (outline) edgeColor ushr 24 else 0}")
    add("--freetype-shadow-color=${edgeColor and 0xFFFFFF}")
    add("--freetype-shadow-opacity=${if (shadow) edgeColor ushr 24 else 0}")
    typefaceName?.takeIf(String::isNotBlank)?.let { add("--freetype-font=$it") }
}

package com.idealplayer.app.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Approved A2 Cinematic Premium semantic colors from Figma node 39:2.
 *
 * Red is reserved for primary actions, live state and transient D-pad focus. Blue represents a
 * persistent route or data selection. Purple is intentionally absent from the production palette.
 */
object IdealPlayerColors {
    val Background = Color(0xFF0A0A0F)
    val Surface = Color(0xFF12121A)
    val SurfaceVariant = Color(0xFF171724)
    val SurfaceElevated = Color(0xFF1E1E30)
    val SurfaceFocus = Color(0xFF24131A)
    val SurfaceSelected = Color(0xFF10264A)
    val CardBackground = Color(0xFF161625)
    val CardBorder = Color(0xFF2A2A3E)

    val Primary = Color(0xFFFF1744)
    val PrimaryPressed = Color(0xFFC41136)
    val PrimaryVariant = PrimaryPressed
    val Secondary = Color(0xFF2979FF)
    val SecondaryPressed = Color(0xFF1D5CCF)
    val SecondaryVariant = SecondaryPressed

    val GradientStart = Primary
    val GradientEnd = Secondary

    /** Source-compatible legacy alias. A2 deliberately resolves it to blue, never purple. */
    @Deprecated("A2 has no purple semantic color; use Secondary", ReplaceWith("Secondary"))
    val Accent = Secondary

    /** Source-compatible legacy alias. A2 gradients end in the selected-state blue. */
    @Deprecated("A2 has no purple semantic color; use GradientEnd", ReplaceWith("GradientEnd"))
    val GradientPurple = GradientEnd

    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFFB0B0C0)
    val TextTertiary = Color(0xFF8A8AA0)
    val TextOnPrimary = Color.White

    val DividerColor = CardBorder
    val Shimmer = Color(0xFF2A2A3E)
    val ShimmerHighlight = Color(0xFF3A3A50)

    val Success = Color(0xFF33D17A)
    val Warning = Color(0xFFFFB020)
    val Error = Color(0xFFFF5252)
    val Info = Color(0xFF40C4FF)
    val Disabled = Color(0xFF545466)

    val FocusBorder = Primary
    val SelectedBorder = Secondary
    val FocusGlow = Color(0x24FF1744)

    val GlassBackground = Color(0x1AFFFFFF)
    val GlassBorder = Color(0x33FFFFFF)

    val RatingStarColor = Warning
    val OverlayDark = Color(0xB8000000)
    val OverlayPlayer = Color(0xE0000000)
    val OverlayGradient = Color.Transparent
}

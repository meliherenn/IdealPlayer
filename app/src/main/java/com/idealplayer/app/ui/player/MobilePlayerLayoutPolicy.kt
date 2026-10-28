package com.idealplayer.app.ui.player

/** Width decisions for touch-player chrome, kept independent from Compose for unit testing. */
internal object MobilePlayerLayoutPolicy {
    private const val EXPANDED_TRANSPORT_MIN_WIDTH_DP = 840f

    fun usesExpandedTransport(widthDp: Float): Boolean {
        return widthDp >= EXPANDED_TRANSPORT_MIN_WIDTH_DP
    }
}

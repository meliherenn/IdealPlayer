package com.idealplayer.app.core.model

data class CatchupWindow(val startTimeMs: Long, val endTimeMs: Long) {
    init {
        require(startTimeMs > 0 && endTimeMs > startTimeMs)
        require(endTimeMs - startTimeMs <= 7 * 24 * 60 * 60 * 1000L)
    }
    val durationMinutes: Int get() = ((endTimeMs - startTimeMs + 59_999) / 60_000).toInt()
}

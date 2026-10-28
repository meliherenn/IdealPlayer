package com.idealplayer.app.benchmark

import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

internal const val FIRST_CHANNEL_TITLE = "Benchmark Channel 01"

internal data class BenchmarkPlaybackStatus(
    val confirmed: Boolean,
    val requestId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val seekable: Boolean,
    val playing: Boolean,
    val engineName: String?,
    val surfaceAttachmentCount: Long
)

internal fun UiDevice.installSyntheticFixture(mediaPort: Int, engine: String? = null) {
    require(engine == null || engine == "EXOPLAYER" || engine == "VLC")
    val result = executeShellCommand(
        "am broadcast --user 0 " +
            "-a com.idealplayer.app.benchmark.SETUP " +
            "-n $TARGET_PACKAGE/com.idealplayer.app.benchmark.BenchmarkFixtureReceiver " +
            "--ei mediaPort $mediaPort" + (engine?.let { " --es engine $it" } ?: "")
    )
    check("result=-1" in result && "seeded" in result) {
        "Synthetic benchmark fixture setup did not complete: ${result.take(240)}"
    }
}

internal fun UiDevice.forceStopTarget() {
    executeShellCommand("am force-stop $TARGET_PACKAGE")
}

internal fun UiDevice.startTargetAndWait() {
    val result = executeShellCommand("am start -W -n $TARGET_PACKAGE/com.idealplayer.app.MainActivity")
    check("Status: ok" in result) { "Target activity did not start" }
}

internal fun UiDevice.resumeTargetTaskAndWait() {
    // Use the same component intent that originally opened the task. Playback identity is
    // checked by each journey: TV resumes with a new request, whereas PiP keeps the old one.
    startTargetAndWait()
    val deadline = System.currentTimeMillis() + 10_000L
    var lastBounds: Rect? = null
    var resumedActivity: String? = null
    do {
        val bounds = targetVisibleBounds()
        lastBounds = bounds
        if (bounds != null) {
            if (bounds.width() >= displayWidth * PIP_MAX_DISPLAY_FRACTION &&
                bounds.height() >= displayHeight * PIP_MAX_DISPLAY_FRACTION
            ) {
                // System UI can retain accessibility focus after PiP has expanded. The resumed
                // Activity plus its full-size window determines which app is in the foreground.
                resumedActivity = executeShellCommand("dumpsys activity activities")
                    .lineSequence().firstOrNull {
                        it.contains("ResumedActivity", ignoreCase = true) && it.contains("$TARGET_PACKAGE/")
                    }
                if (resumedActivity != null) return
            }
        }
        Thread.sleep(100)
    } while (System.currentTimeMillis() < deadline)
    error("Target task did not resume: resumed=$resumedActivity, window=$lastBounds, display=${displayWidth}x$displayHeight")
}

internal fun UiDevice.playbackStatus(): BenchmarkPlaybackStatus? {
    val result = executeShellCommand(
        "am broadcast --user 0 " +
            "-a com.idealplayer.app.benchmark.PLAYBACK_STATUS " +
            "-n $TARGET_PACKAGE/com.idealplayer.app.benchmark.BenchmarkFixtureReceiver"
    )
    val match = PLAYBACK_STATUS_REGEX.find(result) ?: return null
    return BenchmarkPlaybackStatus(
        confirmed = match.groupValues[1] == "confirmed",
        requestId = match.groupValues[2].toLong(),
        positionMs = match.groupValues[3].toLong(),
        durationMs = match.groupValues[4].toLong(),
        seekable = match.groupValues[5].toBoolean(),
        playing = match.groupValues[6].toBoolean(),
        engineName = match.groupValues[7].takeIf(String::isNotEmpty),
        surfaceAttachmentCount = match.groupValues[8].toLong()
    )
}

internal fun UiDevice.requireConfirmedPlayback(
    afterRequestId: Long = 0L,
    timeoutMs: Long = 10_000L
): BenchmarkPlaybackStatus {
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastStatus: BenchmarkPlaybackStatus? = null
    var attempts = 0
    do {
        attempts++
        lastStatus = playbackStatus()
        if (lastStatus?.confirmed == true && lastStatus.requestId > afterRequestId) {
            return lastStatus
        }
        Thread.sleep(100)
    } while (attempts < MINIMUM_STATUS_SAMPLES || System.currentTimeMillis() < deadline)
    error("Playback was not confirmed after request $afterRequestId; last=$lastStatus")
}

internal fun UiDevice.waitForPlaybackRequestAfter(
    requestId: Long,
    timeoutMs: Long
): BenchmarkPlaybackStatus? {
    val deadline = System.currentTimeMillis() + timeoutMs
    var attempts = 0
    do {
        attempts++
        val status = playbackStatus()
        if (status != null && status.requestId > requestId) return status
        Thread.sleep(100)
    } while (attempts < MINIMUM_STATUS_SAMPLES || System.currentTimeMillis() < deadline)
    return null
}

internal fun UiDevice.requirePlaybackPositionAtLeast(
    requestId: Long,
    minimumPositionMs: Long,
    timeoutMs: Long = 5_000L
): BenchmarkPlaybackStatus {
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastStatus: BenchmarkPlaybackStatus? = null
    var attempts = 0
    do {
        attempts++
        lastStatus = playbackStatus()
        if (
            lastStatus?.confirmed == true &&
            lastStatus.requestId == requestId &&
            lastStatus.positionMs >= minimumPositionMs
        ) {
            return lastStatus
        }
        Thread.sleep(100)
    } while (attempts < MINIMUM_STATUS_SAMPLES || System.currentTimeMillis() < deadline)
    error(
        "Playback request $requestId did not reach $minimumPositionMs ms; " +
            "last=$lastStatus"
    )
}

internal fun UiDevice.requireTargetTotalPssKb(
    memInfo: String = executeShellCommand("dumpsys meminfo $TARGET_PACKAGE")
): Long {
    val totalPss = TOTAL_PSS_REGEX.find(memInfo)?.groupValues?.get(1)
        ?.replace(",", "")
        ?.toLongOrNull()
    check(totalPss != null) { "Target process total PSS was unavailable" }
    return totalPss
}

internal fun UiDevice.isLowRamProfile(): Boolean {
    if (executeShellCommand("getprop ro.config.low_ram").trim() == "true") return true
    val memInfo = executeShellCommand("cat /proc/meminfo")
    val totalRamKb = MEM_TOTAL_REGEX.find(memInfo)?.groupValues?.get(1)?.toLongOrNull()
    return totalRamKb != null && totalRamKb <= LOW_RAM_DEVICE_LIMIT_KB
}

internal fun UiDevice.isMobileProfile(): Boolean {
    val characteristics = executeShellCommand("getprop ro.build.characteristics")
    if (characteristics.split(',').any { it.trim() == "tv" }) return false
    return "android.software.leanback" !in executeShellCommand("pm list features")
}

internal fun UiDevice.requirePlaybackReleased(timeoutMs: Long = 10_000L) {
    check(waitForPlaybackReleased(timeoutMs)) {
        "Playback remained confirmed after leaving the player"
    }
}

internal fun UiDevice.waitForPlaybackReleased(
    timeoutMs: Long,
    requireEngineRelease: Boolean = true
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    var attempts = 0
    do {
        attempts++
        val status = playbackStatus()
        if (status != null && status.requestId == 0L && !status.confirmed && !status.playing &&
            (!requireEngineRelease || status.engineName == null)
        ) return true
        Thread.sleep(100)
    } while (attempts < MINIMUM_STATUS_SAMPLES || System.currentTimeMillis() < deadline)
    return false
}

internal fun UiDevice.isTargetInPictureInPicture(): Boolean {
    val activities = executeShellCommand("dumpsys activity activities")
    val targetIndex = activities.indexOf(TARGET_PACKAGE)
    if (targetIndex >= 0) {
        val targetContext = activities.substring(
            startIndex = (targetIndex - ACTIVITY_CONTEXT_CHARS).coerceAtLeast(0),
            endIndex = (targetIndex + ACTIVITY_CONTEXT_CHARS).coerceAtMost(activities.length)
        )
        if (PICTURE_IN_PICTURE_MARKERS.any(targetContext::contains)) return true
    }

    // Newer platform dumps do not consistently print a named PiP/windowing mode. UiAutomator
    // still exposes the target's small floating window, which is enough to distinguish it from
    // the full-screen activity while Home is foreground.
    val bounds = targetVisibleBounds() ?: return false
    return bounds.width() in 1 until (displayWidth * PIP_MAX_DISPLAY_FRACTION).toInt() &&
        bounds.height() in 1 until (displayHeight * PIP_MAX_DISPLAY_FRACTION).toInt()
}

private fun UiDevice.targetVisibleBounds(): Rect? {
    // Measure the application's window. The first matching node can be VLC's letterboxed
    // SurfaceView, whose bounds do not describe whether the Activity has expanded from PiP.
    val window = InstrumentationRegistry.getInstrumentation().uiAutomation.windows
        .firstOrNull { it.root?.packageName?.toString() == TARGET_PACKAGE }
        ?: return null
    return Rect().also(window::getBoundsInScreen)
}

private val PLAYBACK_STATUS_REGEX = Regex(
    "data=\\\"(confirmed|pending):(\\d+):(\\d+):(\\d+):(true|false):(true|false):(EXOPLAYER|VLC)?:(\\d+)\\\""
)
private val TOTAL_PSS_REGEX = Regex("TOTAL PSS:\\s+([\\d,]+)")
private val MEM_TOTAL_REGEX = Regex("MemTotal:\\s+(\\d+)\\s+kB")
private const val LOW_RAM_DEVICE_LIMIT_KB = 1_024L * 1_024L
private const val ACTIVITY_CONTEXT_CHARS = 4_000
private const val PIP_MAX_DISPLAY_FRACTION = 0.8f
private const val MINIMUM_STATUS_SAMPLES = 2
private val PICTURE_IN_PICTURE_MARKERS = listOf(
    "mLastReportedPictureInPictureMode=true",
    "windowingMode=pin",
    "windowingMode=2"
)

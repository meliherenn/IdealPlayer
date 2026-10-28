package com.idealplayer.app.benchmark

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Same AOT-compiled release-like artifact/fixture journey before and after R5 edits. */
@RunWith(AndroidJUnit4::class)
class R5DeviceMeasurement {
    @Test fun measure() {
        check(TARGET_PACKAGE == "com.idealplayer.app.r5benchmark")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val output = File(instrumentation.context.getExternalFilesDir(null), "r5").apply { mkdirs() }
        val result = JSONObject()
        try {
            SyntheticMediaServer().use { server ->
                device.forceStopTarget()
                device.installSyntheticFixture(server.port, "EXOPLAYER")
                val startup = JSONArray()
                repeat(5) {
                    device.forceStopTarget()
                    device.pressHome()
                    val raw = device.executeShellCommand("am start -W -n $TARGET_PACKAGE/com.idealplayer.app.MainActivity")
                    check("Status: ok" in raw)
                    startup.put(Regex("TotalTime: (\\d+)").find(raw)!!.groupValues[1].toLong())
                    check(device.wait(Until.hasObject(By.text(FIRST_CHANNEL_TITLE)), 15_000))
                    device.waitForIdle()
                }
                result.put("startup_total_time_ms", startup)
                File(output, "home-meminfo.txt").writeText(device.executeShellCommand("dumpsys meminfo $TARGET_PACKAGE"))
                device.executeShellCommand("dumpsys gfxinfo $TARGET_PACKAGE reset")
                repeat(4) {
                    repeat(12) { device.pressDPadLeft() }
                    repeat(8) { device.pressDPadRight(); device.waitForIdle(); device.pressDPadLeft(); device.waitForIdle() }
                }
                File(output, "navigation-gfxinfo.txt").writeText(device.executeShellCommand("dumpsys gfxinfo $TARGET_PACKAGE framestats"))
                // End traversal on the Home drawer, then enter the first card with D-pad.
                // Clicking its text while the drawer overlays it can hit the drawer instead.
                device.pressDPadRight()
                device.waitForIdle()
                device.pressDPadCenter()
                var status = device.requireConfirmedPlayback()
                val playback = JSONArray()
                fun sample() {
                    val raw = device.executeShellCommand("am broadcast --user 0 -a com.idealplayer.app.benchmark.R5_PLAYBACK -n $TARGET_PACKAGE/com.idealplayer.app.benchmark.BenchmarkFixtureReceiver")
                    val match = Regex("r5:(\\d+):(-?\\d+):(-?\\d+)").find(raw) ?: error("Missing R5 metrics")
                    check(match.groupValues[1].toLong() == status.requestId)
                    playback.put(JSONObject().put("request", status.requestId)
                        .put("first_frame_ms", match.groupValues[2].toLong()).put("confirmed_ms", match.groupValues[3].toLong()))
                }
                sample()
                device.executeShellCommand("dumpsys gfxinfo $TARGET_PACKAGE reset")
                repeat(20) { index ->
                    device.pressKeyCode(if (index < 10) KeyEvent.KEYCODE_CHANNEL_UP else KeyEvent.KEYCODE_CHANNEL_DOWN)
                    status = device.requireConfirmedPlayback(afterRequestId = status.requestId)
                    sample()
                }
                result.put("playback", playback)
                File(output, "playback-meminfo.txt").writeText(device.executeShellCommand("dumpsys meminfo $TARGET_PACKAGE"))
                File(output, "zap-gfxinfo.txt").writeText(device.executeShellCommand("dumpsys gfxinfo $TARGET_PACKAGE framestats"))
                device.pressBack()
                device.waitForIdle()
                result.put("status", "PASS")
            }
        } catch (error: Throwable) {
            result.put("status", "FAIL").put("failure_type", error.javaClass.simpleName)
            device.dumpWindowHierarchy(File(output, "failure-window.xml"))
            throw error
        } finally {
            File(output, "result.json").writeText(result.toString(2))
            device.forceStopTarget()
        }
    }
}

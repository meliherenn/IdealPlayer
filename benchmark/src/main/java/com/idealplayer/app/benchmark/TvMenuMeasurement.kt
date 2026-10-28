package com.idealplayer.app.benchmark

import android.os.SystemClock
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Measures complete drawer animations separately from rapid, interrupting D-pad input. */
@RunWith(AndroidJUnit4::class)
class TvMenuMeasurement {
    @Test fun remoteReturnsToChosenChannel() {
        check(TARGET_PACKAGE == "com.idealplayer.app.r5benchmark")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val output = File(instrumentation.context.getExternalFilesDir(null), "menu-focus").apply { mkdirs() }
        val result = JSONObject().put("expected_channel", "Benchmark Channel 04")
        try {
            SyntheticMediaServer().use { server ->
                device.forceStopTarget()
                device.installSyntheticFixture(server.port, "EXOPLAYER")
                device.startTargetAndWait()
                check(device.wait(Until.hasObject(By.text(FIRST_CHANNEL_TITLE)), 15_000))
                SystemClock.sleep(1_000)
                repeat(3) { device.pressDPadRight(); SystemClock.sleep(250) }
                device.takeScreenshot(File(output, "chosen-before-menu.png"))
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_MENU)
                SystemClock.sleep(500)
                device.pressDPadDown()
                SystemClock.sleep(300)
                device.pressDPadRight()
                SystemClock.sleep(600)
                device.pressDPadCenter()
                device.requireConfirmedPlayback()
                check(device.wait(Until.hasObject(By.text("Benchmark Channel 04")), 3_000)) {
                    "Menu roundtrip opened a different channel"
                }
                device.takeScreenshot(File(output, "playback.png"))
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

    @Test fun measure() {
        check(TARGET_PACKAGE == "com.idealplayer.app.r5benchmark")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val output = File(instrumentation.context.getExternalFilesDir(null), "menu").apply { mkdirs() }
        val result = JSONObject()
        val appContext = instrumentation.context.createPackageContext(TARGET_PACKAGE, 0)
        val homeLabel = appContext.getString(appContext.resources.getIdentifier("nav_home", "string", TARGET_PACKAGE))
        var tracing = false
        fun focusLabel(): String {
            // TV accessibility caches can retain both the old and new focused virtual node.
            // Ask the provider for current input focus instead of taking the first cached match.
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val node = instrumentation.uiAutomation.rootInActiveWindow
                ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: error("No focused target in app")
            check(node.packageName?.toString() == TARGET_PACKAGE)
            // Compose may expose the focused clickable as an unlabeled parent and its
            // merged text/artwork semantics as children. Inspect the focused subtree.
            fun labels(item: AccessibilityNodeInfo): List<String> =
                listOfNotNull(item.contentDescription?.toString(), item.text?.toString()) +
                    (0 until item.childCount).flatMap { index -> item.getChild(index)?.let(::labels).orEmpty() }
            return labels(node).filter(String::isNotBlank).distinct().joinToString(" | ")
        }
        fun cycle() {
            device.pressDPadRight()
            SystemClock.sleep(600) // Includes 180 ms collapse delay + 220 ms animation.
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            check(!device.hasObject(By.text(homeLabel))) { "Right did not collapse the drawer" }
            device.pressDPadLeft()
            SystemClock.sleep(400)
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            check(device.hasObject(By.text(homeLabel))) { "Left did not expand the drawer" }
        }
        try {
            SyntheticMediaServer().use { server ->
                device.forceStopTarget()
                device.installSyntheticFixture(server.port, "EXOPLAYER")
                device.startTargetAndWait()
                check(device.wait(Until.hasObject(By.text(FIRST_CHANNEL_TITLE)), 15_000))
                SystemClock.sleep(1_000) // Exclude destination entry focus from steady-state animation samples.
                repeat(12) { device.pressDPadLeft() }
                SystemClock.sleep(600)
                result.put("drawer_focus", focusLabel())
                repeat(2) { cycle() }

                // Diagnostic trace is outside the scored samples so tracing overhead is excluded.
                device.executeShellCommand("atrace --async_start -b 8192 -a $TARGET_PACKAGE gfx view")
                tracing = true
                repeat(6) { cycle() }
                File(output, "drawer-trace.txt").writeText(device.executeShellCommand("atrace --async_stop"))
                tracing = false

                repeat(3) { round ->
                    device.executeShellCommand("dumpsys gfxinfo $TARGET_PACKAGE reset")
                    repeat(12) { cycle() }
                    File(output, "settled-$round.txt").writeText(device.executeShellCommand("dumpsys gfxinfo $TARGET_PACKAGE framestats"))
                    device.executeShellCommand("dumpsys gfxinfo $TARGET_PACKAGE reset")
                    repeat(4) {
                        repeat(12) { device.pressDPadLeft() }
                        repeat(8) { device.pressDPadRight(); device.waitForIdle(); device.pressDPadLeft(); device.waitForIdle() }
                    }
                    SystemClock.sleep(600)
                    File(output, "rapid-$round.txt").writeText(device.executeShellCommand("dumpsys gfxinfo $TARGET_PACKAGE framestats"))
                }
                File(output, "meminfo.txt").writeText(device.executeShellCommand("dumpsys meminfo $TARGET_PACKAGE"))
                device.dumpWindowHierarchy(File(output, "drawer-window.xml"))
                // Record the same remote roundtrip even when the baseline chooses a wrong
                // target. These observations are assessed separately from frame measurements.
                val focus = JSONObject().put("start", focusLabel())
                device.pressDPadDown(); SystemClock.sleep(400)
                focus.put("down", focusLabel())
                device.pressDPadRight(); SystemClock.sleep(600)
                focus.put("right", focusLabel())
                device.pressDPadLeft(); SystemClock.sleep(400)
                focus.put("left", focusLabel())
                device.pressBack(); SystemClock.sleep(600)
                focus.put("back", focusLabel())
                result.put("focus_roundtrip", focus)
                result.put("status", "PASS")
            }
        } catch (error: Throwable) {
            result.put("status", "FAIL").put("failure_type", error.javaClass.simpleName)
            device.dumpWindowHierarchy(File(output, "failure-window.xml"))
            throw error
        } finally {
            if (tracing) device.executeShellCommand("atrace --async_stop")
            File(output, "result.json").writeText(result.toString(2))
            device.forceStopTarget()
        }
    }
}

package com.idealplayer.app.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import android.view.KeyEvent
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class TvMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @get:Rule
    val failureEvidence = object : org.junit.rules.TestWatcher() {
        override fun failed(error: Throwable, description: org.junit.runner.Description) {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val directory = java.io.File(InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null), "phase-f").apply { mkdirs() }
            device.dumpWindowHierarchy(java.io.File(directory, "failure-window.xml"))
            device.takeScreenshot(java.io.File(directory, "failure-screen.png"))
        }
    }

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Require
        ),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
    }

    @Test
    fun coldStartupUnprofiled() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
    }

    @Test
    fun tvFocusTraversalFrames() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.waitForIdle()
            // A previous iteration may leave focus at the right edge of the first rail/card row.
            // Reset to a stable edge outside the measured block so every iteration performs
            // real focus transitions on both the empty onboarding state and a populated Home.
            repeat(12) { device.pressDPadLeft() }
            device.waitForIdle()
        }
    ) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(8) {
            device.pressDPadRight()
            device.waitForIdle()
            device.pressDPadLeft()
            device.waitForIdle()
        }
    }

    @Test
    fun guideScrollFrames() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        SyntheticMediaServer().use { mediaServer ->
            benchmarkRule.measureRepeated(
                packageName = TARGET_PACKAGE,
                metrics = listOf(FrameTimingMetric()),
                compilationMode = CompilationMode.Partial(),
                iterations = 5,
                setupBlock = {
                    device.forceStopTarget()
                    device.installSyntheticFixture(mediaServer.port)
                    device.forceStopTarget()
                    pressHome()
                    startActivityAndWait()
                    requireSyntheticHome(device)
                    // Move to the stable left edge instead of toggling Menu. On a cold launch
                    // the drawer collapse animation may still be running, so Menu can close it
                    // while repeated Left always lands on the selected Home rail item.
                    focusDrawerHome(device)
                    repeat(3) {
                        device.pressDPadDown()
                        device.waitForIdle()
                    }
                    device.pressDPadCenter()
                    check(device.wait(Until.hasObject(By.textContains("Program 1")), UI_TIMEOUT_MS)) {
                        "Synthetic Guide did not become visible"
                    }
                    repeat(8) { device.pressDPadUp() }
                    repeat(3) { device.pressDPadDown() }
                    device.waitForIdle()
                }
            ) {
                repeat(10) {
                    device.pressDPadDown()
                    device.waitForIdle()
                }
                repeat(10) {
                    device.pressDPadUp()
                    device.waitForIdle()
                }
            }
        }
    }

    @Test
    fun playerOpenFrames() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        SyntheticMediaServer().use { mediaServer ->
            benchmarkRule.measureRepeated(
                packageName = TARGET_PACKAGE,
                metrics = listOf(FrameTimingMetric()),
                compilationMode = CompilationMode.Partial(),
                iterations = 5,
                setupBlock = {
                    device.forceStopTarget()
                    device.installSyntheticFixture(mediaServer.port)
                    device.forceStopTarget()
                    pressHome()
                    startActivityAndWait()
                    requireSyntheticHome(device)
                    focusFirstSyntheticChannel(device)
                }
            ) {
                device.pressDPadCenter()
                device.requireConfirmedPlayback()
            }
        }
    }

    @Test
    fun liveChannelSwitchFrames() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        SyntheticMediaServer().use { mediaServer ->
            benchmarkRule.measureRepeated(
                packageName = TARGET_PACKAGE,
                metrics = listOf(FrameTimingMetric()),
                compilationMode = CompilationMode.Partial(),
                iterations = 5,
                setupBlock = {
                    device.forceStopTarget()
                    // Channel switching updates last-watched ordering. Re-seed every iteration
                    // so the measured journey always starts at channel 1 with room to advance.
                    device.installSyntheticFixture(mediaServer.port)
                    device.forceStopTarget()
                    pressHome()
                    startActivityAndWait()
                    requireSyntheticHome(device)
                    focusFirstSyntheticChannel(device)
                    device.pressDPadCenter()
                    device.requireConfirmedPlayback()
                }
            ) {
                repeat(CHANNEL_SWITCHES_PER_ITERATION) {
                    val previousRequestId = device.requireConfirmedPlayback().requestId
                    device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_UP)
                    device.requireConfirmedPlayback(afterRequestId = previousRequestId)
                }
            }
        }
    }

    @Test
    fun lowRamPlaybackJourneyMemory() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assumeTrue(
            "Run with a low-RAM device flag or no more than 1 GiB total RAM",
            device.isLowRamProfile()
        )

        SyntheticMediaServer().use { mediaServer ->
            device.forceStopTarget()
            device.installSyntheticFixture(mediaServer.port)
            device.forceStopTarget()
            device.startTargetAndWait()
            requireSyntheticHome(device)

            val totalPssSamplesKb = mutableListOf<Long>()
            val evidence = java.io.File(InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null), "phase-f").apply { mkdirs() }
            val loopEvidence = org.json.JSONArray()
            java.io.File(evidence, "device-memory.txt").writeText(
                device.executeShellCommand("cat /proc/meminfo") + "\nheap=" + device.executeShellCommand("getprop dalvik.vm.heapsize")
            )
            repeat(LOW_RAM_WARMUP_LOOPS + LOW_RAM_JOURNEY_LOOPS) { loopIndex ->
                navigateDrawerTo(device, "tv_guide")
                check(device.wait(Until.hasObject(By.textStartsWith("Program ")), UI_TIMEOUT_MS)) {
                    "Synthetic Guide did not become visible"
                }
                repeat(10) { device.pressDPadDown() }
                repeat(10) { device.pressDPadUp() }

                navigateDrawerTo(device, "nav_home")
                check(device.wait(Until.hasObject(By.textContains("Benchmark Channel")), UI_TIMEOUT_MS)) {
                    "Synthetic Home did not return after Guide"
                }
                focusFirstSyntheticChannel(device)
                device.pressDPadCenter()
                val first = device.requireConfirmedPlayback()
                device.requirePlaybackPositionAtLeast(first.requestId, 500)
                repeat(LOW_RAM_CHANNEL_SWITCHES / 2) {
                    device.switchChannelAndRequireConfirmation(KeyEvent.KEYCODE_CHANNEL_UP)
                }
                repeat(LOW_RAM_CHANNEL_SWITCHES / 2) {
                    device.switchChannelAndRequireConfirmation(KeyEvent.KEYCODE_CHANNEL_DOWN)
                }

                device.pressBack()
                check(device.wait(Until.hasObject(By.textContains("Benchmark Channel")), UI_TIMEOUT_MS)) {
                    "Player did not release back to Home"
                }
                device.waitForIdle()
                device.requirePlaybackReleased()
                Thread.sleep(LOW_RAM_IDLE_STABILIZATION_MS)

                // The assertion and archived breakdown must describe the same process sample.
                val meminfo = device.executeShellCommand("dumpsys meminfo $TARGET_PACKAGE")
                val totalPssKb = device.requireTargetTotalPssKb(meminfo)
                java.io.File(evidence, "loop-$loopIndex-meminfo.txt").writeText(meminfo)
                loopEvidence.put(org.json.JSONObject().apply {
                    put("loop", loopIndex)
                    put("warmup", loopIndex < LOW_RAM_WARMUP_LOOPS)
                    put("confirmed_request", first.requestId)
                    put("confirmed_zaps", LOW_RAM_CHANNEL_SWITCHES)
                    put("idle_ms", LOW_RAM_IDLE_STABILIZATION_MS)
                    put("settled_pss_kb", totalPssKb)
                    put("engine_after_exit", org.json.JSONObject.NULL)
                })
                java.io.File(evidence, "low-ram-journey.json").writeText(org.json.JSONObject().apply {
                    put("loops", loopEvidence)
                    put("status", "INCOMPLETE")
                }.toString(2))
                check(totalPssKb < LOW_RAM_TOTAL_PSS_LIMIT_KB) {
                    "Low-RAM total PSS $totalPssKb KB exceeds $LOW_RAM_TOTAL_PSS_LIMIT_KB KB"
                }
                if (loopIndex >= LOW_RAM_WARMUP_LOOPS) {
                    totalPssSamplesKb += totalPssKb
                }
            }
            println("IDEALPLAYER_LOW_RAM_TOTAL_PSS_KB=${totalPssSamplesKb.joinToString(",")}")
            check(
                !hasMaterialMonotonicGrowth(
                    samplesKb = totalPssSamplesKb,
                    minimumStepKb = LOW_RAM_MATERIAL_GROWTH_STEP_KB
                )
            ) {
                "Low-RAM total PSS grows materially across loops: $totalPssSamplesKb KB"
            }
            check(totalPssSamplesKb.last() <= totalPssSamplesKb.first() + LOW_RAM_RETAINED_GROWTH_LIMIT_KB) {
                "Low-RAM retained PSS growth exceeds limit: $totalPssSamplesKb KB"
            }
            java.io.File(evidence, "low-ram-journey.json").writeText(org.json.JSONObject().apply {
                put("loops", loopEvidence)
                put("status", "PASS")
                put("settled_budget_kb", LOW_RAM_TOTAL_PSS_LIMIT_KB)
                put("retained_growth_budget_kb", LOW_RAM_RETAINED_GROWTH_LIMIT_KB)
                put("material_growth_step_kb", LOW_RAM_MATERIAL_GROWTH_STEP_KB)
            }.toString(2))
        }
    }

    private fun requireSyntheticHome(device: UiDevice) {
        check(device.wait(Until.hasObject(By.text(FIRST_CHANNEL_TITLE)), UI_TIMEOUT_MS)) {
            "Synthetic Home did not become visible"
        }
        device.waitForIdle()
    }

    private fun focusFirstSyntheticChannel(device: UiDevice) {
        focusDrawerHome(device)
        device.pressDPadRight()
        check(waitForFocusedNode(device) { left -> left >= CONTENT_FOCUS_MIN_X }) {
            "Synthetic channel card did not receive focus"
        }
        device.waitForIdle()
    }

    private fun navigateDrawerTo(device: UiDevice, resourceName: String) {
        val context = InstrumentationRegistry.getInstrumentation().context.createPackageContext(TARGET_PACKAGE, 0)
        // The app's saved language can differ from the instrumentation process's locale.
        val contexts = listOf("tr", "en").map { language ->
            context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag(language))
            })
        }
        val menuResources = listOf("nav_home", "nav_search", "nav_live_tv", "tv_guide")
        val menuLabels = menuResources.map { name -> contexts.map { localized ->
            localized.getString(localized.resources.getIdentifier(name, "string", TARGET_PACKAGE))
        }.toSet() }
        val targetIndex = menuResources.indexOf(resourceName)
        check(targetIndex >= 0)
        fun focusedIndex(): Int {
            return try {
                val focused = device.findObjects(By.pkg(TARGET_PACKAGE).focused(true))
                    .firstOrNull { it.visibleBounds.left < DRAWER_FOCUS_MAX_X } ?: return -1
                // Compose replaces accessibility nodes while the drawer animates; reacquire on
                // the next bounded poll instead of retaining a stale UiObject2 across that frame.
                menuLabels.indexOfFirst { labels -> labels.any { name ->
                    focused.contentDescription == name || focused.text == name ||
                        focused.findObject(By.desc(name)) != null || focused.findObject(By.text(name)) != null
                } }
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                -1
            }
        }
        focusDrawerHome(device)
        repeat(menuLabels.size + 1) {
            val initialDeadline = System.currentTimeMillis() + FOCUS_TIMEOUT_MS
            var currentIndex = focusedIndex()
            while (currentIndex < 0 && System.currentTimeMillis() < initialDeadline) {
                Thread.sleep(50)
                currentIndex = focusedIndex()
            }
            if (currentIndex == targetIndex) {
                device.pressDPadCenter()
                return
            }
            check(currentIndex >= 0) { "Focused drawer item is not a Home/Search/Live/Guide destination" }
            if (currentIndex < targetIndex) device.pressDPadDown() else device.pressDPadUp()
            val nextIndex = currentIndex + if (currentIndex < targetIndex) 1 else -1
            val deadline = System.currentTimeMillis() + FOCUS_TIMEOUT_MS
            while (focusedIndex() != nextIndex && System.currentTimeMillis() < deadline) Thread.sleep(50)
            check(focusedIndex() == nextIndex) {
                "Drawer focus did not move from ${menuResources[currentIndex]}"
            }
            device.waitForIdle()
        }
        error("Drawer destination did not receive focus: $resourceName")
    }

    private fun focusDrawerHome(device: UiDevice) {
        check(waitForFocusedNode(device) { true }) { "TV screen did not establish focus" }
        repeat(12) { device.pressDPadLeft() }
        check(waitForFocusedNode(device) { left -> left < DRAWER_FOCUS_MAX_X }) {
            "TV drawer did not receive focus"
        }
        device.waitForIdle()
    }

    private fun waitForFocusedNode(
        device: UiDevice,
        timeoutMs: Long = FOCUS_TIMEOUT_MS,
        acceptsLeft: (Int) -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val focused = device.findObject(By.pkg(TARGET_PACKAGE).focused(true))
            if (focused != null && acceptsLeft(focused.visibleBounds.left)) return true
            Thread.sleep(50)
        }
        return false
    }

    private companion object {
        const val UI_TIMEOUT_MS = 10_000L
        const val FOCUS_TIMEOUT_MS = 5_000L
        const val DRAWER_FOCUS_MAX_X = 150
        const val CONTENT_FOCUS_MIN_X = 200
        const val CHANNEL_SWITCHES_PER_ITERATION = 8
        const val LOW_RAM_CHANNEL_SWITCHES = 10
        const val LOW_RAM_WARMUP_LOOPS = 1
        const val LOW_RAM_JOURNEY_LOOPS = 3
        const val LOW_RAM_IDLE_STABILIZATION_MS = 60_000L
        const val LOW_RAM_TOTAL_PSS_LIMIT_KB = 280L * 1_024L
        const val LOW_RAM_MATERIAL_GROWTH_STEP_KB = 2L * 1_024L
        const val LOW_RAM_RETAINED_GROWTH_LIMIT_KB = 10L * 1_024L
    }
}

private fun UiDevice.switchChannelAndRequireConfirmation(keyCode: Int) {
    val previousRequestId = requireConfirmedPlayback().requestId
    pressKeyCode(keyCode)
    requireConfirmedPlayback(afterRequestId = previousRequestId)
}

private fun hasMaterialMonotonicGrowth(samplesKb: List<Long>, minimumStepKb: Long): Boolean {
    if (samplesKb.size < 3) return false
    return samplesKb.zipWithNext().all { (before, after) ->
        after > before + minimumStepKb
    }
}

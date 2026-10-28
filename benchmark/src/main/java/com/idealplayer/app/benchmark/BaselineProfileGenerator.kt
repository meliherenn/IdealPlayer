package com.idealplayer.app.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun launcherStartup() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
        }
    }

    @Test
    fun tvFocusJourneys() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = false
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()

            // Cover a deterministic 10-foot focus journey. Resetting to the left edge keeps
            // the journey useful both for the two-card first-run state and for populated Home
            // rails, without relying on provider data or synthetic production records.
            repeat(12) { device.pressDPadLeft() }
            device.waitForIdle()
            repeat(8) {
                device.pressDPadRight()
                device.waitForIdle()
                device.pressDPadLeft()
                device.waitForIdle()
            }
            device.pressBack()
            device.waitForIdle()
        }
    }
}

internal val TARGET_PACKAGE: String
    get() = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        .getString("targetPackage") ?: "com.idealplayer.app"

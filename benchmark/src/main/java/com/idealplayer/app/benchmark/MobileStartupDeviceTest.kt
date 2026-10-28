package com.idealplayer.app.benchmark

import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reproduces the audit's launcher/rotation/input path without playback or synthetic catalog. */
@RunWith(AndroidJUnit4::class)
class MobileStartupDeviceTest {
    @Test
    fun coldLaunchAndRotationRetainFocusedResponsiveWindow() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assumeTrue("Phone startup requires a mobile emulator", device.isMobileProfile())
        val startupPackage = InstrumentationRegistry.getArguments().getString("startupPackage") ?: TARGET_PACKAGE
        require(startupPackage == TARGET_PACKAGE || startupPackage == "$TARGET_PACKAGE.debug")
        try {
            repeat(5) {
                device.executeShellCommand("am force-stop $startupPackage")
                device.setOrientationNatural()
                val result = device.executeShellCommand("am start -W -n $startupPackage/.MainActivity")
                check("Status: ok" in result) { "Startup target did not launch" }
                device.requireFocusedTargetWindow(startupPackage, Surface.ROTATION_0)
                device.setOrientationLeft()
                device.requireFocusedTargetWindow(startupPackage, Surface.ROTATION_90)
                // Exercise input dispatch while the landscape window owns focus.
                device.pressDPadRight()
                device.setOrientationNatural()
                device.requireFocusedTargetWindow(startupPackage, Surface.ROTATION_0)
            }
        } finally {
            device.unfreezeRotation()
            device.executeShellCommand("am force-stop $startupPackage")
        }
    }

    private fun UiDevice.requireFocusedTargetWindow(startupPackage: String, rotation: Int) {
        val deadline = System.currentTimeMillis() + 15_000L
        do {
            val window = executeShellCommand("dumpsys window windows")
            val activity = executeShellCommand("dumpsys activity activities")
            val focused = (window + "\n" + activity).lineSequence().any {
                ("mCurrentFocus=" in it || "mFocusedWindow=" in it || "Resumed:" in it) &&
                    "$startupPackage/" in it
            }
            if (displayRotation == rotation && focused && wait(Until.hasObject(By.pkg(startupPackage)), 500)) {
                waitForIdle()
                return
            }
            Thread.sleep(200)
        } while (System.currentTimeMillis() < deadline)
        error("Target did not retain a focused window after rotation=$rotation")
    }
}

package com.idealplayer.app

import android.app.UiAutomation
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.SystemClock
import android.view.Surface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idealplayer.app.core.common.DeviceUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileWindowRuntimeTest {
    @Test
    fun launcherContractsKeepMobileFlexibleAndTvLandscapeOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mobileInfo = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA
        )
        val tvInfo = context.packageManager.getActivityInfo(
            ComponentName(context, TvMainActivity::class.java),
            PackageManager.GET_META_DATA
        )

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, mobileInfo.screenOrientation)
        assertTrue(mobileInfo.configChanges and ActivityInfo.CONFIG_ORIENTATION != 0)
        assertTrue(mobileInfo.configChanges and ActivityInfo.CONFIG_SCREEN_SIZE != 0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, tvInfo.screenOrientation)
    }

    @Test
    fun mobileActivitySurvivesPortraitAndLandscapeWithoutTvLock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeFalse(DeviceUtils.resolveUiMode(instrumentation.targetContext).isTv)
        val automation = instrumentation.uiAutomation

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Rotation 0 is landscape on tablets with a naturally wide display.
            // Infer the natural orientation before freezing rotation, rather than
            // treating every non-TV device as a naturally portrait phone.
            var naturalOrientation = Configuration.ORIENTATION_UNDEFINED
            scenario.onActivity { activity ->
                val orientation = activity.resources.configuration.orientation
                assertTrue(orientation == Configuration.ORIENTATION_PORTRAIT ||
                    orientation == Configuration.ORIENTATION_LANDSCAPE)
                @Suppress("DEPRECATION")
                val rotation = activity.windowManager.defaultDisplay.rotation
                naturalOrientation = if (rotation == Surface.ROTATION_90 ||
                    rotation == Surface.ROTATION_270) oppositeOrientation(orientation) else orientation
            }
            try {
                assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0))
                waitForOrientation(scenario, naturalOrientation)
                scenario.onActivity { activity ->
                    assertNotEquals(
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                        activity.requestedOrientation
                    )
                }

                assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
                waitForOrientation(scenario, oppositeOrientation(naturalOrientation))
                scenario.onActivity { activity ->
                    assertNotEquals(
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                        activity.requestedOrientation
                    )
                }
            } finally {
                automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
            }
        }
    }

    private fun waitForOrientation(
        scenario: ActivityScenario<MainActivity>,
        expectedOrientation: Int
    ) {
        val deadline = SystemClock.uptimeMillis() + ORIENTATION_TIMEOUT_MS
        var actualOrientation = Configuration.ORIENTATION_UNDEFINED
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                actualOrientation = activity.resources.configuration.orientation
            }
            if (actualOrientation == expectedOrientation) return
            Thread.sleep(100)
        }
        assertEquals(expectedOrientation, actualOrientation)
    }

    private fun oppositeOrientation(orientation: Int): Int =
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            Configuration.ORIENTATION_LANDSCAPE
        } else {
            Configuration.ORIENTATION_PORTRAIT
        }

    private companion object {
        const val ORIENTATION_TIMEOUT_MS = 5_000L
    }
}

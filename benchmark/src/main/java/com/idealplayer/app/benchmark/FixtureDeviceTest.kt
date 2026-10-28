package com.idealplayer.app.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import org.junit.runner.RunWith

/** A fixture failure must be diagnosed before interpreting playback measurements. */
@RunWith(AndroidJUnit4::class)
class FixtureDeviceTest {
    @Test
    fun repeatedSeedReplacesCatalogWithoutDuplicatesAcrossProcessRestarts() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        SyntheticMediaServer().use { server ->
            try {
                repeat(3) {
                    device.forceStopTarget()
                    device.installSyntheticFixture(server.port)
                    val status = device.executeShellCommand(
                        "am broadcast --user 0 -a com.idealplayer.app.benchmark.FIXTURE_STATUS " +
                            "-n $TARGET_PACKAGE/com.idealplayer.app.benchmark.BenchmarkFixtureReceiver"
                    )
                    check("result=-1" in status && "data=\"fixture:1:180:2880\"" in status) {
                        "Fixture must contain exactly 1 playlist, 180 channels, 2880 programs: $status"
                    }
                }
            } finally {
                device.forceStopTarget()
            }
        }
    }

    @Test
    fun invalidPortReportsSafeFailureStage() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            val result = device.executeShellCommand(
                "am broadcast --user 0 -a com.idealplayer.app.benchmark.SETUP " +
                    "-n $TARGET_PACKAGE/com.idealplayer.app.benchmark.BenchmarkFixtureReceiver --ei mediaPort 0"
            )
            check("result=0" in result && "data=\"failed:validate_port:IllegalArgumentException\"" in result) {
                "Invalid fixture input must fail with a safe stage: $result"
            }
        } finally {
            device.forceStopTarget()
        }
    }
}

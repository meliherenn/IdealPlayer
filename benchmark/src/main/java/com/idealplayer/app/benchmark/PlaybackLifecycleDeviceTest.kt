package com.idealplayer.app.benchmark

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native engine/surface checks use only the local synthetic media server. */
@RunWith(AndroidJUnit4::class)
class PlaybackLifecycleDeviceTest {
    @Test fun tvMedia3BackgroundAndZap() = tvJourney("EXOPLAYER")
    @Test fun tvVlcBackgroundAndStableSurface() = tvJourney("VLC")

    private fun tvJourney(engine: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assumeTrue("TV lifecycle journey", !device.isMobileProfile())
        SyntheticMediaServer().use { server ->
            try {
                device.forceStopTarget()
                device.installSyntheticFixture(server.port, engine)
                device.forceStopTarget()
                device.startTargetAndWait()
                check(device.wait(Until.hasObject(By.text(FIRST_CHANNEL_TITLE)), 15_000))
                device.waitForIdle()
                repeat(12) { device.pressDPadLeft() }
                device.pressDPadRight()
                device.waitForIdle()
                device.pressDPadCenter()
                val initial = device.requireConfirmedPlayback(timeoutMs = 20_000)
                val surface = device.engineSurfaceStatus()
                check(if (engine == "VLC") surface.first == "VLC" else surface.first == "EXOPLAYER") {
                    "Requested engine was not active: $surface"
                }
                device.requirePlaybackPositionAtLeast(initial.requestId, initial.positionMs + 3_000, 10_000)
                check(device.engineSurfaceStatus() == surface) { "Progress updates reattached the native surface" }
                repeat(3) {
                    val request = device.requireConfirmedPlayback().requestId
                    device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_UP)
                    device.requireConfirmedPlayback(request, 20_000)
                }
                device.pressHome()
                check(device.wait(Until.hasObject(By.pkg(device.launcherPackageName)), 10_000)) {
                    "TV Home did not open the launcher; check emulator setup before playback lifecycle"
                }
                check(device.waitForPlaybackReleased(10_000, requireEngineRelease = false)) {
                    "TV Home left playback active"
                }
                device.resumeTargetTaskAndWait()
                device.requireConfirmedPlayback(timeoutMs = 20_000)
                // First Back may hide an overlay, second exits the player route.
                device.pressBack()
                if (!device.waitForPlaybackReleased(2_000)) device.pressBack()
                check(device.waitForPlaybackReleased(10_000)) { "TV Back did not release playback" }
            } catch (failure: Throwable) {
                // Preserve the foreground window and playback state before teardown so a
                // failed Home transition can be distinguished from an engine stop failure.
                runCatching {
                    val directory = File(
                        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                            ?.takeIf { it.isNotBlank() }?.let(::File)
                            ?: InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null),
                        "phase-g"
                    ).apply { mkdirs() }
                    device.takeScreenshot(File(directory, "tv-$engine-failure.png"))
                    File(directory, "tv-$engine-failure.txt").writeText(
                        "playback=${device.playbackStatus()}\n" +
                            device.executeShellCommand("dumpsys activity activities").lineSequence()
                                .filter { it.contains("ResumedActivity") || it.contains("mState=") }
                                .joinToString("\n")
                    )
                }
                throw failure
            } finally {
                device.forceStopTarget()
            }
        }
    }

    private fun UiDevice.engineSurfaceStatus(): Pair<String, Long> {
        val status = checkNotNull(playbackStatus()) { "Missing native engine status" }
        return checkNotNull(status.engineName) to status.surfaceAttachmentCount
    }
}

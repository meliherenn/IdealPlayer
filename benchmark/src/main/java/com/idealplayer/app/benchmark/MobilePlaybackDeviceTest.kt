package com.idealplayer.app.benchmark

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class MobilePlaybackDeviceTest {
    @Test
    fun playbackSurvivesRotationAndEntersPictureInPictureBeforeCleanTeardown() = mobileJourney("EXOPLAYER")

    @Test
    fun vlcSurvivesRotationAndEntersPictureInPictureBeforeCleanTeardown() = mobileJourney("VLC")

    @Test
    fun tabletResizeReleasesInlineMedia3() = inlineResizeJourney("EXOPLAYER")

    @Test
    fun tabletResizeReleasesInlineVlc() = inlineResizeJourney("VLC")

    private fun inlineResizeJourney(engine: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assumeTrue("Inline playback requires a non-TV runtime", device.isMobileProfile())
        SyntheticMediaServer().use { mediaServer ->
            try {
                device.forceStopTarget()
                device.executeShellCommand("wm size 1200x1920")
                device.executeShellCommand("wm density 160")
                device.installSyntheticFixture(mediaServer.port, engine)
                device.forceStopTarget()
                device.startTargetAndWait()
                device.ensureTargetForeground()
                // Android 12's phone launcher can stay portrait even with rotation frozen.
                // Rotate the resizable target activity after it owns the foreground window.
                device.setOrientationLeft()
                check(device.waitForDisplayRotation()) { "Inline player activity did not rotate" }
                val liveTab = device.waitForLocalizedAction("Live TV", "Canlı TV", UI_TIMEOUT_MS)
                check(liveTab != null) { "Tablet Live TV navigation was not visible" }
                liveTab.clickClickableAncestor("Live TV tab")

                val emptyPanel = localizedPattern("Select a channel from the list", "Sol listeden bir kanal seçin")
                check(device.wait(Until.hasObject(By.text(emptyPanel)), UI_TIMEOUT_MS)) {
                    "Expanded tablet did not expose an inline playback panel"
                }
                val channel = device.wait(Until.findObject(By.text(FIRST_CHANNEL_TITLE)), UI_TIMEOUT_MS)
                check(channel != null)
                channel.clickClickableAncestor("inline channel")
                val initial = device.requireConfirmedPlayback(timeoutMs = 20_000)
                check(initial.engineName == engine) { "Inline playback used a different engine" }
                check(device.wait(Until.gone(By.text(emptyPanel)), UI_TIMEOUT_MS))

                // Keep the Activity and live-TV nav entry, but cross the inline layout threshold.
                device.executeShellCommand("wm size 600x800")
                check(device.waitForPlaybackReleased(10_000)) {
                    "Shrinking the tablet left its hidden inline engine active"
                }
                device.executeShellCommand("wm size 1200x1920")
                check(device.wait(Until.hasObject(By.text(emptyPanel)), UI_TIMEOUT_MS)) {
                    "Expanded tablet did not restore its empty inline panel"
                }
                check(device.playbackStatus()?.requestId == 0L) {
                    "Restoring the tablet panel restarted hidden playback"
                }
                val nextChannel = device.wait(Until.findObject(By.text(FIRST_CHANNEL_TITLE)), UI_TIMEOUT_MS)
                check(nextChannel != null)
                nextChannel.clickClickableAncestor("restored inline channel")
                device.requireConfirmedPlayback(afterRequestId = initial.requestId, timeoutMs = 20_000)
            } catch (error: Throwable) {
                device.captureFailureEvidence("inline-$engine")
                throw error
            } finally {
                device.forceStopTarget()
                device.executeShellCommand("wm size reset")
                device.executeShellCommand("wm density reset")
                device.unfreezeRotation()
            }
        }
    }

    private fun mobileJourney(engine: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assumeTrue("Mobile playback journey requires a non-TV runtime", device.isMobileProfile())

        val mediaBytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("synthetic-playback-300s.mp4").use { it.readBytes() }
        SyntheticMediaServer(mediaBytes).use { mediaServer ->
            try {
                device.setOrientationNatural()
                check(device.waitForNaturalDisplayRotation()) {
                    "Phone did not reset to its natural orientation before playback"
                }
                device.forceStopTarget()
                device.installSyntheticFixture(mediaServer.port, engine)
                device.forceStopTarget()
                device.startTargetAndWait()

                var playbackRequest: BenchmarkPlaybackStatus? = null
                var remainingChannelTaps = 3
                while (playbackRequest == null && remainingChannelTaps > 0) {
                    remainingChannelTaps--
                    device.ensureTargetForeground()
                    val channel = device.wait(
                        Until.findObject(By.text(FIRST_CHANNEL_TITLE)),
                        UI_TIMEOUT_MS
                    )
                    check(channel != null) { "Synthetic channel was not visible on mobile Home" }
                    channel.clickClickableAncestor("synthetic channel card")
                    playbackRequest = device.waitForPlaybackRequestAfter(
                        requestId = 0L,
                        timeoutMs = PLAYBACK_REQUEST_START_TIMEOUT_MS
                    )
                }
                check(playbackRequest != null) { "Synthetic channel card did not start playback" }
                val initialPlayback = device.requireConfirmedPlayback()
                check(initialPlayback.engineName == engine) { "Mobile playback used a different engine" }

                device.setOrientationLeft()
                check(device.waitForDisplayRotation()) { "Phone did not rotate to landscape" }
                check(device.requireConfirmedPlayback().requestId == initialPlayback.requestId) {
                    "Rotation unexpectedly replaced the active playback request"
                }

                device.verifyScreenLockBlocksSeekAndUnlockRestoresIt(initialPlayback.requestId)

                device.pressHome()
                check(device.waitForPictureInPicture()) {
                    "Active mobile playback did not enter picture-in-picture"
                }
                check(device.requireConfirmedPlayback().requestId == initialPlayback.requestId) {
                    "Picture-in-picture unexpectedly replaced the active playback request"
                }

                device.resumeTargetTaskAndWait()
                check(device.requireConfirmedPlayback().requestId == initialPlayback.requestId) {
                    "Expanding picture-in-picture unexpectedly replaced the playback request"
                }
                // The resume command can return while the PiP expand/rotation animation is still
                // handing input back to the full-screen activity. Wait for that transition so
                // Back exercises PlayerScreen teardown rather than the system animation.
                device.waitForIdle()
                Thread.sleep(PIP_EXPAND_SETTLE_MS)
                var playbackReleased = false
                var remainingBackTaps = 2
                while (!playbackReleased && remainingBackTaps > 0) {
                    remainingBackTaps--
                    // PiP may return in portrait. Resolve the actual app control instead of
                    // reusing a landscape coordinate or matching the system navigation button.
                    val back = By.pkg(TARGET_PACKAGE).desc(localizedPattern("Back", "Geri"))
                    if (!device.hasObject(back)) {
                        device.click(device.displayWidth / 2, device.displayHeight / 2)
                    }
                    device.wait(Until.findObject(back), UI_TIMEOUT_MS)
                        ?.clickClickableAncestor("player back")
                    if (device.waitForPlaybackReleased(BACK_ACTION_TIMEOUT_MS)) {
                        playbackReleased = true
                    }
                }
                check(playbackReleased) { "Player back action did not release playback after PiP" }
            } catch (error: Throwable) {
                device.captureFailureEvidence("fullscreen-$engine")
                throw error
            } finally {
                device.unfreezeRotation()
                device.forceStopTarget()
            }
        }
    }

    private fun UiDevice.waitForDisplayRotation(): Boolean {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) {
                waitForIdle()
                return true
            }
            Thread.sleep(100)
        }
        return false
    }

    private fun UiDevice.waitForNaturalDisplayRotation(): Boolean {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (displayRotation == Surface.ROTATION_0) {
                waitForIdle()
                return true
            }
            Thread.sleep(100)
        }
        return false
    }

    private fun UiDevice.waitForPictureInPicture(): Boolean {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isTargetInPictureInPicture()) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun UiDevice.ensureTargetForeground() {
        val deadline = System.currentTimeMillis() + FOREGROUND_RECOVERY_TIMEOUT_MS
        var targetStableSince: Long? = null
        while (System.currentTimeMillis() < deadline) {
            if (currentPackageName == TARGET_PACKAGE) {
                val now = System.currentTimeMillis()
                val stableSince = targetStableSince ?: now.also { targetStableSince = it }
                if (now - stableSince >= TARGET_FOREGROUND_STABILITY_MS) {
                    return
                }
                Thread.sleep(FOREGROUND_STABILITY_SAMPLE_MS)
                continue
            }
            targetStableSince = null
            pressBack()
            Thread.sleep(FOREGROUND_RECOVERY_SETTLE_MS)
            if (currentPackageName != TARGET_PACKAGE) {
                resumeTargetTaskAndWait()
            }
        }
        check(currentPackageName == TARGET_PACKAGE) {
            "System UI interruption kept the target app in the background: $currentPackageName"
        }
    }

    private fun UiDevice.verifyScreenLockBlocksSeekAndUnlockRestoresIt(requestId: Long) {
        val beforeLock = requireConfirmedPlayback()
        check(beforeLock.requestId == requestId && beforeLock.seekable && beforeLock.durationMs > 0L) {
            "Synthetic playback did not expose the finite seek window required by gesture checks"
        }

        var lockAction = waitForLocalizedAction(
            english = "Lock Screen",
            turkish = "Ekranı kilitle",
            timeoutMs = SHORT_UI_TIMEOUT_MS
        )
        var options = if (lockAction == null) {
            waitForLocalizedAction(
                english = "Options",
                turkish = "Seçenekler",
                timeoutMs = SHORT_UI_TIMEOUT_MS
            )
        } else {
            null
        }
        if (lockAction == null && options == null) {
            click(displayWidth / 2, displayHeight / 2)
            lockAction = waitForLocalizedAction(
                english = "Lock Screen",
                turkish = "Ekranı kilitle",
                timeoutMs = SHORT_UI_TIMEOUT_MS
            )
            if (lockAction == null) {
                options = waitForLocalizedAction(
                    english = "Options",
                    turkish = "Seçenekler",
                    timeoutMs = UI_TIMEOUT_MS
                )
            }
        }
        if (lockAction == null) {
            check(options != null) {
                "Neither adaptive player options nor screen lock was visible; " +
                    "pip=${isTargetInPictureInPicture()}, labels=${visibleTargetLabels()}"
            }
            options.clickClickableAncestor("player options")
            waitForIdle()

            lockAction = findObject(By.text(localizedPattern("Lock Screen", "Ekranı kilitle")))
            var remainingScrolls = 4
            while (lockAction == null && remainingScrolls > 0) {
                remainingScrolls--
                swipe(
                    displayWidth / 2,
                    (displayHeight * 0.82f).toInt(),
                    displayWidth / 2,
                    (displayHeight * 0.42f).toInt(),
                    20
                )
                lockAction = wait(
                    Until.findObject(By.text(localizedPattern("Lock Screen", "Ekranı kilitle"))),
                    SHORT_UI_TIMEOUT_MS
                )
            }
        }
        check(lockAction != null) {
            "Screen lock action was not reachable in player options; labels=${visibleTargetLabels()}"
        }
        lockAction.clickClickableAncestor("screen lock")
        waitForIdle()

        val unlock = waitForLocalizedAction(
            english = "Unlock",
            turkish = "Kilidi aç",
            timeoutMs = UI_TIMEOUT_MS
        )
        check(unlock != null) {
            "Persistent unlock action was not visible; labels=${visibleTargetLabels()}"
        }
        dismissImmersiveConfirmation()
        val lockedSampleStarted = SystemClock.elapsedRealtime()
        val lockedPosition = requireConfirmedPlayback().positionMs
        doubleTapRightGesture()
        Thread.sleep(LOCKED_GESTURE_OBSERVATION_MS)
        val afterLockedGesture = requireConfirmedPlayback()
        check(afterLockedGesture.requestId == requestId)
        val lockedElapsed = SystemClock.elapsedRealtime() - lockedSampleStarted
        val lockedAdvance = afterLockedGesture.positionMs - lockedPosition
        check(lockedAdvance - lockedElapsed < MINIMUM_SEEK_ADVANCE_MS) {
            "Screen lock allowed a seek gesture: position advanced $lockedAdvance ms in $lockedElapsed ms"
        }

        unlock.clickClickableAncestor("screen unlock")
        check(
            wait(
                Until.gone(By.desc(localizedPattern("Unlock", "Kilidi aç"))),
                UI_TIMEOUT_MS
            ) && wait(
                Until.gone(By.text(localizedPattern("Unlock", "Kilidi aç"))),
                UI_TIMEOUT_MS
            )
        ) { "Screen lock did not close after the unlock action" }

        click(
            displayWidth / 2,
            (displayHeight * SEEK_GESTURE_Y_FRACTION).toInt()
        )
        check(
            wait(
                Until.gone(By.desc(localizedPattern("Options", "Seçenekler"))),
                UI_TIMEOUT_MS
            )
        ) { "Player controls did not hide before the unlocked seek gesture" }
        dismissImmersiveConfirmation()
        val seekSampleStarted = SystemClock.elapsedRealtime()
        val beforeSeek = requireConfirmedPlayback()
        doubleTapRightGesture()
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        var seekAdvance = 0L
        do {
            val afterSeek = requireConfirmedPlayback()
            check(afterSeek.requestId == requestId)
            val elapsed = SystemClock.elapsedRealtime() - seekSampleStarted
            seekAdvance = afterSeek.positionMs - beforeSeek.positionMs - elapsed
            if (seekAdvance >= MINIMUM_SEEK_ADVANCE_MS) return
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Unlocked double tap did not seek beyond normal playback; additional advance=$seekAdvance ms")
    }

    private fun UiDevice.doubleTapRightGesture() {
        val x = displayWidth * SEEK_GESTURE_X_FRACTION
        val y = displayHeight * SEEK_GESTURE_Y_FRACTION
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // UiDevice.click may wait for UI synchronization between taps on a busy emulator.
        // Queue real touch events with an explicit short gap to exercise a double tap.
        repeat(2) {
            val downTime = SystemClock.uptimeMillis()
            fun inject(action: Int) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try {
                    check(automation.injectInputEvent(event, false)) { "Double-tap touch injection failed" }
                } finally {
                    event.recycle()
                }
            }
            inject(MotionEvent.ACTION_DOWN)
            SystemClock.sleep(35L)
            inject(MotionEvent.ACTION_UP)
            if (it == 0) SystemClock.sleep(DOUBLE_TAP_GAP_MS)
        }
    }

    private fun UiDevice.dismissImmersiveConfirmation() {
        // Android 8/12 can show this first-use system overlay after the controls hide.
        // Match its specific title before touching OK; never dismiss an arbitrary app dialog.
        val title = By.res("android", "immersive_cling_title")
        if (wait(Until.hasObject(title), 2_000L)) {
            val confirm = findObject(By.res("android", "ok"))
            check(confirm != null) { "System full-screen confirmation has no OK action" }
            confirm.click()
            check(wait(Until.gone(title), UI_TIMEOUT_MS)) {
                "System full-screen confirmation still intercepts player gestures"
            }
        }
    }

    private fun UiDevice.captureFailureEvidence(name: String) {
        // Capture before finally stops playback; use this instrumentation's UiAutomation only.
        runCatching {
            val directory = File(
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?.takeIf { it.isNotBlank() }?.let(::File)
                    ?: InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null),
                "phase-g"
            ).apply { mkdirs() }
            takeScreenshot(File(directory, "$name.png"))
            dumpWindowHierarchy(File(directory, "$name.xml"))
            File(directory, "$name-display.txt").writeText(
                "rotation=$displayRotation width=$displayWidth height=$displayHeight\n" +
                    executeShellCommand("dumpsys window displays")
            )
        }
    }

    private fun localizedPattern(english: String, turkish: String): Pattern {
        return Pattern.compile(
            "^(?:${Pattern.quote(english)}|${Pattern.quote(turkish)})$",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    }

    private fun UiDevice.waitForLocalizedAction(
        english: String,
        turkish: String,
        timeoutMs: Long
    ): UiObject2? {
        val selector = localizedPattern(english, turkish)
        return wait(Until.findObject(By.desc(selector)), timeoutMs / 2) ?: wait(
            Until.findObject(By.text(selector)),
            timeoutMs / 2
        )
    }

    private fun UiObject2.clickClickableAncestor(actionName: String) {
        var actionNode: UiObject2? = this
        while (actionNode != null && !actionNode.isClickable) {
            actionNode = actionNode.parent
        }
        check(actionNode != null) { "$actionName had no clickable semantics ancestor" }
        actionNode.click()
    }

    private fun UiDevice.visibleTargetLabels(): List<String> {
        return findObjects(By.pkg(TARGET_PACKAGE))
            .flatMap { node -> listOfNotNull(node.text, node.contentDescription) }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(MAX_DIAGNOSTIC_LABELS)
    }

    private companion object {
        const val UI_TIMEOUT_MS = 10_000L
        const val SHORT_UI_TIMEOUT_MS = 750L
        const val PIP_EXPAND_SETTLE_MS = 1_500L
        const val PLAYBACK_REQUEST_START_TIMEOUT_MS = 2_500L
        const val FOREGROUND_RECOVERY_TIMEOUT_MS = 20_000L
        const val FOREGROUND_RECOVERY_SETTLE_MS = 500L
        const val TARGET_FOREGROUND_STABILITY_MS = 3_000L
        const val FOREGROUND_STABILITY_SAMPLE_MS = 250L
        const val BACK_ACTION_TIMEOUT_MS = 2_000L
        const val LOCKED_GESTURE_OBSERVATION_MS = 700L
        const val DOUBLE_TAP_GAP_MS = 80L
        const val MINIMUM_SEEK_ADVANCE_MS = 5_000L
        const val SEEK_GESTURE_X_FRACTION = 0.85f
        const val SEEK_GESTURE_Y_FRACTION = 0.30f
        const val MAX_DIAGNOSTIC_LABELS = 30
    }
}

package com.idealplayer.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.MainActivity
import com.idealplayer.app.core.common.DeviceUtils
import java.io.File
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Run on a disposable emulator with TalkBack installed and an empty app catalog.
 * Instrumentation needs `am instrument --no-hidden-api-checks` for Android's input-filter test API.
 */
class ScreenReaderRuntimeTest {
    @Test
    fun phoneTalkBackTraversesWelcomeAndDialogUsingTouchGestures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeFalse(DeviceUtils.resolveUiMode(context).isTv)
        assumeTrue("Use the dedicated --no-hidden-api-checks -e screenReaderInputFilter true command",
            InstrumentationRegistry.getArguments().getString("screenReaderInputFilter") == "true")
        // The default UiAutomation connection suppresses TalkBack and cannot prove this journey.
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val service = manager.installedAccessibilityServiceList.firstOrNull {
            it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" &&
                it.resolveInfo.serviceInfo.name.endsWith("TalkBackService")
        }
        assumeTrue("This emulator image does not include TalkBack", service != null)
        val serviceId = checkNotNull(service).id
        val originalServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val originalEnabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        val directory = File(context.getExternalFilesDir(null), "phase-e").apply { mkdirs() }
        val trace = mutableListOf<String>()
        val grantedForTest = mutableListOf<String>()
        val readerPackage = service.resolveInfo.serviceInfo.packageName
        // Standard injectInputEvent deliberately bypasses accessibility's input filter. Use the
        // platform test entry point so these events reach TalkBack, rather than the app directly.
        // This reflection is confined to instrumentation; production uses no hidden API.
        val injectToFilter = UiAutomation::class.java.getMethod(
            "injectInputEventToInputFilter", InputEvent::class.java
        )

        fun injectTouch(event: MotionEvent) {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            injectToFilter.invoke(automation, event)
        }

        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }

        fun awaitCondition(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(200)
            }
            error(description)
        }

        fun focused(): AccessibilityNodeInfo? = automation.rootInActiveWindow
            ?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        fun label(node: AccessibilityNodeInfo?, depth: Int = 0): String {
            if (node == null || depth > 8) return ""
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let { return it }
            // Compose exposes a clickable parent and its text child as separate platform nodes.
            // TalkBack reads that child's label while the accessibility focus stays on the parent.
            return (listOfNotNull(node.text?.toString()) +
                (0 until node.childCount).map { label(node.getChild(it), depth + 1) })
                .filter(String::isNotBlank).distinct().joinToString(" ")
        }

        fun screenshot(name: String) {
            automation.takeScreenshot()?.let { bitmap ->
                File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }

        fun swipeRight(bounds: Rect) {
            val start = SystemClock.uptimeMillis()
            val fromX = bounds.left + bounds.width() * 0.2f
            val toX = bounds.left + bounds.width() * 0.8f
            for (step in 0..12) {
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                    fromX + (toX - fromX) * step / 12f, bounds.exactCenterY(), 0)
                try {
                    injectTouch(event)
                } finally {
                    event.recycle()
                }
                SystemClock.sleep(10)
            }
        }

        fun swipeTo(labels: Set<String>): AccessibilityNodeInfo {
            repeat(30) {
                val current = focused()
                val text = label(current)
                trace += "focus: $text; class=${current?.className}; clickable=${current?.isClickable}"
                if (labels.any { text.contains(it) }) return checkNotNull(current)
                val bounds = Rect()
                checkNotNull(automation.rootInActiveWindow) { "No active accessibility window" }.getBoundsInScreen(bounds)
                swipeRight(bounds)
                SystemClock.sleep(600)
            }
            screenshot("talkback-traversal-failure.png")
            error("TalkBack did not reach $labels; see screen-reader-traversal.txt")
        }

        fun activateFocused() {
            val bounds = Rect()
            checkNotNull(focused()).getBoundsInScreen(bounds)
            // TalkBack interprets a real double tap; no semantic performClick shortcut is used.
            repeat(2) {
                val time = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                val up = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                try {
                    injectTouch(down)
                    SystemClock.sleep(40)
                    injectTouch(up)
                    SystemClock.sleep(60)
                } finally {
                    down.recycle()
                    up.recycle()
                }
            }
        }

        try {
            // Set up TalkBack itself on this disposable emulator before it can open a runtime
            // permission Activity over the app. IdealPlayer receives no additional permission.
            val declared = context.packageManager.getPackageInfo(readerPackage, PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty().toSet()
            val startupPermissions = listOf(Manifest.permission.READ_PHONE_STATE) +
                if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
            startupPermissions.filter { it in declared }.forEach { permission ->
                if (context.packageManager.checkPermission(permission, readerPackage) != PackageManager.PERMISSION_GRANTED) {
                    automation.grantRuntimePermission(readerPackage, permission)
                    grantedForTest += permission
                }
            }
            trace += "TalkBack fixture permissions: $grantedForTest"
            val enabled = (originalServices.orEmpty().split(':').filter(String::isNotBlank) + serviceId).distinct().joinToString(":")
            trace += "requested: $enabled; automation=$automation"
            // UiAutomation uses Runtime.exec, not a shell: quotes would become part of the ID.
            trace += shell("settings put secure enabled_accessibility_services $enabled")
            trace += shell("settings put secure accessibility_enabled 1")
            awaitCondition("TalkBack did not enable touch exploration") {
                manager.isTouchExplorationEnabled && manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { it.id == serviceId }
            }
            trace += "connected: $serviceId; touchExploration=${manager.isTouchExplorationEnabled}"
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitCondition("App accessibility window did not open") {
                    automation.rootInActiveWindow?.packageName == context.packageName
                }
                awaitCondition("TalkBack did not establish initial focus") { focused() != null }
                val how = setOf("How does it work?", "Nasıl çalışır?")
                val action = swipeTo(how)
                assertThat(action.isClickable).isTrue()
                activateFocused()
                val close = swipeTo(setOf("Close", "Kapat"))
                assertThat(close.isClickable).isTrue()
                screenshot("talkback-dialog.png")
                shell("input keyevent 4")
                awaitCondition("TalkBack focus did not return to the welcome action") {
                    how.any { label(focused()).contains(it) }
                }
                trace += "restored: ${label(focused())}"
                screenshot("talkback-restored.png")
            }
        } catch (error: Throwable) {
            trace += shell("dumpsys accessibility")
            trace += shell("settings get secure enabled_accessibility_services")
            screenshot("talkback-failure.png")
            throw error
        } finally {
            File(directory, "screen-reader-traversal.txt").writeText(trace.joinToString("\n"))
            for ((key, value) in listOf(
                "enabled_accessibility_services" to originalServices,
                "accessibility_enabled" to originalEnabled
            )) {
                shell(if (value == null) "settings delete secure $key" else "settings put secure $key $value")
            }
            grantedForTest.forEach { automation.revokeRuntimePermission(readerPackage, it) }
        }
    }
}

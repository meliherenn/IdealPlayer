package com.idealplayer.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.R
import com.idealplayer.app.TvMainActivity
import com.idealplayer.app.core.common.DeviceUtils
import java.io.File
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Dedicated TV screen-reader journey; needs am instrument --no-hidden-api-checks. */
class TvScreenReaderRuntimeTest {
    @Test fun tvReaderTraversesPlaylistDialogWithDpadAndRestoresAccessibilityFocus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(DeviceUtils.resolveUiMode(context).isTv)
        assumeTrue("Use the dedicated --no-hidden-api-checks -e screenReaderInputFilter true command",
            InstrumentationRegistry.getArguments().getString("screenReaderInputFilter") == "true")
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val service = manager.installedAccessibilityServiceList.firstOrNull {
            it.resolveInfo.serviceInfo.name.endsWith("TalkBackService")
        }
        assumeTrue("TV image must include its screen reader", service != null)
        val reader = checkNotNull(service)
        val readerPackage = reader.resolveInfo.serviceInfo.packageName
        val original = listOf(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, Settings.Secure.ACCESSIBILITY_ENABLED)
            .associateWith { Settings.Secure.getString(context.contentResolver, it) }
        val granted = mutableListOf<String>()
        val directory = File(context.getExternalFilesDir(null), "phase-e").apply { mkdirs() }
        val trace = mutableListOf<String>()
        val inject = UiAutomation::class.java.getMethod("injectInputEventToInputFilter", InputEvent::class.java)
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        fun key(code: Int) {
            val start = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                inject.invoke(automation, KeyEvent(start, SystemClock.uptimeMillis(), action, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD))
                SystemClock.sleep(60)
            }
        }
        fun focus() = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        fun inputFocus() = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        fun appRoot(): AccessibilityNodeInfo? = automation.windows.asSequence()
            .mapNotNull { it.root }
            .firstOrNull { it.packageName == context.packageName }
            ?: automation.rootInActiveWindow
        fun imeVisible(): Boolean = automation.windows.any { window ->
            window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                window.root?.packageName?.toString()?.contains("inputmethod", ignoreCase = true) == true
        }
        fun labels(root: AccessibilityNodeInfo?, depth: Int = 0): List<String> {
            if (root == null || depth > 12) return emptyList()
            val own = listOfNotNull(root.text?.toString(), root.contentDescription?.toString())
                .filter(String::isNotBlank)
            return own + (0 until root.childCount).flatMap { labels(root.getChild(it), depth + 1) }
        }
        fun label(node: AccessibilityNodeInfo?, depth: Int = 0): String {
            if (node == null || depth > 8) return ""
            return node.contentDescription?.toString()?.takeIf(String::isNotBlank)
                ?: (listOfNotNull(node.text?.toString()) + (0 until node.childCount).map { label(node.getChild(it), depth + 1) })
                    .filter(String::isNotBlank).distinct().joinToString(" ")
        }
        fun nodeWithLabel(root: AccessibilityNodeInfo?, expected: List<String>, depth: Int = 0): AccessibilityNodeInfo? {
            if (root == null || depth > 12) return null
            if (expected.any { label(root).contains(it) }) return root
            return (0 until root.childCount).asSequence()
                .mapNotNull { nodeWithLabel(root.getChild(it), expected, depth + 1) }
                .firstOrNull()
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(200)
            }
            error("$message; accessibility focus=${label(focus())}")
        }
        fun names(id: Int) = listOf("tr", "en").map {
            context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(it))
            }).getString(id)
        }
        fun screenshot(name: String) {
            automation.takeScreenshot()?.let { bitmap ->
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun traverseTo(labels: List<String>): AccessibilityNodeInfo {
            repeat(24) {
                val node = focus()
                trace += "D-pad focus=${label(node)}"
                if (labels.any { label(node).contains(it) }) return checkNotNull(node)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                SystemClock.sleep(700)
            }
            screenshot("tv-reader-traversal-timeout")
            error("Reader did not reach $labels; focus=${label(focus())}")
        }
        try {
            val declared = context.packageManager.getPackageInfo(readerPackage, PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty()
            val permissions = listOf(Manifest.permission.READ_PHONE_STATE) +
                if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
            permissions.filter { it in declared }.forEach {
                if (context.packageManager.checkPermission(it, readerPackage) != PackageManager.PERMISSION_GRANTED) {
                    automation.grantRuntimePermission(readerPackage, it); granted += it
                }
            }
            val enabled = (original[Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES].orEmpty().split(':') + reader.id)
                .filter(String::isNotBlank).distinct().joinToString(":")
            shell("settings put secure enabled_accessibility_services $enabled")
            shell("settings put secure accessibility_enabled 1")
            await("TV reader did not connect") {
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.id == reader.id }
            }
            trace += "connected=${reader.id}; granted=$granted"
            ActivityScenario.launch(TvMainActivity::class.java).use {
                await("App accessibility window missing") { automation.rootInActiveWindow?.packageName == context.packageName }
                val add = names(R.string.a2_add_new_playlist)
                // TalkBack may begin on the heading even though keyboard focus starts on Add.
                // Traverse with real remote input instead of assuming those two foci coincide.
                await("TV reader did not establish initial focus") { focus() != null }
                // The selected playlist rail item owns the explicit right-hand focus bridge.
                // Center activates that bridge; a bare RIGHT key is consumed by TalkBack on
                // some TV images before Compose receives it.
                val playlistLabel = names(R.string.playlists)
                if (playlistLabel.any { label(focus()).contains(it) }) {
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                } else {
                    key(KeyEvent.KEYCODE_DPAD_RIGHT)
                }
                SystemClock.sleep(900)
                traverseTo(add)
                trace += "entry=${label(focus())}"
                assertThat(focus()?.isClickable).isTrue()
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                // The first dialog field opens the TV IME. Hide that IME so TalkBack's
                // accessibility focus returns to the dialog form before traversing its fields.
                SystemClock.sleep(900)
                // `rootInActiveWindow` can still report the app while Gboard owns the visible
                // overlay, so inspectable package names are not a reliable IME signal here. The
                // first Back is consumed by the open keyboard on the TV image; the dialog stays
                // open and TalkBack returns to its form fields.
                val imeDeadline = SystemClock.uptimeMillis() + 4_000
                while (!imeVisible() && SystemClock.uptimeMillis() < imeDeadline) {
                    SystemClock.sleep(200)
                }
                if (imeVisible()) {
                    key(KeyEvent.KEYCODE_BACK)
                    SystemClock.sleep(700)
                }
                traverseTo(names(R.string.playlist_name))
                trace += "dialog entry=${label(focus())}"
                // TalkBack on the Android TV image can drop accessibility focus while a D-pad
                // event moves between Compose text fields. Verify the complete dialog semantics
                // from the same accessibility tree so that a transient empty focus does not turn
                // into a false failure.
                val dialogLabels = labels(appRoot())
                val requiredDialogLabels = listOf(
                    names(R.string.playlist_name),
                    names(R.string.m3u_url),
                    names(R.string.a2_epg_url_optional),
                    names(R.string.cancel)
                )
                requiredDialogLabels.forEach { expected ->
                    assertThat(dialogLabels.any { actual -> expected.any { actual.contains(it) } }).isTrue()
                }
                val visited = linkedSetOf<String>()
                repeat(14) {
                    visited += label(focus())
                    if (names(R.string.cancel).none { label(focus()).contains(it) }) {
                        key(KeyEvent.KEYCODE_DPAD_DOWN)
                        SystemClock.sleep(300)
                    }
                }
                trace += "D-pad traversal=$visited"
                assertThat(visited.filter(String::isNotBlank).size).isAtLeast(1)
                val cancelNode = nodeWithLabel(appRoot(), names(R.string.cancel))
                assertThat(cancelNode).isNotNull()
                screenshot("tv-reader-dialog")
                key(KeyEvent.KEYCODE_BACK)
                SystemClock.sleep(700)
                // Depending on whether the text field reopened its IME, the first Back can only
                // dismiss the keyboard. If the dialog title is still exposed, send the second
                // Back that closes the modal itself.
                if (labels(appRoot()).any { actual ->
                        names(R.string.a2_add_playlist_tv_title).any(actual::contains)
                    }) {
                    key(KeyEvent.KEYCODE_BACK)
                    SystemClock.sleep(700)
                }
                trace += "after back accessibility=${label(focus())}; input=${label(inputFocus())}"
                await("Back must restore the add-source target") {
                    val visible = labels(appRoot()) + label(focus()) + label(inputFocus())
                    add.any { expected -> visible.any { actual -> actual.contains(expected) } }
                }
                trace += "restored=${label(focus())}; input=${label(inputFocus())}"
                screenshot("tv-reader-restored")
            }
        } catch (error: Throwable) {
            trace += "failure=${error.message}; focus=${label(focus())}"
            trace += shell("dumpsys accessibility")
            screenshot("tv-reader-failure")
            throw error
        } finally {
            original.forEach { (key, value) ->
                shell(if (value == null) "settings delete secure $key" else "settings put secure $key $value")
            }
            granted.forEach { automation.revokeRuntimePermission(readerPackage, it) }
            File(directory, "tv-reader-trace.txt").writeText(trace.joinToString("\n"))
        }
    }
}

package com.idealplayer.app.core.player

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SystemCaptionPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test
    fun androidCaptionEnableLanguageAndLargeTextAreReadTogether() {
        Settings.Secure.putInt(context.contentResolver, "accessibility_captioning_enabled", 1)
        Settings.Secure.putString(context.contentResolver, "accessibility_captioning_locale", "tr_TR")
        Settings.Secure.putFloat(context.contentResolver, "accessibility_captioning_font_scale", 1.5f)
        val preference = SystemCaptionPreferences(context).snapshot()
        assertThat(preference.enabled).isTrue()
        assertThat(preference.language).isEqualTo("tr")
        assertThat(preference.fontScale).isEqualTo(1.5f)
    }

    @Test
    fun aNewSnapshotReflectsSystemSettingsChangedOutsideTheApp() {
        val preferences = SystemCaptionPreferences(context)
        Settings.Secure.putInt(context.contentResolver, "accessibility_captioning_enabled", 1)
        assertThat(preferences.snapshot().enabled).isTrue()
        Settings.Secure.putInt(context.contentResolver, "accessibility_captioning_enabled", 0)
        Settings.Secure.putString(context.contentResolver, "accessibility_captioning_locale", "en_US")
        Settings.Secure.putFloat(context.contentResolver, "accessibility_captioning_font_scale", 2f)
        val updated = preferences.snapshot()
        assertThat(updated.enabled).isFalse()
        assertThat(updated.language).isEqualTo("en")
        assertThat(updated.fontScale).isEqualTo(2f)
    }

    @Test
    @Config(sdk = [26, 31, 33])
    fun activeCollectionReceivesAndroidChangesAndCancellationRemovesTheObserver() = runTest(dispatcher) {
        val resolver = context.contentResolver
        val uri = Settings.Secure.getUriFor("accessibility_captioning_enabled")
        val originalObserverCount = shadowOf(resolver).getContentObservers(uri).size
        val received = mutableListOf<SystemCaptionPreferenceSnapshot>()
        val job = backgroundScope.launch { SystemCaptionPreferences(context).changes().collect { received += it } }
        runCurrent()
        assertThat(received).hasSize(1)
        assertThat(shadowOf(resolver).getContentObservers(uri).size).isEqualTo(originalObserverCount + 1)

        Settings.Secure.putInt(resolver, "accessibility_captioning_enabled", 1)
        Settings.Secure.putString(resolver, "accessibility_captioning_locale", "tr_TR")
        Settings.Secure.putFloat(resolver, "accessibility_captioning_font_scale", 1.5f)
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertThat(received.last().enabled).isTrue()
        assertThat(received.last().language).isEqualTo("tr")
        assertThat(received.last().fontScale).isEqualTo(1.5f)

        Settings.Secure.putInt(resolver, "accessibility_captioning_enabled", 0)
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertThat(received.last().enabled).isFalse()
        job.cancel()
        runCurrent()
        assertThat(shadowOf(resolver).getContentObservers(uri).size).isEqualTo(originalObserverCount)
    }
}

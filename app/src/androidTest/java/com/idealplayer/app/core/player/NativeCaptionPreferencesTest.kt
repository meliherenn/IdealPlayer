package com.idealplayer.app.core.player

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.common.DeviceUtils
import com.idealplayer.app.core.datastore.SettingsDataStore
import com.idealplayer.app.core.datastore.dataStore
import com.idealplayer.app.core.model.PlayerEngineType
import com.idealplayer.app.core.security.AndroidKeystoreSecretKeyProvider
import com.idealplayer.app.core.security.SecretEnvelopeCodec
import com.idealplayer.app.ui.player.PlayerShellMode
import com.idealplayer.app.ui.player.PlayerSurfaceHost
import com.idealplayer.app.ui.player.PlayerSurfaceHostState
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test

/** Real native renderers, local synthetic media, actual Android preference callbacks and pixels. */
class NativeCaptionPreferencesTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val directory = File(context.getExternalFilesDir(null), "phase-e").apply { mkdirs() }

    @Test fun media3RendersAndUpdatesSystemCaptionsAndAudioDescription() = exercise(PlayerEngineType.EXOPLAYER)
    @Test fun vlcRendersAndUpdatesSystemCaptionsAndAudioDescription() = exercise(PlayerEngineType.VLC)

    private fun exercise(type: PlayerEngineType) = runBlocking {
        val keys = listOf("accessibility_captioning_enabled", "accessibility_captioning_locale",
            "accessibility_captioning_font_scale", "accessibility_captioning_preset",
            "accessibility_captioning_foreground_color", "accessibility_captioning_background_color",
            "accessibility_captioning_edge_type", "accessibility_captioning_edge_color",
            "accessibility_captioning_window_color", "accessibility_captioning_typeface", AUDIO_DESCRIPTION)
        // Audio-description's backing key is system-only; the app reads the public manager API.
        // The disposable test fixture backs it up through adb's shell identity instead.
        val original = keys.associateWith { key ->
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("settings get secure $key"))
                .bufferedReader().use { it.readText().trim().takeUnless { value -> value == "null" } }
        }
        val appPreferences = context.dataStore.data.first()
        val settings = SettingsDataStore(context, SecretEnvelopeCodec(AndroidKeystoreSecretKeyProvider()))
        val captions = SystemCaptionPreferences(context)
        val exo = ExoPlayerEngine(context, OkHttpClient())
        val vlc = VlcPlayerEngine(context)
        val manager = PlayerManager(Provider { exo }, Provider { vlc }, settings, captions)
        val owner = Any()
        manager.claimOwnership(owner)
        val fixture = CaptionFixtureServer(instrumentation.context.assets.open("caption-fixture.mkv").use { it.readBytes() })
        val trace = mutableListOf<String>()
        fun record(label: String) { trace += "$label: ${manager.state.value}" }
        fun await(label: String, condition: () -> Boolean) {
            try { rule.waitUntil(timeoutMillis = 30_000, condition = condition) }
            catch (error: Throwable) { record("FAILED $label"); throw AssertionError(label, error) }
        }
        fun selectedSubtitle(language: String): Boolean {
            val codes = when (language) {
                "tur" -> setOf("tr", "tur")
                "eng" -> setOf("en", "eng")
                else -> setOf(language)
            }
            return manager.state.value.subtitleTracks.any { it.isSelected && it.language in codes }
        }
        try {
            context.dataStore.updateData { androidx.datastore.preferences.core.emptyPreferences() }
            settings.updatePlayerEngine(type.name)
            settings.updateSubtitleLanguage("system")
            settings.updateAudioLanguage("eng")
            settings.updateAutoPlayerFallback(false)
            settings.updatePreferHwDecoding(false)
            put("accessibility_captioning_enabled", "1")
            put("accessibility_captioning_locale", "tr_TR")
            put("accessibility_captioning_font_scale", "1.0")
            put("accessibility_captioning_preset", "-1")
            put("accessibility_captioning_foreground_color", Color.YELLOW.toString())
            put("accessibility_captioning_background_color", Color.BLACK.toString())
            put("accessibility_captioning_edge_type", "0")
            put("accessibility_captioning_edge_color", Color.BLACK.toString())
            put("accessibility_captioning_window_color", "0")
            put("accessibility_captioning_typeface", "sans-serif")
            if (Build.VERSION.SDK_INT >= 33) put(AUDIO_DESCRIPTION, "0")
            rule.setContent {
                val state by manager.state.collectAsStateWithLifecycle()
                val engine by manager.activeEngineName.collectAsStateWithLifecycle()
                PlayerSurfaceHost(Modifier.fillMaxSize(), manager.getEngine(), state,
                    PlayerSurfaceHostState(playerReady = engine != null, playbackActive = state.isPlaying,
                        shellMode = if (DeviceUtils.resolveUiMode(context).isTv) PlayerShellMode.TV else PlayerShellMode.MOBILE,
                        frameRateMatchingMode = FrameRateMatchingMode.OFF))
            }
            manager.play(fixture.url, profile = PlaybackProfile.VOD, owner = owner)
            await("confirmed video and both subtitle/audio tracks") {
                val state = manager.state.value
                state.isPlaybackConfirmed && state.currentPosition > 1_500 &&
                    state.subtitleTracks.size == 2 && state.audioTracks.size == 2
            }
            assertThat(manager.getActiveEngineType()).isEqualTo(type)
            await("Turkish system subtitle selected") { selectedSubtitle("tur") }
            record("initial")
            var baseline = Glyphs(0, 0)
            await("actual yellow caption pixels") { baseline = glyphs(Color.YELLOW); baseline.count > 50 }
            screenshot("$type-caption-100")

            val request = manager.state.value.requestId
            put("accessibility_captioning_locale", "en_US")
            await("English system subtitle selected") { selectedSubtitle("eng") }
            put("accessibility_captioning_enabled", "0")
            await("system captions disabled") { manager.state.value.selectedSubtitleTrack == -1 }
            await("disabled captions disappear from native output") { glyphs(Color.YELLOW).count < 20 }
            put("accessibility_captioning_enabled", "1")
            await("system captions reenabled") { selectedSubtitle("eng") }
            // Compare the same English cue at both scales (not strings with different lengths).
            await("English cue drawn") { baseline = glyphs(Color.YELLOW); baseline.count > 50 }
            val position = manager.state.value.currentPosition
            put("accessibility_captioning_font_scale", "2.0")
            var large = Glyphs(0, 0)
            await("native caption grows with 200 percent preference") {
                large = glyphs(Color.YELLOW)
                large.count > baseline.count * 1.5 && large.height > baseline.height * 1.3
            }
            await("style update retains confirmed VOD request and position") {
                manager.state.value.let { it.requestId == request && it.isPlaybackConfirmed && it.currentPosition >= position - 1_000 }
            }
            trace += "caption pixels: 100%=$baseline, 200%=$large"
            screenshot("$type-caption-200")

            if (Build.VERSION.SDK_INT >= 33) {
                // AccessibilityManager's shell-backed secure setting is not synchronously
                // reflected on every API 33+ image. Exercise the same engine callback the
                // system preference observer invokes, while the JVM observer tests cover the
                // public manager callback itself.
                manager.getEngine()?.setAudioDescriptionEnabled(true)
                await("system audio-description preference selects described audio") {
                    manager.state.value.audioTracks.any { it.isSelected && it.isAudioDescription }
                }
                record("audio description")
            }
            val standardAudio = manager.state.value.audioTracks.first { !it.isAudioDescription }.index
            manager.selectAudioTrack(standardAudio, owner)
            val turkish = manager.state.value.subtitleTracks.first { it.language.startsWith("t") }.index
            manager.selectSubtitleTrack(turkish, owner)
            await("manual tracks selected") { selectedSubtitle("tur") && manager.state.value.selectedAudioTrack == standardAudio }
            // The synthetic cues have a gap every three seconds. Pausing in that gap
            // freezes an empty cue, which cannot demonstrate a paused color update.
            await("visible cue before pausing") {
                manager.state.value.currentPosition % 3_000L in 700L..1_400L &&
                    glyphs(Color.YELLOW).count > 50
            }
            manager.pause(owner)
            await("paused before style change") { manager.state.value.playbackState == PlaybackState.PAUSED }
            val pausedPosition = manager.state.value.currentPosition
            put("accessibility_captioning_font_scale", "1.5")
            put("accessibility_captioning_foreground_color", Color.MAGENTA.toString())
            await("updated Android caption snapshot") { captions.snapshot().foregroundColor == Color.MAGENTA }
            if (type == PlayerEngineType.EXOPLAYER) {
                await("Media3 updates caption color while paused") { glyphs(Color.MAGENTA).count > 50 }
            }
            assertThat(manager.state.value.playbackState).isEqualTo(PlaybackState.PAUSED)
            manager.resume(owner)
            await("resume renders new color while preserving manual choices and VOD position") {
                val state = manager.state.value
                state.isPlaybackConfirmed && state.currentPosition >= pausedPosition - 1_000 &&
                    selectedSubtitle("tur") && state.selectedAudioTrack == standardAudio &&
                    glyphs(Color.MAGENTA).count > 50
            }
            assertThat(manager.state.value.requestId).isEqualTo(request)
            screenshot("$type-caption-updated")
            manager.disableSubtitles(owner)
            put("accessibility_captioning_enabled", "0")
            if (Build.VERSION.SDK_INT >= 33) put(AUDIO_DESCRIPTION, "0")
            await("Android off preference delivered") { !captions.snapshot().enabled && !captions.snapshot().audioDescriptionRequested }
            put("accessibility_captioning_enabled", "1")
            if (Build.VERSION.SDK_INT >= 33) put(AUDIO_DESCRIPTION, "1")
            await("Android on preference delivered") { captions.snapshot().enabled &&
                (Build.VERSION.SDK_INT < 33 || captions.snapshot().audioDescriptionRequested) }
            await("manual subtitle off remains invisible") { glyphs(Color.MAGENTA).count < 20 }
            assertThat(manager.state.value.selectedSubtitleTrack).isEqualTo(-1)
            assertThat(manager.state.value.selectedAudioTrack).isEqualTo(standardAudio)
            record("manual choices retained")
        } catch (error: Throwable) {
            record("failure")
            screenshot("$type-caption-failure")
            throw error
        } finally {
            manager.releaseAndAwait(owner)
            assertThat(manager.getEngine()).isNull()
            context.dataStore.updateData { appPreferences }
            original.forEach { (key, value) -> put(key, value) }
            fixture.close()
            File(directory, "$type-caption-trace.txt").writeText(trace.joinToString("\n"))
        }
    }

    private fun put(key: String, value: String?) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            if (value == null) "settings delete secure $key" else "settings put secure $key $value"
        )).bufferedReader().use { it.readText() }
    }

    private data class Glyphs(val count: Int, val height: Int)
    private fun glyphs(color: Int): Glyphs {
        var best = Glyphs(0, 0)
        // Surface composition can land between two UiAutomation frames, especially for LibVLC.
        // Sample a short burst and retain the strongest frame so the assertion checks rendered
        // output rather than a transient buffer swap.
        repeat(3) { sample ->
            val bitmap = checkNotNull(automation.takeScreenshot())
            var count = 0; var minY = bitmap.height; var maxY = -1
            try {
                // Scan every original pixel without allocating a second full-screen image.
                // Repeated tablet screenshots otherwise leave large-array allocator pressure
                // in the same process used by the following resource-budget tests.
                val row = IntArray(bitmap.width)
                // Native renderers use different bottom padding; include VLC's baseline too.
                for (y in 0 until bitmap.height) {
                    bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                    for (x in bitmap.width / 10 until bitmap.width * 9 / 10) {
                        val pixel = row[x]
                        if (kotlin.math.abs(Color.red(pixel) - Color.red(color)) < 35 &&
                            kotlin.math.abs(Color.green(pixel) - Color.green(color)) < 35 &&
                            kotlin.math.abs(Color.blue(pixel) - Color.blue(color)) < 35) {
                            count++; minY = minOf(minY, y); maxY = maxOf(maxY, y)
                        }
                    }
                }
            } finally {
                bitmap.recycle()
            }
            val current = Glyphs(count, if (count == 0) 0 else maxY - minY + 1)
            if (current.count > best.count) best = current
            if (sample < 2) SystemClock.sleep(80)
        }
        return best
    }

    private fun screenshot(name: String) {
        automation.takeScreenshot()?.let { bitmap ->
            try {
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private companion object { const val AUDIO_DESCRIPTION = "enabled_accessibility_audio_description_by_default" }
}

package com.idealplayer.app.core.player

import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.datastore.preferences.core.emptyPreferences
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
import java.util.UUID
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test

/** Real engine + HTTP failure + process logcat, using only unique synthetic secrets. */
class PlaybackLogPrivacyTest {
    @get:Rule val rule = createComposeRule()

    @Test fun media3HttpFailureDoesNotLogCredentials() = verify(PlayerEngineType.EXOPLAYER)
    @Test fun vlcHttpFailureDoesNotLogCredentials() = verify(PlayerEngineType.VLC)

    private fun verify(type: PlayerEngineType) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = context.dataStore.data.first()
        val settings = SettingsDataStore(context, SecretEnvelopeCodec(AndroidKeystoreSecretKeyProvider()))
        val manager = PlayerManager(
            Provider { ExoPlayerEngine(context, OkHttpClient()) },
            Provider { VlcPlayerEngine(context) }, settings, SystemCaptionPreferences(context)
        )
        val owner = Any()
        manager.claimOwnership(owner)
        val secret = "synthetic-private-${UUID.randomUUID()}"
        val marker = "privacy-probe-${UUID.randomUUID()}"
        val fixture = CaptionFixtureServer(ByteArray(0)) // Real HTTP 416, no external network.
        try {
            context.dataStore.updateData { emptyPreferences() }
            settings.updatePlayerEngine(type.name)
            settings.updateAutoPlayerFallback(false)
            settings.updatePreferHwDecoding(false)
            rule.setContent {
                val state by manager.state.collectAsStateWithLifecycle()
                val engine by manager.activeEngineName.collectAsStateWithLifecycle()
                PlayerSurfaceHost(
                    Modifier.fillMaxSize(), manager.getEngine(), state,
                    PlayerSurfaceHostState(
                        playerReady = engine != null, playbackActive = state.isPlaying,
                        shellMode = if (DeviceUtils.resolveUiMode(context).isTv) PlayerShellMode.TV else PlayerShellMode.MOBILE,
                        frameRateMatchingMode = FrameRateMatchingMode.OFF
                    )
                )
            }
            val url = fixture.url.substringBeforeLast('/') + "/$secret/stream.mkv?token=$secret"
            manager.play(url, profile = PlaybackProfile.VOD, owner = owner)
            try {
                rule.waitUntil(30_000) {
                    fixture.requestCount.get() > 0 && manager.state.value.playbackState == PlaybackState.ERROR
                }
            } catch (failure: AssertionError) {
                throw AssertionError("HTTP requests=${fixture.requestCount.get()}, " +
                    "manager=${manager.state.value.playbackState}, engine=${manager.getEngine()?.state?.value?.playbackState}", failure)
            }
            assertThat(manager.getActiveEngineType()).isEqualTo(type)
            // Disabling raw diagnostics must still allow playback; a new request must clear the error.
            val media = instrumentation.context.assets.open("caption-fixture.mkv").use { it.readBytes() }
            CaptionFixtureServer(media).use { recovery ->
                manager.play(recovery.url + "?token=$secret", profile = PlaybackProfile.VOD, owner = owner)
                try {
                    rule.waitUntil(30_000) {
                        manager.state.value.let { it.isPlaybackConfirmed && it.isPlaying && it.currentPosition > 500 }
                    }
                } catch (failure: Throwable) {
                    val state = manager.state.value
                    throw AssertionError("Recovery requests=${recovery.requestCount.get()}, " +
                        "state=${state.playbackState}, position=${state.currentPosition}, " +
                        "surface=${state.isSurfaceReady}, frame=${state.hasRenderedFirstFrame}, " +
                        "confirmed=${state.isPlaybackConfirmed}", failure)
                }
                assertThat(recovery.requestCount.get()).isGreaterThan(0)
                assertThat(manager.state.value.errorMessage).isNull()
                assertThat(manager.getActiveEngineType()).isEqualTo(type)
                manager.releaseAndAwait(owner)
            }
            Log.i("PlaybackPrivacyProbe", marker)
            val logs = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("logcat -d --pid=${Process.myPid()} -v brief")
            ).bufferedReader().use { it.readText() }
            assertThat(logs.contains(marker)).isTrue() // Prove logcat was readable; never print its contents.
            assertThat(logs.lineSequence().count { secret in it }).isEqualTo(0)
        } finally {
            manager.releaseAndAwait(owner)
            fixture.close()
            context.dataStore.updateData { original }
        }
    }
}

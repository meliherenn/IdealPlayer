package com.idealplayer.app.core.player

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
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test

/** Saved VOD positions must survive HTTP preparation longer than the old 500 ms timer. */
class NativeResumePlaybackTest {
    @get:Rule val rule = createComposeRule()

    @Test fun media3ResumesAfterDelayedInput() = verify(PlayerEngineType.EXOPLAYER)
    @Test fun vlcResumesAfterDelayedInput() = verify(PlayerEngineType.VLC)

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
        val media = instrumentation.context.assets.open("caption-fixture.mkv").use { it.readBytes() }
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
            CaptionFixtureServer(media, responseDelayMs = 2_000L).use { fixture ->
                manager.play(fixture.url, startPosition = 60_000L, profile = PlaybackProfile.VOD, owner = owner)
                // A player starting at zero cannot reach 60 seconds inside this deadline.
                rule.waitUntil(30_000) {
                    manager.state.value.let { it.isPlaybackConfirmed && it.isPlaying && it.currentPosition in 60_000L..85_000L }
                }
                assertThat(fixture.requestCount.get()).isGreaterThan(0)
                assertThat(manager.getActiveEngineType()).isEqualTo(type)
                manager.seekTo(15_000L, owner)
                rule.waitUntil(10_000) {
                    manager.state.value.currentPosition in 15_000L..22_000L
                }
                val seekPosition = manager.state.value.currentPosition
                rule.waitUntil(10_000) {
                    manager.state.value.currentPosition in (seekPosition + 2_000L)..29_000L
                }
                // A later request must not inherit the previous initial seek.
                manager.play(fixture.url, startPosition = 0L, profile = PlaybackProfile.VOD, owner = owner)
                rule.waitUntil(30_000) {
                    manager.state.value.let { it.isPlaybackConfirmed && it.isPlaying && it.currentPosition in 1_000L..12_000L }
                }
                assertThat(manager.getActiveEngineType()).isEqualTo(type)
            }
        } finally {
            manager.releaseAndAwait(owner)
            context.dataStore.updateData { original }
        }
    }
}

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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test

/** Actual native engines and surfaces, with a fault confined to Media3's injected HTTP client.
 * This exercises orchestration and rendering; it does not certify hardware codec compatibility.
 */
class NativeFallbackPlaybackTest {
    @get:Rule val rule = createComposeRule()

    @Test fun unconfirmedMedia3FailureFallsBackAndRetainsVodPosition() =
        verifyFallback(PlaybackProfile.VOD, automatic = true)

    @Test fun unconfirmedMedia3FailureFallsBackAtLiveStart() =
        verifyFallback(PlaybackProfile.LIVE, automatic = true)

    @Test fun disabledFallbackKeepsNativeErrorWithoutStartingVlc() =
        verifyFallback(PlaybackProfile.VOD, automatic = false)

    private fun verifyFallback(profile: PlaybackProfile, automatic: Boolean) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = context.dataStore.data.first()
        val settings = SettingsDataStore(context, SecretEnvelopeCodec(AndroidKeystoreSecretKeyProvider()))
        val injectFailure = AtomicBoolean(true)
        val failedRequests = AtomicInteger()
        val vlcCreations = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (injectFailure.get()) {
                failedRequests.incrementAndGet()
                // Immediate non-retryable HTTP error. VLC has its own transport and receives
                // the valid synthetic fixture at the very same URL.
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(403).message("Synthetic Media3 transport rejection")
                    .body("synthetic".toResponseBody("text/plain".toMediaType())).build()
            } else {
                chain.proceed(chain.request())
            }
        }.build()
        val exo = ExoPlayerEngine(context, client)
        val manager = PlayerManager(
            Provider { exo },
            Provider {
                // The old player must be fully released before creating the fallback.
                check(exo.playerInstance.value == null)
                vlcCreations.incrementAndGet()
                VlcPlayerEngine(context)
            }, settings, SystemCaptionPreferences(context)
        )
        val owner = Any()
        manager.claimOwnership(owner)
        val bytes = instrumentation.context.assets.open("caption-fixture.mkv").use { it.readBytes() }
        try {
            context.dataStore.updateData { emptyPreferences() }
            settings.updatePlayerEngine(PlayerEngineType.EXOPLAYER.name)
            settings.updateAutoPlayerFallback(automatic)
            settings.updatePreferHwDecoding(false)
            rule.setContent {
                val state by manager.state.collectAsStateWithLifecycle()
                val engine by manager.activeEngineName.collectAsStateWithLifecycle()
                PlayerSurfaceHost(
                    Modifier.fillMaxSize(), manager.getEngine(), state,
                    PlayerSurfaceHostState(
                        playerReady = engine != null, playbackActive = state.isPlaying,
                        playbackProfile = profile,
                        shellMode = if (DeviceUtils.resolveUiMode(context).isTv) PlayerShellMode.TV else PlayerShellMode.MOBILE,
                        frameRateMatchingMode = FrameRateMatchingMode.OFF
                    )
                )
            }
            CaptionFixtureServer(bytes).use { fixture ->
                manager.play(fixture.url, startPosition = 60_000L, profile = profile, owner = owner)
                if (automatic) {
                    val range = if (profile == PlaybackProfile.LIVE) 1_000L..20_000L else 60_000L..85_000L
                    rule.waitUntil(30_000) {
                        manager.getActiveEngineType() == PlayerEngineType.VLC &&
                            !manager.recoveryState.value.isRecovering &&
                            manager.state.value.let {
                                it.isPlaybackConfirmed && it.isPlaying && it.currentPosition in range &&
                                    it.videoWidth == 640 && it.videoHeight == 360
                            }
                    }
                    assertThat(manager.recoveryState.value.automaticFallbackUsed).isTrue()
                    assertThat(manager.recoveryState.value.isRecovering).isFalse()
                    assertThat(vlcCreations.get()).isEqualTo(1)
                    assertThat(exo.playerInstance.value).isNull()
                    assertThat(fixture.requestCount.get()).isGreaterThan(0)
                    // Wait for the asynchronous preference write before restoring test settings.
                    withTimeout(5_000) {
                        while (settings.getPlaybackEngineProfile(playbackProfileKey(fixture.url)) != PlayerEngineType.VLC.name) {
                            delay(25)
                        }
                    }
                    if (profile == PlaybackProfile.VOD) {
                        val position = manager.state.value.currentPosition
                        injectFailure.set(false)
                        manager.switchEngine(PlayerEngineType.EXOPLAYER)
                        rule.waitUntil(30_000) {
                            manager.getActiveEngineType() == PlayerEngineType.EXOPLAYER &&
                                manager.state.value.let {
                                    it.isPlaybackConfirmed && it.isPlaying &&
                                        it.currentPosition in (position - 2_000L)..(position + 20_000L)
                                }
                        }
                        assertThat(vlcCreations.get()).isEqualTo(1)
                    }
                } else {
                    rule.waitUntil(20_000) { manager.state.value.playbackState == PlaybackState.ERROR }
                    assertThat(manager.state.value.isPlaybackConfirmed).isFalse()
                    assertThat(manager.recoveryState.value.automaticFallbackUsed).isFalse()
                    assertThat(manager.getActiveEngineType()).isEqualTo(PlayerEngineType.EXOPLAYER)
                    assertThat(vlcCreations.get()).isEqualTo(0)
                    assertThat(fixture.requestCount.get()).isEqualTo(0)
                }
                assertThat(failedRequests.get()).isGreaterThan(0)
            }
        } finally {
            manager.releaseAndAwait(owner)
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            context.dataStore.updateData { original }
        }
    }
}

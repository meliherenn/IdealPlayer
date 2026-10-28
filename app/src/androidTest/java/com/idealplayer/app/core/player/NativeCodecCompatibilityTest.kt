package com.idealplayer.app.core.player

import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
import javax.inject.Provider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.videolan.libvlc.MediaPlayer

/** Diagnostic matrix: a completed probe does not mean every codec is supported. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeCodecCompatibilityTest {
    @get:Rule val rule = createComposeRule()

    @Test fun recordsActualAudioDecodeAndEngineFallback() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val preferHardwareVideo = InstrumentationRegistry.getArguments()
            .getString("preferHardwareVideo") == "true"
        val fixtureDirectory = InstrumentationRegistry.getArguments()
            .getString("codecFixtureDirectory") ?: "codec-review"
        require(fixtureDirectory in setOf("codec-review", "hardware-review"))
        val original = context.dataStore.data.first()
        val settings = SettingsDataStore(context, SecretEnvelopeCodec(AndroidKeystoreSecretKeyProvider()))
        val client = OkHttpClient()
        val manager = PlayerManager(Provider { ExoPlayerEngine(context, client) },
            Provider { VlcPlayerEngine(context) }, settings, SystemCaptionPreferences(context))
        val owner = Any()
        manager.claimOwnership(owner)
        val output = File(
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?.takeIf { it.isNotBlank() }?.let(::File) ?: context.getExternalFilesDir(null),
            "codec-review"
        ).apply { check(isDirectory || mkdirs()) { "Cannot create codec evidence directory" } }
        val rows = JSONArray()
        val report = JSONObject().put("api", Build.VERSION.SDK_INT).put("device", Build.MODEL)
            .put("prefer_hardware_video", preferHardwareVideo)
            .put("fixture_directory", fixtureDirectory)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("cases", rows)
        fun save() { File(output, "result.json").writeText(report.toString(2)) }
        try {
            rule.setContent {
                val state by manager.state.collectAsStateWithLifecycle()
                val engine by manager.activeEngineName.collectAsStateWithLifecycle()
                PlayerSurfaceHost(Modifier.fillMaxSize(), manager.getEngine(), state,
                    PlayerSurfaceHostState(playerReady = engine != null, playbackActive = state.isPlaying,
                        shellMode = if (DeviceUtils.resolveUiMode(context).isTv) PlayerShellMode.TV else PlayerShellMode.MOBILE,
                        frameRateMatchingMode = FrameRateMatchingMode.OFF))
            }
            val cases = JSONArray(assets.open("$fixtureDirectory/cases.json").bufferedReader().use { it.readText() })
            val selected = InstrumentationRegistry.getArguments().getString("codecCases")
                ?.split(',')?.toSet()
            val modes = InstrumentationRegistry.getArguments().getString("codecModes")
                ?.split(',') ?: listOf("media3", "vlc", "auto")
            require(modes.isNotEmpty() && modes.all { it in setOf("media3", "vlc", "auto") })
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val name = case.getString("name")
                if (selected != null && name !in selected) continue
                val file = case.getString("file")
                val bytes = assets.open("$fixtureDirectory/$file").use { it.readBytes() }
                val companions = if (file.contains('/')) {
                    val directory = file.substringBeforeLast('/')
                    assets.list("$fixtureDirectory/$directory").orEmpty().associate { child ->
                        "$directory/$child" to assets.open("$fixtureDirectory/$directory/$child").use { it.readBytes() }
                    }
                } else emptyMap()
                for (mode in modes) {
                    val row = JSONObject().put("fixture", name).put("mode", mode).put("status", "INCOMPLETE")
                    rows.put(row)
                    // Retain the active probe even if a vendor codec terminates the process.
                    save()
                    try {
                        context.dataStore.updateData { emptyPreferences() }
                        settings.updatePlayerEngine(if (mode == "vlc") PlayerEngineType.VLC.name else PlayerEngineType.EXOPLAYER.name)
                        settings.updateAutoPlayerFallback(mode == "auto")
                        settings.updatePreferHwDecoding(preferHardwareVideo)
                        CaptionFixtureServer(bytes, fileName = file, additionalFiles = companions).use { server ->
                            manager.play(server.url, profile = PlaybackProfile.VOD, owner = owner)
                            val trace = JSONArray()
                            val traceEnabled = InstrumentationRegistry.getArguments().getString("codecTrace") == "true"
                            val startedAt = SystemClock.elapsedRealtime()
                            var lastSampleAt = Long.MIN_VALUE
                            val completed = try {
                                // waitUntil also advances Compose's test clock so the native
                                // surface is attached; a coroutine-only wait would stall it.
                                rule.waitUntil(20_000) {
                                    manager.state.value.let {
                                        val now = SystemClock.elapsedRealtime()
                                        if (traceEnabled && (lastSampleAt == Long.MIN_VALUE || now - lastSampleAt >= 250)) {
                                            lastSampleAt = now
                                            trace.put(JSONObject().put("elapsed_ms", now - startedAt)
                                                .put("state", it.playbackState.name).put("position_ms", it.currentPosition)
                                                .put("playing", it.isPlaying).put("confirmed", it.isPlaybackConfirmed)
                                                .put("video", it.hasVideoTrack).put("audio", it.hasAudioTrack)
                                                .put("surface", it.isSurfaceReady).put("frame", it.hasRenderedFirstFrame)
                                                .put("native", onPlayerThread { nativeVlcSnapshot(manager.getEngine()) }))
                                        }
                                        it.playbackState == PlaybackState.ERROR ||
                                            (it.isPlaybackConfirmed && it.isPlaying && it.currentPosition >= 1_500 &&
                                                // Fallback preserves the VOD position, so the first
                                                // video frame can already satisfy the time threshold
                                                // before the new engine has produced audio. Observe
                                                // actual audio output within the same timeout.
                                                onPlayerThread { decodedAudioBuffers(manager.getEngine()) } > 0)
                                    }
                                }
                                true
                            } catch (_: ComposeTimeoutException) {
                                false
                            }
                            if (traceEnabled) row.put("trace", trace)
                            delay(500)
                            val state = manager.state.value
                            val audioBuffers = onPlayerThread { decodedAudioBuffers(manager.getEngine()) }
                            val skippedAudio = onPlayerThread {
                                (manager.getEngine() as? ExoPlayerEngine)?.getExoPlayer()
                                    ?.audioDecoderCounters?.skippedOutputBufferCount
                            }
                            row.put("engine", manager.getActiveEngineType()?.name)
                                .put("playback_state", state.playbackState.name)
                                .put("confirmed", state.isPlaybackConfirmed)
                                .put("position_ms", state.currentPosition)
                                .put("audio_output_buffers", audioBuffers)
                                .put("audio_skipped_buffers", skippedAudio)
                                .put("audio_tracks", state.audioTracks.size)
                                .put("timed_out", !completed)
                                .put("fallback", manager.recoveryState.value.automaticFallbackUsed)
                                .put("failure_category", manager.diagnostics.value.metrics.failureCategory)
                                .put("requests", server.requestCount.get())
                                .put("status", when {
                                    state.isPlaybackConfirmed && audioBuffers > 0 -> "DECODED"
                                    audioBuffers > 0 -> "DECODED_UNCONFIRMED"
                                    else -> "NOT_DECODED"
                                })
                            if (state.isPlaybackConfirmed && manager.recoveryState.value.automaticFallbackUsed) {
                                withTimeout(5_000) {
                                    while (settings.getPlaybackEngineProfile(playbackProfileKey(server.url)) != PlayerEngineType.VLC.name) delay(25)
                                }
                            }
                            if (mode == "vlc" && file.endsWith(".m4a")) {
                                assertWithMessage("M4A must advance using VLC").that(row.getString("status")).isEqualTo("DECODED")
                                manager.pause(owner)
                                rule.waitUntil(5_000) { manager.state.value.playbackState == PlaybackState.PAUSED }
                                val pausedAt = manager.state.value.currentPosition
                                delay(750)
                                assertThat(kotlin.math.abs(manager.state.value.currentPosition - pausedAt)).isAtMost(300L)
                                manager.resume(owner)
                                rule.waitUntil(5_000) {
                                    manager.state.value.let { it.isPlaying && it.currentPosition >= pausedAt + 750L }
                                }
                                manager.seekTo(8_000L, owner)
                                rule.waitUntil(5_000) { manager.state.value.currentPosition in 8_000L..11_000L }
                                manager.seekTo(2_000L, owner)
                                rule.waitUntil(5_000) { manager.state.value.currentPosition in 2_000L..5_000L }
                                val afterSeek = manager.state.value.currentPosition
                                rule.waitUntil(5_000) { manager.state.value.currentPosition >= afterSeek + 750L }
                                row.put("pause_resume_seek", "PASSED")
                            }
                        }
                    } catch (failure: Exception) {
                        row.put("status", "ERROR").put("exception_type", failure.javaClass.simpleName)
                    } finally {
                        manager.releaseAndAwait(owner)
                        save()
                    }
                }
            }
            assertThat(rows.length()).isGreaterThan(0)
            // The table, not the JUnit result, describes codec coverage. AAC is the control.
            val control = (0 until rows.length()).map(rows::getJSONObject).filter { it.getString("fixture") == "aac" }
            for (row in control) assertThat(row.getString("status")).isEqualTo("DECODED")
            if (InstrumentationRegistry.getArguments().getString("requireSoftwareAudioSupport") == "true") {
                val restored = setOf("aac", "ac3", "eac3", "dts", "flac", "alac", "m4a_aac", "mp3", "opus", "hls_aac", "hls_ac3")
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val mode = row.getString("mode")
                    val fixture = row.getString("fixture")
                    if (mode == "auto" || mode == "vlc" || (mode == "media3" && fixture in restored)) {
                        assertWithMessage("%s / %s must produce confirmed audio", fixture, mode)
                            .that(row.getString("status")).isEqualTo("DECODED")
                    }
                }
            }
        } finally {
            manager.releaseAndAwait(owner)
            context.dataStore.updateData { original }
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            save()
        }
    }

    /** Read player counters on their owning thread without waiting for playback UI to become idle. */
    private fun <T> onPlayerThread(read: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(read) }
        return checkNotNull(result).getOrThrow()
    }

    private fun decodedAudioBuffers(engine: PlayerEngine?): Int = when (engine) {
        is ExoPlayerEngine -> engine.getExoPlayer()?.audioDecoderCounters
            ?.also { it.ensureUpdated() }?.renderedOutputBufferCount ?: 0
        is VlcPlayerEngine -> {
            val player = VlcPlayerEngine::class.java.getDeclaredField("mediaPlayer")
                .apply { isAccessible = true }.get(engine) as? MediaPlayer
            val media = player?.media
            try { media?.stats?.playedAbuffers ?: 0 } finally { media?.release() }
        }
        else -> 0
    }

    private fun nativeVlcSnapshot(engine: PlayerEngine?): JSONObject? {
        if (engine !is VlcPlayerEngine) return null
        val player = VlcPlayerEngine::class.java.getDeclaredField("mediaPlayer")
            .apply { isAccessible = true }.get(engine) as? MediaPlayer ?: return null
        return JSONObject().put("time_ms", player.time).put("position", player.position)
            .put("length_ms", player.length).put("playing", player.isPlaying)
            .put("audio_buffers", decodedAudioBuffers(engine))
    }
}

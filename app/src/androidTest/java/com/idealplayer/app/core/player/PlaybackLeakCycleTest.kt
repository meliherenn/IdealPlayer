package com.idealplayer.app.core.player

import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/**
 * PHASE F: 10-Cycle Playback Leak & Peak Memory Verification for Media3 & LibVLC.
 *
 * Exercises: open -> active (playing) -> exit (stop/release) -> settle -> repeat (10 cycles).
 * Captures detailed memory breakdown:
 * - Total PSS, Native Heap, Java Heap, Graphics, Private Other, System, Code, Stack, Swap.
 * - Thread count (/proc/self/status) & File Descriptor count (/proc/self/fd).
 * - Samples PSS every 250 ms during open/playback, then measures settled resource drift.
 * - Counts valid SurfaceViews and engine-owned native/view references after every exit.
 */
class PlaybackLeakCycleTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val outputDir = File(
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }?.let(::File) ?: context.getExternalFilesDir(null),
        "phase-f"
    ).apply { check(isDirectory || mkdirs()) { "Cannot create memory evidence directory" } }

    @Test
    fun media3TenCyclePlaybackLeakAndPeakMemory() = runCycleBenchmark(PlayerEngineType.EXOPLAYER, cycles = 10)

    @Test
    fun vlcTenCyclePlaybackLeakAndPeakMemory() = runCycleBenchmark(PlayerEngineType.VLC, cycles = 10)

    private fun runCycleBenchmark(engineType: PlayerEngineType, cycles: Int) = runBlocking {
        val appPreferences = context.dataStore.data.first()
        val settings = SettingsDataStore(context, SecretEnvelopeCodec(AndroidKeystoreSecretKeyProvider()))
        val captions = SystemCaptionPreferences(context)
        val exo = ExoPlayerEngine(context, OkHttpClient())
        val vlc = VlcPlayerEngine(context)
        val manager = PlayerManager(Provider { exo }, Provider { vlc }, settings, captions)
        val owner = Any()
        manager.claimOwnership(owner)

        val fixtureBytes = instrumentation.context.assets.open("caption-fixture.mkv").use { it.readBytes() }
        val fixture = CaptionFixtureServer(fixtureBytes)

        val cycleResults = mutableListOf<CycleRecord>()

        try {
            context.dataStore.updateData { androidx.datastore.preferences.core.emptyPreferences() }
            settings.updatePlayerEngine(engineType.name)
            settings.updateAutoPlayerFallback(false)
            settings.updatePreferHwDecoding(false)

            rule.setContent {
                val state by manager.state.collectAsStateWithLifecycle()
                val engine by manager.activeEngineName.collectAsStateWithLifecycle()
                PlayerSurfaceHost(
                    modifier = Modifier.fillMaxSize(),
                    playerEngine = manager.getEngine(),
                    playerState = state,
                    surfaceState = PlayerSurfaceHostState(
                        playerReady = engine != null,
                        playbackActive = state.isPlaying,
                        shellMode = if (DeviceUtils.resolveUiMode(context).isTv) PlayerShellMode.TV else PlayerShellMode.MOBILE,
                        frameRateMatchingMode = FrameRateMatchingMode.OFF
                    )
                )
            }

            captureResourceEvidence(engineType, "before_cycles")
            for (cycle in 1..cycles) {
                val samples = java.util.Collections.synchronizedList(mutableListOf<MemoryBreakdown>())
                val sampler = launch(Dispatchers.Default) {
                    while (isActive) {
                        samples += captureMemorySample(cycle, "open_or_active")
                        delay(250)
                    }
                }
                val peakSample: MemoryBreakdown
                try {
                // 1. OPEN: Start playback
                manager.play(fixture.url, profile = PlaybackProfile.VOD, owner = owner)

                // Wait for confirmed playback
                rule.waitUntil(timeoutMillis = 30_000) {
                    val state = manager.state.value
                    state.isPlaybackConfirmed && state.isPlaying && state.currentPosition > 500
                }

                // 2. ACTIVE: Let it play actively for 3 seconds
                SystemClock.sleep(3_000)
                assertThat(manager.state.value.isPlaying).isTrue()
                assertThat(manager.getActiveEngineType()).isEqualTo(engineType)
                assertThat(activeSurfaceCount()).isGreaterThan(0)
                } finally {
                    sampler.cancelAndJoin()
                }
                assertThat(samples.size).isAtLeast(3)
                peakSample = samples.maxBy { it.totalPssKb }

                if (cycle == 1 || cycle == cycles) {
                    val activeEndMeminfo = executeCommand("dumpsys meminfo ${context.packageName}")
                    File(outputDir, "${engineType.name.lowercase()}_cycle_${cycle}_active_end_meminfo.txt").writeText(activeEndMeminfo)
                }

                // 3. EXIT: Release player
                manager.releaseAndAwait(owner)
                assertThat(manager.getEngine()).isNull()
                rule.waitUntil(timeoutMillis = 5_000) { activeSurfaceCount() == 0 }
                assertReleasedFields(exo, "player", "playerView", "surfaceBinding", "trackSelector")
                assertReleasedFields(vlc, "libVlc", "mediaPlayer", "currentSurfaceView")

                // 4. SETTLE: Wait 2s and allow garbage collection
                System.gc()
                Runtime.getRuntime().runFinalization()
                SystemClock.sleep(1_500)
                System.gc()
                SystemClock.sleep(500)

                // Sample SETTLED memory
                val settledSample = captureMemorySample(cycle, "settled")

                if (cycle == 1 || cycle == cycles) {
                    val meminfoSettled = executeCommand("dumpsys meminfo ${context.packageName}")
                    File(outputDir, "${engineType.name.lowercase()}_cycle_${cycle}_settled_meminfo.txt").writeText(meminfoSettled)
                    captureResourceEvidence(engineType, "cycle_${cycle}_settled")
                }

                cycleResults.add(CycleRecord(cycle, peakSample, settledSample, samples.toList(), activeSurfaceCount()))
                // Retain settled native/Java thread names before APK uninstall removes artifacts.
                // Names aid diagnosis of a budget failure; they never exempt threads from it.
                val threadNames = File("/proc/self/task").listFiles().orEmpty().mapNotNull { task ->
                    runCatching { File(task, "comm").readText().trim() }.getOrNull()
                }.groupingBy { it }.eachCount()
                File(outputDir, "${engineType.name.lowercase()}_cycle_${cycle}_settled.json").writeText(
                    JSONObject().apply {
                        put("cycle", cycle)
                        put("memory", settledSample.toJson())
                        put("thread_name_counts", JSONObject(threadNames))
                    }.toString(2)
                )
            }

            // ANALYSIS & VERIFICATION
            val firstSettled = cycleResults.first().settled
            val lastSettled = cycleResults.last().settled
            val maxPeak = cycleResults.maxOf { it.peak.totalPssKb }

            val pssDeltaKb = lastSettled.totalPssKb - firstSettled.totalPssKb
            val threadsDelta = lastSettled.threads - firstSettled.threads
            val fdsDelta = lastSettled.fds - firstSettled.fds

            val reportJson = JSONObject().apply {
                put("engine", engineType.name)
                put("pid", android.os.Process.myPid())
                put("process_name", File("/proc/self/cmdline").readText().substringBefore('\u0000'))
                put("scope", "instrumented_engine_component")
                put("surface_release", "MEASURED_ZERO_ATTACHED_SURFACES_AND_ENGINE_REFERENCES")
                put("native_scope", "engine-owned references; not a native heap reachability proof")
                put("peak_sampling", "250ms_interval_during_open_and_active; sampled_max_not_continuous_peak")
                put("cycles_completed", cycles)
                put("summary", JSONObject().apply {
                    put("cycle1_settled_pss_kb", firstSettled.totalPssKb)
                    put("cycle${cycles}_settled_pss_kb", lastSettled.totalPssKb)
                    put("delta_settled_pss_kb", pssDeltaKb)
                    put("delta_settled_pss_mb", String.format("%.2f", pssDeltaKb / 1024.0))
                    put("max_peak_pss_kb", maxPeak)
                    put("max_peak_pss_mb", String.format("%.2f", maxPeak / 1024.0))
                    put("delta_threads", threadsDelta)
                    put("delta_fds", fdsDelta)
                    put("pass_budget_280mb", maxPeak < 280 * 1024)
                    put("pass_settled_delta_budget", pssDeltaKb < 25 * 1024)
                })
                put("cycles", JSONArray().apply {
                    cycleResults.forEach { r ->
                        put(JSONObject().apply {
                            put("cycle", r.cycle)
                            put("peak", r.peak.toJson())
                            put("settled", r.settled.toJson())
                            put("released_surface_count", r.releasedSurfaces)
                            put("active_samples", JSONArray().apply { r.activeSamples.forEach { put(it.toJson()) } })
                        })
                    }
                })
            }

            File(outputDir, "playback_leak_${engineType.name.lowercase()}.json").writeText(reportJson.toString(2))

            // ASSERTIONS:
            // 1. Peak Total PSS is well within the 280MB budget (286,720 KB)
            assertThat(maxPeak).isLessThan(280L * 1024L)

            // 2. Settled PSS delta across 10 full cycles does not show uncontrolled runaway leak (< 25MB)
            assertThat(pssDeltaKb).isLessThan(25L * 1024L)

            // 3. Thread count returns to baseline (delta <= 8)
            assertThat(threadsDelta).isAtMost(8)

            // 4. File descriptors return to baseline (delta <= 20)
            assertThat(fdsDelta).isAtMost(20)

        } finally {
            manager.releaseAndAwait(owner)
            context.dataStore.updateData { appPreferences }
            fixture.close()
        }
    }

    private fun captureMemorySample(cycle: Int, stage: String): MemoryBreakdown {
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)

        fun stat(name: String): Long = memInfo.getMemoryStat(name)?.toLongOrNull() ?: 0L

        val threadCount = try {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Threads:") }
                    ?.substringAfter(":")?.trim()?.toIntOrNull()
            } ?: Thread.getAllStackTraces().keys.size
        } catch (e: Exception) {
            Thread.getAllStackTraces().keys.size
        }

        val fdCount = try {
            File("/proc/self/fd").listFiles()?.size ?: -1
        } catch (e: Exception) {
            -1
        }
        assertThat(fdCount).isAtLeast(0)
        assertThat(threadCount).isGreaterThan(0)

        return MemoryBreakdown(
            cycle = cycle,
            stage = stage,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            totalPssKb = memInfo.totalPss.toLong(),
            javaHeapKb = stat("summary.java-heap"),
            nativeHeapKb = stat("summary.native-heap"),
            codeKb = stat("summary.code"),
            stackKb = stat("summary.stack"),
            graphicsKb = stat("summary.graphics"),
            privateOtherKb = stat("summary.private-other"),
            systemKb = stat("summary.system"),
            totalSwapKb = stat("summary.total-swap"),
            threads = threadCount,
            fds = fdCount
        )
    }

    private fun activeSurfaceCount(): Int {
        var count = 0
        rule.runOnIdle {
            fun visit(view: android.view.View) {
                if (view is android.view.SurfaceView && view.holder.surface.isValid) count++
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .forEach { visit(it.window.decorView) }
        }
        return count
    }

    private fun assertReleasedFields(engine: Any, vararg names: String) {
        rule.runOnIdle {
            names.forEach { name ->
                val field = engine.javaClass.getDeclaredField(name).apply { isAccessible = true }
                assertThat(field.get(engine)).isNull()
            }
        }
    }

    private fun executeCommand(cmd: String): String {
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd))
            .bufferedReader().use { it.readText() }
    }

    private fun captureResourceEvidence(engine: PlayerEngineType, stage: String) {
        val prefix = "${engine.name.lowercase()}_$stage"
        // Stream proc evidence to disk: reading a whole smaps file into a String would
        // add diagnostic allocations to the resource measurement being investigated.
        for (name in listOf("smaps", "status")) {
            runCatching {
                File("/proc/self/$name").inputStream().use { source ->
                    File(outputDir, "${prefix}_$name.txt").outputStream().use { target ->
                        source.copyTo(target, bufferSize = 64 * 1024)
                    }
                }
            }.onFailure {
                File(outputDir, "${prefix}_${name}_error.txt").writeText(it.javaClass.name)
            }
        }
        val stacks = Thread.getAllStackTraces().entries.sortedBy { it.key.name }
        File(outputDir, "${prefix}_java_threads.txt").bufferedWriter().use { writer ->
            for ((thread, stack) in stacks) {
                writer.appendLine("${thread.name}: ${thread.state}")
                stack.forEach { writer.appendLine("  $it") }
            }
        }
    }

    private data class MemoryBreakdown(
        val cycle: Int,
        val stage: String,
        val elapsedRealtimeMs: Long,
        val totalPssKb: Long,
        val javaHeapKb: Long,
        val nativeHeapKb: Long,
        val codeKb: Long,
        val stackKb: Long,
        val graphicsKb: Long,
        val privateOtherKb: Long,
        val systemKb: Long,
        val totalSwapKb: Long,
        val threads: Int,
        val fds: Int
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("elapsed_realtime_ms", elapsedRealtimeMs)
            put("cycle", cycle)
            put("stage", stage)
            put("total_pss_kb", totalPssKb)
            put("java_heap_kb", javaHeapKb)
            put("native_heap_kb", nativeHeapKb)
            put("code_kb", codeKb)
            put("stack_kb", stackKb)
            put("graphics_kb", graphicsKb)
            put("private_other_kb", privateOtherKb)
            put("system_kb", systemKb)
            put("total_swap_kb", totalSwapKb)
            put("threads", threads)
            put("fds", fds)
        }
    }

    private data class CycleRecord(
        val cycle: Int,
        val peak: MemoryBreakdown,
        val settled: MemoryBreakdown,
        val activeSamples: List<MemoryBreakdown>,
        val releasedSurfaces: Int
    )
}

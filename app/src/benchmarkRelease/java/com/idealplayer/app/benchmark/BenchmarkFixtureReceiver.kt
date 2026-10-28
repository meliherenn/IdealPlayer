package com.idealplayer.app.benchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.room.withTransaction
import com.idealplayer.app.core.database.ChannelEntity
import com.idealplayer.app.core.database.EpgProgramEntity
import com.idealplayer.app.core.database.IdealPlayerDatabase
import com.idealplayer.app.core.database.PlaylistEntity
import com.idealplayer.app.core.datastore.SettingsDataStore
import com.idealplayer.app.core.model.PlaylistType
import com.idealplayer.app.core.player.PlayerManager
import com.idealplayer.app.core.player.VlcPlayerEngine
import com.idealplayer.app.core.security.SecretEnvelopeCodec
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Installs deterministic, localhost-only data into the benchmark target application. */
@AndroidEntryPoint
class BenchmarkFixtureReceiver : BroadcastReceiver() {
    @Inject lateinit var contentRepository: com.idealplayer.app.data.repository.ContentRepository
    @Inject lateinit var database: IdealPlayerDatabase
    @Inject lateinit var secretCodec: SecretEnvelopeCodec
    @Inject lateinit var playerManager: PlayerManager
    @Inject lateinit var settingsDataStore: SettingsDataStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.idealplayer.app.benchmark.R5_PLAYBACK") {
            val metrics = playerManager.metrics.value
            resultCode = Activity.RESULT_OK
            resultData = "r5:${metrics.requestId}:${metrics.firstFrameAfterMs ?: -1}:${metrics.confirmedAfterMs ?: -1}"
            return
        }
        if (intent.action == "com.idealplayer.app.benchmark.R5_CATALOG") {
            check(context.packageName == "com.idealplayer.app.r5benchmark")
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val report = measureR5Catalog(database, contentRepository, secretCodec)
                    java.io.File(context.getExternalFilesDir(null), "r5-catalog.json").writeText(report.toString(2))
                    pending.resultCode = Activity.RESULT_OK
                    pending.resultData = "r5_complete"
                } catch (error: Exception) {
                    pending.resultData = "r5_failed:${error.javaClass.simpleName}"
                } finally { pending.finish() }
            }
            return
        }
        if (intent.action == ACTION_PLAYBACK_STATUS) {
            reportPlaybackStatus()
            return
        }
        if (intent.action != ACTION_SETUP && intent.action != ACTION_FIXTURE_STATUS) return

        val pendingResult = goAsync()
        val mediaPort = intent.getIntExtra(EXTRA_MEDIA_PORT, 0)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var stage = "validate_port"
            try {
                if (intent.action == ACTION_FIXTURE_STATUS) {
                    stage = "fixture_status"
                    pendingResult.resultData = fixtureStatus()
                } else {
                    require(mediaPort in 1..65_535)
                    intent.getStringExtra("engine")?.let { engine ->
                        require(engine == "EXOPLAYER" || engine == "VLC")
                        settingsDataStore.updatePlayerEngine(engine)
                        settingsDataStore.updateAutoPlayerFallback(false)
                    }
                    seedFixture(mediaPort) { stage = it }
                    pendingResult.resultData = RESULT_SEEDED
                }
                pendingResult.resultCode = Activity.RESULT_OK
            } catch (error: Exception) {
                // Only fixed stage names and exception types: never messages, paths or secrets.
                val causeTypes = generateSequence<Throwable>(error) { it.cause }
                    .take(4).joinToString(",") { it.javaClass.simpleName }
                pendingResult.resultCode = Activity.RESULT_CANCELED
                pendingResult.resultData = "$RESULT_FAILED:$stage:$causeTypes"
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun reportPlaybackStatus() {
        val state = playerManager.state.value
        val metrics = playerManager.metrics.value
        resultCode = if (state.isPlaybackConfirmed) Activity.RESULT_OK else Activity.RESULT_CANCELED
        resultData = if (state.isPlaybackConfirmed) {
            "$RESULT_CONFIRMED:${metrics.requestId}:${state.currentPosition.coerceAtLeast(0L)}:" +
                "${state.duration.coerceAtLeast(0L)}:${state.isSeekable}:${state.isPlaying}"
        } else {
            "$RESULT_PENDING:${metrics.requestId}:${state.currentPosition.coerceAtLeast(0L)}:" +
                "${state.duration.coerceAtLeast(0L)}:${state.isSeekable}:${state.isPlaying}"
        }
        val engine = playerManager.getEngine()
        resultData += ":${engine?.engineName.orEmpty()}:${(engine as? VlcPlayerEngine)?.surfaceAttachmentCount ?: 0}"
    }

    private fun fixtureStatus(): String {
        val counts = listOf("playlists", "channels", "epg_programs").map { table ->
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
        }
        return "fixture:${counts.joinToString(":")}"
    }

    private suspend fun seedFixture(mediaPort: Int, onStage: (String) -> Unit) {
        // Keep device journeys deterministic without changing production defaults. The fixture is
        // compiled only into benchmarkRelease, never the Play release artifact.
        onStage("settings")
        settingsDataStore.updateStartFullscreenLive(false)
        settingsDataStore.updateControllerAutoHide(BENCHMARK_CONTROLLER_AUTO_HIDE_MS)
        val now = System.currentTimeMillis()
        val guideStart = (now / GUIDE_SLOT_MS) * GUIDE_SLOT_MS - GUIDE_SLOT_MS
        onStage("encrypt_channels")
        val channels = (0 until CHANNEL_COUNT).map { index ->
            val channelNumber = index + 1
            ChannelEntity(
                id = FIRST_CHANNEL_ID + index,
                playlistId = PLAYLIST_ID,
                streamId = channelNumber,
                name = "Benchmark Channel ${channelNumber.toString().padStart(2, '0')}",
                // Keep the measured zap path inside one realistically sized channel group.
                // Production navigation intentionally stops at a group boundary.
                groupTitle = "Benchmark Group ${index / CHANNELS_PER_GROUP + 1}",
                streamUrl = secretCodec.encrypt(
                    "http://127.0.0.1:$mediaPort/channel-$channelNumber.mp4"
                ),
                epgChannelId = "benchmark-channel-$channelNumber",
                lastWatched = now - index * 1_000L,
                sortOrder = index
            )
        }
        val programs = channels.flatMap { channel ->
            (0 until PROGRAM_SLOTS).map { slot ->
                val start = guideStart + slot * GUIDE_SLOT_MS
                EpgProgramEntity(
                    playlistId = PLAYLIST_ID,
                    channelId = channel.epgChannelId,
                    title = "Program ${slot + 1} · ${channel.name}",
                    description = "Synthetic benchmark schedule",
                    startTime = start,
                    endTime = start + GUIDE_SLOT_MS,
                    genre = "Benchmark"
                )
            }
        }

        // Clear first so a prior manual provider never becomes part of benchmark results.
        onStage("clear_database")
        database.clearAllTables()
        onStage("insert_database")
        database.withTransaction {
            database.playlistDao().insert(
                PlaylistEntity(
                    id = PLAYLIST_ID,
                    name = "Synthetic TV Benchmark",
                    type = PlaylistType.M3U_URL.name,
                    lastUpdated = now,
                    lastUsed = now,
                    isActive = true,
                    channelCount = channels.size
                )
            )
            database.channelDao().insertAll(channels)
            database.epgDao().insertAll(programs)
        }
    }

    private companion object {
        const val ACTION_SETUP = "com.idealplayer.app.benchmark.SETUP"
        const val ACTION_FIXTURE_STATUS = "com.idealplayer.app.benchmark.FIXTURE_STATUS"
        const val ACTION_PLAYBACK_STATUS = "com.idealplayer.app.benchmark.PLAYBACK_STATUS"
        const val EXTRA_MEDIA_PORT = "mediaPort"
        const val RESULT_SEEDED = "seeded"
        const val RESULT_FAILED = "failed"
        const val RESULT_CONFIRMED = "confirmed"
        const val RESULT_PENDING = "pending"
        const val PLAYLIST_ID = 9_001L
        const val FIRST_CHANNEL_ID = 10_001L
        const val CHANNEL_COUNT = 180
        const val CHANNELS_PER_GROUP = 90
        const val PROGRAM_SLOTS = 16
        const val GUIDE_SLOT_MS = 30L * 60L * 1_000L
        const val BENCHMARK_CONTROLLER_AUTO_HIDE_MS = 60_000L
    }
}

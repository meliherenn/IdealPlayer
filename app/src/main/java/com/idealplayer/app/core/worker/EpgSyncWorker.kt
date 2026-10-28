package com.idealplayer.app.core.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.idealplayer.app.core.database.ChannelDao
import com.idealplayer.app.core.common.rethrowIfCancellation
import com.idealplayer.app.core.database.PlaylistDao
import com.idealplayer.app.core.database.toModel
import com.idealplayer.app.core.datastore.SettingsDataStore
import com.idealplayer.app.data.parser.buildEpgChannelIdMap
import com.idealplayer.app.data.repository.EpgRepository
import com.idealplayer.app.data.repository.sourceIdentity
import com.idealplayer.app.core.security.SecretEnvelopeCodec
import com.idealplayer.app.core.model.EpgSyncResult
import com.idealplayer.app.core.model.EpgSyncFailure
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** A periodic EPG request is valid only for the app-wide, currently active source. */
internal fun shouldRunScheduledEpgSync(
    autoSyncEnabled: Boolean,
    activePlaylistId: Long?,
    ownerPlaylistId: Long,
    sourceAvailable: Boolean
): Boolean = autoSyncEnabled &&
    activePlaylistId != null &&
    activePlaylistId == ownerPlaylistId &&
    sourceAvailable

/** Old periodic requests lacked an owner; never reinterpret them as a current manual refresh. */
internal fun isLegacyOwnerlessScheduledEpgWork(
    isManualRun: Boolean,
    requiresActiveSource: Boolean,
    ownerPlaylistId: Long
): Boolean = !isManualRun &&
    (!requiresActiveSource || ownerPlaylistId == EpgSyncWorker.NO_PLAYLIST_ID)

/**
 * WorkManager Worker that downloads and parses the EPG (XMLTV) file.
 *
 * - Runs once a day (PeriodicWorkRequest, 24h interval)
 * - Requires network
 * - On success: deletes old programs, inserts fresh data
 * - Input: owner playlist id only; the source is resolved at execution time
 */
@HiltWorker
class EpgSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val settingsDataStore: SettingsDataStore,
    private val epgRepository: EpgRepository,
    private val secretCodec: SecretEnvelopeCodec
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val requireActiveSource = inputData.getBoolean(KEY_REQUIRE_ACTIVE_SOURCE, false)
        val isManualRun = inputData.getBoolean(KEY_MANUAL_RUN, false)
        val ownerPlaylistId = inputData.getLong(KEY_OWNER_PLAYLIST_ID, NO_PLAYLIST_ID)
        if (isLegacyOwnerlessScheduledEpgWork(isManualRun, requireActiveSource, ownerPlaylistId)) {
            Timber.d("EpgSyncWorker: cancelled legacy ownerless schedule")
            return Result.success()
        }
        val activePlaylist = playlistDao.getActive()
        if (activePlaylist == null) {
            if (requireActiveSource) cancel(applicationContext, ownerPlaylistId)
            Timber.d("EpgSyncWorker: no active playlist, skipping")
            return Result.success()
        }
        if (activePlaylist.id != ownerPlaylistId) {
            if (requireActiveSource) cancel(applicationContext, ownerPlaylistId)
            Timber.d("EpgSyncWorker: playlist owner is no longer active, skipping")
            return Result.success()
        }
        val settings = settingsDataStore.settings.first()
        val ownerPlaylist = activePlaylist
        val owner = activePlaylist.sourceIdentity()
        if (epgRepository.captureOwner(ownerPlaylistId) != owner) return Result.success()
        val epgUrl = settings.epgUrl.takeIf { settings.epgOwnerPlaylistId == ownerPlaylistId }.orEmpty().trim().ifBlank {
            secretCodec.decryptOrEmpty(ownerPlaylist.epgUrl).trim()
        }
        if (epgUrl.isBlank()) {
            Timber.w("EpgSyncWorker: owner has no EPG source, skipping")
            return Result.success()
        }
        if (requireActiveSource && !shouldRunScheduledEpgSync(
                autoSyncEnabled = settings.epgAutoSync,
                activePlaylistId = activePlaylist.id,
                ownerPlaylistId = ownerPlaylistId,
                sourceAvailable = true
            )
        ) {
            cancel(applicationContext, ownerPlaylistId)
            Timber.d("EpgSyncWorker: cancelled stale scheduled source")
            return Result.success()
        }
        return try {
            // Snapshot the owner before networking. EpgRepository checks this id again inside
            // its Room transaction, so an activation that happens while downloading cannot
            // persist A's channel mapping into newly-active playlist B.
            val channelIdMap = buildChannelIdMap(activePlaylist.id)
            when (val result = epgRepository.fetchAndSave(
                url = epgUrl,
                channelIdMap = channelIdMap.takeIf { it.isNotEmpty() },
                expectedActivePlaylistId = activePlaylist.id,
                expectedOwner = owner
            )) {
                is EpgSyncResult.Success -> Result.success()
                EpgSyncResult.StaleSource -> Result.success()
                is EpgSyncResult.Failure -> {
                    if (result.reason == EpgSyncFailure.TRANSPORT && runAttemptCount < 2) Result.retry()
                    else Result.failure(workDataOf("failure" to result.reason.name))
                }
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            // Network/parser exceptions can retain the user-provided EPG URL.
            Timber.e("EpgSyncWorker failed: %s", e.javaClass.simpleName)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private suspend fun buildChannelIdMap(playlistId: Long): Map<String, String> {
        val channels = channelDao.getByPlaylistSnapshot(playlistId)
            .map { it.copy(streamUrl = "", catchupSource = "").toModel() }
        return buildEpgChannelIdMap(channels)
    }

    companion object {
        const val KEY_REQUIRE_ACTIVE_SOURCE = "require_active_source"
        const val KEY_MANUAL_RUN = "manual_run"
        const val KEY_OWNER_PLAYLIST_ID = "owner_playlist_id"
        const val WORK_NAME_PREFIX = "epg_sync_"
        const val NO_PLAYLIST_ID = -1L

        /**
         * Enqueue a daily EPG sync for one playlist owner. [legacyUrl] is used only to cancel
         * pre-migration URL-keyed work and is never written into new WorkManager input.
         */
        fun enqueue(context: Context, ownerPlaylistId: Long, legacyUrl: String = "") {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<EpgSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_REQUIRE_ACTIVE_SOURCE to true,
                        KEY_OWNER_PLAYLIST_ID to ownerPlaylistId
                    )
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.MINUTES
                )
                .build()

            val workManager = WorkManager.getInstance(context)
            workManager.cancelLegacyWork(legacyUrl)
            workManager.enqueueUniquePeriodicWork(
                workNameFor(ownerPlaylistId),
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Timber.d("EpgSyncWorker: enqueued daily sync for playlist=%d", ownerPlaylistId)
        }

        /**
         * Run an immediate one-time EPG sync (e.g. on first playlist load).
         */
        fun runNow(context: Context, ownerPlaylistId: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<EpgSyncWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_OWNER_PLAYLIST_ID to ownerPlaylistId,
                        KEY_MANUAL_RUN to true
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Timber.d("EpgSyncWorker: one-time sync enqueued for playlist=%d", ownerPlaylistId)
        }

        fun cancel(context: Context, ownerPlaylistId: Long, legacyUrl: String = "") {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(workNameFor(ownerPlaylistId))
                cancelLegacyWork(legacyUrl)
            }
        }

        internal fun workNameFor(ownerPlaylistId: Long): String =
            "$WORK_NAME_PREFIX${ownerPlaylistId.coerceAtLeast(0L)}"

        private fun WorkManager.cancelLegacyWork(epgUrl: String) {
            if (epgUrl.isBlank()) return
            cancelUniqueWork(opaqueLegacyWorkNameFor(epgUrl))
            cancelUniqueWork(legacyWorkNameFor(epgUrl))
        }

        private fun opaqueLegacyWorkNameFor(epgUrl: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(epgUrl.trim().toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return "$WORK_NAME_PREFIX$digest"
        }

        private fun legacyWorkNameFor(epgUrl: String): String =
            "$WORK_NAME_PREFIX${epgUrl.hashCode()}"

    }
}

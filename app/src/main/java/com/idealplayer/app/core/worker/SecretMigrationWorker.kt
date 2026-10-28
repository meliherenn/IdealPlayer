package com.idealplayer.app.core.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.idealplayer.app.core.database.ChannelDao
import com.idealplayer.app.core.database.EpisodeDao
import com.idealplayer.app.core.database.FavoriteDao
import com.idealplayer.app.core.database.IdealPlayerDatabase
import com.idealplayer.app.core.database.MovieDao
import com.idealplayer.app.core.database.PlaylistDao
import com.idealplayer.app.core.database.WatchHistoryDao
import com.idealplayer.app.core.database.encryptSecrets
import com.idealplayer.app.core.common.rethrowIfCancellation
import com.idealplayer.app.core.common.removeLegacyArtworkCache
import com.idealplayer.app.core.datastore.SettingsDataStore
import com.idealplayer.app.core.security.SecretEnvelopeCodec
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber

/** Rewrites legacy plaintext provider and playback source fields using the v1 AES-GCM envelope. */
@HiltWorker
class SecretMigrationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val database: IdealPlayerDatabase,
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val episodeDao: EpisodeDao,
    private val watchHistoryDao: WatchHistoryDao,
    private val favoriteDao: FavoriteDao,
    private val settingsDataStore: SettingsDataStore,
    private val secretCodec: SecretEnvelopeCodec
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Also runs for already migrated users; database migration version is independent.
            removeLegacyArtworkCache(applicationContext)
            if (settingsDataStore.secretMigrationVersion.first() >= MIGRATION_VERSION) return@withContext Result.success()
            // Each committed batch is a checkpoint: encrypted rows are excluded on restart.
            migratePages { afterId ->
                playlistDao.getLegacySecretPage(afterId, CHUNK_SIZE).onEach { entity ->
                    currentCoroutineContext().ensureActive()
                    val secured = entity.encryptSecrets(secretCodec)
                    if (secured != entity) playlistDao.update(secured)
                }.lastOrNull()?.id
            }
            migratePages { afterId ->
                channelDao.getLegacySecretPage(afterId, CHUNK_SIZE).onEach { entity ->
                    currentCoroutineContext().ensureActive()
                    val secured = entity.encryptSecrets(secretCodec)
                    if (secured != entity) channelDao.insertAll(listOf(secured))
                }.lastOrNull()?.id
            }
            migratePages { afterId ->
                movieDao.getLegacySecretPage(afterId, CHUNK_SIZE).onEach { entity ->
                    currentCoroutineContext().ensureActive()
                    val secured = entity.encryptSecrets(secretCodec)
                    if (secured != entity) movieDao.insertAll(listOf(secured))
                }.lastOrNull()?.id
            }
            migratePages { afterId ->
                episodeDao.getLegacySecretPage(afterId, CHUNK_SIZE).onEach { entity ->
                    currentCoroutineContext().ensureActive()
                    val secured = entity.encryptSecrets(secretCodec)
                    if (secured != entity) episodeDao.insertAll(listOf(secured))
                }.lastOrNull()?.id
            }
            migratePages { afterId ->
                watchHistoryDao.getLegacySecretPage(afterId, CHUNK_SIZE).onEach { entity ->
                    currentCoroutineContext().ensureActive()
                    val secured = entity.copy(streamUrl = secretCodec.encrypt(entity.streamUrl))
                    if (secured != entity) watchHistoryDao.insert(secured)
                }.lastOrNull()?.id
            }
            migratePages { afterId ->
                favoriteDao.getLegacySecretPage(afterId, CHUNK_SIZE).onEach { entity ->
                    currentCoroutineContext().ensureActive()
                    val secured = entity.encryptSecrets(secretCodec)
                    if (secured != entity) favoriteDao.insert(secured)
                }.lastOrNull()?.id
            }
            settingsDataStore.migrateLegacySecrets(MIGRATION_VERSION)
            Timber.i("Secret envelope migration completed")
            Result.success()
        } catch (error: Exception) {
            error.rethrowIfCancellation()
            Timber.w("Secret envelope migration unavailable: %s", error.javaClass.simpleName)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private suspend fun migratePages(migrate: suspend (Long) -> Long?) {
        var cursor = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            cursor = database.withTransaction { migrate(cursor) } ?: break
        }
    }

    companion object {
        private const val WORK_NAME = "secret_envelope_migration_v1"
        internal const val MIGRATION_VERSION = 1
        private const val CHUNK_SIZE = 100
        private const val MAX_RETRIES = 2

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SecretMigrationWorker>().build()
            )
        }
    }
}

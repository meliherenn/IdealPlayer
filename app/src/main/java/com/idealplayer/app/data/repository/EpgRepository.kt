package com.idealplayer.app.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.idealplayer.app.core.common.SensitiveLog
import com.idealplayer.app.core.common.InputSizeLimitExceededException
import com.idealplayer.app.core.common.isUsableArtworkUrl
import com.idealplayer.app.core.common.rethrowIfCancellation
import com.idealplayer.app.core.database.ChannelDao
import com.idealplayer.app.core.database.EpgDao
import com.idealplayer.app.core.database.EpgProgramEntity
import com.idealplayer.app.core.database.IdealPlayerDatabase
import com.idealplayer.app.core.database.PlaylistDao
import com.idealplayer.app.core.database.EpgSyncStateEntity
import com.idealplayer.app.core.database.toModel
import com.idealplayer.app.core.model.EpgSyncResult
import com.idealplayer.app.core.model.EpgSyncFailure
import com.idealplayer.app.core.model.Channel
import com.idealplayer.app.core.worker.EpgSyncWorker
import com.idealplayer.app.data.parser.EpgProgram
import com.idealplayer.app.data.parser.XmltvDocumentException
import com.idealplayer.app.data.parser.XmltvParseLimitExceededException
import com.idealplayer.app.data.parser.XmltvParser
import com.idealplayer.app.data.parser.MAX_XMLTV_COMPRESSED_BYTES
import com.idealplayer.app.data.parser.asXmltvInputStream
import com.idealplayer.app.data.parser.buildEpgChannelIdMap
import com.idealplayer.app.data.parser.epgLookupKeys
import com.idealplayer.app.data.parser.epgLookupKeysForChannel
import com.idealplayer.app.data.parser.toUiModel
import com.idealplayer.app.core.security.SecretEnvelopeCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpgRepository @Inject constructor(
    private val database: IdealPlayerDatabase,
    private val epgDao: EpgDao,
    private val channelDao: ChannelDao,
    private val playlistDao: PlaylistDao,
    private val xmltvParser: XmltvParser,
    private val okHttpClient: OkHttpClient,
    private val secretCodec: SecretEnvelopeCodec,
    @ApplicationContext private val context: Context
) {
    private companion object {
        private const val MAX_SQL_BIND_ARGS = 800
        private const val EPG_HISTORY_RETENTION_MS = 7 * 24 * 60 * 60 * 1000L
    }

    /**
     * Returns a live flow of current and upcoming programs for [channelId].
     * Automatically refreshed when DB changes.
     */
    fun getProgramsForChannel(playlistId: Long, channelId: String): Flow<List<EpgProgram>> =
        epgDao.getPrograms(playlistId, channelId, System.currentTimeMillis())
            .map { list -> list.map { it.toUiModel() } }

    /**
     * Returns the currently-airing program for [channelId], or null.
     */
    suspend fun getCurrentProgram(playlistId: Long, channelId: String): EpgProgram? =
        epgDao.getCurrentProgram(playlistId, channelId, System.currentTimeMillis())?.toUiModel()

    suspend fun getCurrentProgram(channel: Channel): EpgProgram? =
        withContext(Dispatchers.IO) {
            val candidates = epgLookupKeysForChannel(channel)
            queryCurrentProgramForCandidates(channel.playlistId, candidates, System.currentTimeMillis())?.toUiModel()
        }

    /**
     * Returns the next program after [nowMs] for [channelId], or null.
     */
    suspend fun getNextProgram(playlistId: Long, channelId: String, nowMs: Long = System.currentTimeMillis()): EpgProgram? =
        withContext(Dispatchers.IO) {
            epgDao.getPrograms(playlistId, channelId, nowMs).first()
                .firstOrNull { it.startTime > nowMs }
                ?.toUiModel()
        }

    suspend fun getNextProgram(channel: Channel, nowMs: Long = System.currentTimeMillis()): EpgProgram? =
        withContext(Dispatchers.IO) {
            val candidates = epgLookupKeysForChannel(channel)
            queryUpcomingProgramsForCandidates(channel.playlistId, candidates, nowMs)
                .filter { it.startTime > nowMs }
                .firstProgramForCandidates(candidates)
                ?.toUiModel()
        }

    suspend fun getProgramsForChannel(
        channel: Channel,
        nowMs: Long = System.currentTimeMillis(),
        limit: Int = 24
    ): List<EpgProgram> = withContext(Dispatchers.IO) {
        val candidates = epgLookupKeysForChannel(channel)
        val programsByLookupId = queryUpcomingProgramsForCandidates(channel.playlistId, candidates, nowMs, limit)
            .groupBy(EpgProgramEntity::channelId)

        candidates
            .firstNotNullOfOrNull { candidate ->
                programsByLookupId[candidate]?.takeIf { it.isNotEmpty() }
            }
            .orEmpty()
            .distinctBy { program -> "${program.channelId}|${program.startTime}|${program.title}" }
            .sortedBy(EpgProgramEntity::startTime)
            .take(limit)
            .map(EpgProgramEntity::toUiModel)
    }

    suspend fun captureOwner(playlistId: Long): PlaylistSourceIdentity? =
        playlistDao.getActive()?.takeIf { it.id == playlistId }?.sourceIdentity()

    fun observeSyncState(playlistId: Long): Flow<EpgSyncStateEntity?> =
        epgDao.observeSyncState(playlistId)

    suspend fun fetchAndSave(
        url: String,
        channelIdMap: Map<String, String>? = null,
        expectedActivePlaylistId: Long,
        expectedOwner: PlaylistSourceIdentity? = null
    ): EpgSyncResult = withContext(Dispatchers.IO) {
        val owner = expectedOwner ?: captureOwner(expectedActivePlaylistId)
            ?: return@withContext EpgSyncResult.StaleSource
        if (owner.playlistId != expectedActivePlaylistId || captureOwner(owner.playlistId) != owner) {
            return@withContext EpgSyncResult.StaleSource
        }
        var parsingComplete = false
        try {
            val request = Request.Builder().url(url).header("User-Agent", "IdealPlayer/Android").build()
            EpgStagingStore(context).use { staging ->
                val parseResult = okHttpClient.newCall(request).awaitResponse().use { response ->
                    if (!response.isSuccessful) throw IOException("EPG transport failed")
                    val body = response.body ?: throw IOException("EPG body missing")
                    if (body.contentLength() > MAX_XMLTV_COMPRESSED_BYTES) {
                        throw InputSizeLimitExceededException(MAX_XMLTV_COMPRESSED_BYTES)
                    }
                    xmltvParser.parseDocumentStreaming(
                        inputStream = body.byteStream().asXmltvInputStream(url, body.contentType()?.toString()),
                        channelIdMap = channelIdMap,
                        batchSize = 250
                    ) { batch ->
                        currentCoroutineContext().ensureActive()
                        staging.append(batch)
                    }
                }
                parsingComplete = true
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    // Activation/source changes were free to proceed during the download.
                    if (captureOwner(owner.playlistId) != owner) return@withTransaction EpgSyncResult.StaleSource
                    epgDao.deleteOld(owner.playlistId, System.currentTimeMillis() - EPG_HISTORY_RETENTION_MS)
                    staging.forEachBatch { batch ->
                        currentCoroutineContext().ensureActive()
                        epgDao.insertAll(batch.map { it.copy(id = 0, playlistId = owner.playlistId) })
                    }
                    persistChannelArtwork(parseResult.channelIcons, owner)
                    val now = System.currentTimeMillis()
                    epgDao.saveSyncState(EpgSyncStateEntity(owner.playlistId, lastSuccessAt = now, lastAttemptAt = now))
                    EpgSyncResult.Success(staging.count)
                }
            }
        } catch (error: Exception) {
            error.rethrowIfCancellation()
            val reason = when (error) {
                is XmltvParseLimitExceededException, is InputSizeLimitExceededException -> EpgSyncFailure.SIZE_LIMIT
                is XmltvDocumentException -> EpgSyncFailure.INVALID_DOCUMENT
                else -> if (parsingComplete) EpgSyncFailure.STORAGE else EpgSyncFailure.TRANSPORT
            }
            recordFailure(owner, reason)
            Timber.w("EPG sync failed: %s", reason.name)
            EpgSyncResult.Failure(reason)
        }
    }

    suspend fun savePrograms(
        programs: List<EpgProgramEntity>,
        owner: PlaylistSourceIdentity
    ): EpgSyncResult = withContext(Dispatchers.IO) { persistPrograms(programs, owner) }

    private suspend fun persistPrograms(
        programs: List<EpgProgramEntity>,
        owner: PlaylistSourceIdentity,
        channelIcons: Map<String, String> = emptyMap()
    ): EpgSyncResult = database.withTransaction {
        currentCoroutineContext().ensureActive()
        if (captureOwner(owner.playlistId) != owner) return@withTransaction EpgSyncResult.StaleSource
        epgDao.deleteOld(owner.playlistId, System.currentTimeMillis() - EPG_HISTORY_RETENTION_MS)
        programs.chunked(500).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            epgDao.insertAll(chunk.map { it.copy(id = 0, playlistId = owner.playlistId) })
        }
        persistChannelArtwork(channelIcons, owner)
        val now = System.currentTimeMillis()
        epgDao.saveSyncState(EpgSyncStateEntity(owner.playlistId, lastSuccessAt = now, lastAttemptAt = now))
        EpgSyncResult.Success(programs.size)
    }

    private suspend fun recordFailure(owner: PlaylistSourceIdentity, reason: EpgSyncFailure) {
        try {
            database.withTransaction {
                if (captureOwner(owner.playlistId) != owner) return@withTransaction
                val previous = epgDao.getSyncState(owner.playlistId) ?: EpgSyncStateEntity(owner.playlistId)
                epgDao.saveSyncState(previous.copy(lastAttemptAt = System.currentTimeMillis(), failure = reason.name))
            }
        } catch (error: Exception) {
            error.rethrowIfCancellation()
            Timber.w("EPG status persistence unavailable: %s", error.javaClass.simpleName)
        }
    }

    /**
     * Uses only artwork declared by the user's XMLTV source, then shares it with quality
     * aliases of the same station. Existing usable provider logos are never overwritten.
     */
    private suspend fun persistChannelArtwork(
        channelIcons: Map<String, String>,
        owner: PlaylistSourceIdentity
    ): Int {
        if (channelIcons.isEmpty()) return 0
        return database.withTransaction {
            if (captureOwner(owner.playlistId) != owner) return@withTransaction 0
            val activePlaylistId = owner.playlistId

            val iconByLookupKey = linkedMapOf<String, String>()
            channelIcons.forEach { (channelId, iconUrl) ->
                if (isUsableArtworkUrl(iconUrl)) {
                    epgLookupKeys(channelId).forEach { key ->
                        iconByLookupKey.putIfAbsent(key, iconUrl)
                    }
                }
            }
            if (iconByLookupKey.isEmpty()) return@withTransaction 0

            // Retain only the compact alias index, never a second full channel/entity snapshot.
            var afterId = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = channelDao.getRefreshPage(activePlaylistId, afterId, 500)
                if (page.isEmpty()) break
                page.filter { isUsableArtworkUrl(it.logoUrl) }.forEach { row ->
                    row.artworkLookupKeys().forEach { iconByLookupKey.putIfAbsent(it, row.logoUrl) }
                }
                afterId = page.last().id
            }
            afterId = 0L
            var changed = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = channelDao.getRefreshPage(activePlaylistId, afterId, 500)
                if (page.isEmpty()) break
                val updates = page.mapNotNull { entity ->
                    if (isUsableArtworkUrl(entity.logoUrl)) null else {
                        epgLookupKeysForChannel(entity.copy(streamUrl = "", catchupSource = "").toModel())
                            .firstNotNullOfOrNull(iconByLookupKey::get)?.let { entity.copy(logoUrl = it) }
                    }
                }
                if (updates.isNotEmpty()) channelDao.insertAll(updates)
                changed += updates.size
                afterId = page.last().id
            }
            changed
        }
    }

    /**
     * Schedule daily background EPG sync via WorkManager.
     */
    fun scheduleDailySync(epgUrl: String, ownerPlaylistId: Long) {
        EpgSyncWorker.enqueue(context, ownerPlaylistId, legacyUrl = epgUrl)
    }

    fun cancelScheduledSync(ownerPlaylistId: Long, legacyUrl: String = "") {
        EpgSyncWorker.cancel(context, ownerPlaylistId, legacyUrl)
    }

    /**
     * Trigger an immediate EPG refresh.
     */
    fun syncNow(ownerPlaylistId: Long) {
        EpgSyncWorker.runNow(context, ownerPlaylistId)
    }

    /**
     * Delete EPG data older than the catch-up/guide history window.
     */
    suspend fun pruneOld(playlistId: Long) {
        epgDao.deleteOld(playlistId, System.currentTimeMillis() - EPG_HISTORY_RETENTION_MS)
    }

    /**
     * Returns a map of app channel id to the currently-airing program.
     * The lookup accepts tvg-id, XMLTV id variants, channel names, and stream ids.
     */
    suspend fun getCurrentProgramsForChannels(
        channels: List<Channel>
    ): Map<Long, EpgProgram> = withContext(Dispatchers.IO) {
        queryChannelPrograms(channels) { playlistId, ids ->
            epgDao.getCurrentProgramsForIds(playlistId, ids, System.currentTimeMillis())
        }.mapNotNull { (id, programs) -> programs.firstOrNull()?.toUiModel()?.let { id to it } }.toMap()
    }

    suspend fun getGuideProgramsForChannels(
        channels: List<Channel>,
        windowStart: Long,
        windowEnd: Long
    ): Map<Long, List<EpgProgram>> = withContext(Dispatchers.IO) {
        if (windowEnd <= windowStart) return@withContext emptyMap()
        queryChannelPrograms(channels) { playlistId, ids ->
            epgDao.getProgramsInWindowForIds(playlistId, ids, windowStart, windowEnd)
        }.mapValues { (_, programs) -> programs.map(EpgProgramEntity::toUiModel) }
    }

    private suspend fun queryChannelPrograms(
        channels: List<Channel>,
        query: suspend (Long, List<String>) -> List<EpgProgramEntity>
    ): Map<Long, List<EpgProgramEntity>> = buildMap {
        // Identical tvg-id values in different playlists must never share a lookup bucket.
        for ((playlistId, ownedChannels) in channels.groupBy(Channel::playlistId)) {
            val candidates = ownedChannels.distinctBy(Channel::id)
                .associate { it.id to epgLookupKeysForChannel(it) }
            val programs = candidates.values.flatten().distinct().chunked(MAX_SQL_BIND_ARGS)
                .flatMap { query(playlistId, it) }.groupBy(EpgProgramEntity::channelId)
            candidates.forEach { (id, keys) -> put(id, selectGuideProgramsForCandidates(programs, keys)) }
        }
    }

    /**
     * M3U/Xtream channel id'lerini XMLTV channel id'leriyle eşleştiren map oluşturur.
     * Önce tam eşleşme, sonra normalize edilmiş fuzzy eşleşme dener.
     */
    suspend fun buildChannelIdMap(channels: List<Channel>): Map<String, String> {
        return buildEpgChannelIdMap(channels)
    }

    private suspend fun queryCurrentProgramForCandidates(
        playlistId: Long,
        candidates: List<String>,
        nowMs: Long
    ): EpgProgramEntity? {
        if (candidates.isEmpty()) return null
        return candidates
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap { ids -> epgDao.getCurrentProgramsForIds(playlistId, ids, nowMs) }
            .firstProgramForCandidates(candidates)
    }

    private suspend fun queryUpcomingProgramsForCandidates(
        playlistId: Long,
        candidates: List<String>,
        nowMs: Long,
        limit: Int = 10
    ): List<EpgProgramEntity> {
        if (candidates.isEmpty()) return emptyList()
        return candidates
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap { ids -> epgDao.getUpcomingProgramsForIds(playlistId, ids, nowMs, limit) }
    }
}

internal fun selectGuideProgramsForCandidates(
    programsByLookupId: Map<String, List<EpgProgramEntity>>,
    candidates: List<String>
): List<EpgProgramEntity> = candidates
    .firstNotNullOfOrNull { candidate -> programsByLookupId[candidate]?.takeIf(List<EpgProgramEntity>::isNotEmpty) }
    .orEmpty()
    .distinctBy { program -> "${program.channelId}|${program.startTime}|${program.title}" }
    .sortedBy(EpgProgramEntity::startTime)

private fun List<EpgProgramEntity>.firstProgramForCandidates(
    candidates: List<String>
): EpgProgramEntity? {
    if (isEmpty()) return null

    val programsByLookupId = groupBy(EpgProgramEntity::channelId)
    candidates.forEach { candidate ->
        programsByLookupId[candidate]?.firstOrNull()?.let { return it }
    }
    return null
}

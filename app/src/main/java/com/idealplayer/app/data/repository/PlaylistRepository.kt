package com.idealplayer.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.idealplayer.app.core.common.Resource
import com.idealplayer.app.core.common.limitedTo
import com.idealplayer.app.core.common.rethrowIfCancellation
import com.idealplayer.app.core.database.*
import com.idealplayer.app.core.datastore.SettingsDataStore
import com.idealplayer.app.core.model.*
import com.idealplayer.app.core.player.parsePlaybackSource
import com.idealplayer.app.core.security.SecretEnvelopeCodec
import com.idealplayer.app.data.parser.M3uParser
import com.idealplayer.app.data.parser.XtreamClient
import com.idealplayer.app.data.parser.XtreamContentSection
import com.idealplayer.app.data.parser.XtreamStreamingSink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val categoryDao: CategoryDao,
    private val database: IdealPlayerDatabase,
    private val m3uParser: M3uParser,
    private val xtreamClient: XtreamClient,
    private val epgRepository: EpgRepository,
    private val okHttpClient: OkHttpClient,
    private val settingsDataStore: SettingsDataStore,
    private val secretCodec: SecretEnvelopeCodec,
    private val ownedFiles: OwnedPlaylistFileStore,
    @ApplicationContext private val context: Context
) {
    private val epgDiscoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val epgDiscoveryJobs = ConcurrentHashMap<Long, Job>()
    private val activeEpgSyncJobs = ConcurrentHashMap<Long, Job>()
    private val catalogSyncMutexes = ConcurrentHashMap<Long, Mutex>()
    private val _catalogSyncProgress = MutableStateFlow<Map<Long, CatalogSyncProgress>>(emptyMap())
    val catalogSyncProgress: StateFlow<Map<Long, CatalogSyncProgress>> =
        _catalogSyncProgress.asStateFlow()
    // EPG rows/settings are app-global even though playlists are not. Serialize ownership
    // transitions with source/schedule commits so a late A result cannot overwrite B.
    private val epgSourceMutex = Mutex()

    fun getAllPlaylists(): Flow<List<Playlist>> =
        playlistDao.getAll().map { list -> list.map { it.toModel(secretCodec) } }.flowOn(Dispatchers.IO)

    fun getActivePlaylist(): Flow<Playlist?> =
        playlistDao.getActiveFlow().map { it?.toModel(secretCodec) }.flowOn(Dispatchers.IO)

    fun getActivePlaylistSummary(): Flow<PlaylistSummary?> =
        playlistDao.getActiveFlow().map { it?.toModel()?.toSummary() }.flowOn(Dispatchers.IO)

    suspend fun getPlaylistById(id: Long): Playlist? = withContext(Dispatchers.IO) {
        playlistDao.getById(id)?.toModel(secretCodec)
    }

    fun getPlaylistSummaries(): Flow<List<PlaylistSummary>> = playlistDao.getAll().map { entities ->
        entities.map { entity ->
            entity.toModel(secretCodec).toSummary(
                requiresCredentialReentry = secretCodec.requiresReentry(
                    entity.url,
                    entity.serverUrl,
                    entity.username,
                    entity.password,
                    entity.epgUrl
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    suspend fun getPlaylistSourceSecrets(id: Long): PlaylistSourceSecrets? = withContext(Dispatchers.IO) {
        playlistDao.getById(id)?.let { entity ->
            val playlist = entity.toModel(secretCodec)
            PlaylistSourceSecrets(
                playlistUrl = playlist.url,
                serverUrl = playlist.serverUrl,
                username = playlist.username,
                password = playlist.password,
                epgUrl = playlist.epgUrl,
                requiresCredentialReentry = secretCodec.requiresReentry(
                    entity.url,
                    entity.serverUrl,
                    entity.username,
                    entity.password,
                    entity.epgUrl
                )
            )
        }
    }

    suspend fun savePlaylist(playlist: Playlist): Long = withContext(Dispatchers.IO) {
        if (playlist.id > 0) {
            updatePlaylist(playlist)
            return@withContext playlist.id
        }
        val entity = playlist.toSecureEntity(secretCodec)
        playlistDao.insert(entity)
    }

    suspend fun updatePlaylist(playlist: Playlist) = withContext(Dispatchers.IO) {
        epgSourceMutex.withLock {
            if (playlistDao.getById(playlist.id) == null) return@withLock
            epgDiscoveryJobs.remove(playlist.id)?.cancel()
            activeEpgSyncJobs.remove(playlist.id)?.cancel()
            if (settingsDataStore.settings.first().epgOwnerPlaylistId == playlist.id) {
                settingsDataStore.updateEpgSource("", 0)
            }
            database.withTransaction {
                val previous = playlistDao.getById(playlist.id) ?: return@withTransaction
                playlistDao.update(playlist.toSecureEntity(secretCodec).copy(sourceRevision = previous.sourceRevision + 1))
                database.epgDao().deleteByPlaylist(playlist.id)
                database.epgDao().deleteSyncState(playlist.id)
            }
        }
    }

    suspend fun deletePlaylist(id: Long) {
        getPlaylistById(id)?.let { deletePlaylist(it) }
    }

    suspend fun syncPlaylist(id: Long): Resource<Unit> =
        getPlaylistById(id)?.let { syncPlaylist(it) } ?: Resource.Error("Playlist unavailable")

    suspend fun deletePlaylist(playlist: Playlist) = withContext(Dispatchers.IO) {
        epgSourceMutex.withLock {
            val ownedPath = playlistDao.getById(playlist.id)?.filePath.orEmpty()
            // EPG rows are app-wide and only the active playlist is scheduled. Do not cancel
            // the active source when deleting an unrelated inactive playlist that happens to
            // declare a matching URL; cancel only when the scheduled owner is being removed.
            if (playlistDao.getActive()?.id == playlist.id) {
                val configuredEpgUrl = settingsDataStore.settings.first().epgUrl
                epgRepository.cancelScheduledSync(
                    ownerPlaylistId = playlist.id,
                    legacyUrl = configuredEpgUrl.ifBlank { playlist.epgUrl }
                )
            }
            epgDiscoveryJobs.remove(playlist.id)?.cancel()
            activeEpgSyncJobs.remove(playlist.id)?.cancel()

            database.withTransaction {
                replacePlaylistContent(playlist.id)
                playlistDao.delete(playlist.toSecureEntity(secretCodec))
            }
            // Never delete a SAF document or a legacy user-owned path. References can be shared
            // by older records, so remove a private file only after its last owner is gone.
            if (ownedFiles.owns(ownedPath) &&
                playlistDao.getAll().first().none { it.filePath == ownedPath }
            ) {
                ownedFiles.delete(ownedPath)
            }
        }
    }

    suspend fun activatePlaylist(id: Long) {
        val activatedPlaylist = epgSourceMutex.withLock {
            val previousActiveId = playlistDao.getActive()?.id
            // Cancel the previous owner's work and advance the activation revision atomically.
            // A → B → A must not accept a response from A's earlier activation.
            if (previousActiveId != id) {
                previousActiveId?.let { previousId ->
                    epgRepository.cancelScheduledSync(
                        ownerPlaylistId = previousId,
                        legacyUrl = settingsDataStore.settings.first().epgUrl
                    )
                }
                previousActiveId?.let { previousId ->
                    epgDiscoveryJobs.remove(previousId)?.cancel()
                    activeEpgSyncJobs.remove(previousId)?.cancel()
                }
            }
            val playlist = database.withTransaction {
                playlistDao.deactivateAll()
                playlistDao.activate(id)
                playlistDao.getById(id)?.toModel(secretCodec)
            }
            settingsDataStore.updateLastPlaylistId(id)
            playlist
        }

        // New playlists are synced before they become active during onboarding. Start EPG
        // discovery only after this ownership transition so the schedule has a valid owner.
        activatedPlaylist?.let(::syncActivePlaylistEpgInBackground)
    }

    suspend fun hasSyncedContent(playlistId: Long): Boolean =
        channelDao.countByPlaylist(playlistId) > 0 ||
            movieDao.countByPlaylist(playlistId) > 0 ||
            seriesDao.countByPlaylist(playlistId) > 0

    suspend fun testConnection(playlist: Playlist): Result<String> = withContext(Dispatchers.IO) {
        try {
            when (playlist.type) {
                PlaylistType.M3U_URL -> testM3uUrlConnection(playlist.url)
                PlaylistType.XTREAM_CODES -> {
                    val result = xtreamClient.authenticate(playlist.serverUrl, playlist.username, playlist.password)
                    result.map { "Connection successful - ${it.userInfo?.status}" }
                }
                PlaylistType.M3U_FILE -> {
                    openLocalPlaylist(playlist.filePath).use { }
                    Result.success("Local file ready")
                }
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Timber.e("Connection test failed: %s", e.javaClass.simpleName)
            Result.failure(e)
        }
    }

    suspend fun syncPlaylist(playlist: Playlist): Resource<Unit> = withContext(Dispatchers.IO) {
        require(playlist.id > 0L) { "Playlist must be saved before synchronization" }
        val syncMutex = catalogSyncMutexes.getOrPut(playlist.id) { Mutex() }
        syncMutex.withLock {
            val staging = CatalogStagingStore(context, playlist.id)
            try {
                reportCatalogSync(playlist.id, CatalogSyncStage.PREPARING)
                val storedSource = playlistDao.getById(playlist.id)
                    ?: return@withLock Resource.Error("Playlist is no longer available")
                val expectedSource = storedSource.sourceIdentity()
                val normalizedPlaylist = storedSource.toModel(secretCodec).normalizedForSync()
                reportCatalogSync(playlist.id, CatalogSyncStage.LOADING_SOURCE)
                val loadingStarted = System.nanoTime()

                var capturedEpgUrls = emptyList<String>()
                var loadedSections = XtreamContentSection.entries.toSet()
                when (normalizedPlaylist.type) {
                    PlaylistType.M3U_URL -> {
                        capturedEpgUrls = streamM3uUrlToStaging(normalizedPlaylist, staging)
                    }
                    PlaylistType.M3U_FILE -> {
                        capturedEpgUrls = streamM3uFileToStaging(normalizedPlaylist, staging)
                    }
                    PlaylistType.XTREAM_CODES -> {
                        loadedSections = streamXtreamToStaging(normalizedPlaylist, staging)
                    }
                }

                val totalItems = staging.totalItems()
                Timber.d("Catalog source and staging: items=%d elapsedMs=%d", totalItems,
                    (System.nanoTime() - loadingStarted) / 1_000_000)
                if (staging.channelCount() + staging.movieCount() + staging.seriesCount() <= 0) {
                    throw IllegalStateException("Empty playlist")
                }
                currentCoroutineContext().ensureActive()

                val syncTime = System.currentTimeMillis()
                var persistedItems = 0
                val onItemsPersisted: (Int) -> Unit = { count ->
                    persistedItems += count
                    reportCatalogSync(
                        playlistId = playlist.id,
                        stage = CatalogSyncStage.PERSISTING_CATALOG,
                        processedItems = persistedItems,
                        totalItems = totalItems
                    )
                }
                reportCatalogSync(
                    playlistId = playlist.id,
                    stage = CatalogSyncStage.PERSISTING_CATALOG,
                    totalItems = totalItems
                )

                val publishingStarted = System.nanoTime()
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    check(playlistDao.getById(playlist.id)?.sourceIdentity() == expectedSource) {
                        "Playlist source changed during synchronization"
                    }
                    snapshotExistingContent(staging, playlist.id, loadedSections)

                    replacePlaylistContent(playlist.id, loadedSections)
                    publishStagedCatalog(
                        staging = staging,
                        playlistId = playlist.id,
                        preserveExistingEpisodes = normalizedPlaylist.type == PlaylistType.XTREAM_CODES &&
                            XtreamContentSection.SERIES in loadedSections,
                        syncTime = syncTime,
                        onItemsPersisted = onItemsPersisted
                    )

                    reportCatalogSync(playlist.id, CatalogSyncStage.FINALIZING)
                    val channelCount = channelDao.countByPlaylist(playlist.id)
                    val movieCount = movieDao.countByPlaylist(playlist.id)
                    val seriesCount = seriesDao.countByPlaylist(playlist.id)
                    playlistDao.updateCounts(playlist.id, channelCount, movieCount, seriesCount)
                }
                Timber.d("Catalog publication: items=%d elapsedMs=%d", totalItems,
                    (System.nanoTime() - publishingStarted) / 1_000_000)

                val epgDiscoverySeed = when (normalizedPlaylist.type) {
                    PlaylistType.M3U_URL, PlaylistType.M3U_FILE -> EpgDiscoverySeed(
                        type = EpgDiscoveryType.M3U,
                        epgUrls = capturedEpgUrls.ifEmpty { staging.getEpgUrls() }
                    )
                    PlaylistType.XTREAM_CODES -> EpgDiscoverySeed(
                        type = EpgDiscoveryType.XTREAM
                    )
                }

                reportCatalogSync(playlist.id, CatalogSyncStage.SCHEDULING_EPG)
                syncDiscoveredEpgInBackground(normalizedPlaylist, epgDiscoverySeed)
                reportCatalogSync(playlist.id, CatalogSyncStage.COMPLETED)

                Resource.Success(Unit)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Timber.e("Sync failed for playlist %d: %s", playlist.id, e.javaClass.simpleName)
                // Transport exceptions can embed credential-bearing request URLs. Keep the
                // throwable in-process for typed handling, but expose only stable safe copy.
                Resource.Error("Playlist synchronization failed", e)
            } finally {
                staging.discard()
                _catalogSyncProgress.update { progress -> progress - playlist.id }
            }
        }
    }

    fun observeCatalogSyncProgress(playlistId: Long): Flow<CatalogSyncProgress?> =
        catalogSyncProgress.map { progress -> progress[playlistId] }.distinctUntilChanged()

    private fun reportCatalogSync(
        playlistId: Long,
        stage: CatalogSyncStage,
        processedItems: Int = 0,
        totalItems: Int? = null
    ) {
        _catalogSyncProgress.update { progress ->
            progress + (
                playlistId to CatalogSyncProgress(
                    playlistId = playlistId,
                    stage = stage,
                    processedItems = processedItems,
                    totalItems = totalItems
                )
            )
        }
    }

    suspend fun ensureEpgSynced(playlistId: Long, forceRefresh: Boolean = false): Boolean =
        getPlaylistById(playlistId)?.let { ensureEpgSynced(it, forceRefresh) } ?: false

    suspend fun ensureEpgSynced(
        playlist: Playlist,
        forceRefresh: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (epgRepository.captureOwner(playlist.id) != playlist.sourceIdentity()) {
            Timber.d("Skipping EPG discovery for inactive playlist %d", playlist.id)
            return@withContext false
        }
        epgDiscoveryJobs[playlist.id]
            ?.takeIf(Job::isActive)
            ?.let { runningJob ->
                runningJob.join()
                return@withContext hasCurrentEpgMatches(playlist.id)
            }

        val settings = settingsDataStore.settings.first()
        if (!forceRefresh && settings.epgLastSync > 0L) {
            val hasCurrentPrograms = hasCurrentEpgMatches(playlist.id)
            if (hasCurrentPrograms) {
                return@withContext false
            }
            Timber.d("EPG URL exists but no current programs matched; rediscovering for playlist ${playlist.id}")
        }

        runCatching {
            val normalizedPlaylist = playlist.normalizedForSync()
            val seed = when (normalizedPlaylist.type) {
                PlaylistType.M3U_URL -> EpgDiscoverySeed(EpgDiscoveryType.M3U, loadM3uEpgUrls(normalizedPlaylist))
                PlaylistType.M3U_FILE -> EpgDiscoverySeed(EpgDiscoveryType.M3U, loadM3uFileEpgUrls(normalizedPlaylist))
                PlaylistType.XTREAM_CODES -> EpgDiscoverySeed(EpgDiscoveryType.XTREAM)
            }

            syncDiscoveredEpg(normalizedPlaylist, seed)
        }.getOrElse { error ->
            error.rethrowIfCancellation()
            Timber.w(
                "EPG auto discovery failed for playlist %d: %s",
                playlist.id,
                error.javaClass.simpleName
            )
            false
        }
    }

    private suspend fun loadM3uEpgUrls(playlist: Playlist): List<String> {
        val request = Request.Builder().url(playlist.url).build()
        okHttpClient.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Playlist request failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw Exception("Empty response")
            require(body.contentLength() <= MAX_PLAYLIST_BYTES || body.contentLength() < 0L) {
                "Playlist is larger than the ${MAX_PLAYLIST_BYTES / MEBIBYTE} MiB limit"
            }
            val syncJob = currentCoroutineContext()[Job]
            return m3uParser.discoverEpgUrls(
                input = body.byteStream().limitedTo(MAX_PLAYLIST_BYTES),
                cancellationCheck = { syncJob?.ensureActive() }
            )
        }
    }

    private suspend fun loadM3uFileEpgUrls(playlist: Playlist): List<String> {
        val syncJob = currentCoroutineContext()[Job]
        return openLocalPlaylist(playlist.filePath).use { input ->
            m3uParser.discoverEpgUrls(
                input = input.limitedTo(MAX_PLAYLIST_BYTES),
                cancellationCheck = { syncJob?.ensureActive() }
            )
        }
    }

    private suspend fun streamM3uUrlToStaging(
        playlist: Playlist,
        staging: CatalogStagingStore
    ): List<String> {
        val request = Request.Builder().url(playlist.url).build()
        return okHttpClient.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Playlist request failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw Exception("Empty response")
            require(body.contentLength() <= MAX_PLAYLIST_BYTES || body.contentLength() < 0L) {
                "Playlist is larger than the ${MAX_PLAYLIST_BYTES / MEBIBYTE} MiB limit"
            }
            val syncJob = currentCoroutineContext()[Job]
            var capturedEpgUrls = emptyList<String>()
            val sink = object : com.idealplayer.app.data.parser.M3uStreamingSink {
                override fun onChannels(channels: List<ChannelEntity>) = staging.insertChannels(channels)
                override fun onMovies(movies: List<MovieEntity>) = staging.insertMovies(movies)
                override fun onSeries(series: List<SeriesEntity>) = staging.insertSeries(series)
                override fun onEpisodes(seriesName: String, episodes: List<EpisodeEntity>) =
                    staging.insertEpisodes(episodes, seriesName)
                override fun onEpgUrls(urls: List<String>) {
                    capturedEpgUrls = urls
                    staging.insertEpgUrls(urls)
                }
            }
            m3uParser.parseStreaming(
                input = body.byteStream().limitedTo(MAX_PLAYLIST_BYTES),
                playlistId = playlist.id,
                sink = sink,
                nextEpisodeIndex = staging::nextEpisodeIndex,
                cancellationCheck = { syncJob?.ensureActive() }
            )
            capturedEpgUrls
        }
    }

    private suspend fun streamM3uFileToStaging(
        playlist: Playlist,
        staging: CatalogStagingStore
    ): List<String> {
        val syncJob = currentCoroutineContext()[Job]
        var capturedEpgUrls = emptyList<String>()
        val sink = object : com.idealplayer.app.data.parser.M3uStreamingSink {
            override fun onChannels(channels: List<ChannelEntity>) = staging.insertChannels(channels)
            override fun onMovies(movies: List<MovieEntity>) = staging.insertMovies(movies)
            override fun onSeries(series: List<SeriesEntity>) = staging.insertSeries(series)
            override fun onEpisodes(seriesName: String, episodes: List<EpisodeEntity>) =
                staging.insertEpisodes(episodes, seriesName)
            override fun onEpgUrls(urls: List<String>) {
                capturedEpgUrls = urls
                staging.insertEpgUrls(urls)
            }
        }
        openLocalPlaylist(playlist.filePath).use { input ->
            m3uParser.parseStreaming(
                input = input.limitedTo(MAX_PLAYLIST_BYTES),
                playlistId = playlist.id,
                sink = sink,
                nextEpisodeIndex = staging::nextEpisodeIndex,
                cancellationCheck = { syncJob?.ensureActive() }
            )
        }
        return capturedEpgUrls
    }

    private suspend fun streamXtreamToStaging(
        playlist: Playlist,
        staging: CatalogStagingStore
    ): Set<XtreamContentSection> {
        val syncJob = currentCoroutineContext()[Job]
        val sink = object : XtreamStreamingSink {
            override fun onChannels(channels: List<ChannelEntity>) { staging.insertChannels(channels) }
            override fun onMovies(movies: List<MovieEntity>) { staging.insertMovies(movies) }
            override fun onSeries(series: List<SeriesEntity>) { staging.insertSeries(series) }
            override fun onCategories(categories: List<CategoryEntity>) { staging.insertCategories(categories) }
        }
        return xtreamClient.loadContentStreaming(
            serverUrl = playlist.serverUrl,
            username = playlist.username,
            password = playlist.password,
            playlistId = playlist.id,
            sink = sink,
            batchSize = 500,
            cancellationCheck = { syncJob?.ensureActive() }
        )
    }

    private suspend fun publishStagedCatalog(
        staging: CatalogStagingStore,
        playlistId: Long,
        preserveExistingEpisodes: Boolean,
        syncTime: Long,
        onItemsPersisted: (Int) -> Unit
    ) {
        val totalChannels = staging.channelCount()
        var offset = 0
        while (offset < totalChannels) {
            currentCoroutineContext().ensureActive()
            val chunk = staging.readChannels(limit = INSERT_CHUNK_SIZE, offset = offset)
            if (chunk.isEmpty()) break
            val prepared = staging.stateIndex.mergeChannels(chunk)
                .encryptSecrets(secretCodec)
            channelDao.insertAll(prepared)
            onItemsPersisted(chunk.size)
            offset += chunk.size
        }

        val totalMovies = staging.movieCount()
        offset = 0
        while (offset < totalMovies) {
            currentCoroutineContext().ensureActive()
            val chunk = staging.readMovies(limit = INSERT_CHUNK_SIZE, offset = offset)
            if (chunk.isEmpty()) break
            val prepared = staging.stateIndex.mergeMovies(chunk, syncTime)
                .encryptSecrets(secretCodec)
            movieDao.insertAll(prepared)
            onItemsPersisted(chunk.size)
            offset += chunk.size
        }

        val totalSeries = staging.seriesCount()
        offset = 0
        while (offset < totalSeries) {
            currentCoroutineContext().ensureActive()
            val chunk = staging.readSeries(limit = INSERT_CHUNK_SIZE, offset = offset)
            if (chunk.isEmpty()) break
            val prepared = staging.stateIndex.mergeSeries(chunk, syncTime)
            seriesDao.insertAll(prepared)
            onItemsPersisted(chunk.size)
            offset += chunk.size
        }

        if (preserveExistingEpisodes) {
            var afterId = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = staging.stateIndex.episodePage(afterId, INSERT_CHUNK_SIZE)
                if (page.isEmpty()) break
                val survivingIds = seriesDao.getSurvivingIds(playlistId, page.map { it.seriesId }.distinct()).toSet()
                episodeDao.insertAll(page.filter { it.seriesId in survivingIds }.encryptSecrets(secretCodec))
                afterId = page.last().id
            }
        }

        // Insert staged M3U episodes and link with seriesId
        val totalM3uEpisodes = staging.episodeCount()
        var epOffset = 0
        while (epOffset < totalM3uEpisodes) {
            currentCoroutineContext().ensureActive()
            val chunk = staging.readM3uEpisodesWithSeriesName(limit = INSERT_CHUNK_SIZE, offset = epOffset)
            if (chunk.isEmpty()) break
            val storedSeries = seriesDao.getByNames(playlistId, chunk.map { it.first }.distinct()).associateBy { it.name }
            val resolvedEpisodes = staging.stateIndex.mergeEpisodes(chunk.mapNotNull { (seriesName, ep) ->
                val series = storedSeries[seriesName] ?: return@mapNotNull null
                ep.copy(seriesId = series.id)
            })

            if (resolvedEpisodes.isNotEmpty()) {
                val encrypted = resolvedEpisodes.encryptSecrets(secretCodec)
                episodeDao.insertAll(encrypted)
            }
            onItemsPersisted(chunk.size)
            epOffset += chunk.size
        }

        if (totalM3uEpisodes > 0) seriesDao.recountEpisodes(playlistId)

        val totalCategories = staging.categoryCount()
        var catOffset = 0
        while (catOffset < totalCategories) {
            currentCoroutineContext().ensureActive()
            val chunk = staging.readCategories(limit = INSERT_CHUNK_SIZE, offset = catOffset)
            if (chunk.isEmpty()) break
            categoryDao.insertAll(chunk)
            onItemsPersisted(chunk.size)
            catOffset += chunk.size
        }
    }

    /**
     * IPTV servers often reject HEAD while accepting the GET used by the actual sync. Try the
     * inexpensive probe first, then a tiny ranged GET before reporting a false failure.
     */
    private fun testM3uUrlConnection(url: String): Result<String> {
        val headSucceeded = runCatching {
            okHttpClient.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
                response.isSuccessful
            }
        }.getOrDefault(false)
        if (headSucceeded) return Result.success("Connection successful")

        return runCatching {
            okHttpClient.newCall(
                Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-1023")
                    .get()
                    .build()
            ).execute().use { response ->
                if (response.isSuccessful) {
                    "Connection successful"
                } else {
                    throw IllegalStateException("HTTP ${response.code}")
                }
            }
        }
    }

    private fun openLocalPlaylist(location: String): java.io.InputStream {
        val trimmed = location.trim()
        require(trimmed.isNotBlank()) { "Playlist file is not selected" }
        if (ownedFiles.owns(trimmed)) return ownedFiles.open(trimmed)

        if (trimmed.startsWith("content://", ignoreCase = true)) {
            return context.contentResolver.openInputStream(Uri.parse(trimmed))
                ?: throw IllegalStateException("Selected playlist file cannot be opened")
        }

        val file = java.io.File(trimmed)
        if (!file.isFile) throw IllegalStateException("Selected playlist file cannot be opened")
        return file.inputStream()
    }

    private suspend fun replacePlaylistContent(
        playlistId: Long,
        sections: Set<XtreamContentSection> = XtreamContentSection.entries.toSet()
    ) {
        if (XtreamContentSection.LIVE in sections) {
            channelDao.deleteByPlaylist(playlistId)
            categoryDao.deleteByPlaylistAndContentType(playlistId, ContentType.LIVE.name)
        }
        if (XtreamContentSection.MOVIES in sections) {
            movieDao.deleteByPlaylist(playlistId)
            categoryDao.deleteByPlaylistAndContentType(playlistId, ContentType.MOVIE.name)
        }
        if (XtreamContentSection.SERIES in sections) {
            episodeDao.deleteByPlaylist(playlistId)
            seriesDao.deleteByPlaylist(playlistId)
            categoryDao.deleteByPlaylistAndContentType(playlistId, ContentType.SERIES.name)
        }
    }

    private suspend fun syncDiscoveredEpg(
        playlist: Playlist,
        seed: EpgDiscoverySeed
    ): Boolean {
        if (!isActivePlaylist(playlist.id)) {
            Timber.d("Skipping EPG source discovery for inactive playlist %d", playlist.id)
            return false
        }
        val channelIdMap = linkedMapOf<String, String>()
        val fallbackChannels = mutableListOf<Channel>()
        val streamUrls = mutableListOf<String>()
        var afterId = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = channelDao.getRefreshPage(playlist.id, afterId, INSERT_CHUNK_SIZE)
            if (page.isEmpty()) break
            epgRepository.buildChannelIdMap(page.map { it.toCatalogModel() }).forEach { (key, value) ->
                channelIdMap.putIfAbsent(key, value)
            }
            page.take((XTREAM_SHORT_EPG_CHANNEL_LIMIT - streamUrls.size).coerceAtLeast(0))
                .forEach { streamUrls += secretCodec.decryptOrEmpty(it.streamUrl) }
            page.filter { it.streamId > 0 }.take((XTREAM_SHORT_EPG_CHANNEL_LIMIT - fallbackChannels.size).coerceAtLeast(0))
                .forEach { ch ->
                    val decryptedUrl = if (playlist.type == PlaylistType.XTREAM_CODES) {
                        ""
                    } else {
                        secretCodec.decryptOrEmpty(ch.streamUrl)
                    }
                    fallbackChannels += ch.toCatalogModel().copy(streamUrl = decryptedUrl)
                }
            afterId = page.last().id
        }
        if (afterId == 0L) return false
        val discoveredEpgUrls = when (seed.type) {
            EpgDiscoveryType.M3U -> resolveM3uEpgUrls(
                epgUrls = seed.epgUrls,
                playlistUrl = playlist.url,
                streamUrls = streamUrls
            )
            EpgDiscoveryType.XTREAM -> listOf(buildXtreamEpgUrl(playlist))
        }
            .plus(playlist.epgUrl.splitEpgSourceUrls())
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

        if (discoveredEpgUrls.isNotEmpty() && syncXmltvEpgUrls(playlist, channelIdMap, discoveredEpgUrls)) {
            return true
        }

        if (discoveredEpgUrls.isEmpty()) {
            Timber.d("No EPG URL discovered for playlist ${playlist.id}")
        }

        if (syncXtreamApiEpg(playlist, fallbackChannels)) {
            return true
        }

        // Guide data must come from the user's playlist, explicitly configured XMLTV URL, or
        // the playlist's own Xtream credentials. Do not silently fetch bundled public sources.
        return false
    }

    private fun syncDiscoveredEpgInBackground(
        playlist: Playlist,
        seed: EpgDiscoverySeed
    ) {
        if (epgDiscoveryJobs[playlist.id]?.isActive == true) {
            Timber.d("EPG discovery already running for playlist ${playlist.id}")
            return
        }

        val job = epgDiscoveryScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                if (isActivePlaylist(playlist.id)) {
                    syncDiscoveredEpg(playlist, seed)
                } else {
                    Timber.d("Deferred EPG discovery until playlist %d is active", playlist.id)
                }
            } catch (error: Exception) {
                error.rethrowIfCancellation()
                Timber.w(
                    "Background EPG discovery failed for playlist %d: %s",
                    playlist.id,
                    error.javaClass.simpleName
                )
            } finally {
                epgDiscoveryJobs.remove(playlist.id, currentCoroutineContext()[Job])
            }
        }
        epgDiscoveryJobs.put(playlist.id, job)?.cancel()
        job.start()
    }

    private fun syncActivePlaylistEpgInBackground(playlist: Playlist) {
        if (activeEpgSyncJobs[playlist.id]?.isActive == true) return

        val job = epgDiscoveryScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                ensureEpgSynced(playlist, forceRefresh = true)
            } catch (error: Exception) {
                error.rethrowIfCancellation()
                Timber.w(
                    "Active playlist EPG discovery failed for playlist %d: %s",
                    playlist.id,
                    error.javaClass.simpleName
                )
            } finally {
                activeEpgSyncJobs.remove(playlist.id, currentCoroutineContext()[Job])
            }
        }
        activeEpgSyncJobs.put(playlist.id, job)?.cancel()
        job.start()
    }

    private suspend fun isActivePlaylist(playlistId: Long): Boolean =
        playlistDao.getActive()?.id == playlistId

    private suspend fun syncXmltvEpgUrls(
        playlist: Playlist,
        channelIdMap: Map<String, String>,
        epgUrls: List<String>
    ): Boolean {
        if (!isActivePlaylist(playlist.id)) return false

        for (discoveredEpgUrl in epgUrls) {
            val owner = playlist.sourceIdentity()
            if (epgRepository.captureOwner(playlist.id) != owner) return false
            val result = epgRepository.fetchAndSave(
                url = discoveredEpgUrl,
                channelIdMap = channelIdMap,
                expectedActivePlaylistId = playlist.id,
                expectedOwner = owner
            )
            if (result == EpgSyncResult.StaleSource) return false
            val savedProgramCount = (result as? EpgSyncResult.Success)?.programCount ?: continue

            if (savedProgramCount <= 0) {
                Timber.w("Auto EPG sync parsed no programs for playlist ${playlist.id}")
                continue
            }

            if (!hasCurrentEpgMatches(playlist.id)) {
                Timber.w("Auto EPG sync parsed programs but matched no current channels for playlist ${playlist.id}")
                continue
            }

            if (!commitXmltvEpgSource(owner, discoveredEpgUrl)) return false
            Timber.d("Auto EPG sync completed for playlist ${playlist.id}: $savedProgramCount programs")
            return true
        }

        return false
    }

    private suspend fun syncXtreamApiEpg(
        playlist: Playlist,
        channels: List<Channel>
    ): Boolean {
        if (!isActivePlaylist(playlist.id)) return false
        val credentials = resolveXtreamCredentials(
            playlist = playlist,
            streamUrls = channels.map { it.streamUrl }
        ) ?: return false

        val targetChannels = channels
            .filter { it.streamId > 0 }
            .take(XTREAM_SHORT_EPG_CHANNEL_LIMIT)
        if (targetChannels.isEmpty()) return false

        val owner = playlist.sourceIdentity()
        if (epgRepository.captureOwner(playlist.id) != owner) return false
        val programs = xtreamClient.loadLiveEpgPrograms(
            serverUrl = credentials.serverUrl,
            username = credentials.username,
            password = credentials.password,
            channels = targetChannels
        )
        val savedProgramCount = (epgRepository.savePrograms(
            programs = programs,
            owner = owner
        ) as? EpgSyncResult.Success)?.programCount ?: return false
        if (savedProgramCount <= 0) {
            Timber.w("Xtream API EPG sync parsed no programs for playlist ${playlist.id}")
            return false
        }

        if (!commitXtreamEpgSource(owner)) return false
        Timber.d("Xtream API EPG sync completed for playlist ${playlist.id}: $savedProgramCount programs")
        return true
    }

    /** Commits only while the captured source revision still owns the schedule. */
    private suspend fun commitXmltvEpgSource(owner: PlaylistSourceIdentity, epgUrl: String): Boolean =
        epgSourceMutex.withLock {
            if (epgRepository.captureOwner(owner.playlistId) != owner) return@withLock false
            val playlistId = owner.playlistId

            val freshSettings = settingsDataStore.settings.first()
            if (freshSettings.epgUrl != epgUrl) {
                epgRepository.cancelScheduledSync(playlistId, freshSettings.epgUrl)
            }
            // Rewrites legacy plaintext even when the logical source did not change.
            settingsDataStore.updateEpgSource(epgUrl, playlistId)
            settingsDataStore.updateEpgLastSync(System.currentTimeMillis())
            if (freshSettings.epgAutoSync) {
                epgRepository.scheduleDailySync(epgUrl, playlistId)
            }
            true
        }

    /** Xtream API EPG has no durable XMLTV URL, so clear any previous XMLTV schedule safely. */
    private suspend fun commitXtreamEpgSource(owner: PlaylistSourceIdentity): Boolean =
        epgSourceMutex.withLock {
            if (epgRepository.captureOwner(owner.playlistId) != owner) return@withLock false
            val playlistId = owner.playlistId

            val freshSettings = settingsDataStore.settings.first()
            epgRepository.cancelScheduledSync(playlistId, freshSettings.epgUrl)
            if (freshSettings.epgUrl.isNotBlank()) {
                settingsDataStore.updateEpgSource("", playlistId)
            }
            settingsDataStore.updateEpgLastSync(System.currentTimeMillis())
            true
        }

    private suspend fun hasCurrentEpgMatches(playlistId: Long): Boolean {
        var afterId = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = channelDao.getRefreshPage(playlistId, afterId, INSERT_CHUNK_SIZE)
            if (page.isEmpty()) return false
            if (epgRepository.getCurrentProgramsForChannels(page.map { it.toCatalogModel() }).isNotEmpty()) return true
            afterId = page.last().id
        }
    }

    private suspend fun snapshotExistingContent(
        staging: CatalogStagingStore,
        playlistId: Long,
        sections: Set<XtreamContentSection>
    ) {
        val index = staging.stateIndex
        suspend fun <T> copyPages(load: suspend (Long) -> List<T>, id: (T) -> Long, save: (List<T>) -> Unit) {
            var afterId = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = load(afterId)
                if (page.isEmpty()) break
                save(page)
                afterId = id(page.last())
            }
        }
        if (XtreamContentSection.LIVE in sections) copyPages(
            { channelDao.getRefreshPage(playlistId, it, INSERT_CHUNK_SIZE) }, ChannelEntity::id
        ) { rows ->
            // Provider IDs own Xtream identity; old URLs are not needed for merging state.
            // M3U rows still need their plaintext URL as the natural identity.
            val clear = rows.map { if (it.streamId > 0) it else it.decryptSecrets(secretCodec) }
            index.rememberChannels(clear)
            index.channelArtwork(clear)
        }
        if (XtreamContentSection.MOVIES in sections) copyPages(
            { movieDao.getRefreshPage(playlistId, it, INSERT_CHUNK_SIZE) }, MovieEntity::id
        ) { rows ->
            val clear = rows.map { if (it.streamId > 0) it else it.decryptSecrets(secretCodec) }
            index.rememberMovies(clear)
            index.movieArtwork(clear)
        }
        if (XtreamContentSection.SERIES in sections) {
            copyPages({ seriesDao.getRefreshPage(playlistId, it, INSERT_CHUNK_SIZE) }, SeriesEntity::id) {
                index.rememberSeries(it)
                index.seriesArtwork(it)
            }
            copyPages({ episodeDao.getRefreshPage(playlistId, it, INSERT_CHUNK_SIZE) }, EpisodeEntity::id) { rows ->
                index.rememberEpisodes(rows.map { it.decryptSecrets(secretCodec) })
            }
        }
    }

    private companion object {
        const val INSERT_CHUNK_SIZE = 500
        const val XTREAM_SHORT_EPG_CHANNEL_LIMIT = 160
        const val MEBIBYTE = 1024L * 1024L
        // Direct URL/file imports can be larger than a Connected setup payload, but parsing
        // streams into isolated staging; the source still needs a clear byte bound.
        const val MAX_PLAYLIST_BYTES = 10L * MEBIBYTE
    }
}

internal fun resolveM3uEpgUrl(epgUrl: String, playlistUrl: String): String {
    val trimmed = epgUrl.trim()
    if (trimmed.isBlank()) {
        return buildXtreamEpgUrlFromM3uPlaylistUrl(playlistUrl)
    }
    return resolveExplicitM3uEpgUrl(trimmed, playlistUrl)
}

internal fun resolveM3uEpgUrls(
    epgUrls: List<String>,
    playlistUrl: String,
    streamUrls: List<String>
): List<String> =
    buildList {
        epgUrls.forEach { epgUrl ->
            add(resolveExplicitM3uEpgUrl(epgUrl, playlistUrl))
        }
        add(buildXtreamEpgUrlFromM3uPlaylistUrl(playlistUrl))
        streamUrls.forEach { streamUrl ->
            add(buildXtreamEpgUrlFromStreamUrl(streamUrl))
        }
    }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

private fun String.splitEpgSourceUrls(): List<String> =
    lineSequence()
        .flatMap { line -> line.split(',', ';').asSequence() }
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()

private fun resolveExplicitM3uEpgUrl(epgUrl: String, playlistUrl: String): String {
    val trimmed = epgUrl.trim()
    if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return trimmed
    }

    return playlistUrl
        .toHttpUrlOrNull()
        ?.resolve(trimmed)
        ?.toString()
        ?: trimmed
}

internal fun buildXtreamEpgUrlFromM3uPlaylistUrl(playlistUrl: String): String {
    val httpUrl = playlistUrl.toHttpUrlOrNull() ?: return ""
    val username = httpUrl.queryParameter("username")?.takeIf(String::isNotBlank)
        ?: httpUrl.queryParameter("user")?.takeIf(String::isNotBlank) ?: return ""
    val password = httpUrl.queryParameter("password")?.takeIf(String::isNotBlank)
        ?: httpUrl.queryParameter("pass")?.takeIf(String::isNotBlank) ?: return ""

    return httpUrl.newBuilder()
        .encodedPath("/xmltv.php")
        .query(null)
        .addQueryParameter("username", username)
        .addQueryParameter("password", password)
        .build()
        .toString()
}

internal fun buildXtreamEpgUrlFromStreamUrl(streamUrl: String): String {
    val httpUrl = parsePlaybackSource(streamUrl).url.toHttpUrlOrNull() ?: return ""
    var username = httpUrl.queryParameter("username")?.takeIf(String::isNotBlank)
        ?: httpUrl.queryParameter("user")?.takeIf(String::isNotBlank)
    var password = httpUrl.queryParameter("password")?.takeIf(String::isNotBlank)
        ?: httpUrl.queryParameter("pass")?.takeIf(String::isNotBlank)

    if (username.isNullOrBlank() || password.isNullOrBlank()) {
        val pathSegments = httpUrl.pathSegments
        val credentialsStartIndex = pathSegments.indexOfFirst { segment ->
            segment.equals("live", ignoreCase = true) ||
                segment.equals("movie", ignoreCase = true) ||
                segment.equals("series", ignoreCase = true)
        } + 1
        if (credentialsStartIndex <= 0) return ""

        username = pathSegments.getOrNull(credentialsStartIndex)?.takeIf(String::isNotBlank) ?: return ""
        password = pathSegments.getOrNull(credentialsStartIndex + 1)?.takeIf(String::isNotBlank) ?: return ""
    }

    return httpUrl.newBuilder()
        .encodedPath("/xmltv.php")
        .query(null)
        .addQueryParameter("username", username)
        .addQueryParameter("password", password)
        .build()
        .toString()
}

internal data class XtreamCredentials(
    val serverUrl: String,
    val username: String,
    val password: String
)

internal fun resolveXtreamCredentials(
    playlist: Playlist,
    streamUrls: List<String>
): XtreamCredentials? {
    if (
        playlist.type == PlaylistType.XTREAM_CODES &&
        playlist.serverUrl.isNotBlank() &&
        playlist.username.isNotBlank() &&
        playlist.password.isNotBlank()
    ) {
        return XtreamCredentials(
            serverUrl = normalizeXtreamServerUrl(playlist.serverUrl),
            username = playlist.username,
            password = playlist.password
        )
    }

    buildXtreamCredentialsFromM3uPlaylistUrl(playlist.url)?.let { return it }
    return streamUrls.asSequence()
        .mapNotNull(::buildXtreamCredentialsFromStreamUrl)
        .firstOrNull()
}

internal fun buildXtreamCredentialsFromM3uPlaylistUrl(playlistUrl: String): XtreamCredentials? {
    val httpUrl = playlistUrl.toHttpUrlOrNull() ?: return null
    val username = httpUrl.queryParameter("username")?.takeIf(String::isNotBlank)
        ?: httpUrl.queryParameter("user")?.takeIf(String::isNotBlank) ?: return null
    val password = httpUrl.queryParameter("password")?.takeIf(String::isNotBlank)
        ?: httpUrl.queryParameter("pass")?.takeIf(String::isNotBlank) ?: return null

    return XtreamCredentials(
        serverUrl = httpUrl.toXtreamServerUrl(),
        username = username,
        password = password
    )
}

internal fun buildXtreamCredentialsFromStreamUrl(streamUrl: String): XtreamCredentials? {
    val httpUrl = parsePlaybackSource(streamUrl).url.toHttpUrlOrNull() ?: return null
    var username = httpUrl.queryParameter("username")?.takeIf(String::isNotBlank)
        ?: httpUrl.queryParameter("user")?.takeIf(String::isNotBlank)
    var password = httpUrl.queryParameter("password")?.takeIf(String::isNotBlank)
        ?: httpUrl.queryParameter("pass")?.takeIf(String::isNotBlank)

    if (username.isNullOrBlank() || password.isNullOrBlank()) {
        val pathSegments = httpUrl.pathSegments
        val credentialsStartIndex = pathSegments.indexOfFirst { segment ->
            segment.equals("live", ignoreCase = true) ||
                segment.equals("movie", ignoreCase = true) ||
                segment.equals("series", ignoreCase = true)
        } + 1
        if (credentialsStartIndex <= 0) return null

        username = pathSegments.getOrNull(credentialsStartIndex)?.takeIf(String::isNotBlank) ?: return null
        password = pathSegments.getOrNull(credentialsStartIndex + 1)?.takeIf(String::isNotBlank) ?: return null
    }

    return XtreamCredentials(
        serverUrl = httpUrl.toXtreamServerUrl(),
        username = username,
        password = password
    )
}

private fun buildXtreamEpgUrl(playlist: Playlist): String {
    if (playlist.serverUrl.isBlank() || playlist.username.isBlank() || playlist.password.isBlank()) {
        return ""
    }

    val base = playlist.serverUrl.trimEnd('/')
    return base.toHttpUrlOrNull()
        ?.newBuilder()
        ?.addPathSegment("xmltv.php")
        ?.addQueryParameter("username", playlist.username)
        ?.addQueryParameter("password", playlist.password)
        ?.build()
        ?.toString()
        ?: "$base/xmltv.php?username=${playlist.username}&password=${playlist.password}"
}

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, cancelledResponse, _ ->
                    cancelledResponse.close()
                }
            }
        }
    )
}

private enum class EpgDiscoveryType {
    M3U,
    XTREAM
}

private data class EpgDiscoverySeed(
    val type: EpgDiscoveryType,
    val epgUrls: List<String> = emptyList()
)

private fun Playlist.normalizedForSync(): Playlist {
    return when (type) {
        PlaylistType.M3U_URL -> copy(url = normalizeHttpUrl(url))
        PlaylistType.XTREAM_CODES -> copy(serverUrl = normalizeXtreamServerUrl(serverUrl))
        PlaylistType.M3U_FILE -> this
    }
}

private fun normalizeHttpUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return trimmed
    return if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        trimmed
    } else {
        "http://$trimmed"
    }
}

private fun normalizeXtreamServerUrl(value: String): String {
    val normalized = normalizeHttpUrl(value).trimEnd('/')
    return if (normalized.endsWith("/player_api.php", ignoreCase = true)) {
        normalized.dropLast("/player_api.php".length)
    } else {
        normalized
    }
}

private fun okhttp3.HttpUrl.toXtreamServerUrl(): String =
    newBuilder()
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')

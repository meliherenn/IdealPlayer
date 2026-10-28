package com.idealplayer.app.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock

import com.idealplayer.app.core.common.SearchMatcher
import com.idealplayer.app.core.common.StringUtils
import com.idealplayer.app.core.common.isUsableArtworkUrl
import com.idealplayer.app.core.common.orderCategoryNames
import com.idealplayer.app.core.database.*
import com.idealplayer.app.core.common.rethrowIfCancellation
import com.idealplayer.app.core.model.*
import com.idealplayer.app.data.parser.XtreamClient
import com.idealplayer.app.data.parser.XtreamVodMetadata
import com.idealplayer.app.metadata.MetadataProvider
import com.idealplayer.app.metadata.MetadataResult
import com.idealplayer.app.core.security.SecretEnvelopeCodec
import com.idealplayer.app.ui.catalog.CatalogSortOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

private const val SEARCH_CANDIDATE_LIMIT = 360
private const val SEARCH_FULL_SCAN_FALLBACK_MIN_LENGTH = 3

private data class SearchQueryParts(
    val sqlQuery: String,
    val token: String,
    val normalizedQuery: String
)

@Singleton
class ContentRepository @Inject constructor(
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val watchHistoryDao: WatchHistoryDao,
    private val favoriteDao: FavoriteDao,
    private val playlistDao: PlaylistDao,
    private val xtreamClient: XtreamClient,
    private val metadataProvider: MetadataProvider,
    private val secretCodec: SecretEnvelopeCodec,
    private val access: ContentAccessRepository,
    private val database: IdealPlayerDatabase
) {
    private val episodeSyncMutexes = ConcurrentHashMap<Long, Mutex>()
    private val movieArtworkRepairMutex = Mutex()
    private val movieArtworkRepairIds = ConcurrentHashMap.newKeySet<Long>()
    private val movieArtworkAttemptedIds = ConcurrentHashMap.newKeySet<Long>()
    private val seriesArtworkRepairMutex = Mutex()
    private val seriesArtworkRepairIds = ConcurrentHashMap.newKeySet<Long>()
    private val seriesArtworkAttemptedIds = ConcurrentHashMap.newKeySet<Long>()

    val accessChanges = access.changes
    val catalogAccessPolicy = access.policy

    /** Home rows apply permissions before SQL LIMIT; never materialize the full catalog. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getMoviePreview(playlistId: Long, limit: Int = 50): Flow<List<Movie>> = combine(
        database.invalidationTracker.createFlow("movies"), access.policy
    ) { _, _ -> Unit }.mapLatest {
        movieDao.browse(catalogQuery(CatalogTable.MOVIES, playlistId, null,
            permittedCategories(CatalogTable.MOVIES, playlistId), limit = limit))
            .map { it.toCatalogModel() }
    }.flowOn(Dispatchers.IO)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getSeriesPreview(playlistId: Long, limit: Int = 50): Flow<List<Series>> = combine(
        database.invalidationTracker.createFlow("series"), access.policy
    ) { _, _ -> Unit }.mapLatest {
        seriesDao.browse(catalogQuery(CatalogTable.SERIES, playlistId, null,
            permittedCategories(CatalogTable.SERIES, playlistId), limit = limit))
            .map { it.toModel() }
    }.flowOn(Dispatchers.IO)

    fun observeContentAccess(contentId: Long, type: ContentType): Flow<Boolean> =
        access.changes.map { policy -> access.allows(policy, contentId, type) }.distinctUntilChanged()

    suspend fun canPlay(contentId: Long, contentType: String): Boolean {
        val type = runCatching { ContentType.valueOf(contentType.uppercase(java.util.Locale.ROOT)) }.getOrNull()
            ?: return false
        return access.allows(contentId, type, episode = type == ContentType.SERIES)
    }

    // Administration shows actual provider category names even while browsing is restricted.
    fun getParentalCategories(playlistId: Long): Flow<List<String>> = combine(
        channelDao.getGroups(playlistId), movieDao.getCategories(playlistId), seriesDao.getCategories(playlistId)
    ) { live, vod, shows -> orderCategoryNames((live + vod + shows).filter(String::isNotBlank).distinct()) }

    private suspend fun permittedCategories(table: CatalogTable, playlistId: Long): List<String>? {
        val policy = access.snapshot()
        if (!policy.ready) return emptyList()
        if (!policy.enabled) return null
        val categories = when (table) {
            CatalogTable.CHANNELS -> channelDao.getGroups(playlistId).first()
            CatalogTable.MOVIES -> movieDao.getCategories(playlistId).first()
            CatalogTable.SERIES -> seriesDao.getCategories(playlistId).first()
        }
        // Normalize with the same Unicode policy as playback, not SQLite's ASCII LOWER().
        return categories.filter(policy::allows)
    }

    // Channels
    fun getChannels(playlistId: Long): Flow<List<Channel>> =
        access.filter(channelDao.getByPlaylist(playlistId)) { it.groupTitle }.map { list -> list.map { it.toCatalogModel() } }.flowOn(Dispatchers.IO)

    fun getRecentlyWatchedChannels(playlistId: Long): Flow<List<Channel>> =
        access.filter(channelDao.getRecentlyWatched(playlistId)) { it.groupTitle }.map { list -> list.map { it.toCatalogModel() } }.flowOn(Dispatchers.IO)

    fun getChannelsByGroup(playlistId: Long, group: String): Flow<List<Channel>> =
        access.filter(channelDao.getByGroup(playlistId, group)) { it.groupTitle }.map { list -> list.map { it.toCatalogModel() } }.flowOn(Dispatchers.IO)

    fun getChannelGroups(playlistId: Long): Flow<List<String>> =
        access.filter(channelDao.getGroups(playlistId)) { it }.map { orderCategoryNames(it) }

    fun getFavoriteChannels(playlistId: Long): Flow<List<Channel>> =
        access.filter(channelDao.getFavorites(playlistId)) { it.groupTitle }.map { list -> list.map { it.toCatalogModel() } }.flowOn(Dispatchers.IO)

    suspend fun getChannel(id: Long): Channel? = withContext(Dispatchers.IO) {
        channelDao.getById(id)?.takeIf { access.allowsCategory(it.groupTitle) }?.toCatalogModel()
    }

    suspend fun toggleChannelFavorite(id: Long, favorite: Boolean) = withContext(Dispatchers.IO) {
        if (access.allows(id, ContentType.LIVE)) channelDao.setFavorite(id, favorite)
    }

    suspend fun updateChannelLastWatched(id: Long) = withContext(Dispatchers.IO) {
        if (access.allows(id, ContentType.LIVE)) channelDao.updateLastWatched(id)
    }

    suspend fun clearRecentlyWatchedChannels() = channelDao.clearWatchState()

    fun getChannelGroupCounts(playlistId: Long): Flow<Map<String, Int>> =
        access.filter(channelDao.getGroupCounts(playlistId)) { it.groupTitle }
            .map { list -> list.associate { it.groupTitle to it.count } }
            .flowOn(Dispatchers.IO)

    suspend fun getChannelsPaged(
        playlistId: Long,
        groupTitle: String?,
        limit: Int,
        offset: Int, query: String = ""
    ): List<Channel> = getChannelsPage(playlistId, groupTitle, limit, offset, query).items

    suspend fun getChannelsPage(
        playlistId: Long,
        groupTitle: String?,
        limit: Int,
        offset: Int, query: String = ""
    ): CatalogPage<Channel> = withContext(Dispatchers.IO) {
        val allowed = permittedCategories(CatalogTable.CHANNELS, playlistId)
        database.withTransaction {
            val total = channelDao.browseCount(catalogQuery(
                CatalogTable.CHANNELS, playlistId, groupTitle, allowed, query = query, count = true
            ))
            val rows = channelDao.browse(catalogQuery(
                CatalogTable.CHANNELS, playlistId, groupTitle, allowed, sort = 0,
                query = query, limit = limit, offset = offset
            ))
            CatalogPage(rows.map { it.toCatalogModel() }, total)
        }
    }

    suspend fun getChannelFilteredCount(playlistId: Long, groupTitle: String?): Int = withContext(Dispatchers.IO) {
        channelDao.browseCount(catalogQuery(
            CatalogTable.CHANNELS, playlistId, groupTitle, permittedCategories(CatalogTable.CHANNELS, playlistId), count = true
        ))
    }

    // Movies
    fun getMovies(playlistId: Long): Flow<List<Movie>> =
        access.filter(movieDao.getByPlaylist(playlistId)) { it.categoryName }.map { list -> list.map { it.toCatalogModel() } }.flowOn(Dispatchers.IO)

    fun getMoviesByCategory(playlistId: Long, category: String): Flow<List<Movie>> =
        access.filter(movieDao.getByCategory(playlistId, category)) { it.categoryName }.map { list -> list.map { it.toCatalogModel() } }.flowOn(Dispatchers.IO)

    fun getMovieCategories(playlistId: Long): Flow<List<String>> =
        access.filter(movieDao.getCategories(playlistId)) { it }.map { orderCategoryNames(it) }

    fun getFavoriteMovies(playlistId: Long): Flow<List<Movie>> =
        access.filter(movieDao.getFavorites(playlistId)) { it.categoryName }.map { list -> list.map { it.toCatalogModel() } }.flowOn(Dispatchers.IO)

    fun getRecentMovies(playlistId: Long): Flow<List<Movie>> =
        access.filter(movieDao.getRecentlyWatched(playlistId)) { it.categoryName }.map { list -> list.map { it.toCatalogModel() } }.flowOn(Dispatchers.IO)

    fun getLatestAddedMovies(playlistId: Long, limit: Int = 30): Flow<List<Movie>> =
        access.filter(movieDao.getLatestAdded(playlistId, limit)) { it.categoryName }.map { list -> list.map { it.toCatalogModel() } }.flowOn(Dispatchers.IO)

    fun getContinueWatchingMovies(playlistId: Long): Flow<List<Movie>> =
        access.filter(movieDao.getContinueWatching(playlistId)) { it.categoryName }.map { list -> list.map { it.toCatalogModel() } }.flowOn(Dispatchers.IO)

    fun getSimilarMovies(playlistId: Long, category: String, excludeId: Long): Flow<List<Movie>> =
        access.filter(movieDao.getSimilar(playlistId, category, excludeId)) { it.categoryName }.map { list -> list.map { it.toCatalogModel() } }.flowOn(Dispatchers.IO)

    suspend fun getMovie(id: Long): Movie? = withContext(Dispatchers.IO) {
        movieDao.getById(id)?.takeIf { access.allowsCategory(it.categoryName) }?.toCatalogModel()
    }

    suspend fun toggleMovieFavorite(id: Long, favorite: Boolean) = withContext(Dispatchers.IO) {
        if (access.allows(id, ContentType.MOVIE)) movieDao.setFavorite(id, favorite)
    }

    suspend fun updateMovieProgress(id: Long, position: Long, total: Long) = withContext(Dispatchers.IO) {
        if (access.allows(id, ContentType.MOVIE)) movieDao.updateProgress(id, position, total)
    }

    fun getMovieCategoryCounts(playlistId: Long): Flow<Map<String, Int>> =
        access.filter(movieDao.getCategoryCounts(playlistId)) { it.categoryName }
            .map { list -> list.associate { it.categoryName to it.count } }
            .flowOn(Dispatchers.IO)

    suspend fun getMoviesPaged(
        playlistId: Long,
        category: String?,
        sortOption: CatalogSortOption,
        limit: Int,
        offset: Int
    ): List<Movie> = getMoviesPage(playlistId, category, sortOption, limit, offset).items

    suspend fun getMoviesPage(
        playlistId: Long,
        category: String?,
        sortOption: CatalogSortOption,
        limit: Int,
        offset: Int
    ): CatalogPage<Movie> = withContext(Dispatchers.IO) {
        val allowed = permittedCategories(CatalogTable.MOVIES, playlistId)
        database.withTransaction {
            val total = movieDao.browseCount(catalogQuery(
                CatalogTable.MOVIES, playlistId, category, allowed, query = "", count = true
            ))
            val rows = movieDao.browse(catalogQuery(
                CatalogTable.MOVIES, playlistId, category, allowed, sort = sortOption.ordinal,
                query = "", limit = limit, offset = offset
            ))
            CatalogPage(rows.map { it.toCatalogModel() }, total)
        }
    }

    suspend fun getMovieFilteredCount(playlistId: Long, category: String?): Int = withContext(Dispatchers.IO) {
        movieDao.browseCount(catalogQuery(
            CatalogTable.MOVIES, playlistId, category, permittedCategories(CatalogTable.MOVIES, playlistId), count = true
        ))
    }

    suspend fun backfillMissingMovieArtwork(
        playlistId: Long,
        limit: Int = 24
    ): Int = withContext(Dispatchers.IO) {
        if (!movieArtworkRepairMutex.tryLock()) return@withContext 0
        try {
            val playlist = playlistDao.getById(playlistId)?.toModel(secretCodec) ?: return@withContext 0
            val candidates = mutableListOf<MovieEntity>()
            var afterId = 0L
            while (candidates.size < limit.coerceIn(1, 60)) {
                currentCoroutineContext().ensureActive()
                val page = movieDao.getRefreshPage(playlistId, afterId, 500)
                if (page.isEmpty()) break
                candidates += page.filterNot { isUsableArtworkUrl(it.posterUrl) }
                    .take(limit.coerceIn(1, 60) - candidates.size)
                afterId = page.last().id
            }
            if (candidates.isEmpty()) return@withContext 0

            val semaphore = Semaphore(3)
            coroutineScope {
                candidates.map { movie ->
                    async {
                        semaphore.withPermit {
                            try {
                                repairMovieArtwork(movie, playlist, replaceExistingArtwork = false)
                            } catch (error: Exception) {
                                error.rethrowIfCancellation()
                                Timber.w("Movie artwork repair failed for id=%d: %s", movie.id, error.javaClass.simpleName)
                                false
                            }
                        }
                    }
                }.awaitAll().count { repaired -> repaired }
            }
        } finally {
            movieArtworkRepairMutex.unlock()
        }
    }

    suspend fun repairMovieArtwork(movieId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!movieArtworkAttemptedIds.add(movieId)) return@withContext false
        val entity = movieDao.getById(movieId) ?: return@withContext false
        val playlist = playlistDao.getById(entity.playlistId)?.toModel(secretCodec) ?: return@withContext false
        repairMovieArtwork(entity, playlist, replaceExistingArtwork = true)
    }

    private suspend fun repairMovieArtwork(
        entity: MovieEntity,
        playlist: Playlist,
        replaceExistingArtwork: Boolean
    ): Boolean {
        if (!movieArtworkRepairIds.add(entity.id)) return false
        try {
            val movie = entity.toCatalogModel()
            val cached = metadataProvider.getCachedMetadata(movie.name, movie.year, ContentType.MOVIE)
            if (cached != null && cached.improves(movie, replaceExistingArtwork)) {
                updateMovieWithMetadata(movie.id, cached, replaceExistingArtwork)
                return true
            }

            var providerMetadataResult: MetadataResult? = null
            if (playlist.type == PlaylistType.XTREAM_CODES && movie.streamId > 0) {
                val providerMetadata = xtreamClient.loadVodMetadata(
                    serverUrl = playlist.serverUrl,
                    username = playlist.username,
                    password = playlist.password,
                    vodId = movie.streamId
                )
                if (providerMetadata != null) {
                    val providerResult = providerMetadata.toMetadataResult()
                    providerMetadataResult = providerResult
                    if (providerResult.improves(movie, replaceExistingArtwork)) {
                        updateMovieWithMetadata(movie.id, providerResult, replaceExistingArtwork)
                    }
                    if (providerResult.isCompleteCatalogMetadata() || isUsableArtworkUrl(providerResult.posterUrl)) return true
                }
            }

            val requestedTmdbId = providerMetadataResult?.tmdbId?.takeIf { it > 0 } ?: movie.tmdbId
            val fetched = metadataProvider.fetchMetadata(
                title = movie.name,
                year = movie.year,
                contentType = ContentType.MOVIE,
                tmdbId = requestedTmdbId
            )
            val identityCorrected = fetched != null && requestedTmdbId > 0 &&
                fetched.tmdbId > 0 && fetched.tmdbId != requestedTmdbId
            if (fetched != null && (fetched.improves(movie, replaceExistingArtwork) || identityCorrected)) {
                updateMovieWithMetadata(
                    movieId = movie.id,
                    metadata = fetched,
                    replaceExistingArtwork = replaceExistingArtwork || identityCorrected,
                    replaceExistingIdentity = identityCorrected
                )
                return true
            }
            if (replaceExistingArtwork && entity.posterUrl.isNotBlank()) {
                // Provider artwork failed to decode and TMDB had no replacement; clear the broken URL
                // so the card renders its clean placeholder instead of failing on every scroll.
                movieDao.insertAll(listOf(entity.copy(posterUrl = "")))
            }
            return providerMetadataResult?.improves(movie, replaceExistingArtwork) == true
        } finally {
            movieArtworkRepairIds.remove(entity.id)
        }
    }

    // Series
    fun getSeries(playlistId: Long): Flow<List<Series>> =
        access.filter(seriesDao.getByPlaylist(playlistId)) { it.categoryName }.map { list -> list.map { it.toModel() } }.flowOn(Dispatchers.IO)

    fun getSeriesByCategory(playlistId: Long, category: String): Flow<List<Series>> =
        access.filter(seriesDao.getByCategory(playlistId, category)) { it.categoryName }.map { list -> list.map { it.toModel() } }.flowOn(Dispatchers.IO)

    fun getSeriesCategories(playlistId: Long): Flow<List<String>> =
        access.filter(seriesDao.getCategories(playlistId)) { it }.map { orderCategoryNames(it) }

    fun getFavoriteSeries(playlistId: Long): Flow<List<Series>> =
        access.filter(seriesDao.getFavorites(playlistId)) { it.categoryName }.map { list -> list.map { it.toModel() } }.flowOn(Dispatchers.IO)

    fun getLatestAddedSeries(playlistId: Long, limit: Int = 30): Flow<List<Series>> =
        access.filter(seriesDao.getLatestAdded(playlistId, limit)) { it.categoryName }.map { list -> list.map { it.toModel() } }.flowOn(Dispatchers.IO)

    suspend fun getSeriesById(id: Long): Series? = withContext(Dispatchers.IO) {
        seriesDao.getById(id)?.takeIf { access.allowsCategory(it.categoryName) }?.toModel()
    }

    suspend fun toggleSeriesFavorite(id: Long, favorite: Boolean) = withContext(Dispatchers.IO) {
        if (access.allows(id, ContentType.SERIES)) seriesDao.setFavorite(id, favorite)
    }

    fun getSeriesCategoryCounts(playlistId: Long): Flow<Map<String, Int>> =
        access.filter(seriesDao.getCategoryCounts(playlistId)) { it.categoryName }
            .map { list -> list.associate { it.categoryName to it.count } }
            .flowOn(Dispatchers.IO)

    suspend fun getSeriesPaged(
        playlistId: Long,
        category: String?,
        sortOption: CatalogSortOption,
        limit: Int,
        offset: Int
    ): List<Series> = getSeriesPage(playlistId, category, sortOption, limit, offset).items

    suspend fun getSeriesPage(
        playlistId: Long,
        category: String?,
        sortOption: CatalogSortOption,
        limit: Int,
        offset: Int
    ): CatalogPage<Series> = withContext(Dispatchers.IO) {
        val allowed = permittedCategories(CatalogTable.SERIES, playlistId)
        database.withTransaction {
            val total = seriesDao.browseCount(catalogQuery(
                CatalogTable.SERIES, playlistId, category, allowed, query = "", count = true
            ))
            val rows = seriesDao.browse(catalogQuery(
                CatalogTable.SERIES, playlistId, category, allowed, sort = sortOption.ordinal,
                query = "", limit = limit, offset = offset
            ))
            CatalogPage(rows.map { it.toModel() }, total)
        }
    }

    suspend fun getSeriesFilteredCount(playlistId: Long, category: String?): Int = withContext(Dispatchers.IO) {
        seriesDao.browseCount(catalogQuery(
            CatalogTable.SERIES, playlistId, category, permittedCategories(CatalogTable.SERIES, playlistId), count = true
        ))
    }

    suspend fun backfillMissingSeriesArtwork(
        playlistId: Long,
        limit: Int = 24
    ): Int = withContext(Dispatchers.IO) {
        if (!seriesArtworkRepairMutex.tryLock()) return@withContext 0
        try {
            val playlist = playlistDao.getById(playlistId)?.toModel(secretCodec) ?: return@withContext 0
            val candidates = mutableListOf<SeriesEntity>()
            var afterId = 0L
            while (candidates.size < limit.coerceIn(1, 60)) {
                currentCoroutineContext().ensureActive()
                val page = seriesDao.getRefreshPage(playlistId, afterId, 500)
                if (page.isEmpty()) break
                candidates += page.filterNot { isUsableArtworkUrl(it.posterUrl) }
                    .take(limit.coerceIn(1, 60) - candidates.size)
                afterId = page.last().id
            }
            if (candidates.isEmpty()) return@withContext 0

            val semaphore = Semaphore(3)
            coroutineScope {
                candidates.map { series ->
                    async {
                        semaphore.withPermit {
                            repairSeriesMetadata(series, playlist, replaceExistingArtwork = false)
                        }
                    }
                }.awaitAll().count { repaired -> repaired }
            }
        } finally {
            seriesArtworkRepairMutex.unlock()
        }
    }

    suspend fun repairSeriesArtwork(seriesId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!seriesArtworkAttemptedIds.add(seriesId)) return@withContext false
        val entity = seriesDao.getById(seriesId) ?: return@withContext false
        val playlist = playlistDao.getById(entity.playlistId)?.toModel(secretCodec) ?: return@withContext false
        repairSeriesMetadata(entity, playlist, replaceExistingArtwork = true)
    }

    /**
     * Incrementally enriches the whole catalog without flooding provider/TMDB endpoints.
     * Callers persist the returned offsets so failed titles cannot permanently block later
     * rows in a large IPTV catalog.
     */
    suspend fun enrichIncompleteCatalogMetadata(
        playlistId: Long,
        movieOffset: Int,
        seriesOffset: Int,
        movieLimit: Int = 60,
        seriesLimit: Int = 40
    ): CatalogMetadataRefresh = withContext(Dispatchers.IO) {
        val playlist = playlistDao.getById(playlistId)?.toModel(secretCodec)
            ?: return@withContext CatalogMetadataRefresh()
        // Missing posters are immediately visible in every catalog grid, so rotate through
        // those rows before spending the foreground budget on secondary plot/cast metadata.
        // The cursor still advances inside the priority group, preventing an unmatchable title
        // from permanently blocking later artwork repairs.
        val movieSelection = selectMetadataPage(movieOffset, movieLimit.coerceIn(1, 60),
            { movieDao.getRefreshPage(playlistId, it, 500) }, MovieEntity::id, MovieEntity::needsCatalogMetadata,
            { !isUsableArtworkUrl(it.posterUrl) })
        val seriesSelection = selectMetadataPage(seriesOffset, seriesLimit.coerceIn(1, 40),
            { seriesDao.getRefreshPage(playlistId, it, 500) }, SeriesEntity::id, SeriesEntity::needsCatalogMetadata,
            { !isUsableArtworkUrl(it.posterUrl) })
        val selectedMovies = movieSelection.first
        val selectedSeries = seriesSelection.first
        val semaphore = Semaphore(2)

        val movieUpdates = coroutineScope {
            selectedMovies.map { movie ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            repairMovieArtwork(movie, playlist, replaceExistingArtwork = false)
                        }.getOrElse { error ->
                            error.rethrowIfCancellation()
                            Timber.w(
                                "Movie metadata enrichment failed for id=%d: %s",
                                movie.id,
                                error.javaClass.simpleName
                            )
                            false
                        }
                    }
                }
            }.awaitAll().count { it }
        }
        val seriesUpdates = coroutineScope {
            selectedSeries.map { series ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            repairSeriesMetadata(series, playlist, replaceExistingArtwork = false)
                        }.getOrElse { error ->
                            error.rethrowIfCancellation()
                            Timber.w(
                                "Series metadata enrichment failed for id=%d: %s",
                                series.id,
                                error.javaClass.simpleName
                            )
                            false
                        }
                    }
                }
            }.awaitAll().count { it }
        }

        CatalogMetadataRefresh(
            movieProcessed = selectedMovies.size,
            seriesProcessed = selectedSeries.size,
            updated = movieUpdates + seriesUpdates,
            movieRemaining = movieSelection.second,
            seriesRemaining = seriesSelection.second,
            nextMovieOffset = nextCircularOffset(movieOffset, selectedMovies.size, movieSelection.second),
            nextSeriesOffset = nextCircularOffset(seriesOffset, selectedSeries.size, seriesSelection.second)
        )
    }

    private suspend fun repairSeriesMetadata(
        entity: SeriesEntity,
        playlist: Playlist,
        replaceExistingArtwork: Boolean
    ): Boolean {
        if (!seriesArtworkRepairIds.add(entity.id)) return false
        try {
            val series = entity.toModel()
            val cached = metadataProvider.getCachedMetadata(
                series.name,
                series.year,
                ContentType.SERIES
            )
            if (cached != null && cached.improves(series, replaceExistingArtwork)) {
                updateSeriesWithMetadata(series.id, cached, replaceExistingArtwork)
                return true
            }

            var providerMetadataResult: MetadataResult? = null
            if (
                playlist.type == PlaylistType.XTREAM_CODES &&
                series.seriesId > 0 &&
                (!isUsableArtworkUrl(series.posterUrl) || !isUsableArtworkUrl(series.backdropUrl))
            ) {
                val providerMetadata = xtreamClient.loadSeriesMetadata(
                    serverUrl = playlist.serverUrl,
                    username = playlist.username,
                    password = playlist.password,
                    seriesId = series.seriesId
                )
                if (providerMetadata != null) {
                    val providerResult = providerMetadata.toMetadataResult()
                    providerMetadataResult = providerResult
                    if (providerResult.improves(series, replaceExistingArtwork)) {
                        updateSeriesWithMetadata(series.id, providerResult, replaceExistingArtwork)
                    }
                    if (providerResult.isCompleteCatalogMetadata() || isUsableArtworkUrl(providerResult.posterUrl)) return true
                }
            }
            val requestedTmdbId = providerMetadataResult?.tmdbId?.takeIf { it > 0 } ?: series.tmdbId
            val fetched = metadataProvider.fetchMetadata(
                title = series.name,
                year = series.year,
                contentType = ContentType.SERIES,
                tmdbId = requestedTmdbId
            )
            if (fetched == null) {
                if (replaceExistingArtwork && entity.posterUrl.isNotBlank()) {
                    seriesDao.insertAll(listOf(entity.copy(posterUrl = "")))
                }
                return providerMetadataResult?.improves(series, replaceExistingArtwork) == true
            }
            val identityCorrected = requestedTmdbId > 0 && fetched.tmdbId > 0 &&
                fetched.tmdbId != requestedTmdbId
            if (!fetched.improves(series, replaceExistingArtwork) && !identityCorrected) {
                if (replaceExistingArtwork && entity.posterUrl.isNotBlank()) {
                    seriesDao.insertAll(listOf(entity.copy(posterUrl = "")))
                }
                return false
            }
            updateSeriesWithMetadata(
                seriesId = series.id,
                metadata = fetched,
                replaceExistingArtwork = replaceExistingArtwork || identityCorrected,
                replaceExistingIdentity = identityCorrected
            )
            return true
        } finally {
            seriesArtworkRepairIds.remove(entity.id)
        }
    }

    // Episodes
    fun getEpisodes(seriesId: Long): Flow<List<Episode>> =
        combine(episodeDao.getBySeries(seriesId), access.changes) { list, policy ->
            if (access.allows(policy, seriesId, ContentType.SERIES)) list.map { it.toCatalogModel() } else emptyList()
        }.flowOn(Dispatchers.IO)

    fun getEpisodesBySeason(seriesId: Long, season: Int): Flow<List<Episode>> =
        getEpisodes(seriesId).map { list -> list.filter { it.seasonNumber == season } }

    fun getSeasons(seriesId: Long): Flow<List<Int>> =
        getEpisodes(seriesId).map { list -> list.map { it.seasonNumber }.distinct().sorted() }

    suspend fun getAllEpisodes(seriesId: Long): List<Episode> =
        getEpisodes(seriesId).first()

    suspend fun getEpisodesForSeason(seriesId: Long, season: Int): List<Episode> =
        getEpisodesBySeason(seriesId, season).first()

    suspend fun getEpisode(id: Long): Episode? = withContext(Dispatchers.IO) {
        episodeDao.getById(id)?.takeIf { access.allows(it.seriesId, ContentType.SERIES) }?.toCatalogModel()
    }

    suspend fun updateEpisodeProgress(id: Long, position: Long, total: Long) = withContext(Dispatchers.IO) {
        val episode = episodeDao.getById(id)
        if (episode != null && access.allows(episode.seriesId, ContentType.SERIES)) {
            episodeDao.updateProgress(id, position, total)
        }
    }

    suspend fun getLastWatchedEpisode(seriesId: Long): Episode? =
        episodeDao.getLastWatched(seriesId)?.takeIf { access.allows(seriesId, ContentType.SERIES) }?.toCatalogModel()

    suspend fun getNextEpisode(seriesId: Long, season: Int, episode: Int): Episode? {
        if (!access.allows(seriesId, ContentType.SERIES)) return null
        val next = episodeDao.getEpisode(seriesId, season, episode + 1)
        if (next != null) return next.toCatalogModel()
        return episodeDao.getEpisode(seriesId, season + 1, 1)?.toCatalogModel()
    }

    suspend fun getSeriesResumeEpisode(seriesId: Long): Episode? {
        if (!access.allows(seriesId, ContentType.SERIES)) return null
        val series = seriesDao.getById(seriesId)
        val lastWatchedEpisodeId = series?.lastWatchedEpisodeId ?: 0L
        if (lastWatchedEpisodeId > 0) {
            episodeDao.getById(lastWatchedEpisodeId)?.takeIf { it.seriesId == seriesId }
                ?.toCatalogModel()?.let { return it }
        }

        episodeDao.getLastWatched(seriesId)?.toCatalogModel()?.let { return it }
        return getAllEpisodes(seriesId).firstOrNull()
    }

    suspend fun updateSeriesLastWatchedEpisode(seriesId: Long, episodeId: Long) = withContext(Dispatchers.IO) {
        if (!access.allows(seriesId, ContentType.SERIES)) return@withContext
        val series = seriesDao.getById(seriesId) ?: return@withContext
        val episode = episodeDao.getById(episodeId)
        if (episode?.seriesId != seriesId || series.lastWatchedEpisodeId == episodeId) return@withContext
        seriesDao.insertAll(listOf(series.copy(lastWatchedEpisodeId = episodeId)))
    }

    /** Cached rows remain visible while stale data refreshes; explicit refresh bypasses the TTL. */
    suspend fun syncSeriesEpisodes(series: Series, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        episodeSyncMutexes.getOrPut(series.id) { Mutex() }.withLock {
            if (!access.allows(series.id, ContentType.SERIES)) return@withLock false
            val storedSeries = seriesDao.getById(series.id) ?: return@withLock false
            if (!episodesNeedRefresh(storedSeries.episodesRefreshedAt, System.currentTimeMillis(), force)) {
                return@withLock false
            }
            val source = playlistDao.getById(storedSeries.playlistId) ?: return@withLock false
            val owner = source.sourceIdentity()
            val playlist = source.toModel(secretCodec)
            if (playlist.type != PlaylistType.XTREAM_CODES || storedSeries.seriesId <= 0) return@withLock false
            val incoming = xtreamClient.loadSeriesEpisodes(
                serverUrl = playlist.serverUrl, username = playlist.username, password = playlist.password,
                seriesId = storedSeries.seriesId, dbSeriesId = storedSeries.id
            )
            currentCoroutineContext().ensureActive()
            database.withTransaction {
                val currentSeries = seriesDao.getById(storedSeries.id)
                if (playlistDao.getById(playlist.id)?.sourceIdentity() != owner ||
                    currentSeries?.playlistId != playlist.id || currentSeries.seriesId != storedSeries.seriesId
                ) return@withTransaction false
                val existing = episodeDao.getBySeriesSnapshot(storedSeries.id)
                val merged = mergeRefreshedEpisodes(incoming, existing)
                merged.chunked(500).forEach { chunk ->
                    currentCoroutineContext().ensureActive()
                    episodeDao.insertAll(chunk.encryptSecrets(secretCodec))
                }
                val persisted = episodeDao.getBySeriesSnapshot(storedSeries.id)
                seriesDao.markEpisodesRefreshed(storedSeries.id, System.currentTimeMillis(),
                    persisted.size, persisted.map { it.seasonNumber }.distinct().size)
                true
            }
        }
    }

    /**
     * Fetch metadata from TMDB for a movie or series with missing details.
     * Returns the MetadataResult if successful and confidence is sufficient, null otherwise.
     */
    suspend fun enrichMetadata(title: String, year: Int, contentType: ContentType, tmdbId: Int = 0): MetadataResult? {
        return withContext(Dispatchers.IO) {
            try {
                val result = metadataProvider.fetchMetadata(title, year, contentType, tmdbId)
                // MetadataProvider already handles confidence thresholds
                // A null return means no confident match was found
                result
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Timber.e("Metadata enrichment failed: %s", e.javaClass.simpleName)
                null
            }
        }
    }

    suspend fun getCachedMetadata(title: String, year: Int, contentType: ContentType): MetadataResult? {
        return withContext(Dispatchers.IO) {
            try {
                metadataProvider.getCachedMetadata(title, year, contentType)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Timber.e("Metadata cache lookup failed: %s", e.javaClass.simpleName)
                null
            }
        }
    }

    /**
     * Update a movie entity with enriched metadata from TMDB.
     * Prefers TMDB data when provider data is empty/generic.
     */
    suspend fun updateMovieWithMetadata(
        movieId: Long,
        metadata: MetadataResult,
        replaceExistingArtwork: Boolean = false,
        replaceExistingIdentity: Boolean = false
    ) {
        withContext(Dispatchers.IO) {
            val entity = movieDao.getById(movieId) ?: return@withContext
            val updated = entity.copy(
                plot = metadata.overview.ifBlank { entity.plot },
                cast = metadata.cast.ifBlank { entity.cast },
                director = metadata.director.ifBlank { entity.director },
                genre = metadata.genre.ifBlank { entity.genre },
                rating = if ((replaceExistingIdentity || entity.rating == 0.0) && metadata.rating > 0) metadata.rating else entity.rating,
                year = if ((replaceExistingIdentity || entity.year == 0) && metadata.year > 0) metadata.year else entity.year,
                posterUrl = if (
                    isUsableArtworkUrl(metadata.posterUrl) &&
                    (replaceExistingArtwork || !isUsableArtworkUrl(entity.posterUrl))
                ) metadata.posterUrl else entity.posterUrl,
                backdropUrl = if (
                    isUsableArtworkUrl(metadata.backdropUrl) &&
                    (replaceExistingArtwork || !isUsableArtworkUrl(entity.backdropUrl))
                ) metadata.backdropUrl else entity.backdropUrl,
                tmdbId = if ((replaceExistingIdentity || entity.tmdbId == 0) && metadata.tmdbId > 0) metadata.tmdbId else entity.tmdbId,
                imdbId = if (replaceExistingIdentity) {
                    metadata.imdbId
                } else if (entity.imdbId.isBlank() && metadata.imdbId.isNotBlank()) {
                    metadata.imdbId
                } else {
                    entity.imdbId
                },
                duration = if ((replaceExistingIdentity || entity.duration == 0) && metadata.runtime > 0) metadata.runtime else entity.duration
            )
            movieDao.insertAll(listOf(updated))
        }
    }

    /**
     * Update a series entity with enriched metadata from TMDB.
     */
    suspend fun updateSeriesWithMetadata(
        seriesId: Long,
        metadata: MetadataResult,
        replaceExistingArtwork: Boolean = false,
        replaceExistingIdentity: Boolean = false
    ) {
        withContext(Dispatchers.IO) {
            val entity = seriesDao.getById(seriesId) ?: return@withContext
            val updated = entity.copy(
                plot = metadata.overview.ifBlank { entity.plot },
                cast = metadata.cast.ifBlank { entity.cast },
                director = metadata.director.ifBlank { entity.director },
                genre = metadata.genre.ifBlank { entity.genre },
                rating = if ((replaceExistingIdentity || entity.rating == 0.0) && metadata.rating > 0) metadata.rating else entity.rating,
                year = if ((replaceExistingIdentity || entity.year == 0) && metadata.year > 0) metadata.year else entity.year,
                posterUrl = if (
                    isUsableArtworkUrl(metadata.posterUrl) &&
                    (replaceExistingArtwork || !isUsableArtworkUrl(entity.posterUrl))
                ) metadata.posterUrl else entity.posterUrl,
                backdropUrl = if (
                    isUsableArtworkUrl(metadata.backdropUrl) &&
                    (replaceExistingArtwork || !isUsableArtworkUrl(entity.backdropUrl))
                ) metadata.backdropUrl else entity.backdropUrl,
                tmdbId = if ((replaceExistingIdentity || entity.tmdbId == 0) && metadata.tmdbId > 0) metadata.tmdbId else entity.tmdbId,
                imdbId = if (replaceExistingIdentity) {
                    metadata.imdbId
                } else if (entity.imdbId.isBlank() && metadata.imdbId.isNotBlank()) {
                    metadata.imdbId
                } else {
                    entity.imdbId
                }
            )
            seriesDao.insertAll(listOf(updated))
        }
    }

    // Watch History
    fun getWatchHistory(): Flow<List<WatchHistoryItem>> =
        allowedHistory(watchHistoryDao.getRecent())

    fun getContinueWatching(playlistId: Long? = null): Flow<List<WatchHistoryItem>> =
        allowedHistory(watchHistoryDao.getContinueWatching(), playlistId).map(::dedupeContinueWatching)

    private fun allowedHistory(source: Flow<List<WatchHistoryEntity>>, playlistId: Long? = null): Flow<List<WatchHistoryItem>> =
        combine(source, access.changes) { list, policy ->
            list.filter {
                val type = runCatching { ContentType.valueOf(it.contentType) }.getOrNull()
                val owner = if (playlistId == null) null else when (type) {
                    ContentType.LIVE -> channelDao.getById(it.contentId)?.playlistId
                    ContentType.MOVIE -> movieDao.getById(it.contentId)?.playlistId
                    ContentType.SERIES -> episodeDao.getById(it.contentId)?.seriesId?.let { id -> seriesDao.getById(id)?.playlistId }
                    null -> null
                }
                (playlistId == null || owner == playlistId) && type != null &&
                    access.allows(policy, it.contentId, type, episode = type == ContentType.SERIES)
            }.map { it.toCatalogModel() }
        }.flowOn(Dispatchers.IO)

    suspend fun addWatchHistory(item: WatchHistoryItem) {
        if (!access.allows(item.contentId, item.contentType, episode = item.contentType == ContentType.SERIES)) return
        val existing = watchHistoryDao.getByContent(item.contentId, item.contentType.name)
        val entity = item.toSecureEntity(secretCodec).copy(id = existing?.id ?: 0)
        watchHistoryDao.insert(entity)
    }

    suspend fun clearWatchHistory() {
        withContext(Dispatchers.IO) {
            watchHistoryDao.deleteAll()
            movieDao.clearWatchProgress()
            episodeDao.clearWatchProgress()
            seriesDao.clearWatchProgress()
            channelDao.clearWatchState()
        }
    }

    suspend fun clearContinueWatchingHistory() {
        withContext(Dispatchers.IO) {
            watchHistoryDao.deleteAll()
        }
    }

    // Favorites
    fun getAllFavorites(): Flow<List<FavoriteEntity>> =
        combine(favoriteDao.getAll(), access.changes) { list, policy ->
            list.filter {
                val type = runCatching { ContentType.valueOf(it.contentType) }.getOrNull()
                type != null && access.allows(policy, it.contentId, type)
            }.map { it.copy(streamUrl = "") }
        }.flowOn(Dispatchers.IO)

    fun isFavorite(contentId: Long, type: ContentType): Flow<Boolean> =
        combine(favoriteDao.isFavorite(contentId, type.name), access.changes) { stored, policy ->
            stored && access.allows(policy, contentId, type)
        }.flowOn(Dispatchers.IO)

    suspend fun addFavorite(contentId: Long, type: ContentType, title: String, posterUrl: String, streamUrl: String) {
        if (!access.allows(contentId, type)) return
        favoriteDao.insert(
            FavoriteEntity(
                contentId = contentId,
                contentType = type.name,
                title = title,
                posterUrl = posterUrl,
                streamUrl = streamUrl
            ).encryptSecrets(secretCodec)
        )
    }

    suspend fun removeFavorite(contentId: Long, type: ContentType) {
        favoriteDao.delete(contentId, type.name)
    }

    // Search
    fun searchChannels(playlistId: Long, query: String): Flow<List<Channel>> =
        access.filter(channelDao.getByPlaylist(playlistId)) { it.groupTitle }
            .map { list ->
                SearchMatcher.rank(
                    query = query,
                    items = list,
                    primary = { it.name },
                    secondary = { listOf(it.groupTitle, it.epgChannelId) }
                ).map { it.toCatalogModel() }
            }
            .flowOn(Dispatchers.Default)

    suspend fun searchChannelsNow(
        playlistId: Long,
        query: String,
        allowFullScanFallback: Boolean = true
    ): List<Channel> =
        rankCandidateSearch(
            query = query,
            allowFullScanFallback = allowFullScanFallback,
            loadCandidates = { parts ->
                channelDao.searchCandidates(
                    playlistId = playlistId,
                    query = parts.sqlQuery,
                    token = parts.token,
                    limit = SEARCH_CANDIDATE_LIMIT
                )
            },
            loadFallback = { afterId -> channelDao.getRefreshPage(playlistId, afterId, 500) },
            fallbackId = ChannelEntity::id,
            category = ChannelEntity::groupTitle,
            primary = { it.name },
            secondary = { listOf(it.groupTitle, it.epgChannelId, it.streamId.takeIf { id -> id > 0 }?.toString().orEmpty()) },
            year = { 0 },
            mapper = { it.toCatalogModel() }
        ).let { items -> access.filter(items) { it.groupTitle } }

    fun searchMovies(playlistId: Long, query: String): Flow<List<Movie>> =
        access.filter(movieDao.getByPlaylist(playlistId)) { it.categoryName }
            .map { list ->
                SearchMatcher.rank(
                    query = query,
                    items = list,
                    primary = { it.name },
                    secondary = { listOf(it.categoryName, it.genre, it.releaseDate) },
                    year = { it.year }
                ).map { it.toCatalogModel() }
            }
            .flowOn(Dispatchers.Default)

    suspend fun searchMoviesNow(
        playlistId: Long,
        query: String,
        allowFullScanFallback: Boolean = true
    ): List<Movie> =
        rankCandidateSearch(
            query = query,
            allowFullScanFallback = allowFullScanFallback,
            loadCandidates = { parts ->
                movieDao.searchCandidates(
                    playlistId = playlistId,
                    query = parts.sqlQuery,
                    token = parts.token,
                    limit = SEARCH_CANDIDATE_LIMIT
                )
            },
            loadFallback = { afterId -> movieDao.getRefreshPage(playlistId, afterId, 500) },
            fallbackId = MovieEntity::id,
            category = MovieEntity::categoryName,
            primary = { it.name },
            secondary = {
                listOf(
                    it.categoryName,
                    it.genre,
                    it.releaseDate,
                    it.streamId.takeIf { id -> id > 0 }?.toString().orEmpty()
                )
            },
            year = { it.year },
            mapper = { it.toCatalogModel() }
        ).let { items -> access.filter(items) { it.categoryName } }

    fun searchSeries(playlistId: Long, query: String): Flow<List<Series>> =
        access.filter(seriesDao.getByPlaylist(playlistId)) { it.categoryName }
            .map { list ->
                SearchMatcher.rank(
                    query = query,
                    items = list,
                    primary = { it.name },
                    secondary = { listOf(it.categoryName, it.genre, it.releaseDate) },
                    year = { it.year }
                ).map { it.toModel() }
            }
            .flowOn(Dispatchers.Default)

    suspend fun searchSeriesNow(
        playlistId: Long,
        query: String,
        allowFullScanFallback: Boolean = true
    ): List<Series> =
        rankCandidateSearch(
            query = query,
            allowFullScanFallback = allowFullScanFallback,
            loadCandidates = { parts ->
                seriesDao.searchCandidates(
                    playlistId = playlistId,
                    query = parts.sqlQuery,
                    token = parts.token,
                    limit = SEARCH_CANDIDATE_LIMIT
                )
            },
            loadFallback = { afterId -> seriesDao.getRefreshPage(playlistId, afterId, 500) },
            fallbackId = SeriesEntity::id,
            category = SeriesEntity::categoryName,
            primary = { it.name },
            secondary = {
                listOf(
                    it.categoryName,
                    it.genre,
                    it.releaseDate,
                    it.seriesId.takeIf { id -> id > 0 }?.toString().orEmpty()
                )
            },
            year = { it.year },
            mapper = { it.toModel() }
        ).let { items -> access.filter(items) { it.categoryName } }

    private fun dedupeContinueWatching(items: List<WatchHistoryItem>): List<WatchHistoryItem> {
        val seenKeys = LinkedHashSet<String>()

        return items.filter { item ->
            val key = when (item.contentType) {
                ContentType.SERIES -> {
                    val seriesKey = item.seriesName.ifBlank { item.title }
                    "SERIES:${seriesKey.lowercase()}"
                }
                ContentType.MOVIE -> "MOVIE:${item.contentId}"
                ContentType.LIVE -> "LIVE:${item.contentId}"
            }

            seenKeys.add(key)
        }
    }

    private suspend fun <Entity, Model> rankCandidateSearch(
        query: String,
        allowFullScanFallback: Boolean,
        loadCandidates: suspend (SearchQueryParts) -> List<Entity>,
        loadFallback: suspend (Long) -> List<Entity>,
        fallbackId: (Entity) -> Long,
        category: (Entity) -> String,
        primary: (Entity) -> String,
        secondary: (Entity) -> List<String>,
        year: (Entity) -> Int,
        mapper: (Entity) -> Model
    ): List<Model> {
        val parts = query.toSearchQueryParts() ?: return emptyList()
        val policy = access.snapshot()
        val candidates = withContext(Dispatchers.IO) { loadCandidates(parts) }.filter { policy.allows(category(it)) }
        val rankedCandidates = rankSearchItems(
            items = candidates,
            query = query,
            primary = primary,
            secondary = secondary,
            year = year,
            mapper = mapper
        )

        if (
            rankedCandidates.isNotEmpty() ||
            !allowFullScanFallback ||
            parts.normalizedQuery.length < SEARCH_FULL_SCAN_FALLBACK_MIN_LENGTH
        ) {
            return rankedCandidates
        }

        return withContext(Dispatchers.Default) {
            var afterId = 0L
            var best = emptyList<Entity>()
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = withContext(Dispatchers.IO) { loadFallback(afterId) }
                if (page.isEmpty()) break
                best = SearchMatcher.rank(query = query, items = best + page.filter { policy.allows(category(it)) },
                    primary = primary, secondary = secondary, year = year)
                afterId = fallbackId(page.last())
            }
            best.map(mapper)
        }
    }

    private suspend fun <Entity, Model> rankSearchItems(
        items: List<Entity>,
        query: String,
        primary: (Entity) -> String,
        secondary: (Entity) -> List<String>,
        year: (Entity) -> Int,
        mapper: (Entity) -> Model
    ): List<Model> {
        return withContext(Dispatchers.Default) {
            SearchMatcher.rank(
                query = query,
                items = items,
                primary = primary,
                secondary = secondary,
                year = year
            ).map(mapper)
        }
    }

    /** Two keyset scans keep the rotating priority queue bounded to a page plus the work limit. */
    private suspend fun <T> selectMetadataPage(
        offset: Int, limit: Int, load: suspend (Long) -> List<T>, id: (T) -> Long,
        eligible: (T) -> Boolean, priority: (T) -> Boolean
    ): Pair<List<T>, Int> = database.withTransaction {
        suspend fun scan(visit: (T) -> Unit) {
            var afterId = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = load(afterId)
                if (page.isEmpty()) break
                page.filter(eligible).forEach(visit)
                afterId = id(page.last())
            }
        }
        var priorityCount = 0
        var otherCount = 0
        scan { if (priority(it)) priorityCount++ else otherCount++ }
        val priorityTake = minOf(priorityCount, limit)
        val otherTake = minOf(otherCount, limit - priorityTake)
        val selected = sortedMapOf<Int, T>()
        var priorityIndex = 0
        var otherIndex = 0
        scan { row ->
            if (priority(row)) {
                val position = (priorityIndex++ - offset.mod(priorityCount)).mod(priorityCount)
                if (position < priorityTake) selected[position] = row
            } else {
                val position = (otherIndex++ - offset.mod(otherCount)).mod(otherCount)
                if (position < otherTake) selected[priorityTake + position] = row
            }
        }
        selected.values.toList() to (priorityCount + otherCount)
    }

    private fun String.toSearchQueryParts(): SearchQueryParts? {
        val trimmed = trim()
        val normalized = StringUtils.normalizeTitle(trimmed)
        if (normalized.isBlank()) return null

        val terms = normalized
            .split(" ")
            .filter { it.length >= 2 }
        val token = terms.maxByOrNull { it.length } ?: normalized.takeIf { it.length >= 2 }.orEmpty()
        val sqlQuery = trimmed
            .replace('%', ' ')
            .replace('_', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")
            .ifBlank { normalized }

        return SearchQueryParts(
            sqlQuery = sqlQuery,
            token = token,
            normalizedQuery = normalized
        )
    }
}

private fun XtreamVodMetadata.toMetadataResult(): MetadataResult = MetadataResult(
    tmdbId = tmdbId,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    overview = plot,
    genre = genre,
    cast = cast,
    director = director,
    runtime = duration,
    rating = rating,
    year = StringUtils.extractYear(releaseDate) ?: 0,
    confidence = 100.0
)

data class CatalogMetadataRefresh(
    val movieProcessed: Int = 0,
    val seriesProcessed: Int = 0,
    val updated: Int = 0,
    val movieRemaining: Int = 0,
    val seriesRemaining: Int = 0,
    val nextMovieOffset: Int = 0,
    val nextSeriesOffset: Int = 0
)

internal fun MovieEntity.needsCatalogMetadata(): Boolean =
    !isUsableArtworkUrl(posterUrl) ||
        !isUsableArtworkUrl(backdropUrl) ||
        plot.isBlank() ||
        genre.isBlank() ||
        year <= 0 ||
        tmdbId <= 0

internal fun SeriesEntity.needsCatalogMetadata(): Boolean =
    !isUsableArtworkUrl(posterUrl) ||
        !isUsableArtworkUrl(backdropUrl) ||
        plot.isBlank() ||
        genre.isBlank() ||
        year <= 0 ||
        tmdbId <= 0

internal fun nextCircularOffset(currentOffset: Int, processed: Int, total: Int): Int {
    if (total <= 0 || processed <= 0) return 0
    return (currentOffset.mod(total) + processed).mod(total)
}

private fun <T> List<T>.circularSlice(offset: Int, limit: Int): List<T> {
    if (isEmpty() || limit <= 0) return emptyList()
    val start = offset.mod(size)
    return List(minOf(limit, size)) { index -> this[(start + index).mod(size)] }
}

private fun <T> List<T>.prioritizedCircularSlice(
    offset: Int,
    limit: Int,
    isPriority: (T) -> Boolean
): List<T> {
    if (isEmpty() || limit <= 0) return emptyList()
    val priority = filter(isPriority)
    val selectedPriority = priority.circularSlice(offset, limit)
    if (selectedPriority.size >= limit) return selectedPriority

    val fallback = filterNot(isPriority)
    return selectedPriority + fallback.circularSlice(offset, limit - selectedPriority.size)
}

private fun MetadataResult.hasReplacementArtwork(
    currentPoster: String,
    currentBackdrop: String,
    replaceExistingArtwork: Boolean
): Boolean {
    fun isReplacement(candidateUrl: String, currentUrl: String): Boolean =
        isUsableArtworkUrl(candidateUrl) &&
            (!isUsableArtworkUrl(currentUrl) ||
                (replaceExistingArtwork && !candidateUrl.equals(currentUrl, ignoreCase = true)))

    return isReplacement(posterUrl, currentPoster) ||
        isReplacement(backdropUrl, currentBackdrop)
}

private fun MetadataResult.improves(movie: Movie, replaceExistingArtwork: Boolean): Boolean =
    hasReplacementArtwork(movie.posterUrl, movie.backdropUrl, replaceExistingArtwork) ||
        (movie.plot.isBlank() && overview.isNotBlank()) ||
        (movie.genre.isBlank() && genre.isNotBlank()) ||
        (movie.cast.isBlank() && cast.isNotBlank()) ||
        (movie.director.isBlank() && director.isNotBlank()) ||
        (movie.rating <= 0.0 && rating > 0.0) ||
        (movie.year <= 0 && year > 0) ||
        (movie.duration <= 0 && runtime > 0) ||
        (movie.tmdbId <= 0 && tmdbId > 0) ||
        (movie.imdbId.isBlank() && imdbId.isNotBlank())

private fun MetadataResult.improves(series: Series, replaceExistingArtwork: Boolean): Boolean =
    hasReplacementArtwork(series.posterUrl, series.backdropUrl, replaceExistingArtwork) ||
        (series.plot.isBlank() && overview.isNotBlank()) ||
        (series.genre.isBlank() && genre.isNotBlank()) ||
        (series.cast.isBlank() && cast.isNotBlank()) ||
        (series.director.isBlank() && director.isNotBlank()) ||
        (series.rating <= 0.0 && rating > 0.0) ||
        (series.year <= 0 && year > 0) ||
        (series.tmdbId <= 0 && tmdbId > 0) ||
        (series.imdbId.isBlank() && imdbId.isNotBlank())

private fun MetadataResult.isCompleteCatalogMetadata(): Boolean =
    isUsableArtworkUrl(posterUrl) &&
        isUsableArtworkUrl(backdropUrl) &&
        overview.isNotBlank() &&
        genre.isNotBlank() &&
        year > 0 &&
        tmdbId > 0

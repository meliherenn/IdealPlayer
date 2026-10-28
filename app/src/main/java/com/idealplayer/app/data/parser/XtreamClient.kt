package com.idealplayer.app.data.parser

import com.idealplayer.app.core.common.Constants
import com.idealplayer.app.core.common.buildXtreamPathUrl
import com.idealplayer.app.core.common.normalizeRemoteArtworkUrl
import com.idealplayer.app.core.common.rethrowIfCancellation
import com.idealplayer.app.core.database.*
import com.idealplayer.app.core.model.Channel
import com.idealplayer.app.core.model.ContentType
import com.idealplayer.app.core.network.XtreamApi
import com.idealplayer.app.core.network.dto.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import retrofit2.HttpException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XtreamClient @Inject constructor(
    private val api: XtreamApi
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun buildApiUrl(serverUrl: String): String {
        return buildXtreamPathUrl(serverUrl, Constants.XTREAM_API_PATH.trim('/'))
    }

    private fun buildStreamUrl(
        serverUrl: String,
        pathSegment: String,
        username: String,
        password: String,
        streamId: String,
        extension: String? = null
    ): String {
        val normalizedExtension = extension
            ?.trim()
            ?.trimStart('.')
            ?.takeIf { it.isNotBlank() }
        val normalizedStreamId = streamId.trim()

        if (normalizedStreamId.isHttpStreamUrl()) {
            return normalizedStreamId
        }

        val fileName = if (
            normalizedExtension != null &&
            !normalizedStreamId.endsWith(".$normalizedExtension", ignoreCase = true)
        ) {
            "$normalizedStreamId.$normalizedExtension"
        } else {
            normalizedStreamId
        }
        return buildXtreamPathUrl(serverUrl, pathSegment, username, password, fileName)
    }

    suspend fun authenticate(serverUrl: String, username: String, password: String): Result<XtreamAuthResponse> {
        return try {
            val url = buildApiUrl(serverUrl)
            val response = api.authenticate(url, username, password)
            if (response.userInfo?.status?.trim().equals("Active", ignoreCase = true)) {
                Result.success(response)
            } else {
                Result.failure(Exception("Account not active: ${response.userInfo?.status}"))
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logRequestFailure("Xtream authentication", e)
            Result.failure(e)
        }
    }

    suspend fun loadContentStreaming(
        serverUrl: String,
        username: String,
        password: String,
        playlistId: Long,
        sink: XtreamStreamingSink,
        batchSize: Int = 500,
        cancellationCheck: () -> Unit = {}
    ): Set<XtreamContentSection> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        require(serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()) { "Xtream credentials are required" }
        require(batchSize in 1..500)
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val checkActive = { job?.ensureActive(); cancellationCheck() }
        val authentication = authenticate(serverUrl, username, password).getOrThrow()
        val extension = preferredLiveExtension(authentication.userInfo?.allowedOutputFormats.orEmpty())
        val url = buildApiUrl(serverUrl)
        val loaded = mutableSetOf<XtreamContentSection>()
        val sinkLock = Any()

        data class LoadedCategories(
            val names: Map<String, String>,
            val rows: List<CategoryEntity>
        )

        suspend fun loadCategoriesFor(
            type: ContentType,
            categoryAction: String,
            required: Boolean
        ): LoadedCategories? {
            val categories = if (required) api.getCatalog(url, username, password, categoryAction)
                else loadOptionalCatalogSection(categoryAction) { api.getCatalog(url, username, password, categoryAction) }
            if (categories == null) return null
            val names = LinkedHashMap<String, String>()
            val categoryRows = ArrayList<CategoryEntity>()
            json.readCatalogArray(categories, XtreamCategory.serializer(), checkActive) { category ->
                check(categoryRows.size < 10_000) { "Catalog exceeds the category limit" }
                names[category.categoryId] = category.categoryName
                categoryRows += CategoryEntity(
                    playlistId = playlistId, categoryId = category.categoryId.toIntOrNull() ?: 0,
                    name = category.categoryName, contentType = type.name, parentId = category.parentId
                )
            }
            return LoadedCategories(names, categoryRows)
        }

        suspend fun <T, R> streamSection(
            loadedCategories: LoadedCategories?,
            streamAction: String,
            serializer: kotlinx.serialization.DeserializationStrategy<T>,
            required: Boolean = false,
            convert: (T, Map<String, String>, Int) -> R,
            publish: (List<R>) -> Unit
        ): Boolean {
            if (loadedCategories == null) return false
            val streams = if (required) api.getCatalog(url, username, password, streamAction)
                else loadOptionalCatalogSection(streamAction) { api.getCatalog(url, username, password, streamAction) }
            if (streams == null) return false
            val batch = ArrayList<R>(batchSize)
            var index = 0
            json.readCatalogArray(streams, serializer, checkActive) { item ->
                batch += convert(item, loadedCategories.names, index++)
                if (batch.size == batchSize) {
                    val snapshot = batch.toList()
                    batch.clear()
                    synchronized(sinkLock) { publish(snapshot) }
                }
            }
            if (batch.isNotEmpty()) {
                val snapshot = batch.toList()
                batch.clear()
                synchronized(sinkLock) { publish(snapshot) }
            }
            // An unsupported stream endpoint must leave both its old content and categories intact.
            loadedCategories.rows.chunked(batchSize).forEach { chunk ->
                synchronized(sinkLock) { sink.onCategories(chunk) }
            }
            return true
        }

        // Phase 1: Fetch and parse Live, VOD, and Series category lists in parallel.
        val (liveCategories, vodCategories, seriesCategories) = coroutineScope {
            val liveDeferred = async { loadCategoriesFor(ContentType.LIVE, "get_live_categories", required = true) }
            val vodDeferred = async { loadCategoriesFor(ContentType.MOVIE, "get_vod_categories", required = false) }
            val seriesDeferred = async { loadCategoriesFor(ContentType.SERIES, "get_series_categories", required = false) }
            Triple(liveDeferred.await(), vodDeferred.await(), seriesDeferred.await())
        }

        // Phase 2: Stream and stage Live, VOD, and Series catalogs in parallel with serialized sink batches.
        val (liveLoaded, vodLoaded, seriesLoaded) = coroutineScope {
            val liveDeferred = async {
                streamSection(
                    loadedCategories = liveCategories,
                    streamAction = "get_live_streams",
                    serializer = XtreamLiveStream.serializer(),
                    required = true,
                    convert = { stream, names, index ->
                        ChannelEntity(
                            playlistId = playlistId, streamId = stream.streamId, name = stream.name,
                            logoUrl = normalizeRemoteArtworkUrl(serverUrl, stream.streamIcon),
                            groupTitle = names[stream.categoryId].orEmpty(),
                            streamUrl = buildStreamUrl(serverUrl, "live", username, password, stream.streamId.toString(), extension),
                            epgChannelId = stream.epgChannelId.orEmpty(), catchupSource = if (stream.tvArchive > 0) "xtream" else "",
                            sortOrder = index
                        )
                    },
                    publish = sink::onChannels
                )
            }
            val vodDeferred = async {
                streamSection(
                    loadedCategories = vodCategories,
                    streamAction = "get_vod_streams",
                    serializer = XtreamVodStream.serializer(),
                    required = false,
                    convert = { stream, names, index ->
                        MovieEntity(
                            playlistId = playlistId, streamId = stream.streamId, name = stream.name,
                            posterUrl = normalizeRemoteArtworkUrl(serverUrl, stream.streamIcon),
                            streamUrl = buildStreamUrl(serverUrl, "movie", username, password, stream.streamId.toString(), stream.containerExtension),
                            genre = stream.genre, plot = stream.plot, cast = stream.cast, director = stream.director,
                            releaseDate = stream.releaseDate, year = stream.year.toIntOrNull() ?: 0,
                            rating = stream.rating.toDoubleOrNull() ?: (stream.rating5based * 2),
                            containerExtension = stream.containerExtension, categoryId = stream.categoryId.toIntOrNull() ?: 0,
                            categoryName = names[stream.categoryId].orEmpty(), tmdbId = stream.tmdbId?.toIntOrNull() ?: 0,
                            duration = stream.episodeRunTime.toIntOrNull() ?: 0, sourceOrder = index
                        )
                    },
                    publish = sink::onMovies
                )
            }
            val seriesDeferred = async {
                streamSection(
                    loadedCategories = seriesCategories,
                    streamAction = "get_series",
                    serializer = XtreamSeriesStream.serializer(),
                    required = false,
                    convert = { stream, names, index ->
                        SeriesEntity(
                            playlistId = playlistId, seriesId = stream.seriesId, name = stream.name,
                            posterUrl = normalizeRemoteArtworkUrl(serverUrl, stream.cover),
                            backdropUrl = normalizeRemoteArtworkUrl(serverUrl, stream.backdropPath?.firstOrNull().orEmpty()),
                            genre = stream.genre, plot = stream.plot, cast = stream.cast, director = stream.director,
                            releaseDate = stream.releaseDate, year = stream.year.toIntOrNull() ?: 0,
                            rating = stream.rating.toDoubleOrNull() ?: (stream.rating5based * 2),
                            categoryId = stream.categoryId.toIntOrNull() ?: 0, categoryName = names[stream.categoryId].orEmpty(),
                            tmdbId = stream.tmdbId?.toIntOrNull() ?: 0, sourceOrder = index
                        )
                    },
                    publish = sink::onSeries
                )
            }
            Triple(liveDeferred.await(), vodDeferred.await(), seriesDeferred.await())
        }

        if (liveLoaded) loaded += XtreamContentSection.LIVE
        if (vodLoaded) loaded += XtreamContentSection.MOVIES
        if (seriesLoaded) loaded += XtreamContentSection.SERIES
        loaded
    }

    private fun preferredLiveExtension(allowedOutputFormats: List<String>): String {
        val normalized = allowedOutputFormats
            .map { it.trim().trimStart('.').lowercase() }
            .filter(String::isNotBlank)

        return when {
            "m3u8" in normalized -> "m3u8"
            "ts" in normalized -> "ts"
            else -> "m3u8"
        }
    }

    private suspend fun <T> loadOptionalCatalogSection(
        operation: String,
        request: suspend () -> T
    ): T? = try {
        request()
    } catch (error: HttpException) {
        if (error.code() in OPTIONAL_CATALOG_UNSUPPORTED_HTTP_CODES) {
            Timber.i("Xtream %s is unavailable (HTTP %d)", operation, error.code())
            null
        } else {
            throw error
        }
    }

    suspend fun loadSeriesEpisodes(
        serverUrl: String,
        username: String,
        password: String,
        seriesId: Int,
        dbSeriesId: Long
    ): List<EpisodeEntity> {
        return try {
            val url = buildApiUrl(serverUrl)
            val info = api.getSeriesInfo(url, username, password, seriesId = seriesId)
            check(info.episodes is JsonObject || info.episodes is JsonArray) { "Invalid episode document" }
            val episodes = mutableListOf<EpisodeEntity>()
            parseSeriesEpisodes(info.episodes).forEach { (seasonKey, episodeList) ->
                val seasonNum = seasonKey.toIntOrNull()
                    ?: Regex("""\d+""").find(seasonKey)?.value?.toIntOrNull()
                    ?: 1
                episodeList.forEachIndexed { index, episode ->
                    val streamId = episode.id.ifBlank { episode.episodeNum.toString() }
                    val episodeNumber = episode.episodeNum.takeIf { it > 0 } ?: (index + 1)
                    val streamUrl = episode.directSource
                        .trim()
                        .takeIf { it.isHttpStreamUrl() }
                        ?: buildStreamUrl(
                            serverUrl = serverUrl,
                            pathSegment = "series",
                            username = username,
                            password = password,
                            streamId = streamId,
                            extension = episode.containerExtension
                        )
                    episodes.add(
                        EpisodeEntity(
                            sourceEpisodeId = episode.id,
                            seriesId = dbSeriesId,
                            seasonNumber = seasonNum,
                            episodeNumber = episodeNumber,
                            name = episode.title.ifBlank { "Episode $episodeNumber" },
                            plot = episode.info?.plot ?: "",
                            posterUrl = episode.info?.movieImage ?: "",
                            streamUrl = streamUrl,
                            duration = episode.info?.durationSecs ?: 0,
                            rating = episode.info?.rating ?: 0.0,
                            containerExtension = episode.containerExtension
                        )
                    )
                }
            }
            episodes
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logRequestFailure("Series episode loading", e)
            throw e
        }
    }

    suspend fun loadVodMetadata(
        serverUrl: String,
        username: String,
        password: String,
        vodId: Int
    ): XtreamVodMetadata? {
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank() || vodId <= 0) return null
        return try {
            parseXtreamVodMetadata(
                element = api.getVodInfo(buildApiUrl(serverUrl), username, password, vodId = vodId),
                serverUrl = serverUrl
            )
        } catch (error: Exception) {
            error.rethrowIfCancellation()
            logRequestFailure("VOD metadata loading for stream $vodId", error)
            null
        }
    }

    suspend fun loadSeriesMetadata(
        serverUrl: String,
        username: String,
        password: String,
        seriesId: Int
    ): XtreamVodMetadata? {
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank() || seriesId <= 0) return null
        return try {
            val detail = api.getSeriesInfo(
                buildApiUrl(serverUrl),
                username,
                password,
                seriesId = seriesId
            ).info ?: return null
            XtreamVodMetadata(
                posterUrl = normalizeRemoteArtworkUrl(serverUrl, detail.cover),
                backdropUrl = normalizeRemoteArtworkUrl(
                    serverUrl,
                    detail.backdropPath?.firstOrNull().orEmpty()
                ),
                plot = detail.plot,
                cast = detail.cast,
                director = detail.director,
                genre = detail.genre,
                releaseDate = detail.releaseDate,
                duration = parseDurationMinutes(detail.episodeRunTime),
                rating = detail.rating.toDoubleOrNull() ?: 0.0,
                tmdbId = detail.tmdbId?.toIntOrNull() ?: 0
            ).takeIf { metadata ->
                metadata.posterUrl.isNotBlank() ||
                    metadata.backdropUrl.isNotBlank() ||
                    metadata.plot.isNotBlank() ||
                    metadata.tmdbId > 0
            }
        } catch (error: Exception) {
            error.rethrowIfCancellation()
            logRequestFailure("Series metadata loading for series $seriesId", error)
            null
        }
    }

    suspend fun loadLiveEpgPrograms(
        serverUrl: String,
        username: String,
        password: String,
        channels: List<Channel>
    ): List<EpgProgramEntity> {
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
            return emptyList()
        }

        val url = buildApiUrl(serverUrl)
        val programs = mutableListOf<EpgProgramEntity>()
        channels
            .asSequence()
            .filter { it.streamId > 0 }
            .distinctBy { it.streamId }
            .forEach { channel ->
                programs += loadLiveEpgProgramsForChannel(url, username, password, channel)
            }
        return programs
    }

    private suspend fun loadLiveEpgProgramsForChannel(
        url: String,
        username: String,
        password: String,
        channel: Channel
    ): List<EpgProgramEntity> {
        val shortEpg = runCatching {
            api.getLiveEpg(
                url = url,
                username = username,
                password = password,
                action = "get_short_epg",
                streamId = channel.streamId,
                limit = 6
            )
        }
            .map { response -> parseXtreamEpgPrograms(response, channel) }
            .getOrElse { error ->
                error.rethrowIfCancellation()
                logRequestFailure("Short EPG loading for stream ${channel.streamId}", error)
                emptyList()
            }

        if (shortEpg.isNotEmpty()) return shortEpg

        return runCatching {
            api.getLiveEpg(
                url = url,
                username = username,
                password = password,
                action = "get_simple_data_table",
                streamId = channel.streamId
            )
        }
            .map { response -> parseXtreamEpgPrograms(response, channel) }
            .getOrElse { error ->
                error.rethrowIfCancellation()
                logRequestFailure("Simple EPG loading for stream ${channel.streamId}", error)
                emptyList()
            }
    }

    private fun parseSeriesEpisodes(element: JsonElement?): List<Pair<String, List<XtreamEpisode>>> {
        return when (element) {
            is JsonObject -> element.mapNotNull { (seasonKey, seasonValue) ->
                val episodes = decodeEpisodeCollection(seasonValue)
                if (episodes.isEmpty()) null else seasonKey to episodes
            }
            is JsonArray -> {
                val episodes = decodeEpisodeCollection(element)
                if (episodes.isEmpty()) emptyList() else listOf("1" to episodes)
            }
            else -> emptyList()
        }
    }

    private fun decodeEpisodeCollection(element: JsonElement): List<XtreamEpisode> {
        return when (element) {
            is JsonArray -> element.mapNotNull(::decodeEpisode)
            is JsonObject -> {
                decodeEpisode(element)?.let { listOf(it) }
                    ?: element.values.mapNotNull(::decodeEpisode)
            }
            else -> emptyList()
        }
    }

    private fun decodeEpisode(element: JsonElement): XtreamEpisode? {
        val decodedEpisode = runCatching {
            json.decodeFromJsonElement<XtreamEpisode>(element)
        }.getOrNull()
        val flexibleEpisode = (element as? JsonObject)?.toFlexibleXtreamEpisode()
        val episode = when {
            decodedEpisode == null -> flexibleEpisode
            flexibleEpisode == null -> decodedEpisode
            else -> decodedEpisode.withFallback(flexibleEpisode)
        }

        return episode?.takeIf {
            it.id.isNotBlank() ||
                it.episodeNum > 0 ||
                it.directSource.isNotBlank()
        }
    }

    private fun XtreamEpisode.withFallback(fallback: XtreamEpisode): XtreamEpisode = copy(
        id = id.ifBlank { fallback.id },
        episodeNum = episodeNum.takeIf { it > 0 } ?: fallback.episodeNum,
        title = title.ifBlank { fallback.title },
        containerExtension = containerExtension.ifBlank { fallback.containerExtension },
        directSource = directSource.ifBlank { fallback.directSource },
        info = info ?: fallback.info
    )

    private fun JsonObject.toFlexibleXtreamEpisode(): XtreamEpisode {
        val infoObject = this["info"] as? JsonObject
        return XtreamEpisode(
            id = textValue("id", "stream_id"),
            episodeNum = intValue("episode_num", "episode", "episode_number"),
            title = textValue("title", "name"),
            containerExtension = textValue("container_extension", "extension"),
            directSource = textValue("direct_source", "stream_url"),
            info = infoObject?.let {
                XtreamEpisodeInfo(
                    plot = it.textValue("plot", "description"),
                    durationSecs = it.intValue("duration_secs", "duration_seconds"),
                    duration = it.textValue("duration"),
                    movieImage = it.textValue("movie_image", "cover", "poster"),
                    rating = it.doubleValue("rating")
                )
            }
        )
    }

    private fun JsonObject.textValue(vararg keys: String): String {
        keys.forEach { key ->
            val value = runCatching { (this[key] as? JsonPrimitive)?.content }
                .getOrNull()
                ?.trim()
            if (!value.isNullOrBlank() && !value.equals("null", ignoreCase = true)) {
                return value
            }
        }
        return ""
    }

    private fun JsonObject.intValue(vararg keys: String): Int =
        textValue(*keys).toIntOrNull() ?: 0

    private fun JsonObject.doubleValue(vararg keys: String): Double =
        textValue(*keys).toDoubleOrNull() ?: 0.0

    private fun String.isHttpStreamUrl(): Boolean =
        startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

    private fun logRequestFailure(operation: String, error: Throwable) {
        // Retrofit exceptions may retain a request URL containing Xtream credentials.
        Timber.w("%s failed: %s", operation, error.javaClass.simpleName)
    }

    private companion object {
        // These codes are commonly returned by live-only panels for an unknown Xtream action.
        // Do not include auth, rate-limit, or 5xx failures: those need to preserve the snapshot
        // and surface a refresh error to the caller.
        val OPTIONAL_CATALOG_UNSUPPORTED_HTTP_CODES = setOf(400, 404, 405, 501)
    }
}

enum class XtreamContentSection {
    LIVE,
    MOVIES,
    SERIES
}

data class XtreamContentResult(
    val channels: List<ChannelEntity>,
    val movies: List<MovieEntity>,
    val series: List<SeriesEntity>,
    val categories: List<CategoryEntity>,
    /** Sections that were fetched successfully and may replace their persisted snapshot. */
    val loadedSections: Set<XtreamContentSection> = XtreamContentSection.entries.toSet()
)

data class XtreamVodMetadata(
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val releaseDate: String = "",
    val duration: Int = 0,
    val rating: Double = 0.0,
    val tmdbId: Int = 0
)

internal fun parseXtreamVodMetadata(element: JsonElement, serverUrl: String): XtreamVodMetadata? {
    val root = element as? JsonObject ?: return null
    val info = root["info"] as? JsonObject ?: JsonObject(emptyMap())
    val movieData = root["movie_data"] as? JsonObject ?: JsonObject(emptyMap())

    fun JsonObject.firstText(vararg keys: String): String {
        keys.forEach { key ->
            val candidate = when (val value = this[key]) {
                is JsonPrimitive -> value.content.trim()
                is JsonArray -> value.firstOrNull()?.let { (it as? JsonPrimitive)?.content?.trim() }.orEmpty()
                else -> ""
            }
            if (candidate.isNotBlank() && !candidate.equals("null", ignoreCase = true)) return candidate
        }
        return ""
    }

    fun value(vararg keys: String): String = info.firstText(*keys)
        .ifBlank { movieData.firstText(*keys) }
        .ifBlank { root.firstText(*keys) }

    val metadata = XtreamVodMetadata(
        posterUrl = normalizeRemoteArtworkUrl(
            serverUrl,
            value("movie_image", "cover_big", "cover", "stream_icon", "poster", "poster_path")
        ),
        backdropUrl = normalizeRemoteArtworkUrl(
            serverUrl,
            value("backdrop_path", "backdrop", "background")
        ),
        plot = value("plot", "description"),
        cast = value("cast", "actors"),
        director = value("director"),
        genre = value("genre"),
        releaseDate = value("releasedate", "release_date", "date"),
        duration = value("duration_secs", "duration_seconds").toLongOrNull()
            ?.let { seconds -> (seconds / 60L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
            ?: parseDurationMinutes(value("duration", "episode_run_time")),
        rating = value("rating", "rating_5based").toDoubleOrNull() ?: 0.0,
        tmdbId = value("tmdb_id", "tmdb").toIntOrNull() ?: 0
    )
    return metadata.takeIf {
        it.posterUrl.isNotBlank() || it.backdropUrl.isNotBlank() || it.plot.isNotBlank() || it.tmdbId > 0
    }
}

private fun parseDurationMinutes(value: String): Int {
    val parts = value.split(':').mapNotNull(String::toIntOrNull)
    return when (parts.size) {
        3 -> parts[0] * 60 + parts[1]
        2 -> parts[0] * 60 + parts[1]
        1 -> parts[0]
        else -> 0
    }
}

interface XtreamStreamingSink {
    fun onChannels(channels: List<ChannelEntity>)
    fun onMovies(movies: List<MovieEntity>)
    fun onSeries(series: List<SeriesEntity>)
    fun onCategories(categories: List<CategoryEntity>)
}

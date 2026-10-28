package com.idealplayer.app.data.parser

import com.idealplayer.app.core.database.ChannelEntity
import com.idealplayer.app.core.database.EpisodeEntity
import com.idealplayer.app.core.database.MovieEntity
import com.idealplayer.app.core.database.SeriesEntity
import com.idealplayer.app.core.common.rethrowIfCancellation
import com.idealplayer.app.core.model.ContentType
import com.idealplayer.app.core.player.parsePlaybackSource
import com.idealplayer.app.core.player.withPlaybackHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class M3uEntry(
    val name: String,
    val logoUrl: String = "",
    val groupTitle: String = "",
    val tvgId: String = "",
    val tvgName: String = "",
    val url: String,
    val contentType: ContentType = ContentType.LIVE,
    val tvgLanguage: String = "",
    val catchupSource: String = ""
)

data class ParsedEpisode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val displayName: String
)

interface M3uStreamingSink {
    fun onChannels(channels: List<ChannelEntity>)
    fun onMovies(movies: List<MovieEntity>)
    fun onSeries(series: List<SeriesEntity>)
    fun onEpisodes(seriesName: String, episodes: List<EpisodeEntity>)
    fun onEpgUrls(urls: List<String>)
}

/** Safe reason only: provider responses and URLs must never enter a diagnostic message. */
class InvalidPlaylistException(val reason: Reason) : IOException("Invalid playlist: ${reason.name}") {
    enum class Reason { INVALID_STREAM_URI, INCOMPLETE_ENTRY, UNSUPPORTED_DOCUMENT }
}

@Singleton
class M3uParser @Inject constructor() {
    private val epgUrlAttributeKeys = listOf(
        "x-tvg-url",
        "url-tvg",
        "tvg-url",
        "epg-url",
        "epg"
    )

    fun parse(
        input: InputStream,
        playlistId: Long,
        cancellationCheck: () -> Unit = {}
    ): M3uParseResult {
        val entries = mutableListOf<M3uEntry>()
        val reader = BufferedReader(InputStreamReader(input))
        var currentLine: String?
        var extinf: String? = null
        var extinfAttributes: Map<String, String> = emptyMap()
        var epgUrls = emptyList<String>()
        var playbackHeaders = linkedMapOf<String, String>()
        var playbackSubtitles = mutableListOf<String>()

        try {
            var lineNumber = 0
            while (reader.readLine().also { currentLine = it } != null) {
                if (lineNumber++ % CANCELLATION_CHECK_INTERVAL_LINES == 0) {
                    cancellationCheck()
                }
                val line = currentLine?.trim()?.let {
                    if (lineNumber == 1) it.removePrefix("\uFEFF") else it
                } ?: continue
                when {
                    line.startsWith("<") || line.startsWith("{") || line.startsWith("[") ->
                        throw InvalidPlaylistException(InvalidPlaylistException.Reason.UNSUPPORTED_DOCUMENT)
                    line.startsWith("#EXT-X-", ignoreCase = true) ->
                        throw InvalidPlaylistException(InvalidPlaylistException.Reason.UNSUPPORTED_DOCUMENT)
                    line.startsWith("#EXTM3U") -> {
                        epgUrls = parseHeaderEpgUrls(line)
                    }
                    line.startsWith("#EXTINF:") -> {
                        if (extinf != null) {
                            throw InvalidPlaylistException(InvalidPlaylistException.Reason.INCOMPLETE_ENTRY)
                        }
                        extinf = line
                        extinfAttributes = parseAttributes(line)
                        playbackHeaders.putAll(parseEntryPlaybackHeaders(extinfAttributes))
                        playbackSubtitles.addAll(parseEntrySubtitles(extinfAttributes))
                    }
                    line.startsWith("#") -> {
                        parsePlaybackOptions(line).forEach { (name, value) ->
                            playbackHeaders[name] = value
                        }
                        playbackSubtitles.addAll(parseOptionSubtitles(line))
                        val match = EPG_DIRECTIVE_REGEX.find(line)
                        if (match != null) {
                            val urls = match.groupValues[1].splitEpgUrls()
                            if (urls.isNotEmpty()) {
                                epgUrls = (epgUrls + urls).distinct()
                            }
                        }
                    }
                    line.isNotEmpty() && extinf != null -> {
                        requireStreamUri(line)
                        val streamUrl = withPlaybackHeaders(line, playbackHeaders, playbackSubtitles)
                        val entry = parseEntry(extinf, extinfAttributes, streamUrl)
                        if (entry != null) entries.add(entry)
                        extinf = null
                        extinfAttributes = emptyMap()
                        playbackHeaders.clear()
                        playbackSubtitles.clear()
                    }
                    line.isNotEmpty() && extinf == null -> {
                        requireStreamUri(line)
                        val streamUrl = withPlaybackHeaders(line, playbackHeaders, playbackSubtitles)
                        entries.add(M3uEntry(name = line.substringAfterLast("/"), url = streamUrl))
                        playbackHeaders.clear()
                        playbackSubtitles.clear()
                    }
                }
            }
            cancellationCheck()
            if (extinf != null) {
                throw InvalidPlaylistException(InvalidPlaylistException.Reason.INCOMPLETE_ENTRY)
            }
        } catch (e: IOException) {
            // Returning entries accumulated up to this point would make a caller mistake a
            // truncated source for an authoritative playlist and replace valid stored content
            // with only that partial result. Individual malformed EXTINF rows are still handled
            // inside parseEntry, so only stream/transport failures abort the import.
            e.rethrowIfCancellation()
            throw e
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Timber.e("M3U parsing failed: %s", e.javaClass.simpleName)
            // The only intentionally recoverable parse failures are handled per-entry in
            // parseEntry. Anything escaping the main loop means the input is not a complete,
            // trustworthy snapshot and must not be persisted as a partial playlist.
            throw e
        }

        return categorize(entries, playlistId, epgUrls)
    }

    fun parseStreaming(
        input: InputStream,
        playlistId: Long,
        sink: M3uStreamingSink,
        nextEpisodeIndex: (String) -> Int,
        batchSize: Int = 500,
        cancellationCheck: () -> Unit = {}
    ) {
        require(batchSize in 1..500)
        val reader = BufferedReader(InputStreamReader(input))
        var currentLine: String?
        var extinf: String? = null
        var extinfAttributes: Map<String, String> = emptyMap()
        var epgUrls = emptyList<String>()
        var playbackHeaders = linkedMapOf<String, String>()
        var playbackSubtitles = mutableListOf<String>()

        val channelBatch = mutableListOf<ChannelEntity>()
        val movieBatch = mutableListOf<MovieEntity>()
        val seriesBatch = mutableListOf<SeriesEntity>()
        val episodeBatches = mutableMapOf<String, MutableList<EpisodeEntity>>()
        var pendingEpisodes = 0

        var liveIndex = 0
        var movieIndex = 0
        var seriesIndex = 0

        fun flushChannels() {
            if (channelBatch.isNotEmpty()) {
                sink.onChannels(ArrayList(channelBatch))
                channelBatch.clear()
            }
        }

        fun flushMovies() {
            if (movieBatch.isNotEmpty()) {
                sink.onMovies(ArrayList(movieBatch))
                movieBatch.clear()
            }
        }

        fun flushSeries() {
            if (seriesBatch.isNotEmpty()) {
                sink.onSeries(ArrayList(seriesBatch))
                seriesBatch.clear()
            }
        }

        fun flushEpisodes() {
            episodeBatches.forEach { (name, episodes) -> sink.onEpisodes(name, episodes.toList()) }
            episodeBatches.clear()
            pendingEpisodes = 0
        }

        try {
            var lineNumber = 0
            while (reader.readLine().also { currentLine = it } != null) {
                if (lineNumber++ % CANCELLATION_CHECK_INTERVAL_LINES == 0) {
                    cancellationCheck()
                }
                val line = currentLine?.trim()?.let {
                    if (lineNumber == 1) it.removePrefix("\uFEFF") else it
                } ?: continue
                when {
                    line.startsWith("<") || line.startsWith("{") || line.startsWith("[") ->
                        throw InvalidPlaylistException(InvalidPlaylistException.Reason.UNSUPPORTED_DOCUMENT)
                    line.startsWith("#EXT-X-", ignoreCase = true) ->
                        throw InvalidPlaylistException(InvalidPlaylistException.Reason.UNSUPPORTED_DOCUMENT)
                    line.startsWith("#EXTM3U") -> {
                        epgUrls = parseHeaderEpgUrls(line)
                    }
                    line.startsWith("#EXTINF:") -> {
                        if (extinf != null) {
                            throw InvalidPlaylistException(InvalidPlaylistException.Reason.INCOMPLETE_ENTRY)
                        }
                        extinf = line
                        extinfAttributes = parseAttributes(line)
                        playbackHeaders.putAll(parseEntryPlaybackHeaders(extinfAttributes))
                        playbackSubtitles.addAll(parseEntrySubtitles(extinfAttributes))
                    }
                    line.startsWith("#") -> {
                        parsePlaybackOptions(line).forEach { (name, value) ->
                            playbackHeaders[name] = value
                        }
                        playbackSubtitles.addAll(parseOptionSubtitles(line))
                        val match = EPG_DIRECTIVE_REGEX.find(line)
                        if (match != null) {
                            val urls = match.groupValues[1].splitEpgUrls()
                            if (urls.isNotEmpty()) {
                                epgUrls = (epgUrls + urls).distinct()
                            }
                        }
                    }
                    line.isNotEmpty() && extinf != null -> {
                        requireStreamUri(line)
                        val streamUrl = withPlaybackHeaders(line, playbackHeaders, playbackSubtitles)
                        val entry = parseEntry(extinf, extinfAttributes, streamUrl)
                        if (entry != null) {
                            when (entry.contentType) {
                                ContentType.LIVE -> {
                                    channelBatch.add(
                                        ChannelEntity(
                                            playlistId = playlistId,
                                            streamId = extractXtreamStreamId(entry.url),
                                            name = entry.name,
                                            logoUrl = entry.logoUrl,
                                            groupTitle = entry.groupTitle,
                                            streamUrl = entry.url,
                                            epgChannelId = entry.tvgId,
                                            catchupSource = entry.catchupSource,
                                            sortOrder = liveIndex++
                                        )
                                    )
                                    if (channelBatch.size >= batchSize) flushChannels()
                                }
                                ContentType.MOVIE -> {
                                    movieBatch.add(
                                        MovieEntity(
                                            playlistId = playlistId,
                                            streamId = extractXtreamStreamId(entry.url),
                                            name = entry.name,
                                            posterUrl = entry.logoUrl,
                                            streamUrl = entry.url,
                                            categoryName = entry.groupTitle,
                                            genre = entry.groupTitle,
                                            sourceOrder = movieIndex++
                                        )
                                    )
                                    if (movieBatch.size >= batchSize) flushMovies()
                                }
                                ContentType.SERIES -> {
                                    val seriesName = extractSeriesName(entry.name)
                                    val epCount = nextEpisodeIndex(seriesName)
                                    if (epCount == 0) {
                                        seriesBatch.add(
                                            SeriesEntity(
                                                playlistId = playlistId,
                                                name = seriesName,
                                                categoryName = entry.groupTitle,
                                                sourceOrder = seriesIndex++
                                            )
                                        )
                                        if (seriesBatch.size >= batchSize) flushSeries()
                                    }
                                    val parsedEp = parseEpisode(entry.name, seriesName, epCount)
                                    val epList = episodeBatches.getOrPut(seriesName) { mutableListOf() }
                                    epList.add(
                                        EpisodeEntity(
                                            seriesId = 0L,
                                            seasonNumber = parsedEp.seasonNumber,
                                            episodeNumber = parsedEp.episodeNumber,
                                            name = parsedEp.displayName,
                                            posterUrl = entry.logoUrl,
                                            streamUrl = entry.url
                                        )
                                    )
                                    pendingEpisodes++
                                    if (pendingEpisodes >= batchSize) flushEpisodes()
                                }
                            }
                        }
                        extinf = null
                        extinfAttributes = emptyMap()
                        playbackHeaders.clear()
                        playbackSubtitles.clear()
                    }
                    line.isNotEmpty() && extinf == null -> {
                        requireStreamUri(line)
                        val streamUrl = withPlaybackHeaders(line, playbackHeaders, playbackSubtitles)
                        channelBatch.add(
                            ChannelEntity(
                                playlistId = playlistId,
                                name = line.substringAfterLast("/"),
                                streamUrl = streamUrl,
                                sortOrder = liveIndex++
                            )
                        )
                        if (channelBatch.size >= batchSize) flushChannels()
                        playbackHeaders.clear()
                        playbackSubtitles.clear()
                    }
                }
            }
            cancellationCheck()
            if (extinf != null) {
                throw InvalidPlaylistException(InvalidPlaylistException.Reason.INCOMPLETE_ENTRY)
            }
        } catch (e: IOException) {
            e.rethrowIfCancellation()
            throw e
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Timber.e("M3U parsing failed: %s", e.javaClass.simpleName)
            throw e
        }

        flushChannels()
        flushMovies()
        flushSeries()
        flushEpisodes()
        if (epgUrls.isNotEmpty()) {
            sink.onEpgUrls(epgUrls)
        }
    }

    fun parseEpisode(
        rawTitle: String,
        seriesName: String,
        index: Int
    ): ParsedEpisode {
        val normalizedTitle = rawTitle.trim()
        val patterns = listOf(
            Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,3})\b"""),
            Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b"""),
            Regex("""(?i)\bseason\s*(\d{1,2})\D+episode\s*(\d{1,3})\b""")
        )

        patterns.forEach { pattern ->
            val match = pattern.find(normalizedTitle) ?: return@forEach
            val seasonNumber = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 1
            val episodeNumber = match.groupValues.getOrNull(2)?.toIntOrNull() ?: (index + 1)
            val cleanedTitle = normalizedTitle
                .replace(seriesName, "", ignoreCase = true)
                .replace(pattern, "")
                .replace("""^[\s\-_:|]+|[\s\-_:|]+$""".toRegex(), "")
                .ifBlank { "Episode $episodeNumber" }

            return ParsedEpisode(
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                displayName = cleanedTitle
            )
        }

        return ParsedEpisode(
            seasonNumber = 1,
            episodeNumber = index + 1,
            displayName = normalizedTitle
                .replace(seriesName, "", ignoreCase = true)
                .replace("""^[\s\-_:|]+|[\s\-_:|]+$""".toRegex(), "")
                .ifBlank { "Episode ${index + 1}" }
        )
    }

    fun parseText(text: String, playlistId: Long): M3uParseResult {
        return parse(text.byteInputStream(), playlistId)
    }

    /** EPG rediscovery does not need a second in-memory copy of the catalog. */
    fun discoverEpgUrls(input: InputStream, cancellationCheck: () -> Unit = {}): List<String> {
        val urls = linkedSetOf<String>()
        input.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, raw ->
                if (index % CANCELLATION_CHECK_INTERVAL_LINES == 0) cancellationCheck()
                val line = raw.trim().removePrefix("\uFEFF")
                val found = if (line.startsWith("#EXTM3U")) parseHeaderEpgUrls(line)
                    else EPG_DIRECTIVE_REGEX.find(line)?.groupValues?.get(1)?.splitEpgUrls().orEmpty()
                urls.addAll(found)
                require(urls.size <= 128) { "Too many EPG sources" }
            }
        }
        cancellationCheck()
        return urls.toList()
    }

    private fun requireStreamUri(value: String) {
        val source = parsePlaybackSource(value).url
        val scheme = source.substringBefore(':', "").lowercase(Locale.ROOT)
        val valid = when (scheme) {
            "http", "https" -> source.none { it.isWhitespace() || it in "<>\"" } &&
                source.toHttpUrlOrNull() != null
            "rtsp", "rtsps", "rtmp", "rtmps", "rtp", "udp", "srt", "rist", "mms", "mmsh", "mmst" ->
                runCatching { URI(source).let { !it.isOpaque && !it.rawAuthority.isNullOrBlank() } }.getOrDefault(false)
            "file" -> runCatching { URI(source).path?.startsWith('/') == true }.getOrDefault(false)
            "content" -> runCatching { !URI(source).authority.isNullOrBlank() }.getOrDefault(false)
            else -> false
        }
        if (!valid) throw InvalidPlaylistException(InvalidPlaylistException.Reason.INVALID_STREAM_URI)
    }

    private fun parseEntry(extinf: String, attributes: Map<String, String>, url: String): M3uEntry? {
        try {
            val name = extinf.substringAfterLast(",").trim()
            val groupTitle = attributes["group-title"] ?: ""
            val tvgId = attributes["tvg-id"] ?: ""
            val tvgName = attributes["tvg-name"] ?: ""
            val logo = attributes["tvg-logo"] ?: ""
            val catchup = attributes["catchup-source"] ?: ""

            val contentType = detectContentType(url, groupTitle)

            return M3uEntry(
                name = name,
                logoUrl = logo,
                groupTitle = groupTitle,
                tvgId = tvgId,
                tvgName = tvgName,
                url = url,
                contentType = contentType,
                catchupSource = catchup
            )
        } catch (e: Exception) {
            // EXTINF attributes can carry provider-specific request headers. Do not emit the
            // raw line to debug logs.
            Timber.w("Failed to parse EXTINF metadata: %s", e.javaClass.simpleName)
            return null
        }
    }

    private fun parseAttributes(extinf: String): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        ATTRIBUTE_REGEX.findAll(extinf).forEach { match ->
            attrs[match.groupValues[1].lowercase(Locale.ROOT)] = match.groupValues
                .drop(2)
                .firstOrNull(String::isNotBlank)
                .orEmpty()
        }
        return attrs
    }

    private fun parseEntryPlaybackHeaders(attributes: Map<String, String>): Map<String, String> {
        return buildMap {
            attributes["http-user-agent"]
                .orEmpty()
                .ifBlank { attributes["user-agent"].orEmpty() }
                .takeIf(String::isNotBlank)
                ?.let { put("User-Agent", it) }
            attributes["http-referrer"]
                .orEmpty()
                .ifBlank { attributes["referer"].orEmpty() }
                .ifBlank { attributes["referrer"].orEmpty() }
                .takeIf(String::isNotBlank)
                ?.let { put("Referer", it) }
            attributes["http-origin"]
                .orEmpty()
                .ifBlank { attributes["origin"].orEmpty() }
                .takeIf(String::isNotBlank)
                ?.let { put("Origin", it) }
        }
    }

    private fun parseEntrySubtitles(attributes: Map<String, String>): List<String> {
        return SUBTITLE_ATTRIBUTE_KEYS
            .flatMap { key -> attributes[key].orEmpty().splitSubtitleUrls() }
            .distinct()
    }

    private fun parseOptionSubtitles(line: String): List<String> {
        val vlcMatch = EXTVLCOPT_REGEX.find(line)
        if (vlcMatch != null) {
            val key = vlcMatch.groupValues[1].trim().lowercase(Locale.ROOT)
            if (key == "sub-file" || key == "subtitle" || key == "subtitles") {
                return vlcMatch.groupValues[2].splitSubtitleUrls()
            }
        }
        return emptyList()
    }

    private fun parsePlaybackOptions(line: String): Map<String, String> {
        val vlcMatch = EXTVLCOPT_REGEX.find(line)
        if (vlcMatch != null) {
            val value = vlcMatch.groupValues[2].trim().takeIf(String::isNotBlank) ?: return emptyMap()
            val headerName = when (vlcMatch.groupValues[1].trim().lowercase(Locale.ROOT)) {
                "http-user-agent" -> "User-Agent"
                "http-referrer", "http-referer" -> "Referer"
                "http-origin" -> "Origin"
                "http-cookie", "http-cookies" -> "Cookie"
                else -> return emptyMap()
            }
            return mapOf(headerName to value)
        }

        val kodiPrefix = "#KODIPROP:inputstream.adaptive.stream_headers="
        if (line.startsWith(kodiPrefix, ignoreCase = true)) {
            val payload = line.substring(kodiPrefix.length)
            return parsePlaybackSource("https://header.invalid|$payload").headers
        }

        if (line.startsWith("#EXTHTTP:", ignoreCase = true)) {
            val payload = line.substringAfter(':').trim()
            return runCatching {
                Json.parseToJsonElement(payload).jsonObject
                    .mapValues { (_, value) -> value.jsonPrimitive.content }
            }.getOrDefault(emptyMap())
        }

        return emptyMap()
    }

    private fun parseHeaderEpgUrls(header: String): List<String> {
        val attributes = parseAttributes(header)
        return epgUrlAttributeKeys
            .flatMap { key -> attributes[key].orEmpty().splitEpgUrls() }
            .distinct()
    }

    private fun String.splitEpgUrls(): List<String> =
        split(',', ';')
            .map(String::trim)
            .filter(String::isNotBlank)

    private fun String.splitSubtitleUrls(): List<String> =
        split(SUBTITLE_LIST_SEPARATOR_REGEX)
            .map(String::trim)
            .filter(String::isNotBlank)

    private fun detectContentType(url: String, group: String): ContentType {
        val sourceUrl = parsePlaybackSource(url).url
        val lowerUrl = sourceUrl.lowercase(Locale.ROOT)
        val pathExtension = sourceUrl.toHttpUrlOrNull()
            ?.encodedPath
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val lowerGroup = group.lowercase(Locale.ROOT)
        return when {
            lowerUrl.contains("/series/") || lowerGroup.contains("series") || lowerGroup.contains("dizi") -> ContentType.SERIES
            lowerUrl.contains("/movie/") ||
                lowerGroup.contains("movie") ||
                lowerGroup.contains("film") ||
                pathExtension in VOD_FILE_EXTENSIONS -> ContentType.MOVIE
            else -> ContentType.LIVE
        }
    }

    private fun categorize(entries: List<M3uEntry>, playlistId: Long, epgUrls: List<String>): M3uParseResult {
        val channels = mutableListOf<ChannelEntity>()
        val movies = mutableListOf<MovieEntity>()
        val series = mutableMapOf<String, MutableList<M3uEntry>>()
        val seriesSourceOrder = mutableMapOf<String, Int>()
        val seriesCategory = mutableMapOf<String, String>()

        entries.forEachIndexed { index, entry ->
            when (entry.contentType) {
                ContentType.LIVE -> channels.add(
                    ChannelEntity(
                        playlistId = playlistId,
                        streamId = extractXtreamStreamId(entry.url),
                        name = entry.name,
                        logoUrl = entry.logoUrl,
                        groupTitle = entry.groupTitle,
                        streamUrl = entry.url,
                        epgChannelId = entry.tvgId,
                        catchupSource = entry.catchupSource,
                        sortOrder = index
                    )
                )
                ContentType.MOVIE -> movies.add(
                    MovieEntity(
                        playlistId = playlistId,
                        streamId = extractXtreamStreamId(entry.url),
                        name = entry.name,
                        posterUrl = entry.logoUrl,
                        streamUrl = entry.url,
                        categoryName = entry.groupTitle,
                        genre = entry.groupTitle,
                        sourceOrder = index
                    )
                )
                ContentType.SERIES -> {
                    val seriesKey = extractSeriesName(entry.name)
                    seriesSourceOrder.putIfAbsent(seriesKey, index)
                    seriesCategory.putIfAbsent(seriesKey, entry.groupTitle)
                    series.getOrPut(seriesKey) { mutableListOf() }.add(entry)
                }
            }
        }

        val seriesEntities = series.map { (name, _) ->
            SeriesEntity(
                playlistId = playlistId,
                name = name,
                categoryName = seriesCategory[name] ?: "",
                sourceOrder = seriesSourceOrder[name] ?: 0
            )
        }

        return M3uParseResult(
            channels = channels,
            movies = movies,
            series = seriesEntities,
            seriesEpisodes = series,
            epgUrls = epgUrls
        )
    }

    private fun extractSeriesName(name: String): String {
        for (pattern in SERIES_NAME_PATTERNS) {
            val match = pattern.find(name)
            if (match != null) return match.groupValues[1].trim()
        }
        return name.trim()
    }

    private fun extractXtreamStreamId(url: String): Int {
        val sourceUrl = parsePlaybackSource(url).url
        sourceUrl.toHttpUrlOrNull()?.let { httpUrl ->
            listOf("stream_id", "streamId", "stream").forEach { key ->
                httpUrl.queryParameter(key)?.toIntOrNull()?.let { return it }
            }
        }

        return XTREAM_PATH_STREAM_ID_REGEX
            .find(sourceUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    private companion object {
        const val CANCELLATION_CHECK_INTERVAL_LINES = 256
        val VOD_FILE_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "ogv")
        val SUBTITLE_ATTRIBUTE_KEYS = listOf("subtitles", "subtitle", "sub-file", "tvg-subtitles", "sub-url")
        val ATTRIBUTE_REGEX = Regex("""([\w-]+)\s*=\s*(?:"([^"]*?)"|'([^']*?)'|([^\s]+))""")
        val EPG_DIRECTIVE_REGEX = Regex("^#(?:x-tvg-url|url-tvg|tvg-url|epg-url|epg)\\s*[:=]\\s*(.+)$", RegexOption.IGNORE_CASE)
        val EXTVLCOPT_REGEX = Regex(pattern = "^#EXTVLCOPT:([^=]+)=(.*)$", option = RegexOption.IGNORE_CASE)
        val SUBTITLE_LIST_SEPARATOR_REGEX = Regex("""\s*[;,]\s*(?=https?://)""", RegexOption.IGNORE_CASE)
        val SERIES_NAME_PATTERNS = listOf(
            Regex("""^(.+?)\s*[Ss]\d+\s*[Ee]\d+"""),
            Regex("""^(.+?)\s*Season\s*\d+""", RegexOption.IGNORE_CASE),
            Regex("""^(.+?)\s*\d+x\d+""")
        )
        val XTREAM_PATH_STREAM_ID_REGEX = Regex(
            pattern = """/(?:live|movie|series)/[^/?#]+/[^/?#]+/(\d+)(?:\.[^/?#]+)?(?:[?#].*)?$""",
            option = RegexOption.IGNORE_CASE
        )
    }
}

data class M3uParseResult(
    val channels: List<ChannelEntity>,
    val movies: List<MovieEntity>,
    val series: List<SeriesEntity>,
    val seriesEpisodes: Map<String, List<M3uEntry>>,
    val epgUrls: List<String> = emptyList()
) {
    val epgUrl: String
        get() = epgUrls.firstOrNull().orEmpty()
}

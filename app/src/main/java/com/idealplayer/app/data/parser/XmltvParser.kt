package com.idealplayer.app.data.parser

import com.idealplayer.app.core.database.EpgProgramEntity
import com.idealplayer.app.core.common.rethrowIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import timber.log.Timber
import java.io.InputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.SAXParserFactory

/**
 * XMLTV format parser using Android's XmlPullParser.
 *
 * Parses <programme> elements from standard XMLTV XML.
 * All times are normalized to UTC milliseconds.
 *
 * XMLTV time format: "20240419120000 +0300"
 */
@Singleton
class XmltvParser @Inject constructor() {

    /**
     * Parse XMLTV stream and return a list of [EpgProgramEntity].
     * Runs on IO dispatcher.
     *
     * @param inputStream The XMLTV XML input
     * @param channelIdMap Optional mapping from XMLTV channel id to internal channel id
     *                     If null, uses the XMLTV channel-id directly.
     */
    suspend fun parse(
        inputStream: InputStream,
        channelIdMap: Map<String, String>? = null
    ): List<EpgProgramEntity> = parseDocument(inputStream, channelIdMap).programs

    /**
     * Parses programs together with the optional artwork declared on XMLTV <channel> nodes.
     * Keeping this separate from programme artwork lets repositories fill missing station
     * logos from the user's own EPG source without changing the long-standing [parse] API.
     */
    suspend fun parseDocument(
        inputStream: InputStream,
        channelIdMap: Map<String, String>? = null
    ): XmltvParseResult = parseDocument(inputStream, channelIdMap, XmltvParserLimits())

    internal suspend fun parseDocumentStreaming(
        inputStream: InputStream,
        channelIdMap: Map<String, String>? = null,
        limits: XmltvParserLimits = XmltvParserLimits(),
        batchSize: Int = 500,
        onProgramBatch: suspend (List<EpgProgramEntity>) -> Unit
    ): XmltvParseResult {
        return withContext(Dispatchers.IO) {
            val coroutineContext = currentCoroutineContext()
            try {
                inputStream.use {
                    parseInternalStreaming(it, channelIdMap, limits, batchSize, { batch ->
                        // SAX callbacks are synchronous. Retain the caller's cancellation and
                        // Room transaction context when bridging to the suspending sink.
                        kotlinx.coroutines.runBlocking(
                            coroutineContext.minusKey(kotlin.coroutines.ContinuationInterceptor)
                        ) { onProgramBatch(batch) }
                    }) {
                        coroutineContext.ensureActive()
                    }
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                e.findXmltvInputFailure()?.let { throw it }
                Timber.e("XMLTV parse failed: %s", e.javaClass.simpleName)
                if (e is IOException) throw e
                throw XmltvDocumentException()
            }
        }
    }

    internal suspend fun parseDocument(
        inputStream: InputStream,
        channelIdMap: Map<String, String>?,
        limits: XmltvParserLimits
    ): XmltvParseResult {
        return withContext(Dispatchers.IO) {
            val coroutineContext = currentCoroutineContext()
            try {
                inputStream.use {
                    parseInternal(it, channelIdMap, limits) { coroutineContext.ensureActive() }
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                e.findXmltvInputFailure()?.let { throw it }
                // Provider XML can be embedded in parser exception messages.
                Timber.e("XMLTV parse failed: %s", e.javaClass.simpleName)
                if (e is IOException) throw e
                throw XmltvDocumentException()
            }
        }
    }

    private fun parseInternal(
        inputStream: InputStream,
        channelIdMap: Map<String, String>?,
        limits: XmltvParserLimits,
        checkCancellation: () -> Unit
    ): XmltvParseResult {
        val programBuilders = mutableListOf<ProgramBuilder>()
        val programKeys = hashSetOf<String>()
        val channelIds = hashSetOf<String>()
        val channelDisplayNames = linkedMapOf<String, MutableList<String>>()
        val channelIcons = linkedMapOf<String, String>()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            disableFeature("http://xml.org/sax/features/external-general-entities")
            disableFeature("http://xml.org/sax/features/external-parameter-entities")
            disableFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd")
        }

        var currentProgram: ProgramBuilder? = null
        var currentTag: String? = null
        var currentLang: String = ""
        var currentChannelId: String? = null
        var rootSeen = false
        val textBuffer = StringBuilder()

        factory.newSAXParser().parse(inputStream, object : DefaultHandler() {
            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String,
                attributes: Attributes
            ) {
                checkCancellation()
                if (!rootSeen) {
                    if (qName != "tv") throw XmltvDocumentException()
                    rootSeen = true
                }
                when (qName) {
                    "channel" -> {
                        currentChannelId = attributes.getValue("id").orEmpty().take(limits.maxTextChars)
                        currentChannelId?.takeIf(String::isNotBlank)?.let { id ->
                            enforceChannelLimit(channelIds, id, limits.maxChannels)
                        }
                    }

                    "programme" -> {
                        val channelXmlId = attributes.getValue("channel")
                            .orEmpty()
                            .take(limits.maxTextChars)
                        if (channelXmlId.isNotBlank()) {
                            enforceChannelLimit(channelIds, channelXmlId, limits.maxChannels)
                        }
                        val startMs = parseXmltvTimeToEpochMillis(attributes.getValue("start").orEmpty())
                        val stopMs = parseXmltvTimeToEpochMillis(attributes.getValue("stop").orEmpty())

                        currentProgram = if (
                            channelXmlId.isNotBlank() &&
                            startMs > 0 &&
                            stopMs > startMs
                        ) {
                            ProgramBuilder(
                                channelId = channelXmlId,
                                startTime = startMs,
                                endTime = stopMs
                            )
                        } else {
                            null
                        }
                    }

                    "title", "desc", "category", "sub-title", "display-name" -> {
                        currentTag = qName
                        currentLang = attributes.getValue("lang").orEmpty()
                        textBuffer.setLength(0)
                    }

                    "icon" -> {
                        val iconUrl = attributes.getValue("src")
                            .orEmpty()
                            .trim()
                            .take(limits.maxTextChars)
                        if (currentProgram != null) {
                            currentProgram?.iconUrl = iconUrl
                        } else if (iconUrl.isNotBlank()) {
                            currentChannelId
                                ?.takeIf(String::isNotBlank)
                                ?.let { id -> channelIcons.putIfAbsent(id, iconUrl) }
                        }
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                checkCancellation()
                if (currentTag != null) {
                    val remaining = limits.maxTextChars - textBuffer.length
                    if (remaining > 0) {
                        textBuffer.append(ch, start, minOf(length, remaining))
                    }
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                val text = textBuffer.toString().trim()
                when (qName) {
                    "channel" -> currentChannelId = null
                    "display-name" -> {
                        if (text.isNotBlank()) {
                            currentChannelId
                                ?.takeIf(String::isNotBlank)
                                ?.let { id ->
                                    val names = channelDisplayNames.getOrPut(id) { mutableListOf() }
                                    if (text !in names && names.size < limits.maxDisplayNamesPerChannel) {
                                        names.add(text)
                                    }
                                }
                        }
                    }

                    "title" -> currentProgram?.consumeTitle(text, currentLang)
                    "desc" -> currentProgram?.consumeDescription(text, currentLang)
                    "category" -> currentProgram?.consumeCategory(text)
                    "sub-title" -> currentProgram?.consumeSubTitle(text)
                    "programme" -> {
                        currentProgram?.takeIf(ProgramBuilder::isValid)?.let { builder ->
                            if (programKeys.add(builder.deduplicationKey())) {
                                if (programBuilders.size >= limits.maxPrograms) {
                                    throw XmltvParseLimitExceededException("program count", limits.maxPrograms)
                                }
                                programBuilders.add(builder)
                            }
                        }
                        currentProgram = null
                    }
                }

                if (currentTag == qName) {
                    currentTag = null
                    currentLang = ""
                    textBuffer.setLength(0)
                }
            }
        })
        checkCancellation()
        if (!rootSeen) throw XmltvDocumentException()

        val programs = programBuilders.mapNotNull { builder ->
            val resolvedChannelId = resolveEpgChannelId(
                xmlChannelId = builder.channelId,
                displayNames = channelDisplayNames[builder.channelId].orEmpty(),
                channelIdMap = channelIdMap
            )

            resolvedChannelId
                .takeIf(String::isNotBlank)
                ?.let { builder.build(it) }
        }

        val resolvedChannelIcons = linkedMapOf<String, String>()
        channelIcons.forEach { (xmlChannelId, iconUrl) ->
            val resolvedChannelId = resolveEpgChannelId(
                xmlChannelId = xmlChannelId,
                displayNames = channelDisplayNames[xmlChannelId].orEmpty(),
                channelIdMap = channelIdMap
            )
            if (resolvedChannelId.isNotBlank()) {
                resolvedChannelIcons.putIfAbsent(resolvedChannelId, iconUrl)
            }
        }

        Timber.d(
            "XMLTV parsed %d programs and %d channel icons",
            programs.size,
            resolvedChannelIcons.size
        )
        return XmltvParseResult(programs, resolvedChannelIcons)
    }

    private fun parseInternalStreaming(
        inputStream: InputStream,
        channelIdMap: Map<String, String>?,
        limits: XmltvParserLimits,
        batchSize: Int,
        onProgramBatch: (List<EpgProgramEntity>) -> Unit,
        checkCancellation: () -> Unit
    ): XmltvParseResult {
        val programKeys = hashSetOf<String>()
        val channelIds = hashSetOf<String>()
        val channelDisplayNames = linkedMapOf<String, MutableList<String>>()
        val channelIcons = linkedMapOf<String, String>()
        val currentBatch = mutableListOf<EpgProgramEntity>()
        var totalProgramCount = 0

        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            disableFeature("http://xml.org/sax/features/external-general-entities")
            disableFeature("http://xml.org/sax/features/external-parameter-entities")
            disableFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd")
        }

        var currentProgram: ProgramBuilder? = null
        var currentTag: String? = null
        var currentLang: String = ""
        var currentChannelId: String? = null
        var rootSeen = false
        val textBuffer = StringBuilder()

        factory.newSAXParser().parse(inputStream, object : DefaultHandler() {
            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String,
                attributes: Attributes
            ) {
                checkCancellation()
                if (!rootSeen) {
                    if (qName != "tv") throw XmltvDocumentException()
                    rootSeen = true
                }
                when (qName) {
                    "channel" -> {
                        currentChannelId = attributes.getValue("id").orEmpty().take(limits.maxTextChars)
                        currentChannelId?.takeIf(String::isNotBlank)?.let { id ->
                            enforceChannelLimit(channelIds, id, limits.maxChannels)
                        }
                    }

                    "programme" -> {
                        val channelXmlId = attributes.getValue("channel")
                            .orEmpty()
                            .take(limits.maxTextChars)
                        if (channelXmlId.isNotBlank()) {
                            enforceChannelLimit(channelIds, channelXmlId, limits.maxChannels)
                        }
                        val startMs = parseXmltvTimeToEpochMillis(attributes.getValue("start").orEmpty())
                        val stopMs = parseXmltvTimeToEpochMillis(attributes.getValue("stop").orEmpty())

                        currentProgram = if (
                            channelXmlId.isNotBlank() &&
                            startMs > 0 &&
                            stopMs > startMs
                        ) {
                            ProgramBuilder(
                                channelId = channelXmlId,
                                startTime = startMs,
                                endTime = stopMs
                            )
                        } else {
                            null
                        }
                    }

                    "title", "desc", "category", "sub-title", "display-name" -> {
                        currentTag = qName
                        currentLang = attributes.getValue("lang").orEmpty()
                        textBuffer.setLength(0)
                    }

                    "icon" -> {
                        val iconUrl = attributes.getValue("src")
                            .orEmpty()
                            .trim()
                            .take(limits.maxTextChars)
                        if (currentProgram != null) {
                            currentProgram?.iconUrl = iconUrl
                        } else if (iconUrl.isNotBlank()) {
                            currentChannelId
                                ?.takeIf(String::isNotBlank)
                                ?.let { id -> channelIcons.putIfAbsent(id, iconUrl) }
                        }
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                checkCancellation()
                if (currentTag != null) {
                    val remaining = limits.maxTextChars - textBuffer.length
                    if (remaining > 0) {
                        textBuffer.append(ch, start, minOf(length, remaining))
                    }
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                val text = textBuffer.toString().trim()
                when (qName) {
                    "channel" -> currentChannelId = null
                    "display-name" -> {
                        if (text.isNotBlank()) {
                            currentChannelId
                                ?.takeIf(String::isNotBlank)
                                ?.let { id ->
                                    val names = channelDisplayNames.getOrPut(id) { mutableListOf() }
                                    if (text !in names && names.size < limits.maxDisplayNamesPerChannel) {
                                        names.add(text)
                                    }
                                }
                        }
                    }

                    "title" -> currentProgram?.consumeTitle(text, currentLang)
                    "desc" -> currentProgram?.consumeDescription(text, currentLang)
                    "category" -> currentProgram?.consumeCategory(text)
                    "sub-title" -> currentProgram?.consumeSubTitle(text)
                    "programme" -> {
                        currentProgram?.takeIf(ProgramBuilder::isValid)?.let { builder ->
                            if (programKeys.add(builder.deduplicationKey())) {
                                if (++totalProgramCount > limits.maxPrograms) {
                                    throw XmltvParseLimitExceededException("program count", limits.maxPrograms)
                                }
                                val resolvedChannelId = resolveEpgChannelId(
                                    xmlChannelId = builder.channelId,
                                    displayNames = channelDisplayNames[builder.channelId].orEmpty(),
                                    channelIdMap = channelIdMap
                                )
                                if (resolvedChannelId.isNotBlank()) {
                                    currentBatch.add(builder.build(resolvedChannelId))
                                    if (currentBatch.size >= batchSize) {
                                        onProgramBatch(ArrayList(currentBatch))
                                        currentBatch.clear()
                                    }
                                }
                            }
                        }
                        currentProgram = null
                    }
                }

                if (currentTag == qName) {
                    currentTag = null
                    currentLang = ""
                    textBuffer.setLength(0)
                }
            }

            override fun endDocument() {
                if (currentBatch.isNotEmpty()) {
                    onProgramBatch(ArrayList(currentBatch))
                    currentBatch.clear()
                }
            }
        })
        checkCancellation()
        if (!rootSeen) throw XmltvDocumentException()

        val resolvedChannelIcons = linkedMapOf<String, String>()
        channelIcons.forEach { (xmlChannelId, iconUrl) ->
            val resolvedChannelId = resolveEpgChannelId(
                xmlChannelId = xmlChannelId,
                displayNames = channelDisplayNames[xmlChannelId].orEmpty(),
                channelIdMap = channelIdMap
            )
            if (resolvedChannelId.isNotBlank()) {
                resolvedChannelIcons.putIfAbsent(resolvedChannelId, iconUrl)
            }
        }

        Timber.d(
            "XMLTV stream-parsed %d total programs and %d channel icons",
            totalProgramCount,
            resolvedChannelIcons.size
        )
        return XmltvParseResult(emptyList(), resolvedChannelIcons)
    }

    private fun SAXParserFactory.disableFeature(feature: String) {
        runCatching { setFeature(feature, false) }
    }

    private fun enforceChannelLimit(
        channelIds: MutableSet<String>,
        channelId: String,
        maxChannels: Int
    ) {
        if (channelId !in channelIds && channelIds.size >= maxChannels) {
            throw XmltvParseLimitExceededException("channel count", maxChannels)
        }
        channelIds.add(channelId)
    }

    // ─── Builder helpers ────────────────────────────────────────────────────────

    private class ProgramBuilder(
        val channelId: String,
        val startTime: Long,
        val endTime: Long
    ) {
        var title: String = ""
        var description: String = ""
        var genre: String = ""
        var iconUrl: String = ""
        var subTitle: String = ""

        fun consumeTitle(text: String, lang: String) {
            if (text.isBlank()) return
            if (title.isBlank() || lang.startsWith("tr") || lang.startsWith("en")) {
                title = text
            }
        }

        fun consumeDescription(text: String, lang: String) {
            if (text.isBlank()) return
            if (description.isBlank() || lang.startsWith("tr") || lang.startsWith("en")) {
                description = text
            }
        }

        fun consumeCategory(text: String) {
            if (text.isNotBlank() && genre.isBlank()) {
                genre = text
            }
        }

        fun consumeSubTitle(text: String) {
            if (text.isNotBlank() && subTitle.isBlank()) {
                subTitle = text
            }
        }

        fun isValid() = title.isNotBlank() && startTime > 0 && endTime > startTime

        fun deduplicationKey(): String = "$channelId\u0000$startTime\u0000$endTime\u0000$title"

        fun build(resolvedChannelId: String = channelId) = EpgProgramEntity(
            channelId = resolvedChannelId,
            title = buildFullTitle(),
            description = description,
            startTime = startTime,
            endTime = endTime,
            posterUrl = iconUrl,
            genre = genre
        )

        private fun buildFullTitle(): String {
            return if (subTitle.isNotBlank()) "$title: $subTitle" else title
        }
    }
}

internal data class XmltvParserLimits(
    val maxPrograms: Int = 150_000,
    val maxChannels: Int = 50_000,
    val maxTextChars: Int = 16_384,
    val maxDisplayNamesPerChannel: Int = 8
) {
    init {
        require(maxPrograms > 0)
        require(maxChannels > 0)
        require(maxTextChars > 0)
        require(maxDisplayNamesPerChannel > 0)
    }
}

internal class XmltvParseLimitExceededException(
    limitName: String,
    limit: Int
) : IOException("XMLTV $limitName exceeds the configured limit of $limit")

class XmltvDocumentException : IOException("Invalid XMLTV document")

private fun Throwable.findXmltvInputFailure(): Throwable? {
    var candidate: Throwable? = this
    val visited = hashSetOf<Throwable>()
    while (candidate != null && visited.add(candidate)) {
        if (candidate is IOException || candidate is kotlinx.coroutines.CancellationException) return candidate
        candidate = candidate.cause
    }
    return null
}

data class XmltvParseResult(
    val programs: List<EpgProgramEntity> = emptyList(),
    val channelIcons: Map<String, String> = emptyMap()
)

/**
 * Data class for EPG program used in the UI (not the Room entity).
 */
data class EpgProgram(
    val channelId: String,
    val title: String,
    val description: String = "",
    val startTime: Long,
    val endTime: Long,
    val posterUrl: String = "",
    val genre: String = ""
) {
    val durationMs: Long get() = endTime - startTime
    val isCurrentlyAiring: Boolean get() {
        val now = System.currentTimeMillis()
        return startTime <= now && endTime > now
    }
    val progressFraction: Float get() {
        if (!isCurrentlyAiring) return 0f
        val now = System.currentTimeMillis()
        return ((now - startTime).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}

fun EpgProgramEntity.toUiModel() = EpgProgram(
    channelId = channelId,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    posterUrl = posterUrl,
    genre = genre
)

// XMLTV standard time format: "YYYYMMDDHHmmss +ZZZZ"
private val XMLTV_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
// Fallback when the timestamp carries no offset.
private val XMLTV_FORMATTER_NO_TZ = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

/**
 * Parse an XMLTV time string to epoch milliseconds.
 *
 * Format: "20240419120000 +0300" (with offset) or "20240419120000" (no offset).
 *
 * When the string carries no timezone offset, XMLTV convention treats it as local time,
 * so it is interpreted in [fallbackZone] (the device zone by default). This matches
 * [parseXtreamDateMs] in XtreamEpgParser; interpreting it as UTC would shift "now playing"
 * by the device's UTC offset.
 *
 * @return epoch millis, or -1L if the string is blank/unparseable.
 */
internal fun parseXmltvTimeToEpochMillis(
    timeStr: String,
    fallbackZone: ZoneId = ZoneId.systemDefault()
): Long {
    val cleaned = timeStr.trim()
    if (cleaned.isBlank()) return -1L

    return try {
        OffsetDateTime.parse(normalizeXmltvTime(cleaned), XMLTV_FORMATTER)
            .toInstant()
            .toEpochMilli()
    } catch (e: DateTimeParseException) {
        try {
            LocalDateTime.parse(cleaned.take(14), XMLTV_FORMATTER_NO_TZ)
                .atZone(fallbackZone)
                .toInstant()
                .toEpochMilli()
        } catch (e2: DateTimeParseException) {
            Timber.w("Cannot parse XMLTV time: $cleaned")
            -1L
        }
    }
}

private fun normalizeXmltvTime(timeStr: String): String {
    if (timeStr.length <= 14) return timeStr
    val date = timeStr.take(14)
    val timezone = timeStr.drop(14).trim()
    return "$date $timezone"
}

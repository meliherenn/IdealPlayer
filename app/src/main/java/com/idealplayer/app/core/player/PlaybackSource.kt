package com.idealplayer.app.core.player

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

private const val DEFAULT_PLAYBACK_USER_AGENT = "IdealPlayer/Android"
private const val MAX_PLAYBACK_HEADERS = 32
private const val MAX_PLAYBACK_SUBTITLES = 16
private const val SUBTITLE_PARAM_PREFIX = "Subtitle-Url"

data class PlaybackSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitleUrls: List<String> = emptyList()
) {
    val userAgent: String
        get() = headers["User-Agent"] ?: DEFAULT_PLAYBACK_USER_AGENT

    val requestProperties: Map<String, String>
        get() = headers - "User-Agent"
}

/**
 * Parses the common IPTV `url|Header=Value&Header2=Value` convention without
 * allowing line breaks or invalid header names into a network request.
 */
fun parsePlaybackSource(rawValue: String): PlaybackSource {
    val trimmed = rawValue.trim().removeSuffix(".")
    val separatorIndex = trimmed.indexOf('|')
    if (separatorIndex <= 0 || separatorIndex == trimmed.lastIndex) {
        return PlaybackSource(url = trimmed)
    }

    val encodedHeaders = trimmed.substring(separatorIndex + 1)
    if (!encodedHeaders.contains('=')) {
        return PlaybackSource(url = trimmed)
    }

    val headers = LinkedHashMap<String, String>()
    val subtitleUrls = ArrayList<String>()
    encodedHeaders
        .split('&')
        .forEach { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@forEach
            val rawName = decodeHeaderComponent(entry.substring(0, separator)).trim()
            val rawValuePart = decodeHeaderComponent(entry.substring(separator + 1)).trim()
            if (isSubtitleParameterName(rawName)) {
                sanitizeSubtitleUrl(rawValuePart)?.let { safeSub ->
                    if (subtitleUrls.size < MAX_PLAYBACK_SUBTITLES && safeSub !in subtitleUrls) {
                        subtitleUrls += safeSub
                    }
                }
            } else if (headers.size < MAX_PLAYBACK_HEADERS) {
                sanitizeHeader(rawName, rawValuePart)?.let { (safeName, safeValue) ->
                    headers[safeName] = safeValue
                }
            }
        }

    return if (headers.isEmpty() && subtitleUrls.isEmpty()) {
        PlaybackSource(url = trimmed)
    } else {
        PlaybackSource(
            url = trimmed.substring(0, separatorIndex).trim(),
            headers = headers,
            subtitleUrls = subtitleUrls
        )
    }
}

fun withPlaybackHeaders(
    rawUrl: String,
    additionalHeaders: Map<String, String>,
    additionalSubtitles: List<String> = emptyList()
): String {
    val source = parsePlaybackSource(rawUrl)
    val mergedHeaders = LinkedHashMap(source.headers)
    val mergedSubtitles = ArrayList(source.subtitleUrls)
    additionalHeaders.forEach { (name, value) ->
        if (isSubtitleParameterName(name.trim())) {
            sanitizeSubtitleUrl(value.trim())?.let { safeSub ->
                if (mergedSubtitles.size < MAX_PLAYBACK_SUBTITLES && safeSub !in mergedSubtitles) {
                    mergedSubtitles += safeSub
                }
            }
        } else {
            sanitizeHeader(name, value)?.let { (safeName, safeValue) ->
                mergedHeaders[safeName] = safeValue
            }
        }
    }
    additionalSubtitles.forEach { subUrl ->
        sanitizeSubtitleUrl(subUrl.trim())?.let { safeSub ->
            if (mergedSubtitles.size < MAX_PLAYBACK_SUBTITLES && safeSub !in mergedSubtitles) {
                mergedSubtitles += safeSub
            }
        }
    }
    if (mergedHeaders.isEmpty() && mergedSubtitles.isEmpty()) return source.url

    val headerParts = mergedHeaders.entries.map { (name, value) ->
        "${encodeHeaderComponent(name)}=${encodeHeaderComponent(value)}"
    }
    val subtitleParts = mergedSubtitles.map { subUrl ->
        "${encodeHeaderComponent(SUBTITLE_PARAM_PREFIX)}=${encodeHeaderComponent(subUrl)}"
    }
    val encoded = (headerParts + subtitleParts).joinToString("&")
    return "${source.url}|$encoded"
}

fun playbackProfileKey(rawUrl: String): String {
    val normalizedUrl = parsePlaybackSource(rawUrl).url.trim()
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalizedUrl.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

fun playbackSourceSummary(rawUrl: String): Pair<String, String> {
    val sourceUrl = parsePlaybackSource(rawUrl).url
    val schemeSeparator = sourceUrl.indexOf("://")
    if (schemeSeparator <= 0) return "unknown" to "local/unknown"

    val scheme = sourceUrl.substring(0, schemeSeparator).lowercase(Locale.ROOT)
    val authority = sourceUrl.substring(schemeSeparator + 3).substringBefore('/')
    val host = authority.substringAfterLast('@').substringBefore(':').ifBlank { "unknown" }
    val hostFingerprint = MessageDigest.getInstance("SHA-256")
        .digest(host.toByteArray(StandardCharsets.UTF_8))
        .take(6)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return scheme to hostFingerprint
}

private fun isSubtitleParameterName(name: String): Boolean {
    return when (name.lowercase(Locale.ROOT)) {
        "subtitle-url", "subtitle", "subtitles", "sub-file", "tvg-subtitles", "sub-url" -> true
        else -> false
    }
}

private fun sanitizeSubtitleUrl(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank() || trimmed.any { it.isWhitespace() || it in "<>\"\r\n" }) return null
    val scheme = trimmed.substringBefore("://", "").lowercase(Locale.ROOT)
    return if (scheme in setOf("http", "https")) trimmed else null
}

private fun sanitizeHeader(name: String, value: String): Pair<String, String>? {
    val normalizedName = canonicalHeaderName(name.trim()) ?: return null
    val normalizedValue = value.trim()
    if (normalizedValue.isBlank() || normalizedValue.any { it == '\r' || it == '\n' }) return null
    return normalizedName to normalizedValue
}

private fun canonicalHeaderName(name: String): String? {
    if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) return null
    return when (name.lowercase(Locale.ROOT)) {
        "user-agent", "http-user-agent" -> "User-Agent"
        "referer", "referrer", "http-referrer" -> "Referer"
        "origin", "http-origin" -> "Origin"
        "cookie", "http-cookie" -> "Cookie"
        else -> name.split('-').joinToString("-") { part ->
            part.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercaseChar)
        }
    }
}

private fun decodeHeaderComponent(value: String): String = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}.getOrDefault(value)

private fun encodeHeaderComponent(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

package com.idealplayer.app.data.parser

import com.idealplayer.app.core.common.limitedTo
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

const val MAX_XMLTV_COMPRESSED_BYTES = 128L * 1024L * 1024L
const val MAX_XMLTV_EXPANDED_BYTES = 512L * 1024L * 1024L

fun InputStream.asXmltvInputStream(
    sourceUrl: String,
    contentType: String? = null,
    maxCompressedBytes: Long = MAX_XMLTV_COMPRESSED_BYTES,
    maxExpandedBytes: Long = MAX_XMLTV_EXPANDED_BYTES
): InputStream {
    // Apply a budget both before and after decompression. The expanded limit is what prevents a
    // small gzip payload from behaving like a zip bomb on low-memory TV hardware.
    val pushback = PushbackInputStream(
        BufferedInputStream(limitedTo(maxCompressedBytes)),
        2
    )
    val first = pushback.read()
    val second = pushback.read()
    if (second >= 0) pushback.unread(second)
    if (first >= 0) pushback.unread(first)

    val hasGzipMagic = first == 0x1f && second == 0x8b
    val lowerUrl = sourceUrl.lowercase(Locale.ROOT)
    val lowerContentType = contentType.orEmpty().lowercase(Locale.ROOT)
    val looksGzipped = hasGzipMagic ||
        lowerUrl.endsWith(".gz") ||
        lowerUrl.contains(".xml.gz") ||
        lowerContentType.contains("gzip") ||
        lowerContentType.contains("application/x-gzip")

    val decoded = if (looksGzipped) GZIPInputStream(pushback) else pushback
    return decoded.limitedTo(maxExpandedBytes)
}

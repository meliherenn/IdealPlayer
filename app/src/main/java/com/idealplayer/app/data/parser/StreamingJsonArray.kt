package com.idealplayer.app.data.parser

import com.idealplayer.app.core.common.limitedTo
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody

/** Owns the response body; at most one bounded JSON object is decoded at a time. */
internal fun <T> Json.readCatalogArray(
    body: ResponseBody,
    serializer: DeserializationStrategy<T>,
    cancellationCheck: () -> Unit,
    consume: (T) -> Unit
) {
    body.use {
        val input = body.byteStream().limitedTo(64L * 1024 * 1024).buffered(8192)
        var bytesRead = 0
        fun read(): Int {
            if (bytesRead++ % 8192 == 0) cancellationCheck()
            return input.read()
        }
        fun nextToken(): Int {
            var value = read()
            while (value == 32 || value == 9 || value == 10 || value == 13) value = read()
            return value
        }
        fun malformed(): Nothing = throw IOException("Invalid catalog JSON array")
        if (nextToken() != '['.code) malformed()
        var token = nextToken()
        val record = ByteArrayOutputStream(4096)
        var count = 0
        if (token != ']'.code) while (true) {
            cancellationCheck()
            if (token != '{'.code) malformed()
            if (++count > 100_000) throw IOException("Catalog exceeds the item limit")
            record.reset()
            var depth = 0
            var quoted = false
            var escaped = false
            do {
                if (token < 0) malformed()
                if (record.size() >= 256 * 1024) throw IOException("Catalog item exceeds the byte limit")
                record.write(token)
                if (quoted) {
                    if (escaped) escaped = false
                    else if (token == '\\'.code) escaped = true
                    else if (token == '"'.code) quoted = false
                } else when (token) {
                    '"'.code -> quoted = true
                    '{'.code, '['.code -> if (++depth > 128) throw IOException("Catalog nesting exceeds the limit")
                    '}'.code, ']'.code -> depth--
                }
                if (depth > 0) token = read()
            } while (depth > 0)
            consume(decodeFromString(serializer, record.toString(Charsets.UTF_8.name())))
            token = nextToken()
            if (token == ']'.code) break
            if (token != ','.code) malformed()
            token = nextToken()
        }
        // A valid prefix followed by a truncated/error response must not publish a partial snapshot.
        if (nextToken() != -1) malformed()
        cancellationCheck()
    }
}

package com.idealplayer.app.data.repository

import android.content.Context
import com.idealplayer.app.core.database.EpgProgramEntity
import com.idealplayer.app.core.security.SecretEnvelopeCodec
import com.idealplayer.app.core.security.SecretDecodeResult
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.json.*

/** Bounded encrypted batches; no Room writer is held while downloading/parsing XMLTV. */
internal class EpgStagingStore(context: Context) : Closeable {
    private val codec = SecretEnvelopeCodec.forTransientStorage()
    private val directory = File(context.cacheDir, "epg-staging").apply { mkdirs() }
    private val file = File.createTempFile("guide-", ".stage", directory)
    private val handle = RandomAccessFile(file, "rw")
    private val lock = handle.channel.lock()
    var count: Int = 0
        private set

    init {
        // A process death loses the transient key. Only remove files without a live owner.
        directory.listFiles()?.filter { it != file && it.name.startsWith("guide-") && it.name.endsWith(".stage") }
            ?.forEach { abandoned ->
                runCatching {
                    RandomAccessFile(abandoned, "rw").use { old ->
                        old.channel.tryLock()?.use { abandoned.delete() }
                    }
                }
            }
    }

    fun append(batch: List<EpgProgramEntity>) {
        val payload = buildJsonArray {
            batch.forEach { row -> add(buildJsonObject {
                put("channel", row.channelId); put("title", row.title); put("description", row.description)
                put("start", row.startTime); put("end", row.endTime)
                put("poster", row.posterUrl); put("genre", row.genre)
            }) }
        }
        val bytes = codec.encrypt(payload.toString()).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BATCH_BYTES) { "EPG staging batch exceeds limit" }
        require(handle.length() + bytes.size + 4 <= 1024L * 1024 * 1024) { "EPG staging exceeds disk budget" }
        handle.writeInt(bytes.size)
        handle.write(bytes)
        count += batch.size
    }

    suspend fun forEachBatch(consume: suspend (List<EpgProgramEntity>) -> Unit) {
        handle.seek(0)
        while (handle.filePointer < handle.length()) {
            val size = handle.readInt()
            require(size in 1..MAX_BATCH_BYTES) { "Invalid EPG staging batch" }
            val bytes = ByteArray(size).also(handle::readFully)
            val payload = (codec.decode(bytes.toString(Charsets.UTF_8)) as? SecretDecodeResult.Available)
                ?.value ?: error("Invalid encrypted EPG staging batch")
            val rows = Json.parseToJsonElement(payload).jsonArray.map { element ->
                val row = element.jsonObject
                EpgProgramEntity(
                    channelId = row.getValue("channel").jsonPrimitive.content,
                    title = row.getValue("title").jsonPrimitive.content,
                    description = row.getValue("description").jsonPrimitive.content,
                    startTime = row.getValue("start").jsonPrimitive.long,
                    endTime = row.getValue("end").jsonPrimitive.long,
                    posterUrl = row.getValue("poster").jsonPrimitive.content,
                    genre = row.getValue("genre").jsonPrimitive.content
                )
            }
            consume(rows)
        }
    }

    override fun close() {
        try { lock.release() } finally {
            try { handle.close() } finally { file.delete() }
        }
    }

    private companion object { const val MAX_BATCH_BYTES = 64 * 1024 * 1024 }
}

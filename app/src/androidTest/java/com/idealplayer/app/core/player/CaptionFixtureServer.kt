package com.idealplayer.app.core.player

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The production Media3 adapter consumes HTTP sources, so both engines use the same loopback URL. */
internal class CaptionFixtureServer(
    private val bytes: ByteArray,
    private val responseDelayMs: Long = 0L,
    private val fileName: String = "caption-fixture.mkv",
    private val additionalFiles: Map<String, ByteArray> = emptyMap()
) : AutoCloseable {
    val requestCount = AtomicInteger()
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "caption-fixture").apply { isDaemon = true }
    }
    val url = "http://127.0.0.1:${socket.localPort}/$fileName"

    init {
        workers.execute {
            while (!socket.isClosed) {
                try {
                    val connection = socket.accept()
                    connections.add(connection)
                    if (socket.isClosed) {
                        connection.close()
                        connections.remove(connection)
                        break
                    }
                    try {
                        workers.execute {
                            try {
                                runCatching { serve(connection) }
                            } finally {
                                connections.remove(connection)
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        connection.close()
                        connections.remove(connection)
                        if (!socket.isClosed) throw IllegalStateException("Fixture executor closed unexpectedly")
                    }
                } catch (closed: SocketException) {
                    if (!socket.isClosed) throw closed
                }
            }
        }
    }

    private fun serve(connection: Socket) = connection.use {
        it.soTimeout = 5_000
        val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = reader.readLine()?.split(' ') ?: return@use
        val method = request.first()
        val path = request.getOrNull(1)?.substringBefore('?')?.removePrefix("/") ?: return@use
        requestCount.incrementAndGet()
        var range: String? = null
        while (true) {
            val header = reader.readLine()?.takeIf(String::isNotEmpty) ?: break
            if (header.startsWith("Range:", ignoreCase = true)) range = header.substringAfter(':').trim()
        }
        if (responseDelayMs > 0L) Thread.sleep(responseDelayMs)
        val bytes = if (path == fileName) this.bytes else additionalFiles[path]
        if (bytes == null) {
            it.getOutputStream().apply {
                write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            return@use
        }
        val contentType = when (path.substringAfterLast('.')) {
            "m3u8" -> "application/vnd.apple.mpegurl"
            "ts" -> "video/mp2t"
            "m4a" -> "audio/mp4"
            else -> "video/x-matroska"
        }
        val match = range?.let { header -> Regex("bytes=(\\d+)-(\\d*)", RegexOption.IGNORE_CASE).matchEntire(header) }
        val start = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val end = (match?.groupValues?.get(2)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
        val output = it.getOutputStream()
        if (start !in bytes.indices || end < start) {
            output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            val length = end - start + 1
            val status = if (match == null) "200 OK" else "206 Partial Content"
            val contentRange = if (match == null) "" else "Content-Range: bytes $start-$end/${bytes.size}\r\n"
            output.write(("HTTP/1.1 $status\r\nContent-Type: $contentType\r\nAccept-Ranges: bytes\r\n" +
                contentRange + "Content-Length: $length\r\nConnection: close\r\n\r\n").toByteArray())
            if (method != "HEAD") output.write(bytes, start, length)
        }
        output.flush()
    }

    override fun close() {
        socket.close()
        // Interrupting a worker alone does not unblock a socket read/write. Close accepted
        // connections too, so one test's fixture cannot retain threads/FDs in the next test.
        connections.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        check(workers.awaitTermination(5, TimeUnit.SECONDS)) { "Fixture workers did not terminate" }
    }
}

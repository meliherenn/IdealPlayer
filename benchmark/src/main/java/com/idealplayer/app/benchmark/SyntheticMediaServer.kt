package com.idealplayer.app.benchmark

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Local HTTP server used by playback benchmarks; no external provider or network is involved. */
internal class SyntheticMediaServer(private val mediaBytes: ByteArray = MEDIA_BYTES) : Closeable {
    private val running = AtomicBoolean(true)
    // Android may resolve getLoopbackAddress() to IPv6 ::1 while the encrypted fixture URLs
    // deliberately use IPv4 127.0.0.1. Bind the matching address explicitly.
    private val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "idealplayer-synthetic-media").apply { isDaemon = true }
    }

    val port: Int = serverSocket.localPort

    init {
        executor.execute(::acceptConnections)
    }

    private fun acceptConnections() {
        while (running.get()) {
            try {
                val socket = serverSocket.accept()
                executor.execute { serve(socket) }
            } catch (_: SocketException) {
                if (running.get()) throw IllegalStateException("Synthetic media socket closed")
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { connection ->
            connection.soTimeout = SOCKET_TIMEOUT_MS
            val reader = BufferedReader(
                InputStreamReader(connection.getInputStream(), StandardCharsets.ISO_8859_1)
            )
            val requestLine = reader.readLine() ?: return
            val method = requestLine.substringBefore(' ')
            var rangeHeader: String? = null
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = header.substringAfter(':').trim()
                }
            }

            val range = requestedRange(rangeHeader, mediaBytes.size)
            val output = connection.getOutputStream()
            if (range == null && rangeHeader != null) {
                output.write(
                    ("HTTP/1.1 416 Range Not Satisfiable\r\n" +
                        "Content-Range: bytes */${mediaBytes.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
                )
                output.flush()
                return
            }

            val start = range?.first ?: 0
            val end = range?.last ?: mediaBytes.lastIndex
            val length = end - start + 1
            val status = if (range == null) "200 OK" else "206 Partial Content"
            val contentRange = if (range == null) "" else {
                "Content-Range: bytes $start-$end/${mediaBytes.size}\r\n"
            }
            output.write(
                ("HTTP/1.1 $status\r\n" +
                    "Content-Type: video/mp4\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    contentRange +
                    "Content-Length: $length\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
            )
            if (!method.equals("HEAD", ignoreCase = true)) {
                output.write(mediaBytes, start, length)
            }
            output.flush()
        }
    }

    private fun requestedRange(header: String?, size: Int): IntRange? {
        if (header == null) return null
        val match = RANGE_REGEX.matchEntire(header) ?: return null
        val first = match.groupValues[1]
        val last = match.groupValues[2]
        if (first.isEmpty()) {
            val suffixLength = last.toIntOrNull()?.takeIf { it > 0 } ?: return null
            return (size - suffixLength).coerceAtLeast(0)..size - 1
        }
        val start = first.toIntOrNull()?.takeIf { it in 0 until size } ?: return null
        val end = last.toIntOrNull()?.coerceAtMost(size - 1) ?: size - 1
        return if (end >= start) start..end else null
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        serverSocket.close()
        executor.shutdownNow()
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 5_000
        val RANGE_REGEX = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE)
        val MEDIA_BYTES: ByteArray = Base64.getDecoder().decode(
            "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"
        )
    }
}

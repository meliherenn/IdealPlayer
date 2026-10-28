package com.idealplayer.app.di

import com.google.common.truth.Truth.assertThat
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real loopback sockets exercise OkHttp's redirect and Bridge interceptors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackHttpRedirectTest {
    @Test fun `redirect affinity survives and explicit credential wins name collision`() {
        Server { path, _, port ->
            if (path == "/start") "302 Found" to listOf("Location: /end", "Set-Cookie: affinity=node; Path=/", "Set-Cookie: auth=rotated; Path=/")
            else "200 OK" to emptyList()
        }.use { server ->
            val jar = SessionCookieJar()
            val client = buildPlaybackOkHttpClient(OkHttpClient(), jar)
            client.newCall(Request.Builder().url(server.url("/start")).header("Cookie", "auth=explicit").build()).execute().close()
            assertThat(server.requests.last()["cookie"]).isEqualTo("auth=explicit; affinity=node")
            assertThat(jar.loadForRequest(server.url("/end").toHttpUrl()).map { it.name }).containsAtLeast("affinity", "auth")
        }
    }

    @Test fun `cross host redirects strip explicit cookie custom token and referer`() {
        Server { path, _, port ->
            if (path == "/start") "302 Found" to listOf("Location: http://localhost:$port/end", "Set-Cookie: affinity=node; Path=/")
            else "200 OK" to emptyList()
        }.use { server ->
            val client = buildPlaybackOkHttpClient(OkHttpClient(), SessionCookieJar())
            client.newCall(Request.Builder().url(server.url("/start"))
                .header("Cookie", "auth=synthetic").header("Authorization", "Bearer synthetic")
                .header("X-Provider-Token", "synthetic").header("Referer", "https://example.test/private")
                .header("User-Agent", "SyntheticPlayer").build()).execute().close()
            val received = server.requests.last()
            listOf("cookie", "authorization", "x-provider-token", "referer").forEach { assertThat(received[it]).isNull() }
            assertThat(received["user-agent"]).isEqualTo("SyntheticPlayer")
        }
    }

    @Test fun `initial CDN or subtitle URL cannot inherit source credentials`() {
        Server { _, _, _ -> "200 OK" to emptyList() }.use { server ->
            val client = buildPlaybackOkHttpClient(OkHttpClient(), SessionCookieJar(), "https://example.test/source".toHttpUrl())
            client.newCall(Request.Builder().url(server.url("/subtitle.vtt"))
                .header("Cookie", "auth=synthetic").header("X-Token", "synthetic").build()).execute().close()
            assertThat(server.requests.single()["cookie"]).isNull()
            assertThat(server.requests.single()["x-token"]).isNull()
        }
    }

    @Test fun `origin includes scheme and port and cookie rules retain path and secure`() {
        assertThat(sameHttpOrigin("https://example.test/a".toHttpUrl(), "http://example.test/a".toHttpUrl())).isFalse()
        assertThat(sameHttpOrigin("https://example.test/a".toHttpUrl(), "https://example.test:444/a".toHttpUrl())).isFalse()
        val jar = SessionCookieJar()
        jar.saveFromResponse("https://example.test/live".toHttpUrl(), listOf(
            Cookie.Builder().name("sid").value("synthetic").hostOnlyDomain("example.test").path("/live").secure().build()))
        assertThat(jar.loadForRequest("http://example.test/live".toHttpUrl())).isEmpty()
        assertThat(jar.loadForRequest("https://example.test/subtitle".toHttpUrl())).isEmpty()
        assertThat(jar.loadForRequest("https://example.test/live/segment".toHttpUrl())).hasSize(1)
    }

    private class Server(val respond: (String, Map<String, String>, Int) -> Pair<String, List<String>>) : Closeable {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val requests = Collections.synchronizedList(mutableListOf<Map<String, String>>())
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val connection = try { socket.accept() } catch (_: java.io.IOException) { break }
                connection.use {
                    val input = it.getInputStream().bufferedReader()
                    val path = input.readLine().split(' ')[1]
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    requests += headers
                    val (status, extra) = respond(path, headers, socket.localPort)
                    val response = (listOf("HTTP/1.1 $status", "Content-Length: 0", "Connection: close") + extra).joinToString("\r\n") + "\r\n\r\n"
                    it.getOutputStream().write(response.toByteArray())
                }
            }
        }
        fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"
        override fun close() { socket.close(); worker.join(2_000) }
    }
}

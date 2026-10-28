package com.idealplayer.app.core.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.idealplayer.app.di.SessionCookieJar
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Keep QUIC for stateless GETs; stateful playback uses the origin-aware cookie transport. */
@OptIn(UnstableApi::class)
internal class SessionHttpDataSourceFactory(
    private val fallback: HttpDataSource.Factory,
    private val accelerated: HttpDataSource.Factory?,
    private val cookies: SessionCookieJar
) : HttpDataSource.Factory {
    private val properties = HttpDataSource.RequestProperties()

    override fun setDefaultRequestProperties(values: Map<String, String>): HttpDataSource.Factory = apply {
        properties.clearAndSet(values)
        fallback.setDefaultRequestProperties(values)
    }

    override fun createDataSource(): HttpDataSource {
        val secure = fallback.createDataSource()
        val fast = accelerated?.createDataSource() ?: return secure
        return object : HttpDataSource by secure {
            private var active: HttpDataSource = secure
            private val localProperties = mutableMapOf<String, String>()

            override fun open(dataSpec: DataSpec): Long {
                val url = dataSpec.uri.toString().toHttpUrlOrNull()
                val stateful = properties.snapshot.isNotEmpty() || localProperties.isNotEmpty() ||
                    dataSpec.httpRequestHeaders.isNotEmpty() || url == null ||
                    cookies.loadForRequest(url).isNotEmpty()
                active = if (!stateful && dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET) fast else secure
                try {
                    val length = active.open(dataSpec)
                    if (active === fast && fast.uri != dataSpec.uri) {
                        // Re-run redirects through the policy transport even if the final
                        // response succeeded: intermediate Set-Cookie headers are otherwise hidden.
                        fast.close()
                        active = secure
                        return secure.open(dataSpec)
                    }
                    if (active === fast) {
                        val responseUrl = fast.uri?.toString()?.toHttpUrlOrNull()
                        if (responseUrl != null) {
                            val headers = Headers.Builder().apply {
                                fast.responseHeaders.forEach { (name, values) ->
                                    if (name.equals("Set-Cookie", true)) values.forEach { add(name, it) }
                                }
                            }.build()
                            cookies.saveFromResponse(responseUrl, Cookie.parseAll(responseUrl, headers))
                        }
                    }
                    return length
                } catch (error: IOException) {
                    if (active !== fast || Thread.currentThread().isInterrupted ||
                        generateSequence<Throwable>(error) { it.cause }.any {
                            it is java.io.InterruptedIOException && it !is java.net.SocketTimeoutException
                        }) throw error
                    // Includes CDNs whose redirect requires a Set-Cookie challenge. Restart the
                    // GET through OkHttp, which processes every hop; never replay a POST here.
                    runCatching { fast.close() }
                    active = secure
                    return secure.open(dataSpec)
                }
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int) = active.read(buffer, offset, length)
            override fun getUri() = active.uri
            override fun getResponseCode() = active.responseCode
            override fun getResponseHeaders() = active.responseHeaders
            override fun close() = active.close()
            override fun addTransferListener(listener: TransferListener) {
                secure.addTransferListener(listener)
                fast.addTransferListener(listener)
            }
            override fun setRequestProperty(name: String, value: String) {
                localProperties[name] = value
                secure.setRequestProperty(name, value)
            }
            override fun clearRequestProperty(name: String) {
                localProperties.remove(name)
                secure.clearRequestProperty(name)
            }
            override fun clearAllRequestProperties() {
                localProperties.clear()
                secure.clearAllRequestProperties()
            }
        }
    }
}

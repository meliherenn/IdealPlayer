package com.idealplayer.app.di

import android.content.Context
import android.net.http.HttpEngine
import android.os.Build
import com.idealplayer.app.core.network.TmdbApi
import com.idealplayer.app.core.network.XtreamApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Bounded in-memory cookie jar that retains session-affinity and CDN redirect cookies
 * across manifest/segment requests without writing provider tokens to disk.
 * Used exclusively by the playback transport pipeline, never on shared API clients.
 */
internal class SessionCookieJar(
    private val maxHosts: Int = 64,
    private val maxCookiesPerHost: Int = 32,
    private val nowMs: () -> Long = System::currentTimeMillis
) : CookieJar {
    private val lock = Any()
    private val store = LinkedHashMap<String, MutableList<Cookie>>(16, 0.75f, true)

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val hostKey = url.host.lowercase(Locale.ROOT)
        val now = nowMs()
        synchronized(lock) {
            val existing = store.getOrPut(hostKey) { mutableListOf() }
            cookies.forEach { incoming ->
                existing.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                if (incoming.expiresAt > now) {
                    existing += incoming
                }
            }
            existing.removeAll { it.expiresAt <= now }
            while (existing.size > maxCookiesPerHost) {
                existing.removeAt(0)
            }
            if (existing.isEmpty()) {
                store.remove(hostKey)
            }
            while (store.size > maxHosts) {
                val eldestKey = store.entries.firstOrNull()?.key ?: break
                store.remove(eldestKey)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = nowMs()
        synchronized(lock) {
            val matched = mutableListOf<Cookie>()
            val iterator = store.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val cookies = entry.value
                cookies.removeAll { it.expiresAt <= now }
                if (cookies.isEmpty()) {
                    iterator.remove()
                    continue
                }
                cookies.forEach { cookie ->
                    if (cookie.matches(url)) {
                        matched += cookie
                    }
                }
            }
            return matched
        }
    }

    fun clear() {
        synchronized(lock) {
            store.clear()
        }
    }
}

/**
 * Merges session cookies from [cookieJar] with any explicit `Cookie` header provided by
 * the playlist (`#EXTVLCOPT:http-cookie`, `#EXTHTTP`, or pipe headers) so that OkHttp's
 * BridgeInterceptor never overwrites explicit authentication cookies when a CDN sets a
 * load-balancer cookie.
 */
internal fun mergeCookieHeaders(
    explicitCookieHeader: String?,
    jarCookies: List<Cookie>
): String? {
    val merged = LinkedHashMap<String, String>()
    explicitCookieHeader
        ?.split(';')
        ?.forEach { pair ->
            val trimmed = pair.trim()
            val eqIndex = trimmed.indexOf('=')
            if (eqIndex > 0) {
                val name = trimmed.substring(0, eqIndex).trim()
                val value = trimmed.substring(eqIndex + 1).trim()
                if (name.isNotEmpty()) {
                    merged[name] = value
                }
            }
        }
    jarCookies.forEach { cookie ->
        // A playlist credential is authoritative; affinity cookies may add other names.
        merged.putIfAbsent(cookie.name, cookie.value)
    }
    if (merged.isEmpty()) return explicitCookieHeader?.takeIf { it.isNotBlank() }
    return merged.entries.joinToString("; ") { (name, value) -> "$name=$value" }
}

internal fun sameHttpOrigin(first: HttpUrl, second: HttpUrl): Boolean =
    first.scheme == second.scheme && first.host == second.host && first.port == second.port

private data class PlaybackRequestOrigin(val url: HttpUrl, val headers: okhttp3.Headers)

internal fun buildPlaybackOkHttpClient(
    baseClient: OkHttpClient,
    cookieJar: SessionCookieJar,
    credentialOrigin: HttpUrl? = null
): OkHttpClient = baseClient.newBuilder()
    .cookieJar(CookieJar.NO_COOKIES)
    .addInterceptor { chain ->
        val request = chain.request()
        chain.proceed(request.newBuilder().tag(
            PlaybackRequestOrigin::class.java,
            PlaybackRequestOrigin(credentialOrigin ?: request.url, request.headers)
        ).build())
    }
    .addNetworkInterceptor { chain ->
        val request = chain.request()
        val origin = requireNotNull(request.tag(PlaybackRequestOrigin::class.java))
        val sameOrigin = sameHttpOrigin(origin.url, request.url)
        val builder = request.newBuilder().removeHeader("Cookie")
        if (!sameOrigin) {
            // Include custom provider authorization headers, not just Authorization.
            origin.headers.names().filterNot { it.lowercase(Locale.ROOT) in PUBLIC_PLAYBACK_HEADERS }
                .forEach(builder::removeHeader)
            builder.removeHeader("Authorization").removeHeader("Proxy-Authorization")
                .removeHeader("Referer")
        }
        val cookies = mergeCookieHeaders(
            origin.headers["Cookie"].takeIf { sameOrigin },
            cookieJar.loadForRequest(request.url)
        )
        if (cookies != null) builder.header("Cookie", cookies)
        val response = chain.proceed(builder.build())
        // A network interceptor sees every redirect response, before the follow-up request.
        cookieJar.saveFromResponse(response.request.url, Cookie.parseAll(response.request.url, response.headers))
        response
    }
    .build()

private val PUBLIC_PLAYBACK_HEADERS = setOf(
    "user-agent", "accept", "accept-encoding", "accept-language", "range", "if-range",
    "connection", "host", "cache-control", "pragma"
)

/**
 * Probes platform Cronet / Android [HttpEngine] (HTTP/2 & HTTP/3 QUIC) availability
 * without introducing unreviewed native binaries into the packaged APK.
 */
object CronetSupport {
    fun isPlatformHttpEngineAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7

    fun createPlatformHttpEngineOrNull(context: Context): HttpEngine? {
        if (!isPlatformHttpEngineAvailable()) return null
        return runCatching {
            HttpEngine.Builder(context.applicationContext)
                .setEnableHttp2(true)
                .setEnableQuic(true)
                .build()
        }.getOrNull()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request()
                val nextRequest = if (request.header("User-Agent").isNullOrBlank()) {
                    request.newBuilder()
                        .header("User-Agent", "IdealPlayer/Android")
                        .build()
                } else {
                    request
                }
                chain.proceed(nextRequest)
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideXtreamApi(client: OkHttpClient): XtreamApi {
        // The shared client also serves potentially large XMLTV/APK downloads. Bound only the
        // Xtream catalog requests so an unresponsive provider cannot leave a playlist refresh
        // pending indefinitely without cutting off legitimate long-lived downloads elsewhere.
        val xtreamClient = client.newBuilder()
            .callTimeout(2, TimeUnit.MINUTES)
            .build()
        return Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(xtreamClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(XtreamApi::class.java)
    }

    @Provides
    @Singleton
    fun provideTmdbApi(client: OkHttpClient): TmdbApi {
        return Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmdbApi::class.java)
    }
}

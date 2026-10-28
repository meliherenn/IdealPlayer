package com.idealplayer.app.core.common

import android.content.Context
import java.io.IOException
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response

/** Coil persists response headers beside image bytes. Never retain provider cookies/URLs/tokens. */
internal class ArtworkCacheHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // Application interceptors see the final response, after redirects and transparent gzip.
        val response = chain.proceed(chain.request())
        val mime = response.body?.contentType()?.let { "${it.type}/${it.subtype}" }
        val headers = Headers.Builder().apply {
            // Fixed values only: even MIME parameters can contain reflected request identifiers.
            if (mime in ARTWORK_MIME_TYPES) add("Content-Type", checkNotNull(mime))
        }.build()
        return response.newBuilder().headers(headers).build()
    }
}

private val ARTWORK_MIME_TYPES = setOf(
    "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp",
    "image/avif", "image/heif", "image/heic", "image/x-icon", "image/vnd.microsoft.icon"
)

internal const val ARTWORK_CACHE_DIRECTORY = "artwork_cache_v2"

/** Call on IO; the previous cache may contain plaintext HTTP metadata. */
internal fun removeLegacyArtworkCache(context: Context) {
    val legacy = context.cacheDir.resolve("image_cache")
    if (legacy.exists() && !legacy.deleteRecursively()) {
        throw IOException("Legacy artwork cache cleanup failed")
    }
}

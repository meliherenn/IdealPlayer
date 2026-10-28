package com.idealplayer.app.core.common

import android.app.ActivityManager
import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import okhttp3.OkHttpClient
import okhttp3.CookieJar
import java.util.concurrent.TimeUnit

/**
 * Builds an optimized Coil [ImageLoader] with:
 * - 100 MB disk cache
 * - RAM-aware memory cache with an absolute TV-safe ceiling
 * - crossfade(300ms)
 * - Respectful cache policy
 */
fun buildIdealPlayerImageLoader(context: Context, okHttpClient: OkHttpClient): ImageLoader {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val isLowRamDevice = activityManager?.isLowRamDevice == true
    val memoryClassMb = activityManager?.memoryClass ?: 256
    val memoryCacheBytes = imageMemoryCacheMaxSizeBytes(isLowRamDevice, memoryClassMb)

    return ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizeBytes(memoryCacheBytes)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve(ARTWORK_CACHE_DIRECTORY))
                .maxSizeBytes(100L * 1024 * 1024) // 100 MB
                .build()
        }
        .okHttpClient {
            okHttpClient.newBuilder()
                // Coil owns the disk cache; inherited HTTP caches/cookie jars may persist secrets.
                .cache(null)
                .cookieJar(CookieJar.NO_COOKIES)
                .apply { interceptors().add(0, ArtworkCacheHeadersInterceptor()) }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(CachePolicy.ENABLED)
        .crossfade(300)
        .respectCacheHeaders(false) // Many image servers send no-cache headers
        .build()
}

internal fun imageMemoryCachePercent(
    isLowRamDevice: Boolean,
    memoryClassMb: Int
): Double = if (isLowRamDevice || memoryClassMb <= 256) 0.12 else 0.20

internal fun imageMemoryCacheMaxSizeBytes(
    isLowRamDevice: Boolean,
    memoryClassMb: Int
): Int {
    val memoryClassBytes = memoryClassMb.coerceAtLeast(1) * MEBIBYTE
    val proportionalBytes = (memoryClassBytes * imageMemoryCachePercent(isLowRamDevice, memoryClassMb))
        .toLong()
    val absoluteCap = if (isLowRamDevice || memoryClassMb <= 256) {
        24L * MEBIBYTE
    } else {
        64L * MEBIBYTE
    }
    return proportionalBytes.coerceAtMost(absoluteCap).toInt()
}

private const val MEBIBYTE = 1024L * 1024L

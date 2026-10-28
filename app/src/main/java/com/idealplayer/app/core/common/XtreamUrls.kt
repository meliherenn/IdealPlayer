package com.idealplayer.app.core.common

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Every credential is one path segment; slashes, percent signs and query delimiters are data. */
fun buildXtreamPathUrl(serverUrl: String, vararg segments: String): String =
    serverUrl.trim().trimEnd('/').toHttpUrl().newBuilder()
        .query(null).fragment(null)
        .apply { segments.forEach(::addPathSegment) }
        .build().toString()

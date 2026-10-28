package com.idealplayer.app.di

import com.google.common.truth.Truth.assertThat
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class SessionCookieJarTest {

    @Test
    fun `sessionCookieJar retains valid cookies per host and evicts expired entries`() {
        var now = 1_000L
        val jar = SessionCookieJar(maxHosts = 2, maxCookiesPerHost = 2, nowMs = { now })
        val originUrl = HttpUrl.Builder()
            .scheme("https")
            .host("cdn.example.test")
            .addPathSegment("live")
            .build()
        val cookie1 = Cookie.Builder()
            .name("sid")
            .value("token1")
            .domain("cdn.example.test")
            .path("/")
            .expiresAt(5_000L)
            .build()
        val cookie2 = Cookie.Builder()
            .name("affinity")
            .value("nodeA")
            .domain("cdn.example.test")
            .path("/")
            .expiresAt(2_000L)
            .build()

        jar.saveFromResponse(originUrl, listOf(cookie1, cookie2))
        assertThat(jar.loadForRequest(originUrl).map { it.name }).containsExactly("sid", "affinity")

        now = 3_000L
        assertThat(jar.loadForRequest(originUrl).map { it.name }).containsExactly("sid")
    }

    @Test
    fun `explicit credentials cannot be overwritten by affinity cookies`() {
        val cookie = Cookie.Builder().name("auth").value("server").domain("example.test").build()
        assertThat(mergeCookieHeaders("auth=playlist", listOf(cookie))).isEqualTo("auth=playlist")
    }
}

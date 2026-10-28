package com.idealplayer.app.core.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.di.SessionCookieJar
import io.mockk.*
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionHttpDataSourceFactoryTest {
    private val secure = mockk<HttpDataSource>(relaxed = true)
    private val fast = mockk<HttpDataSource>(relaxed = true)
    private val jar = SessionCookieJar()
    private val factory = SessionHttpDataSourceFactory(
        mockk { every { createDataSource() } returns secure; every { setDefaultRequestProperties(any()) } returns this },
        mockk { every { createDataSource() } returns fast }, jar
    )
    private val spec = DataSpec.Builder().setUri("https://example.test/live.m3u8").build()

    @Test fun `public direct GET retains accelerated transport but subsequent cookie session uses secure transport`() {
        every { fast.open(any()) } returns 10L
        every { fast.uri } returns spec.uri
        every { fast.responseHeaders } returns mapOf("Set-Cookie" to listOf("affinity=node; Path=/"))
        assertThat(factory.createDataSource().open(spec)).isEqualTo(10L)
        factory.createDataSource().open(spec)
        verify(exactly = 1) { fast.open(any()); secure.open(any()) }
    }

    @Test fun `successful redirect is replayed securely to capture intermediate cookies`() {
        every { fast.open(any()) } returns 10L
        every { fast.uri } returns Uri.parse("https://cdn.example.test/live.m3u8")
        every { secure.open(any()) } returns 20L
        assertThat(factory.createDataSource().open(spec)).isEqualTo(20L)
        verifyOrder { fast.open(spec); fast.close(); secure.open(spec) }
    }

    @Test fun `custom headers bypass acceleration and interrupted opens never retry`() {
        factory.setDefaultRequestProperties(mapOf("X-Token" to "synthetic"))
        factory.createDataSource().open(spec)
        verify(exactly = 0) { fast.open(any()) }
        factory.setDefaultRequestProperties(emptyMap())
        every { fast.open(any()) } throws java.io.InterruptedIOException("cancelled")
        Thread.currentThread().interrupt()
        try { assertThat(runCatching { factory.createDataSource().open(spec) }.exceptionOrNull()).isInstanceOf(IOException::class.java) }
        finally { Thread.interrupted() }
        verify(exactly = 1) { secure.open(any()) }
    }
}

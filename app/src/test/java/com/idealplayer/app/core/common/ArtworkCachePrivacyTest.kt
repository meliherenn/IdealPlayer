package com.idealplayer.app.core.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.decode.DataSource
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.truth.Truth.assertThat
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class, coil.annotation.ExperimentalCoilApi::class)
class ArtworkCachePrivacyTest {
    // Coil returns results on Main. Robolectric's paused main looper must not be blocked by runBlocking.
    @Before fun setUpDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun restoreDispatcher() { Dispatchers.resetMain() }

    @Test fun `real Coil cache retains image but not credential URL cookies or reflected headers`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.cacheDir.resolve(ARTWORK_CACHE_DIRECTORY).deleteRecursively()
        val calls = AtomicInteger()
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
        val sentinel = "synthetic-artwork-private-token"
        val url = "https://images.invalid.test/account/$sentinel/poster.png?token=$sentinel"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .header("Set-Cookie", "session=$sentinel")
                .header("Content-Location", url)
                .header("X-Provider-Secret", sentinel)
                .header("ETag", sentinel)
                .header("Content-Type", "image/png; reflected=$sentinel")
                .body(png.toResponseBody("image/png; reflected=$sentinel".toMediaType())).build()
        }.build()
        val loader = buildIdealPlayerImageLoader(context, client)
        try {
            val request = ImageRequest.Builder(context).data(url).size(1, 1)
                .memoryCachePolicy(CachePolicy.DISABLED).build()
            val first = loader.execute(request)
            assertThat(first).isInstanceOf(SuccessResult::class.java)
            val second = loader.execute(request)
            assertThat(second).isInstanceOf(SuccessResult::class.java)
            assertThat((second as SuccessResult).dataSource).isEqualTo(DataSource.DISK)
            assertThat(calls.get()).isEqualTo(1)
            val cacheFiles = context.cacheDir.resolve(ARTWORK_CACHE_DIRECTORY).walkTopDown().filter { it.isFile }.toList()
            assertThat(cacheFiles).isNotEmpty()
            cacheFiles.forEach { file ->
                assertThat(file.name).doesNotContain(sentinel)
                assertThat(file.readBytes().toString(Charsets.ISO_8859_1)).doesNotContain(sentinel)
                assertThat(file.readBytes().toString(Charsets.ISO_8859_1)).doesNotContain("images.invalid.test")
            }
        } finally {
            loader.shutdown()
            loader.diskCache?.clear()
        }
    }
}

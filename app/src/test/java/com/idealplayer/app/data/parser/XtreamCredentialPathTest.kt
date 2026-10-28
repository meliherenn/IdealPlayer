package com.idealplayer.app.data.parser

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.network.XtreamApi
import com.idealplayer.app.core.network.dto.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class XtreamCredentialPathTest {
    @Test fun `live movie and episode paths preserve reserved credential characters as single segments`() = runTest {
        val api = mockk<XtreamApi>(relaxed = true)
        coEvery { api.authenticate(any(), any(), any()) } returns XtreamAuthResponse(
            userInfo = XtreamUserInfo(status = "Active", allowedOutputFormats = listOf("ts")))
        coEvery { api.getCatalog(any(), any(), any(), any()) } answers { "[]".toResponseBody() }
        coEvery { api.getCatalog(any(), any(), any(), "get_live_streams") } answers {
            """[{"stream_id":7,"name":"Live"}]""".toResponseBody()
        }
        coEvery { api.getCatalog(any(), any(), any(), "get_vod_streams") } answers {
            """[{"stream_id":8,"name":"Movie","container_extension":"mp4"}]""".toResponseBody()
        }
        coEvery { api.getSeriesInfo(any(), any(), any(), any(), any()) } returns XtreamSeriesInfo(
            episodes = Json.parseToJsonElement("""{"1":[{"id":"9","episode_num":1,"title":"Episode","container_extension":"mkv"}]}"""))
        val client = XtreamClient(api)
        val user = "synthetic/user ?#% ç"
        val pass = "synthetic/pass ?#% ş"
        val result = client.loadContent("https://example.test/base/", user, pass, 1)
        val episodes = client.loadSeriesEpisodes("https://example.test/base/", user, pass, 42, 30)
        for ((url, expected) in listOf(result.channels.single().streamUrl to listOf("live", "7.ts"),
            result.movies.single().streamUrl to listOf("movie", "8.mp4"), episodes.single().streamUrl to listOf("series", "9.mkv"))) {
            val parsed = url.toHttpUrl()
            assertThat(parsed.pathSegments).containsExactly("base", expected[0], user, pass, expected[1]).inOrder()
            assertThat(parsed.query).isNull()
            assertThat(parsed.fragment).isNull()
        }
        assertThat(episodes.single().sourceEpisodeId).isEqualTo("9")
    }
}

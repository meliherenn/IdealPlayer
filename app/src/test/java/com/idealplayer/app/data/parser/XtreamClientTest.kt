package com.idealplayer.app.data.parser

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.network.XtreamApi
import com.idealplayer.app.core.network.dto.XtreamAuthResponse
import com.idealplayer.app.core.network.dto.XtreamCategory
import com.idealplayer.app.core.network.dto.XtreamLiveStream
import com.idealplayer.app.core.network.dto.XtreamSeriesInfo
import com.idealplayer.app.core.network.dto.XtreamUserInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import org.junit.Test

class XtreamClientTest {
    private val api = mockk<XtreamApi>()
    private val client = XtreamClient(api)
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `twenty thousand raw Xtream records arrive in bounded batches`() = runTest {
        mockActiveAuthentication()
        val file = kotlin.io.path.createTempFile("xtream-synthetic", ".json").toFile()
        try {
            file.bufferedWriter().use { out ->
                out.write("[")
                repeat(20_000) { index ->
                    if (index > 0) out.write(",")
                    out.write("{\"stream_id\":${index + 1},\"name\":\"Synthetic $index\"}")
                }
                out.write("]")
            }
            coEvery { api.getCatalog(any(), any(), any(), any()) } answers { "[]".toResponseBody() }
            coEvery { api.getCatalog(any(), any(), any(), "get_live_streams") } answers {
                object : okhttp3.ResponseBody() {
                    private val input = file.source().buffer()
                    override fun contentType() = null
                    override fun contentLength() = file.length()
                    override fun source() = input
                }
            }
            var received = 0
            val sections = client.loadContentStreaming("https://example.test", "user", "pass", 1,
                object : XtreamStreamingSink {
                    override fun onChannels(channels: List<com.idealplayer.app.core.database.ChannelEntity>) {
                        assertThat(channels.size).isAtMost(500)
                        assertThat(channels.first().streamId).isEqualTo(received + 1)
                        received += channels.size
                        assertThat(channels.last().streamId).isEqualTo(received)
                    }
                    override fun onMovies(movies: List<com.idealplayer.app.core.database.MovieEntity>) { assertThat(movies).isEmpty() }
                    override fun onSeries(series: List<com.idealplayer.app.core.database.SeriesEntity>) { assertThat(series).isEmpty() }
                    override fun onCategories(categories: List<com.idealplayer.app.core.database.CategoryEntity>) { assertThat(categories).isEmpty() }
                })
            assertThat(received).isEqualTo(20_000)
            assertThat(sections).containsExactlyElementsIn(XtreamContentSection.entries)
        } finally { file.delete() }
    }

    @Test
    fun `content refresh fails instead of returning a destructive partial snapshot`() = runTest {
        mockActiveAuthentication()
        coEvery {
            api.getCatalog(any(), any(), any(), "get_live_categories")
        } returns """[{"category_id":"1","category_name":"News"}]""".toResponseBody()
        coEvery {
            api.getCatalog(any(), any(), any(), "get_live_streams")
        } returns """[{"stream_id":1,"name":"Synthetic live","category_id":"1"}]""".toResponseBody()
        coEvery {
            api.getCatalog(any(), any(), any(), "get_vod_categories")
        } throws IOException("Synthetic VOD timeout")

        val error = runCatching {
            client.loadContent(
                serverUrl = "https://provider.example",
                username = "user",
                password = "pass",
                playlistId = 1L
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `live only provider keeps a successful live snapshot when optional catalogs are unsupported`() = runTest {
        mockActiveAuthentication()
        coEvery {
            api.getCatalog(any(), any(), any(), "get_live_categories")
        } returns """[{"category_id":"1","category_name":"News"}]""".toResponseBody()
        coEvery {
            api.getCatalog(any(), any(), any(), "get_live_streams")
        } returns """[{"stream_id":1,"name":"Synthetic live","category_id":"1"}]""".toResponseBody()
        coEvery {
            api.getCatalog(any(), any(), any(), "get_vod_categories")
        } throws unsupportedCatalogHttpException()
        coEvery {
            api.getCatalog(any(), any(), any(), "get_series_categories")
        } throws unsupportedCatalogHttpException()

        val result = client.loadContent(
            serverUrl = "https://provider.example",
            username = "user",
            password = "pass",
            playlistId = 1L
        )

        assertThat(result.channels).hasSize(1)
        assertThat(result.loadedSections).containsExactly(XtreamContentSection.LIVE)
    }

    @Test
    fun `first content refresh authenticates the entered password before loading catalogs`() = runTest {
        coEvery {
            api.authenticate(any(), "user", "wrong-pass")
        } returns XtreamAuthResponse(
            userInfo = XtreamUserInfo(status = "Banned")
        )

        val error = runCatching {
            client.loadContent(
                serverUrl = "https://provider.example",
                username = "user",
                password = "wrong-pass",
                playlistId = 1L
            )
        }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("Account not active")
        coVerify(exactly = 0) {
            api.getCatalog(any(), any(), any(), "get_live_streams")
        }
    }

    @Test
    fun `live stream uses a provider supported output format`() = runTest {
        mockActiveAuthentication(allowedOutputFormats = listOf("ts"))
        coEvery {
            api.getCatalog(any(), any(), any(), "get_live_categories")
        } returns """[{"category_id":"1","category_name":"News"}]""".toResponseBody()
        coEvery {
            api.getCatalog(any(), any(), any(), "get_live_streams")
        } returns """[{"stream_id":1,"name":"Synthetic live","category_id":"1"}]""".toResponseBody()
        coEvery {
            api.getCatalog(any(), any(), any(), "get_vod_categories")
        } throws unsupportedCatalogHttpException()
        coEvery {
            api.getCatalog(any(), any(), any(), "get_series_categories")
        } throws unsupportedCatalogHttpException()

        val result = client.loadContent(
            serverUrl = "https://provider.example",
            username = "user",
            password = "pass",
            playlistId = 1L
        )

        assertThat(result.channels.single().streamUrl)
            .isEqualTo("https://provider.example/live/user/pass/1.ts")
    }

    @Test
    fun `series episodes accept mixed provider value types`() = runTest {
        coEvery {
            api.getSeriesInfo(any(), any(), any(), any(), any())
        } returns XtreamSeriesInfo(
            episodes = json.parseToJsonElement(
                """
                {
                  "Season 2": [
                    {
                      "id": 12345,
                      "episode_num": "3",
                      "title": "Synthetic Episode",
                      "container_extension": "mkv",
                      "info": {
                        "duration_secs": "1800",
                        "rating": "8.4"
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val episodes = client.loadSeriesEpisodes(
            serverUrl = "https://provider.example",
            username = "user",
            password = "pass",
            seriesId = 99,
            dbSeriesId = 7L
        )

        assertThat(episodes).hasSize(1)
        assertThat(episodes.single().seasonNumber).isEqualTo(2)
        assertThat(episodes.single().episodeNumber).isEqualTo(3)
        assertThat(episodes.single().duration).isEqualTo(1800)
        assertThat(episodes.single().rating).isWithin(0.001).of(8.4)
        assertThat(episodes.single().streamUrl)
            .isEqualTo("https://provider.example/series/user/pass/12345.mkv")
    }

    @Test
    fun `series episodes survive malformed optional info`() = runTest {
        coEvery {
            api.getSeriesInfo(any(), any(), any(), any(), any())
        } returns XtreamSeriesInfo(
            episodes = json.parseToJsonElement(
                """
                {
                  "1": [
                    {
                      "id": "67890",
                      "episode_num": 1,
                      "title": "Synthetic Episode",
                      "container_extension": "mp4",
                      "info": []
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val episodes = client.loadSeriesEpisodes(
            serverUrl = "https://provider.example",
            username = "user",
            password = "pass",
            seriesId = 99,
            dbSeriesId = 7L
        )

        assertThat(episodes).hasSize(1)
        assertThat(episodes.single().name).isEqualTo("Synthetic Episode")
        assertThat(episodes.single().streamUrl)
            .isEqualTo("https://provider.example/series/user/pass/67890.mp4")
    }

    @Test
    fun `series episodes prefer provider direct source and avoid duplicate extension`() = runTest {
        coEvery {
            api.getSeriesInfo(any(), any(), any(), any(), any())
        } returns XtreamSeriesInfo(
            episodes = json.parseToJsonElement(
                """
                {
                  "1": [
                    {
                      "id": "12345.mkv",
                      "episode_num": 1,
                      "container_extension": "mkv"
                    },
                    {
                      "id": "67890",
                      "episode_num": 2,
                      "container_extension": "mp4",
                      "direct_source": "https://cdn.example/synthetic/episode.mp4",
                      "info": ""
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val episodes = client.loadSeriesEpisodes(
            serverUrl = "https://provider.example",
            username = "user",
            password = "pass",
            seriesId = 99,
            dbSeriesId = 7L
        )

        assertThat(episodes.map { it.streamUrl }).containsExactly(
            "https://provider.example/series/user/pass/12345.mkv",
            "https://cdn.example/synthetic/episode.mp4"
        ).inOrder()
    }

    @Test
    fun `series episodes accept alternate stream id and extension keys`() = runTest {
        coEvery {
            api.getSeriesInfo(any(), any(), any(), any(), any())
        } returns XtreamSeriesInfo(
            episodes = json.parseToJsonElement(
                """
                {
                  "3": [
                    {
                      "stream_id": "24680",
                      "episode_num": 4,
                      "title": "Synthetic Episode",
                      "extension": "ts"
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val episodes = client.loadSeriesEpisodes(
            serverUrl = "https://provider.example",
            username = "user",
            password = "pass",
            seriesId = 99,
            dbSeriesId = 7L
        )

        assertThat(episodes).hasSize(1)
        assertThat(episodes.single().streamUrl)
            .isEqualTo("https://provider.example/series/user/pass/24680.ts")
    }

    @Test
    fun `live vod and series sections stream in parallel while preserving sink order`() = runTest {
        mockActiveAuthentication()
        coEvery { api.getCatalog(any(), any(), any(), "get_live_categories") } returns
            """[{"category_id":"1","category_name":"News"}]""".toResponseBody()
        coEvery { api.getCatalog(any(), any(), any(), "get_vod_categories") } returns
            """[{"category_id":"2","category_name":"Movies"}]""".toResponseBody()
        coEvery { api.getCatalog(any(), any(), any(), "get_series_categories") } returns
            """[{"category_id":"3","category_name":"Series"}]""".toResponseBody()

        coEvery { api.getCatalog(any(), any(), any(), "get_live_streams") } returns
            """[{"stream_id":10,"name":"Live 1","category_id":"1"},{"stream_id":11,"name":"Live 2","category_id":"1"}]""".toResponseBody()
        coEvery { api.getCatalog(any(), any(), any(), "get_vod_streams") } returns
            """[{"stream_id":20,"name":"Movie 1","category_id":"2","container_extension":"mp4"}]""".toResponseBody()
        coEvery { api.getCatalog(any(), any(), any(), "get_series") } returns
            """[{"series_id":30,"name":"Series 1","category_id":"3"}]""".toResponseBody()

        val result = client.loadContent(
            serverUrl = "https://provider.example",
            username = "user",
            password = "pass",
            playlistId = 1L
        )

        assertThat(result.loadedSections).containsExactly(
            XtreamContentSection.LIVE,
            XtreamContentSection.MOVIES,
            XtreamContentSection.SERIES
        )
        assertThat(result.channels.map { it.streamId }).containsExactly(10, 11).inOrder()
        assertThat(result.movies.map { it.streamId }).containsExactly(20)
        assertThat(result.series.map { it.seriesId }).containsExactly(30)
        assertThat(result.categories).hasSize(3)
    }

    private fun mockActiveAuthentication(allowedOutputFormats: List<String> = listOf("m3u8", "ts")) {
        coEvery {
            api.authenticate(any(), "user", "pass")
        } returns XtreamAuthResponse(
            userInfo = XtreamUserInfo(
                username = "user",
                password = "pass",
                status = "Active",
                allowedOutputFormats = allowedOutputFormats
            )
        )
    }

    private fun unsupportedCatalogHttpException(): HttpException = HttpException(
        Response.error<Any>(
            404,
            "unsupported catalog".toResponseBody("text/plain".toMediaType())
        )
    )
}

package com.idealplayer.app.data.parser

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.network.dto.XtreamLiveStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamingJsonArrayTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(text: String): List<XtreamLiveStream> = buildList {
        json.readCatalogArray(text.toResponseBody(), XtreamLiveStream.serializer(), {}) { add(it) }
    }

    @Test fun `escaped delimiters unicode and nested ignored data do not split records`() {
        val rows = decode("""[{"stream_id":1,"name":"ç } [ \\\"","extra":{"nested":[1,2]}},{"stream_id":2,"name":"ş"}]""")
        assertThat(rows.map { it.streamId }).containsExactly(1, 2).inOrder()
        assertThat(rows[1].name).isEqualTo("ş")
    }

    @Test fun `malformed suffix trailing comma and unterminated record fail the entire response`() {
        for (text in listOf("[{\"stream_id\":1,\"name\":\"one\"}", "[]oops", "[{},]", "[{\"name\":\"unfinished")) {
            assertThat(runCatching { decode(text) }.isFailure).isTrue()
        }
        assertThat(decode("[ ]\n")).isEmpty()
    }

    @Test fun `record byte budget is independent of neighboring records`() {
        val item = """{"stream_id":1,"name":"${"x".repeat(180_000)}"}"""
        assertThat(decode("[$item,$item]")).hasSize(2)
        assertThrows(IOException::class.java) { decode("[{\"name\":\"${"x".repeat(262_144)}\"}]") }
    }

    @Test fun `cancellation and sink failures propagate and close response`() {
        val body = "[{\"name\":\"one\"}]".toResponseBody()
        assertThrows(CancellationException::class.java) {
            json.readCatalogArray(body, XtreamLiveStream.serializer(), { throw CancellationException() }) { error("unreachable") }
        }
        assertThrows(IllegalStateException::class.java) {
            json.readCatalogArray("[{}]".toResponseBody(), XtreamLiveStream.serializer(), {}) { error("sink") }
        }
    }
}

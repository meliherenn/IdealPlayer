package com.idealplayer.app.data.parser

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.common.InputSizeLimitExceededException
import com.idealplayer.app.core.common.limitedTo
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import org.junit.Test

class XmltvFailureContractTest {
    private val parser = XmltvParser()
    @Test fun `empty valid document succeeds while HTML malformed and truncated XML fail`() = runBlocking<Unit> {
        assertThat(parser.parseDocument("<tv/>".byteInputStream()).programs).isEmpty()
        for (xml in listOf("", "<html>login</html>", "<tv><programme", "<tv><channel id=\"x\"></tv>")) {
            assertThat(runCatching { parser.parseDocument(xml.byteInputStream()) }.exceptionOrNull())
                .isInstanceOf(XmltvDocumentException::class.java)
        }
    }

    @Test fun `expanded byte limit is surfaced as a limit rather than valid empty data`() = runBlocking<Unit> {
        val input = ("<tv>" + " ".repeat(1_000) + "</tv>").byteInputStream().limitedTo(32)
        assertThat(runCatching { parser.parseDocument(input) }.exceptionOrNull())
            .isInstanceOf(InputSizeLimitExceededException::class.java)
    }

    @Test fun `cancellation closes the input and never publishes its prefix`() = runBlocking<Unit> {
        var closed = false
        lateinit var parsing: Job
        val input = object : ByteArrayInputStream("<tv><channel id=\"fixture\"/></tv>".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                parsing.cancel()
                return super.read(buffer, offset, length)
            }
            override fun close() { closed = true; super.close() }
        }
        var published = false
        parsing = launch(start = CoroutineStart.LAZY) { parser.parseDocument(input); published = true }
        parsing.start(); parsing.join()
        assertThat(parsing.isCancelled).isTrue()
        assertThat(published).isFalse()
        assertThat(closed).isTrue()
    }
}

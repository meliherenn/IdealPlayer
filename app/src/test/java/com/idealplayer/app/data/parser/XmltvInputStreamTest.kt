package com.idealplayer.app.data.parser

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.common.InputSizeLimitExceededException
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class XmltvInputStreamTest {

    @Test
    fun `asXmltvInputStream decompresses gzip xmltv`() {
        val xml = """<?xml version="1.0"?><tv></tv>"""
        val gzipped = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { gzip ->
                gzip.write(xml.toByteArray())
            }
            output.toByteArray()
        }

        val decoded = gzipped.inputStream()
            .asXmltvInputStream("https://example.test/epg.xml.gz")
            .bufferedReader()
            .readText()

        assertThat(decoded).isEqualTo(xml)
    }

    @Test
    fun `expanded gzip payload cannot exceed its memory budget`() {
        val gzipped = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { gzip ->
                gzip.write("x".repeat(1_024).toByteArray())
            }
            output.toByteArray()
        }

        assertThrows(InputSizeLimitExceededException::class.java) {
            gzipped.inputStream()
                .asXmltvInputStream(
                    sourceUrl = "https://example.test/epg.xml.gz",
                    maxCompressedBytes = 1_024,
                    maxExpandedBytes = 128
                )
                .readBytes()
        }
    }

    @Test
    fun `plain XMLTV cannot exceed its download budget`() {
        assertThrows(InputSizeLimitExceededException::class.java) {
            "123456".byteInputStream()
                .asXmltvInputStream(
                    sourceUrl = "https://example.test/epg.xml",
                    maxCompressedBytes = 5,
                    maxExpandedBytes = 100
                )
                .readBytes()
        }
    }
}

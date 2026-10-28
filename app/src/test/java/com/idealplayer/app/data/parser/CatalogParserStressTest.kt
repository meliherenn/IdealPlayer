package com.idealplayer.app.data.parser

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CatalogParserStressTest {

    @Test
    fun `M3U stress fixture parses ten thousand live and ten thousand VOD entries`() {
        val parsed = M3uParser().parseText(
            SyntheticCatalogFixtures.m3u(liveCount = 10_000, movieCount = 10_000),
            playlistId = 42L
        )

        assertThat(parsed.channels).hasSize(10_000)
        assertThat(parsed.movies).hasSize(10_000)
    }

    @Test
    fun `XMLTV stress fixture parses one hundred thousand programmes`() = runTest {
        val parsed = XmltvParser().parseDocument(
            SyntheticCatalogFixtures.xmltv(programCount = 100_000).byteInputStream()
        )

        assertThat(parsed.programs).hasSize(100_000)
        assertThat(parsed.channelIcons).isEmpty()
    }
}

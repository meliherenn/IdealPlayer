package com.idealplayer.app.ui.navigation

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.model.ContentType
import org.junit.Test

class PlaybackDestinationTest {

    @Test fun `past program route retains both timestamps and rejects invalid ranges`() {
        val window = com.idealplayer.app.core.model.CatchupWindow(1_700_000_000_000, 1_700_003_600_000)
        val route = Routes.player(PlaybackDestination(41, ContentType.LIVE, catchup = window))
        assertThat(route).isEqualTo("player/41/LIVE?startPos=0&catchupStart=1700000000000&catchupEnd=1700003600000")
        assertThat(route).doesNotContain("url")
        assertThat(runCatching { window.copy(endTimeMs = window.startTimeMs) }.isFailure).isTrue()
        assertThat(runCatching { window.copy(startTimeMs = -1) }.isFailure).isTrue()
        assertThat(runCatching { PlaybackDestination(41, ContentType.MOVIE, catchup = window) }.isFailure).isTrue()
    }

    @Test
    fun `player route contains identifiers only`() {
        val route = Routes.player(
            PlaybackDestination(
                contentId = 41L,
                contentType = ContentType.LIVE,
                startPosition = 5_000L
            )
        )

        assertThat(route).isEqualTo("player/41/LIVE?startPos=5000")
        assertThat(route).doesNotContain("url")
        assertThat(route).doesNotContain("title")
        assertThat(route).doesNotContain("group")
        assertThat(route).doesNotContain("password")
    }
}

package com.idealplayer.app.ui.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.idealplayer.app.core.designsystem.theme.IdealPlayerTheme
import com.idealplayer.app.core.model.Episode
import com.idealplayer.app.ui.components.rememberTvLazyListFocus
import com.idealplayer.app.ui.components.tvLazyItemFocus
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvRoadmapFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pageKeysFocusOffscreenRowAndNextDpadStartsThere() {
        compose.setContent {
            IdealPlayerTheme(isTv = true) {
                val keys = remember { (0..99).toList() }
                val state = rememberLazyListState()
                val focus = rememberTvLazyListFocus(state, keys)
                LaunchedEffect(Unit) { focus.navigator.focusItem(0) }
                LazyColumn(state = state, modifier = Modifier.height(280.dp).width(300.dp)) {
                    items(keys, key = { it }) { id ->
                        Text("Channel $id", modifier = Modifier.tvLazyItemFocus(focus, id)
                            .testTag("channel-$id")
                            .onPreviewKeyEvent {
                                if (it.type != KeyEventType.KeyDown) false
                                else when (it.key) {
                                    Key.PageDown -> { focus.navigator.moveBy(id, 10); true }
                                    Key.PageUp -> { focus.navigator.moveBy(id, -10); true }
                                    else -> false
                                }
                            }
                            .fillMaxWidth().height(64.dp).clickable {})
                    }
                }
            }
        }
        compose.onNodeWithTag("channel-0").assertIsFocused().performKeyInput { pressKey(Key.PageDown) }
        compose.onNodeWithTag("channel-10").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("channel-11").assertIsFocused().performKeyInput { pressKey(Key.PageUp) }
        compose.onNodeWithTag("channel-1").assertIsFocused()
    }

    @Test fun longSeasonKeepsBrowsedFocusAcrossMetadataRefreshAndCloseRoundtrip() {
        val episodes = mutableStateOf((1..100).map { id ->
            Episode(id = id.toLong(), seriesId = 1, seasonNumber = 1, episodeNumber = id,
                name = "Episode $id", streamUrl = "https://example.test/$id", duration = 42)
        })
        compose.setContent {
            IdealPlayerTheme(isTv = true) {
                TvEpisodeCarouselPanel("Fixture", episodes.value, 70, "Close", {}, {})
            }
        }
        compose.onNodeWithTag("tv-episode-70").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("tv-episode-71").assertIsFocused()
        compose.runOnIdle { episodes.value = episodes.value.map { it.copy(lastPosition = 5_000) } }
        compose.onNodeWithTag("tv-episode-71").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Close").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("tv-episode-71").assertIsFocused()
    }

    @Test fun emptySeasonFocusesCloseAndLoadedSeasonFocusesCurrentEpisode() {
        val episodes = mutableStateOf(emptyList<Episode>())
        compose.setContent {
            IdealPlayerTheme(isTv = true) {
                TvEpisodeCarouselPanel("Fixture", episodes.value, 3, "Close", {}, {})
            }
        }
        compose.onNodeWithText("Close").assertIsFocused()
        compose.runOnIdle {
            episodes.value = (1..6).map { id ->
                Episode(id = id.toLong(), seriesId = 1, seasonNumber = 1, episodeNumber = id,
                    name = "Episode $id", streamUrl = "https://example.test/$id")
            }
        }
        compose.onNodeWithTag("tv-episode-3").assertIsFocused()
    }
}

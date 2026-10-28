package com.idealplayer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.google.common.truth.Truth.assertThat
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.idealplayer.app.core.common.DeviceUtils
import com.idealplayer.app.core.designsystem.theme.IdealPlayerTheme
import com.idealplayer.app.ui.navigation.Routes
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvDrawerFocusTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun requireTvRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("TV focus tests require a television runtime", DeviceUtils.resolveUiMode(context).isTv)
    }

    @Test
    fun movingToVisibleMenuRowDoesNotJumpTheList() {
        composeRule.setContent {
            IdealPlayerTheme(isTv = true) {
                TvDrawerLayout(true, Routes.HOME, {}, {}) {
                    Box(Modifier.fillMaxSize().focusable().testTag("content"))
                }
            }
        }
        composeRule.waitForIdle()
        val search = composeRule.onNodeWithTag("tv_drawer_item_${Routes.SEARCH}")
        val before = search.fetchSemanticsNode().boundsInRoot.top
        composeRule.onNodeWithTag("tv_drawer_item_${Routes.HOME}").performKeyInput {
            keyDown(Key.DirectionDown); keyUp(Key.DirectionDown)
        }
        search.assertIsFocused()
        assertThat(search.fetchSemanticsNode().boundsInRoot.top).isWithin(1f).of(before)
    }

    @Test
    fun rightCancelsQueuedVerticalFocusAndStaysInContent() {
        composeRule.setContent {
            IdealPlayerTheme(isTv = true) {
                TvDrawerLayout(true, Routes.HOME, {}, {}) {
                    Box(Modifier.fillMaxSize().focusable().testTag("content"))
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tv_drawer_item_${Routes.HOME}").performKeyInput {
            keyDown(Key.DirectionDown); keyUp(Key.DirectionDown)
            keyDown(Key.DirectionRight); keyUp(Key.DirectionRight)
        }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onNodeWithTag("content").assertIsFocused()
    }

    @Test
    fun drawerReturnsToLastContentTargetAfterMovingToAnotherMenuRow() {
        composeRule.setContent {
            IdealPlayerTheme(isTv = true) {
                TvDrawerLayout(false, Routes.HOME, {}, {}) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.align(Alignment.TopStart).size(180.dp, 60.dp)
                            .focusable().testTag("first_content"))
                        Box(Modifier.align(Alignment.BottomStart).size(180.dp, 60.dp)
                            .focusable().testTag("last_content"))
                    }
                }
            }
        }
        composeRule.onNodeWithTag("last_content")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithTag("last_content").performKeyInput {
            keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft)
        }
        composeRule.onNodeWithTag("tv_drawer_item_${Routes.HOME}").assertIsFocused()
        composeRule.onNodeWithTag("tv_drawer_item_${Routes.HOME}").performKeyInput {
            keyDown(Key.DirectionDown); keyUp(Key.DirectionDown)
        }
        composeRule.onNodeWithTag("tv_drawer_item_${Routes.SEARCH}").assertIsFocused()
        composeRule.onNodeWithTag("tv_drawer_item_${Routes.SEARCH}").performKeyInput {
            keyDown(Key.DirectionRight); keyUp(Key.DirectionRight)
        }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onNodeWithTag("last_content").assertIsFocused()
        composeRule.onNodeWithTag("last_content").performKeyInput {
            keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft)
        }
        composeRule.onNodeWithTag("tv_drawer_item_${Routes.SEARCH}").assertIsFocused()
        composeRule.onNodeWithTag("tv_drawer_item_${Routes.SEARCH}").performKeyInput {
            keyDown(Key.Back); keyUp(Key.Back)
        }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onNodeWithTag("last_content").assertIsFocused()
    }

    @Test
    fun drawerRestoresTheChosenCardInsideANestedRail() {
        composeRule.setContent {
            IdealPlayerTheme(isTv = true) {
                TvDrawerLayout(false, Routes.HOME, {}, {}) {
                    LazyColumn(Modifier.tvRememberedScrollFocus()) {
                        item(key = "rail") {
                            LazyRow(Modifier.tvRememberedScrollFocus()) {
                                items(4, key = { it }) { index ->
                                    Box(Modifier.size(120.dp, 90.dp).focusable().testTag("card_$index"))
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("card_3")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        // Menu opens from the middle of a rail without moving through its earlier cards.
        composeRule.onNodeWithTag("card_3").performKeyInput {
            keyDown(Key.Menu); keyUp(Key.Menu)
        }
        composeRule.onNodeWithTag("tv_drawer_item_${Routes.HOME}").assertIsFocused()
        composeRule.onNodeWithTag("tv_drawer_item_${Routes.HOME}").performKeyInput {
            keyDown(Key.DirectionDown); keyUp(Key.DirectionDown)
        }
        composeRule.onNodeWithTag("tv_drawer_item_${Routes.SEARCH}").performKeyInput {
            keyDown(Key.DirectionRight); keyUp(Key.DirectionRight)
        }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onNodeWithTag("card_3").assertIsFocused()
    }

    @Test
    fun viewportAnimationDoesNotRemeasureMenuContentEachFrame() {
        val animatedWidth = mutableStateOf(96.dp)
        val targetWidth = mutableStateOf(232.dp)
        val measuredWidths = mutableListOf<Int>()
        composeRule.setContent {
            Box(Modifier.width(900.dp).height(420.dp)) {
                Box(Modifier.testTag("drawer_viewport")
                    .tvDrawerAnimatedViewport(animatedWidth, targetWidth.value)) {
                    // A separate child LayoutNode, as in the real Column -> LazyColumn/text
                    // hierarchy. Another modifier on the viewport itself must be remeasured.
                    Box(Modifier.fillMaxSize().layout { measurable, constraints ->
                            measuredWidths.add(constraints.maxWidth)
                            val child = measurable.measure(constraints)
                            layout(child.width, child.height) { child.placeRelative(0, 0) }
                        })
                }
            }
        }
        composeRule.waitForIdle()
        val initialMeasures = measuredWidths.size
        for (width in listOf(120, 160, 200, 232)) {
            composeRule.runOnIdle { animatedWidth.value = width.dp }
            composeRule.onNodeWithTag("drawer_viewport").assertWidthIsEqualTo(width.dp)
        }
        composeRule.runOnIdle {
            assertThat(measuredWidths).hasSize(initialMeasures)
            targetWidth.value = 96.dp
        }
        composeRule.waitForIdle()
        val collapseMeasures = measuredWidths.size
        for (width in listOf(200, 160, 120, 96)) {
            composeRule.runOnIdle { animatedWidth.value = width.dp }
            composeRule.onNodeWithTag("drawer_viewport").assertWidthIsEqualTo(width.dp)
        }
        composeRule.runOnIdle {
            assertThat(collapseMeasures).isGreaterThan(initialMeasures)
            assertThat(measuredWidths).hasSize(collapseMeasures)
            assertThat(measuredWidths.distinct()).hasSize(2)
        }
    }

    @Test
    fun drawerAnimationKeepsCatalogWidthStable() {
        val expanded = mutableStateOf(true)
        val widths = mutableListOf<Int>()
        composeRule.setContent {
            IdealPlayerTheme(isTv = true) {
                TvDrawerLayout(
                    isExpanded = expanded.value,
                    selectedRoute = Routes.HOME,
                    onToggle = {},
                    onNavigate = {}
                ) {
                    Box(Modifier.fillMaxSize()
                        .onSizeChanged { widths.add(it.width) }
                        .focusable().testTag("stable_catalog"))
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { expanded.value = false }
        composeRule.onNodeWithTag("stable_catalog")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle { expanded.value = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertThat(widths).isNotEmpty()
            assertThat(widths.toSet()).hasSize(1)
            assertThat(widths.singleOrNull() ?: widths.first()).isGreaterThan(0)
        }
    }

    @Test
    fun rightDpadMovesFromDrawerToContentWithoutEscapingTheActivity() {
        composeRule.setContent {
            IdealPlayerTheme(isTv = true) {
                TvDrawerLayout(
                    isExpanded = true,
                    selectedRoute = Routes.HOME,
                    onToggle = {},
                    onNavigate = {}
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .focusable()
                            .testTag("tv_drawer_content_target")
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tv_drawer_item_${Routes.HOME}").assertIsFocused()

        composeRule.onNodeWithTag("tv_drawer_item_${Routes.HOME}").performKeyInput {
            keyDown(Key.DirectionRight)
        }

        composeRule.onNodeWithTag("tv_drawer_content_target").assertIsFocused()
    }

    @Test
    fun verticalDpadTraversesOffscreenDrawerItemsWithoutDetachedRequesterCrash() {
        var transitionsBetweenHomeAndExit = 0
        composeRule.setContent {
            IdealPlayerTheme(isTv = true) {
                val regularDrawerItemCount = tvDrawerItems.size
                SideEffect {
                    transitionsBetweenHomeAndExit = regularDrawerItemCount
                }
                Box(modifier = Modifier.width(900.dp).height(420.dp)) {
                    TvDrawerLayout(
                        isExpanded = true,
                        selectedRoute = Routes.HOME,
                        onToggle = {},
                        onNavigate = {}
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                                .focusable()
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val homeTag = "tv_drawer_item_${Routes.HOME}"
        val exitTag = "tv_drawer_item_${Routes.EXIT}"
        composeRule.onNodeWithTag(homeTag).assertIsFocused()

        composeRule.onNodeWithTag(homeTag).performKeyInput {
            repeat(transitionsBetweenHomeAndExit) {
                keyDown(Key.DirectionDown)
                keyUp(Key.DirectionDown)
            }
        }
        composeRule.onNodeWithTag(exitTag).assertIsFocused()

        composeRule.onNodeWithTag(exitTag).performKeyInput {
            repeat(transitionsBetweenHomeAndExit) {
                keyDown(Key.DirectionUp)
                keyUp(Key.DirectionUp)
            }
        }
        composeRule.onNodeWithTag(homeTag).assertIsFocused()
    }

    @Test
    fun selectedOffscreenDrawerRouteIsScrolledBeforeProgrammaticFocus() {
        composeRule.setContent {
            IdealPlayerTheme(isTv = true) {
                Box(modifier = Modifier.width(900.dp).height(420.dp)) {
                    TvDrawerLayout(
                        isExpanded = true,
                        selectedRoute = Routes.SETTINGS,
                        onToggle = {},
                        onNavigate = {}
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                                .focusable()
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tv_drawer_item_${Routes.SETTINGS}").assertIsFocused()
    }
}

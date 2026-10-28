package com.idealplayer.app.ui.player

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.designsystem.theme.IdealPlayerTheme
import org.junit.Rule
import org.junit.Test

class PlayerBottomSheetTest {
    @get:Rule val rule = createComposeRule()
    private val open = mutableStateOf(true)
    private var backgroundClicks = 0
    private var dismissals = 0
    private var playbackRoot: View? = null
    private var panelRoot: View? = null
    private val playbackFocus = FocusRequester()
    private var playbackFocused = false

    private fun show() {
        rule.setContent {
            IdealPlayerTheme {
                val view = LocalView.current
                SideEffect { playbackRoot = view.rootView }
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.playerSheetBackgroundHidden(open.value)) {
                        Button(onClick = { backgroundClicks++ }, modifier = Modifier
                            .focusRequester(playbackFocus)
                            .onFocusChanged { playbackFocused = it.isFocused }
                            .testTag("playback-action")) {
                            Text("Owned playback action")
                        }
                    }
                    if (open.value) {
                        PlayerBottomSheet("Owned panel", onDismissRequest = {
                            dismissals++
                            open.value = false
                        }) {
                            val panelView = LocalView.current
                            SideEffect { panelRoot = panelView.rootView }
                            Column(Modifier.fillMaxWidth().height(300.dp).testTag("owned-sheet-content")) {
                                Text("Owned panel content")
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("owned-sheet-content").assertIsDisplayed()
    }

    @Test fun panelSharesPlaybackWindowAndHidesUnderlyingAccessibilityNodes() {
        show()
        rule.runOnIdle { assertThat(panelRoot).isSameInstanceAs(playbackRoot) }
        rule.onNodeWithTag("playback-action").assertDoesNotExist()
    }

    @Test fun scrimDismissesWithoutActivatingPlaybackAndRestoresControls() {
        show()
        rule.onNodeWithTag("player-modal-scrim").performTouchInput {
            click(Offset(centerX, 8f))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("player-modal-sheet").assertDoesNotExist()
        rule.runOnIdle {
            assertThat(backgroundClicks).isEqualTo(0)
            assertThat(dismissals).isEqualTo(1)
        }
        rule.onNodeWithTag("playback-action").assertIsDisplayed().performClick()
        rule.runOnIdle { assertThat(backgroundClicks).isEqualTo(1) }
    }

    @Test fun backClosesOnlyPanel() {
        show()
        pressBack()
        rule.waitForIdle()
        rule.onNodeWithTag("player-modal-sheet").assertDoesNotExist()
        rule.onNodeWithTag("playback-action").assertIsDisplayed()
        rule.runOnIdle { assertThat(dismissals).isEqualTo(1) }
    }

    @Test fun keyboardCannotFocusPlaybackBehindPanel() {
        show()
        rule.runOnIdle {
            playbackFocus.requestFocus()
            assertThat(playbackFocused).isFalse()
        }
    }

    @Test fun swipeDownDismissesPanel() {
        show()
        rule.onNodeWithTag("owned-sheet-content").performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.onNodeWithTag("player-modal-sheet").assertDoesNotExist()
        rule.runOnIdle { assertThat(dismissals).isEqualTo(1) }
    }

    @Test fun accessibilityDismissWorksWithoutOfferingInvalidPartialCollapse() {
        show()
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse)).assertCountEquals(0)
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        rule.waitForIdle()
        rule.onNodeWithTag("player-modal-sheet").assertDoesNotExist()
    }
}

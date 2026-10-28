package com.idealplayer.app.ui.onboarding

import android.content.Context
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.R
import com.idealplayer.app.core.common.DeviceUtils
import com.idealplayer.app.core.designsystem.theme.IdealPlayerTheme
import com.idealplayer.app.core.model.Playlist
import com.idealplayer.app.core.model.PlaylistType
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeTrue

/** Real form traversal, including feedback that pushes the action row below the viewport. */
@OptIn(ExperimentalTestApi::class)
@SdkSuppress(minSdkVersion = 29)
class TvPlaylistInputFocusTest {
    @get:Rule val rule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var saves = 0
    private var connectionTests = 0
    private var completeConnectionTest: ((Boolean) -> Unit)? = null
    private val fixture = Playlist(name = "Owned TV focus", type = PlaylistType.M3U_URL,
        url = "https://example.invalid/owned.m3u")

    @Before fun requireTvRuntime() {
        assumeTrue("TV form traversal requires a television runtime", DeviceUtils.resolveUiMode(context).isTv)
    }

    private fun show(deferConnectionResult: Boolean = false) {
        rule.setContent {
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
            IdealPlayerTheme(isTv = true) {
                TvAddPlaylistDialog(
                    initialPlaylist = fixture,
                    onDismiss = {},
                    onAdd = { _, _, _, _, _, _, _ -> saves++ },
                    onTest = { _, _, _, _, _, _, result ->
                        connectionTests++
                        if (deferConnectionResult) completeConnectionTest = result else result(true)
                    }
                )
            }
        }
    }

    private fun field(label: Int) = rule.onNode(hasSetTextAction() and hasText(context.getString(label)))

    private fun hideKeyboard() {
        // TV IMEs can reopen when focus enters a field. Hide the actual dialog's IME before
        // testing app D-pad traversal; keyboard navigation is intentionally a separate state.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val ime = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            WindowInspector.getGlobalWindowViews().forEach { ime.hideSoftInputFromWindow(it.windowToken, 0) }
        }
        SystemClock.sleep(400)
    }

    @Test fun asynchronousConnectionTestKeepsFocusAndIgnoresDuplicateActivation() {
        show(deferConnectionResult = true)
        val testLabel = context.getString(R.string.test_connection)
        val testingLabel = context.getString(R.string.a2_action_testing)
        rule.onNodeWithText(testLabel).performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithText(testLabel).performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithText(testingLabel).assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle {
            assertThat(connectionTests).isEqualTo(1)
            checkNotNull(completeConnectionTest).invoke(true)
        }
        rule.onNodeWithText(testLabel).assertIsFocused().assertIsDisplayed()
            .performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText(context.getString(R.string.a2_action_save_continue)).assertIsFocused()
    }

    @Test fun verticalKeysLeaveUrlAndReachSaveAfterConnectionFeedback() {
        show()
        val testLabel = context.getString(R.string.test_connection)
        rule.onNodeWithText(testLabel).performScrollTo().performClick()
        rule.onNodeWithText(context.getString(R.string.connection_success)).assertExists()
        val url = field(R.string.m3u_url)
        url.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        hideKeyboard()
        url.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        hideKeyboard()
        val epg = field(R.string.a2_epg_url_optional)
        epg.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText(testLabel).assertIsFocused().assertIsDisplayed()
            .performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText(context.getString(R.string.a2_action_save_continue))
            .assertIsFocused().assertIsDisplayed().performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertThat(saves).isEqualTo(1) }
    }

    @Test fun upReturnsToPreviousFieldAndHorizontalKeysKeepEditingFocus() {
        show()
        val epg = field(R.string.a2_epg_url_optional)
        epg.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        hideKeyboard()
        epg.assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        hideKeyboard()
        val url = field(R.string.m3u_url)
        url.assertIsFocused().performKeyInput {
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionRight)
        }
        url.assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        hideKeyboard()
        field(R.string.playlist_name).assertIsFocused()
    }
}

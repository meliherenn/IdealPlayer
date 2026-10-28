package com.idealplayer.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.R
import com.idealplayer.app.core.common.DeviceUtils
import com.idealplayer.app.core.designsystem.theme.IdealPlayerTheme
import com.idealplayer.app.core.designsystem.theme.IdealPlayerColors
import com.idealplayer.app.core.model.Channel
import com.idealplayer.app.core.model.Episode
import com.idealplayer.app.core.model.Series
import com.idealplayer.app.core.model.Playlist
import com.idealplayer.app.core.model.PlaylistType
import com.idealplayer.app.data.parser.EpgProgram
import com.idealplayer.app.ui.components.a2.A2ActionButton
import com.idealplayer.app.ui.components.a2.A2ConfirmationDialog
import com.idealplayer.app.ui.components.a2.A2PinDialog
import com.idealplayer.app.ui.details.DetailState
import com.idealplayer.app.ui.details.MobileDetailContent
import com.idealplayer.app.ui.details.TvDetailContent
import com.idealplayer.app.ui.guide.GuideContent
import com.idealplayer.app.ui.guide.GuideRow
import com.idealplayer.app.ui.guide.GuideStatus
import com.idealplayer.app.ui.guide.TvGuideState
import com.idealplayer.app.ui.onboarding.MobileWelcomeContent
import com.idealplayer.app.ui.onboarding.MobileAddPlaylistDialog
import com.idealplayer.app.ui.onboarding.TvAddPlaylistDialog
import java.io.File
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Actual composed surfaces with synthetic catalog data; no provider URL or network is needed. */
@OptIn(ExperimentalTestApi::class)
class AccessibilityLayoutTest {
    @get:Rule(order = 0) val rule = createComposeRule()
    @get:Rule(order = 1) val failureEvidence = object : TestWatcher() {
        private var changeImeSetting = false
        private var originalImeSetting: String? = null

        override fun starting(description: Description) {
            changeImeSetting = !television && description.methodName ==
                "pinInputAndConfirmStayReachableWithTheKeyboardAtLargeFontSize"
            if (changeImeSetting) {
                originalImeSetting = Settings.Secure.getString(context.contentResolver, "show_ime_with_hard_keyboard")
                imeCommand("settings put secure show_ime_with_hard_keyboard 1")
            }
        }

        override fun failed(error: Throwable, description: Description) {
            runCatching {
                screenshot("failure-${description.methodName}-${scenario.value}")
                val directory = evidenceDirectory()
                File(directory, "failure-${description.methodName}.txt")
                    .writeText(rule.onRoot(useUnmergedTree = true).printToString())
            }
        }

        override fun finished(description: Description) {
            if (changeImeSetting) {
                val value = originalImeSetting
                imeCommand(if (value == null) "settings delete secure show_ime_with_hard_keyboard"
                    else "settings put secure show_ime_with_hard_keyboard $value")
            }
        }

        private fun imeCommand(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
        }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val television = DeviceUtils.resolveUiMode(context).isTv
    private val scenario = mutableStateOf(Scenario("tr", 1f))

    @Test
    fun compactWelcomeActionsAndDialogRemainReachableInBothLanguagesAtEveryFontSize() {
        var starts = 0
        rule.setContent { TestViewport(tv = false, keyboard = true) { MobileWelcomeContent { starts++ } } }
        matrix { case ->
            val start = localized(R.string.a2_welcome_start, case)
            val how = localized(R.string.a2_welcome_how, case)
            rule.onNodeWithText(start).performScrollTo().assertIsDisplayed().assertIsEnabled()
            assertFullText(start)
            rule.onNodeWithText(start).performClick()
            rule.onNodeWithText(how).performScrollTo().assertIsDisplayed().performClick()
            val close = localized(R.string.a2_action_close, case)
            rule.onNodeWithText(close).performScrollTo().assertIsDisplayed().assertIsEnabled()
            assertFullText(close)
            if (case.font == 2f) screenshot("welcome-dialog-${case.language}")
            pressBack()
            rule.onNodeWithText(how).assertIsDisplayed().assertIsFocused()
            if (case.font == 2f) screenshot("welcome-${case.language}")
        }
        rule.runOnIdle { assertThat(starts).isEqualTo(6) }
    }

    @Test
    fun populatedGuideScrollsPastControlsAndInvokesChannelProgramAndRefreshActions() {
        var channels = 0
        var programs = 0
        var refreshes = 0
        rule.setContent {
            TestViewport(tv = television) {
                GuideContent(
                    state = guideFixture(), isTv = television, onBack = {}, onSelectGroup = {},
                    onShiftWindow = {}, onNow = {}, onRefresh = { refreshes++ },
                    onChannelClick = { assertThat(it.id).isEqualTo(1L); channels++ },
                    onProgramClick = { channel, program ->
                        assertThat(channel.id).isEqualTo(1L)
                        assertThat(program.title).isEqualTo("Synthetic programme")
                        programs++
                    }
                )
            }
        }
        matrix { case ->
            rule.onNodeWithTag("guide-refresh").assertIsDisplayed().assertIsEnabled().performClick()
            rule.onNodeWithTag("guide-list")
                .performScrollToNode(hasTestTag("guide-channel-1"))
            rule.onNodeWithTag("guide-channel-1").assertIsDisplayed().performClick()
            val programme = rule.onNodeWithTag("guide-program-1-$WINDOW_START")
            repeat(3) {
                if (!programme.isDisplayed()) {
                    rule.onNodeWithTag("guide-list").performTouchInput { swipeUp() }
                }
            }
            programme.assertIsDisplayed().assertHasClickAction().performClick()
            if (case.font == 2f) screenshot("guide-${case.language}")
        }
        rule.runOnIdle {
            assertThat(channels).isEqualTo(6)
            assertThat(programs).isEqualTo(6)
            assertThat(refreshes).isEqualTo(6)
        }
    }

    @Test
    fun cachedSeriesKeepsPlayNextFavoriteAndRefreshActionsVisibleAndUnclipped() {
        val played = mutableListOf<Long>()
        var favorites = 0
        var refreshes = 0
        rule.setContent {
            TestViewport(tv = television) {
                val onPlay: (String, String, Long, String, Long) -> Unit = { _, _, id, _, _ -> played += id }
                if (television) {
                    TvDetailContent(detailFixture(), {}, onPlay, { favorites++ }, { refreshes++ }, {}, {})
                } else {
                    MobileDetailContent(detailFixture(), {}, onPlay, { favorites++ }, { refreshes++ }, {}, {})
                }
            }
        }
        matrix { case ->
            listOf("detail-play", "detail-next", "detail-favorite", "detail-refresh").forEach { tag ->
                rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            }
            assertFullText(localized(R.string.refresh_episodes, case))
            rule.onNodeWithTag("detail-next").performScrollTo()
            assertFullText(localized(R.string.next_episode, case))
            if (case.font == 2f) screenshot("details-${case.language}")
        }
        rule.runOnIdle {
            assertThat(played).containsExactlyElementsIn(List(6) { listOf(11L, 12L) }.flatten()).inOrder()
            assertThat(favorites).isEqualTo(6)
            assertThat(refreshes).isEqualTo(6)
        }
    }

    @Test
    fun confirmationSupportsDpadAndBackRestoresTheInvokingAction() {
        val visible = mutableStateOf(false)
        var confirmed = 0
        rule.setContent {
            TestViewport(tv = television, keyboard = true) {
                val launcher = remember { FocusRequester() }
                LaunchedEffect(Unit) { withFrameNanos { }; launcher.requestFocus() }
                A2ActionButton(
                    text = stringResource(R.string.refresh_episodes),
                    onClick = { visible.value = true },
                    modifier = Modifier.testTag("dialog-launcher").focusRequester(launcher)
                )
                A2ConfirmationDialog(
                    visible = visible.value,
                    title = stringResource(R.string.a2_welcome_how_title),
                    message = stringResource(R.string.a2_welcome_how_message),
                    confirmLabel = stringResource(R.string.confirm),
                    dismissLabel = stringResource(R.string.cancel),
                    onConfirm = { confirmed++; visible.value = false },
                    onDismiss = { visible.value = false }
                )
            }
        }
        matrix { case ->
            rule.onNodeWithTag("dialog-launcher").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            val confirm = localized(R.string.confirm, case)
            rule.onNodeWithText(confirm).assertIsFocused().performScrollTo().assertIsDisplayed()
            assertFullText(confirm)
            pressBack()
            rule.onNodeWithTag("dialog-launcher").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            rule.onNodeWithText(confirm).performKeyInput { pressKey(Key.DirectionLeft) }
            rule.onNodeWithText(localized(R.string.cancel, case)).assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.Enter) }
            rule.onNodeWithTag("dialog-launcher").assertIsFocused()
        }
        rule.runOnIdle { assertThat(confirmed).isEqualTo(6) }
    }

    @Test
    fun guideDpadReachesPopulatedRowsWithoutMenuAndKeepsOneFocusOwner() {
        var invoked = false
        rule.setContent {
            TestViewport(tv = true) {
                GuideContent(guideFixture(), true, {}, {}, {}, {}, {}, { invoked = true }, { _, _ -> invoked = true })
            }
        }
        rule.onNodeWithTag("guide-refresh").assertIsFocused()
        var rowReached = false
        repeat(8) {
            if (!rowReached) {
                rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
                rule.onAllNodes(isFocused()).assertCountEquals(1)
                val tag = rule.onNode(isFocused()).fetchSemanticsNode().config
                    .getOrElse(SemanticsProperties.TestTag) { "" }
                rowReached = tag.startsWith("guide-channel-") || tag.startsWith("guide-program-")
            }
        }
        assertThat(rowReached).isTrue()
        rule.onNode(isFocused()).assertIsDisplayed().performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertThat(invoked).isTrue() }
    }

    @Test
    fun welcomeDialogAndScrollSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { TestViewport(tv = false) { MobileWelcomeContent {} } }
        val how = localized(R.string.a2_welcome_how, scenario.value)
        rule.onNodeWithText(how).performScrollTo().performClick()
        val close = localized(R.string.a2_action_close, scenario.value)
        rule.onNodeWithText(close).assertExists()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText(close).performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText(how).assertIsDisplayed()
    }

    @Test
    fun playlistConnectionFeedbackUsesLocalizedOutcomeAtEveryFontSize() {
        var successful = true
        var tested = 0
        val fixture = Playlist(
            id = 1, name = "Synthetic playlist", type = PlaylistType.M3U_URL,
            url = "https://fixture.invalid/playlist.m3u"
        )
        rule.setContent {
            TestViewport(tv = television) {
                val onTest: (String, PlaylistType, String, String, String, String, (Boolean) -> Unit) -> Unit =
                    { _, _, _, _, _, _, result -> tested++; result(successful) }
                if (television) {
                    TvAddPlaylistDialog(initialPlaylist = fixture, onDismiss = {}, onAdd = { _, _, _, _, _, _, _ -> }, onTest = onTest)
                } else {
                    MobileAddPlaylistDialog(initialPlaylist = fixture, onDismiss = {}, onAdd = { _, _, _, _, _, _, _ -> }, onTest = onTest)
                }
            }
        }
        matrix { case ->
            for (outcome in listOf(true, false)) {
                rule.runOnIdle { successful = outcome }
                val test = localized(R.string.test_connection, case)
                rule.onNodeWithText(test).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
                val feedback = localized(if (outcome) R.string.connection_success else R.string.connection_failed, case)
                rule.onNodeWithText(feedback).performScrollTo().assertIsDisplayed()
                assertFullText(feedback)
            }
            if (case.font == 2f) screenshot("playlist-feedback-${case.language}")
        }
        rule.runOnIdle { assertThat(tested).isEqualTo(12) }
    }

    @Test
    fun pinInputAndConfirmStayReachableWithTheKeyboardAtLargeFontSize() {
        val pin = mutableStateOf("")
        var confirmed = false
        scenario.value = Scenario("en", 2f)
        rule.setContent {
            TestViewport(tv = television, keyboard = television) {
                A2PinDialog(
                    visible = true, title = "PIN", message = null,
                    pin = pin.value, onPinChange = { pin.value = it },
                    pinContentDescription = "Synthetic PIN", pinStateDescription = "",
                    confirmLabel = stringResource(R.string.confirm), dismissLabel = stringResource(R.string.cancel),
                    onConfirm = { confirmed = true }, onDismiss = {}, confirmEnabled = pin.value.length == 4
                )
            }
        }
        val input = rule.onNodeWithContentDescription("Synthetic PIN")
        input.assertIsFocused()
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        try {
            if (!television) {
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
                input.performTouchInput { click() }
                rule.waitUntil(timeoutMillis = 10_000) {
                    // Floating dialogs on API 26 can report no IME insets while the keyboard
                    // is visibly open. Observe the actual system input-method window instead.
                    val windows = automation.windows
                    try {
                        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    } finally {
                        @Suppress("DEPRECATION")
                        windows.forEach { it.recycle() }
                    }
                }
            }
            input.performTextInput("1234")
            if (!television) screenshot("pin-keyboard-open")
            rule.onNodeWithText("Confirm").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            rule.runOnIdle { assertThat(confirmed).isTrue() }
            screenshot("pin-large-font")
        } finally {
            if (!television) {
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            }
        }
    }

    private fun matrix(check: (Scenario) -> Unit) {
        for (language in listOf("tr", "en")) for (font in listOf(1f, 1.5f, 2f)) {
            val case = Scenario(language, font)
            rule.runOnIdle { scenario.value = case }
            rule.waitForIdle()
            check(case)
        }
    }

    @Composable
    private fun TestViewport(tv: Boolean, keyboard: Boolean = tv, content: @Composable () -> Unit) {
        val case = scenario.value
        val base = LocalContext.current
        val activityResultOwner = checkNotNull(LocalActivityResultRegistryOwner.current)
        val density = LocalDensity.current.density
        val inputMode = LocalInputModeManager.current
        SideEffect { if (keyboard) inputMode.requestInputMode(InputMode.Keyboard) }
        val config = Configuration(LocalConfiguration.current).apply {
            setLocale(Locale.forLanguageTag(case.language))
            fontScale = case.font
            smallestScreenWidthDp = 360
            screenWidthDp = if (tv || television) 860 else 360
            screenHeightDp = 360
        }
        val localized = remember(base, case) { base.createConfigurationContext(config) }
        CompositionLocalProvider(
            LocalContext provides localized, LocalConfiguration provides config,
            LocalActivityResultRegistryOwner provides activityResultOwner,
            LocalDensity provides Density(density, case.font), LocalLayoutDirection provides LayoutDirection.Ltr
        ) {
            IdealPlayerTheme(isTv = tv) {
                key(case) {
                    Box(Modifier.width(if (tv || television) 860.dp else 360.dp).height(360.dp)
                        .background(IdealPlayerColors.Background)) { content() }
                }
            }
        }
    }

    private fun localized(id: Int, case: Scenario): String = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(case.language)) }
    ).getString(id)

    private fun assertFullText(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertThat(layouts).isNotEmpty()
        layouts.forEach { layout ->
            // Compose's semantics reconstruction can retain a full-width paragraph for a
            // wrap-content Text. Compare the actual glyph lines with the measured Text size.
            val clippedLine = (0 until layout.lineCount).any { line ->
                layout.isLineEllipsized(line) ||
                    layout.getLineRight(line) - layout.getLineLeft(line) > layout.size.width + 1f
            }
            check(!clippedLine && layout.multiParagraph.height <= layout.size.height + 1f &&
                layout.getLineEnd(layout.lineCount - 1) == text.length) {
                "Clipped action '$text', ${scenario.value}: size=${layout.size}, " +
                    "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                    "lines=${layout.lineCount}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}"
            }
        }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val directory = evidenceDirectory()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun evidenceDirectory(): File = File(
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }?.let(::File) ?: context.getExternalFilesDir(null),
        "phase-e"
    ).apply { check(isDirectory || mkdirs()) { "Cannot create accessibility evidence directory" } }

    private data class Scenario(val language: String, val font: Float)

    private fun guideFixture() = TvGuideState(
        rows = listOf(GuideRow(
            Channel(id = 1, playlistId = 1, name = "Synthetic channel", streamUrl = "", playbackAvailable = true),
            listOf(EpgProgram("synthetic", "Synthetic programme", startTime = WINDOW_START, endTime = WINDOW_START + 3_600_000))
        )),
        groups = listOf("Synthetic group"), windowStart = WINDOW_START,
        isLoading = false, status = GuideStatus.NO_EPG_MATCH
    )

    private fun detailFixture(): DetailState {
        val first = Episode(11, 1, 1, 1, "Synthetic episode", streamUrl = "", playbackAvailable = true)
        val second = first.copy(id = 12, episodeNumber = 2)
        return DetailState(
            series = Series(id = 1, playlistId = 1, seriesId = 1, name = "Synthetic series"),
            episodes = listOf(first, second), seasons = listOf(1), resumeEpisode = first, nextEpisode = second,
            isLoading = false
        )
    }

    private companion object { const val WINDOW_START = 1_800_000_000_000L }
}

package com.idealplayer.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.R
import com.idealplayer.app.core.common.DeviceUtils
import com.idealplayer.app.core.designsystem.theme.IdealPlayerColors
import com.idealplayer.app.core.designsystem.theme.IdealPlayerTheme
import com.idealplayer.app.core.model.ContentType
import com.idealplayer.app.core.model.SearchResult
import com.idealplayer.app.core.player.PlaybackDiagnostics
import com.idealplayer.app.core.player.PlayerState
import com.idealplayer.app.core.player.TrackInfo
import com.idealplayer.app.ui.components.a2.A2ActionButton
import com.idealplayer.app.ui.player.PlayerSettingsContent
import com.idealplayer.app.ui.search.MobileSearchContent
import com.idealplayer.app.ui.search.SearchState
import com.idealplayer.app.ui.search.TvSearchContent
import com.idealplayer.app.ui.tv.TvOptionPanel
import com.idealplayer.app.ui.tv.TvPanelOption
import java.io.File
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalTestApi::class)
class PlayerSearchAccessibilityTest {
    @get:Rule(order = 0) val rule = createComposeRule()
    @get:Rule(order = 1) val evidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runCatching {
                screenshot("failure-${description.methodName}")
                File(directory, "${description.methodName}.txt").writeText(rule.onRoot(useUnmergedTree = true).printToString())
            }
        }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val tv = DeviceUtils.resolveUiMode(context).isTv
    private val directory = File(context.getExternalFilesDir(null), "phase-e").apply { mkdirs() }
    private data class Case(val language: String, val font: Float, val rtl: Boolean = false)
    private val scenario = mutableStateOf(Case("tr", 1f))

    @Test fun compactSearchScrollsFromFiltersToLastResultInBothLanguagesAndRtl() {
        val opened = mutableListOf<Long>()
        val state = SearchState(query = "Synthetic", results = (1L..40L).map {
            SearchResult(id = it, title = "Synthetic $it", contentType = ContentType.MOVIE)
        })
        rule.setContent { Viewport {
            if (tv) TvSearchContent(state, {}, {}, { id, _ -> opened += id }, { _, _, _, _ -> })
            else MobileSearchContent(state, {}, {}, { id, _ -> opened += id }, { _, _, _, _ -> })
        } }
        matrix {
            rule.onNodeWithTag("search-results").performScrollToKey("MOVIE-40")
            rule.onNodeWithContentDescription("Synthetic 40").assertHasClickAction().assertIsDisplayed().performClick()
            // The header is one grid item with a full-width span; its index is not stable when
            // the result grid changes columns. Scroll by the query action instead of assuming
            // that index zero maps to the header row.
            rule.onNodeWithTag("search-results").performScrollToNode(hasSetTextAction())
            rule.onNode(hasSetTextAction()).assertIsDisplayed()
            if (it.font == 2f) screenshot("search-${it.language}-${it.rtl}")
        }
        assertThat(opened).hasSize(7)
        assertThat(opened.toSet()).containsExactly(40L)
    }

    @Test fun compactPlayerTracksAndSubtitleOffRemainReachableAndExposeSelection() {
        val selections = mutableListOf<Int>()
        var disabled = 0
        rule.setContent { Viewport {
            var section by remember { mutableStateOf<String?>("audio") }
            PlayerSettingsContent(
                playerState = PlayerState(audioTracks = (1..24).map {
                    TrackInfo(it, "Synthetic audio $it", "en", isSelected = it == 24, isAudioDescription = it == 24)
                }), diagnostics = PlaybackDiagnostics(), isTv = tv, isLivePlayback = false,
                sleepTimerRemaining = null, activeSection = section, onSectionChange = { section = it },
                onOpenChannels = {}, onOpenSleepTimer = {}, onLockScreen = {}, onSetAspectRatio = {},
                onSetSpeed = {}, onSetQualityMode = {}, onSelectVideoTrack = {},
                onSelectAudio = { selections += it }, onSelectSubtitle = {}, onDisableSubtitles = { disabled++ },
                onCopyDiagnostics = {}
            )
        } }
        matrix { case ->
            rule.onNodeWithTag("player-settings-list").performScrollToNode(hasText("Synthetic audio 24"))
            rule.onNodeWithText("Synthetic audio 24").assertIsDisplayed().assertIsSelected().performClick()
            rule.onNodeWithTag("player-settings-list").performScrollToNode(hasText(localized(R.string.setting_subtitles, case)))
            rule.onNodeWithText(localized(R.string.setting_subtitles, case)).performClick()
            rule.onNodeWithTag("player-settings-list").performScrollToNode(hasText(localized(R.string.player_subtitles_off, case)))
            rule.onNodeWithText(localized(R.string.player_subtitles_off, case)).assertIsDisplayed().assertIsSelected().performClick()
            if (case.font == 2f) screenshot("player-tracks-${case.language}-${case.rtl}")
        }
        assertThat(selections).hasSize(7)
        assertThat(selections.toSet()).containsExactly(24)
        assertThat(disabled).isEqualTo(7)
    }

    @Test fun tvPanelFocusReachesOffscreenSelectedTrackAndBackRestoresInvokerInRtl() {
        var selections = 0
        rule.setContent { Viewport(forceTv = true) {
            var open by remember { mutableStateOf(false) }
            val invoker = remember { FocusRequester() }
            LaunchedEffect(open) { if (!open) { withFrameNanos { }; invoker.requestFocus() } }
            if (open) {
                BackHandler { open = false }
                TvOptionPanel("Synthetic tracks", (1..24).map {
                    TvPanelOption("track-$it", "Synthetic track $it", selected = it == 24,
                        onClick = { selections++; open = false })
                }, onDismiss = { open = false }, dismissLabel = "Close")
            } else A2ActionButton("Open tracks", onClick = { open = true }, modifier = Modifier.focusRequester(invoker))
        } }
        for (rtl in listOf(false, true)) {
            rule.runOnIdle { scenario.value = Case("tr", 2f, rtl) }
            rule.onNodeWithText("Open tracks").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
            rule.onNodeWithText("Synthetic track 24").assertIsDisplayed().assertIsFocused().assertIsSelected()
            rule.onNodeWithText("Synthetic track 24").performKeyInput { pressKey(Key.DirectionUp) }
            rule.onNodeWithText("Synthetic track 23").assertIsFocused()
            screenshot("tv-track-panel-$rtl")
            pressBack()
            rule.onNodeWithText("Open tracks").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
            rule.onNodeWithText("Synthetic track 24").performKeyInput { pressKey(Key.DirectionCenter) }
            rule.onNodeWithText("Open tracks").assertIsFocused()
        }
        assertThat(selections).isEqualTo(2)
    }

    private fun matrix(check: (Case) -> Unit) {
        val cases = listOf("tr", "en").flatMap { language -> listOf(1f, 1.5f, 2f).map { Case(language, it) } } + Case("tr", 2f, true)
        for (case in cases) {
            rule.runOnIdle { scenario.value = case }
            rule.waitForIdle()
            check(case)
        }
    }

    @Composable private fun Viewport(forceTv: Boolean = tv, content: @Composable () -> Unit) {
        val case = scenario.value
        val base = LocalContext.current
        val registry = checkNotNull(LocalActivityResultRegistryOwner.current)
        val density = LocalDensity.current.density
        val input = LocalInputModeManager.current
        SideEffect { input.requestInputMode(InputMode.Keyboard) }
        val config = Configuration(LocalConfiguration.current).apply {
            setLocale(Locale.forLanguageTag(case.language)); fontScale = case.font
            screenHeightDp = 360; screenWidthDp = if (forceTv || tv) 860 else 360; smallestScreenWidthDp = 360
        }
        val localized = remember(base, case) { base.createConfigurationContext(config) }
        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
            LocalDensity provides Density(density, case.font), LocalActivityResultRegistryOwner provides registry,
            LocalSoftwareKeyboardController provides null,
            LocalLayoutDirection provides if (case.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            IdealPlayerTheme(isTv = forceTv) { key(case) {
                Box(Modifier.width(if (forceTv || tv) 860.dp else 360.dp).height(360.dp)
                    .background(IdealPlayerColors.Background)) { content() }
            } }
        }
    }

    private fun localized(id: Int, case: Case) = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(case.language)) }
    ).getString(id)

    private fun screenshot(name: String) {
        rule.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

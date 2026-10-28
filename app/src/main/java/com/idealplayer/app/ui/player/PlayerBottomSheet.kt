package com.idealplayer.app.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.idealplayer.app.R
import com.idealplayer.app.core.designsystem.theme.IdealPlayerColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Keeps mobile player panels in the playback window, avoiding a second full-screen GPU surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerBottomSheet(
    title: String,
    onDismissRequest: () -> Unit,
    isTv: Boolean = false,
    tonalElevation: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    if (isTv) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            containerColor = IdealPlayerColors.Surface,
            tonalElevation = tonalElevation,
            content = content
        )
        return
    }

    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // The scaffold must not offer a collapse accessibility action for a skipped state.
        confirmValueChange = { it != SheetValue.PartiallyExpanded }
    )
    val scope = rememberCoroutineScope()
    val dismiss = rememberUpdatedState(onDismissRequest)
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state) {
        focusManager.clearFocus(force = true)
        snapshotFlow { state.hasExpandedState }.first { it }
        state.show()
    }
    LaunchedEffect(state) {
        // Ignore initial Hidden; also observe a swipe that interrupts the opening animation.
        snapshotFlow { state.targetValue }.first { it == SheetValue.Expanded }
        snapshotFlow { state.currentValue to state.targetValue }
            .first { (current, target) -> current == SheetValue.Hidden && target == SheetValue.Hidden }
        dismiss.value()
    }
    val close: () -> Unit = { scope.launch { state.hide() }; Unit }
    BackHandler(onBack = close)
    val closeLabel = stringResource(R.string.player_close)

    BottomSheetScaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .semantics { dialog(); paneTitle = title; isTraversalGroup = true }
            .testTag("player-modal-sheet"),
        scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = state),
        sheetPeekHeight = 0.dp,
        sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        sheetContainerColor = IdealPlayerColors.Surface,
        sheetContentColor = IdealPlayerColors.TextPrimary,
        sheetTonalElevation = tonalElevation,
        containerColor = Color.Transparent,
        sheetContent = {
            Column(Modifier.navigationBarsPadding(), content = content)
        }
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = close)
                .semantics { contentDescription = closeLabel }
                .testTag("player-modal-scrim")
        )
    }
}

/** Modal content is a sibling; hide underlying controls from accessibility and keyboard focus. */
internal fun Modifier.playerSheetBackgroundHidden(hidden: Boolean): Modifier = if (hidden) {
    this.focusProperties { canFocus = false }.clearAndSetSemantics { }
} else this

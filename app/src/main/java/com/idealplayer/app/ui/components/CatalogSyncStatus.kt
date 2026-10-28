package com.idealplayer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.idealplayer.app.R
import com.idealplayer.app.core.designsystem.theme.A2Spacing
import com.idealplayer.app.core.model.CatalogSyncProgress
import com.idealplayer.app.core.model.CatalogSyncStage
import com.idealplayer.app.ui.components.a2.A2ActionVariant
import com.idealplayer.app.ui.components.a2.A2StatusSurface
import com.idealplayer.app.ui.components.a2.A2StatusType

/** Shared mobile/TV representation of a cancelable, privacy-safe catalog refresh. */
@Composable
fun CatalogSyncStatusSurface(
    progress: CatalogSyncProgress?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier
) {
    val message = catalogSyncMessage(progress)
    A2StatusSurface(
        type = A2StatusType.Syncing,
        title = stringResource(R.string.a2_playlist_preparing_title),
        message = message,
        modifier = modifier,
        stateDescription = message,
        actionLabel = stringResource(R.string.cancel),
        onAction = onCancel,
        actionModifier = actionModifier,
        actionVariant = A2ActionVariant.Secondary,
        progress = progress?.overallFraction,
        progressContentDescription = stringResource(R.string.catalog_sync_progress),
        progressValueLabel = catalogSyncValueLabel(progress)
    )
}

/** Dedicated modal focus window so D-pad input cannot leak to content during a refresh. */
@Composable
fun CatalogSyncDialog(
    progress: CatalogSyncProgress?,
    onCancel: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }

    LaunchedEffect(progress?.playlistId) {
        withFrameNanos { }
        runCatching { cancelFocusRequester.requestFocus() }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(A2Spacing.xl),
            contentAlignment = Alignment.Center
        ) {
            CatalogSyncStatusSurface(
                progress = progress,
                onCancel = onCancel,
                actionModifier = Modifier.focusRequester(cancelFocusRequester),
                modifier = Modifier.widthIn(min = 320.dp, max = 560.dp)
            )
        }
    }
}

@Composable
private fun catalogSyncMessage(progress: CatalogSyncProgress?): String = when (progress?.stage) {
    CatalogSyncStage.PREPARING, null -> stringResource(R.string.catalog_sync_preparing)
    CatalogSyncStage.LOADING_SOURCE -> stringResource(R.string.catalog_sync_loading_source)
    CatalogSyncStage.PERSISTING_CATALOG -> {
        val total = progress.totalItems
        if (total != null && total > 0) {
            pluralStringResource(
                R.plurals.catalog_sync_persisting_items,
                total,
                progress.processedItems.coerceAtMost(total),
                total
            )
        } else {
            stringResource(R.string.catalog_sync_persisting)
        }
    }
    CatalogSyncStage.FINALIZING -> stringResource(R.string.catalog_sync_finalizing)
    CatalogSyncStage.SCHEDULING_EPG -> stringResource(R.string.catalog_sync_scheduling_epg)
    CatalogSyncStage.COMPLETED -> stringResource(R.string.catalog_sync_completed)
}

@Composable
private fun catalogSyncValueLabel(progress: CatalogSyncProgress?): String? {
    val total = progress?.totalItems?.takeIf { it > 0 } ?: return null
    return stringResource(
        R.string.catalog_sync_items_value,
        progress.processedItems.coerceAtMost(total),
        total
    )
}

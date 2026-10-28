package com.idealplayer.app.ui.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.idealplayer.app.R

@Composable
internal fun CatalogPageRetry(onRetry: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        FilledTonalButton(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = 56.dp).onFocusChanged { focused = it.isFocused },
            border = if (focused) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null
        ) { Text(stringResource(R.string.retry)) }
    }
}

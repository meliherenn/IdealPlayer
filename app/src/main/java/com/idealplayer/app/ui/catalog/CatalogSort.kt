package com.idealplayer.app.ui.catalog

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.idealplayer.app.R
import com.idealplayer.app.core.designsystem.theme.IdealPlayerColors
import com.idealplayer.app.core.model.Movie
import com.idealplayer.app.core.model.Series
import java.text.Collator
import java.util.Locale

enum class CatalogSortOption(@StringRes val labelRes: Int) {
    TITLE(R.string.catalog_sort_title),
    RATING(R.string.catalog_sort_rating),
    RECENT(R.string.catalog_sort_recent)
}

internal fun sortMovies(
    movies: List<Movie>,
    option: CatalogSortOption,
    locale: Locale = Locale.getDefault()
): List<Movie> = movies.sortedWith(
    catalogComparator(
        option = option,
        title = Movie::name,
        rating = Movie::rating,
        addedAt = Movie::addedAt,
        sourceOrder = Movie::sourceOrder,
        id = Movie::id,
        locale = locale
    )
)

internal fun sortSeries(
    series: List<Series>,
    option: CatalogSortOption,
    locale: Locale = Locale.getDefault()
): List<Series> = series.sortedWith(
    catalogComparator(
        option = option,
        title = Series::name,
        rating = Series::rating,
        addedAt = Series::addedAt,
        sourceOrder = Series::sourceOrder,
        id = Series::id,
        locale = locale
    )
)

private fun <T> catalogComparator(
    option: CatalogSortOption,
    title: (T) -> String,
    rating: (T) -> Double,
    addedAt: (T) -> Long,
    sourceOrder: (T) -> Int,
    id: (T) -> Long,
    locale: Locale
): Comparator<T> {
    val titleCollator = Collator.getInstance(locale).apply {
        strength = Collator.PRIMARY
    }
    val titleComparator = Comparator<T> { left, right ->
        val result = titleCollator.compare(title(left).trim(), title(right).trim())
        if (result != 0) result else id(left).compareTo(id(right))
    }
    return when (option) {
        CatalogSortOption.TITLE -> titleComparator
        CatalogSortOption.RATING -> Comparator { left, right ->
            val result = safeRating(rating(right)).compareTo(safeRating(rating(left)))
            if (result != 0) result else titleComparator.compare(left, right)
        }
        CatalogSortOption.RECENT -> Comparator { left, right ->
            val addedResult = addedAt(right).compareTo(addedAt(left))
            if (addedResult != 0) {
                addedResult
            } else {
                val orderResult = sourceOrder(left).compareTo(sourceOrder(right))
                if (orderResult != 0) orderResult else id(right).compareTo(id(left))
            }
        }
    }
}

private fun safeRating(value: Double): Double =
    value.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

@Composable
fun CatalogSortMenu(
    selected: CatalogSortOption,
    onSelected: (CatalogSortOption) -> Unit,
    isTv: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val firstItemRequester = remember { FocusRequester() }
    val selectedLabel = stringResource(selected.labelRes)
    val description = stringResource(R.string.catalog_sort_description, selectedLabel)

    LaunchedEffect(expanded) {
        if (expanded) {
            repeat(4) {
                withFrameNanos { }
                val focused = runCatching {
                    firstItemRequester.requestFocus()
                    true
                }
                    .getOrDefault(false)
                if (focused) return@LaunchedEffect
            }
        }
    }

    Box(
        modifier = modifier.then(
            if (isTv) {
                Modifier
                    .width(244.dp)
                    .height(68.dp)
            } else {
                Modifier.size(48.dp)
            }
        )
    ) {
        if (isTv) {
            FilledTonalButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxSize(),
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = IdealPlayerColors.SurfaceSelected,
                    contentColor = IdealPlayerColors.TextPrimary
                ),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(2.dp, IdealPlayerColors.SelectedBorder)
            ) {
                Icon(
                    Icons.Filled.FilterList,
                    contentDescription = description,
                    tint = IdealPlayerColors.Primary
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.catalog_sort_heading),
                        style = MaterialTheme.typography.labelSmall,
                        color = IdealPlayerColors.TextSecondary
                    )
                    Text(
                        text = selectedLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = IdealPlayerColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            FilledTonalIconButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxSize(),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = IdealPlayerColors.SurfaceSelected,
                    contentColor = IdealPlayerColors.Primary
                )
            ) {
                Icon(Icons.Filled.FilterList, contentDescription = description)
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            CatalogSortOption.entries.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    leadingIcon = {
                        if (selected == option) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                    modifier = if (index == 0) {
                        Modifier.focusRequester(firstItemRequester)
                    } else {
                        Modifier
                    }
                )
            }
        }
    }
}

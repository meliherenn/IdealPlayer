package com.idealplayer.app.ui.series

import com.idealplayer.app.ui.components.tvRememberedScrollFocus
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.idealplayer.app.core.designsystem.theme.IdealPlayerColors
import com.idealplayer.app.core.designsystem.theme.LocalIdealPlayerDimens
import com.idealplayer.app.core.model.Series
import com.idealplayer.app.data.repository.ContentRepository
import com.idealplayer.app.data.repository.PlaylistRepository
import com.idealplayer.app.ui.components.*
import com.idealplayer.app.ui.components.a2.A2ContentCard
import com.idealplayer.app.ui.components.a2.A2ContentCardKind
import com.idealplayer.app.ui.catalog.CatalogSortMenu
import com.idealplayer.app.ui.catalog.CatalogSortOption
import com.idealplayer.app.ui.catalog.CatalogPager
import com.idealplayer.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.idealplayer.app.R

import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope

data class SeriesState(
    val series: List<Series> = emptyList(),
    val categories: List<String> = emptyList(),
    val categoryCounts: Map<String, Int> = emptyMap(),
    val selectedCategory: String? = null,
    val sortOption: CatalogSortOption = CatalogSortOption.TITLE,
    val isLoading: Boolean = true,
    val totalCount: Int = 0,
    val canLoadMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false
)

@HiltViewModel
class SeriesViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val contentRepository: ContentRepository
) : ViewModel() {
    private val _state = MutableStateFlow(SeriesState())
    val state: StateFlow<SeriesState> = _state.asStateFlow()
    private val pager = CatalogPager<Series>()
    private var activePlaylistId: Long? = null
    private var sourceScope: CoroutineScope? = null

    companion object { const val PAGE_SIZE = 60 }

    init {
        viewModelScope.launch {
            pager.state.collect { page ->
                _state.update { it.copy(
                    series = page.items, totalCount = page.totalCount,
                    isLoading = page.isLoading, isLoadingMore = page.isLoadingMore,
                    canLoadMore = page.canLoadMore, loadFailed = page.failed
                ) }
            }
        }
        viewModelScope.launch {
            playlistRepository.getActivePlaylistSummary()
                .distinctUntilChangedBy { it?.id to it?.sourceRevision }
                .collectLatest { playlist ->
                    pager.clear()
                    sourceScope = null
                    activePlaylistId = playlist?.id
                    _state.value = SeriesState(isLoading = playlist != null)
                    if (playlist == null) return@collectLatest
                    coroutineScope {
                        sourceScope = this
                        launch(Dispatchers.IO) { contentRepository.backfillMissingSeriesArtwork(playlist.id) }
                        combine(
                            contentRepository.getSeriesCategoryCounts(playlist.id),
                            contentRepository.catalogAccessPolicy
                        ) { counts, policy -> counts to policy }.collect { (counts, policy) ->
                            val retained = _state.value.series.filter { policy.allows(it.categoryName) }
                            _state.update { it.copy(
                                categories = counts.keys.toList(), categoryCounts = counts,
                                selectedCategory = it.selectedCategory?.takeIf(counts::containsKey),
                                series = retained
                            ) }
                            loadInitialPage(playlist.id, retained)
                        }
                    }
                }
        }
    }

    private fun loadInitialPage(playlistId: Long, retained: List<Series> = emptyList()) {
        val scope = sourceScope ?: return
        val selection = _state.value
        pager.reset(scope, retained) { offset, limit ->
            contentRepository.getSeriesPage(playlistId, selection.selectedCategory, selection.sortOption, limit, offset)
        }
    }

    fun selectCategory(category: String?) {
        if (_state.value.selectedCategory == category) return
        _state.update { it.copy(selectedCategory = category, series = emptyList()) }
        activePlaylistId?.let { loadInitialPage(it) }
    }

    fun selectSortOption(option: CatalogSortOption) {
        if (_state.value.sortOption == option) return
        _state.update { it.copy(sortOption = option, series = emptyList()) }
        activePlaylistId?.let { loadInitialPage(it) }
    }

    fun loadMore() = pager.append()
    fun retryPage() = pager.retry()

    fun repairArtwork(seriesId: Long) {
        viewModelScope.launch(Dispatchers.IO) { contentRepository.repairSeriesArtwork(seriesId) }
    }
}

@Composable
fun SeriesScreen(
    isTv: Boolean,
    onNavigate: (String) -> Unit,
    onSeriesClick: (Long) -> Unit,
    viewModel: SeriesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var isDrawerExpanded by remember { mutableStateOf(false) }

    if (isTv) {
        IdealPlayerDrawer(
            isExpanded = isDrawerExpanded,
            selectedRoute = Routes.SERIES,
            isTv = true,
            onToggle = { isDrawerExpanded = !isDrawerExpanded },
            onNavigate = onNavigate
        ) {
            if (state.isLoading && state.categories.isEmpty()) LoadingScreen()
            else TvSeriesContent(state, viewModel, onSeriesClick)
        }
    } else {
        if (state.isLoading && state.categories.isEmpty()) LoadingScreen()
        else MobileSeriesContent(
            state = state,
            viewModel = viewModel,
            onSeriesClick = onSeriesClick,
            onSearch = { onNavigate(Routes.SEARCH) }
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvSeriesContent(
    state: SeriesState,
    viewModel: SeriesViewModel,
    onSeriesClick: (Long) -> Unit
) {
    val dimens = LocalIdealPlayerDimens.current
    val allCategoryFocusRequester = remember { FocusRequester() }
    val display = state.series
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, state.series.size, state.canLoadMore, state.isLoadingMore, state.loadFailed) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                if (lastVisible != null && lastVisible >= state.series.size - 12 && state.canLoadMore && !state.isLoadingMore && !state.loadFailed) {
                    viewModel.loadMore()
                }
            }
    }

    // Preserve the current category/grid focus across filtering and data refreshes. The
    // category panel itself is always composed, so it is a safe initial target once loading
    // finishes without continuously stealing focus from the remote user.
    LaunchedEffect(state.isLoading) {
        if (hasRequestedInitialFocus || state.isLoading) return@LaunchedEffect
        withFrameNanos { }
        allCategoryFocusRequester.requestFocusSafely()
        hasRequestedInitialFocus = true
    }

    Row(modifier = Modifier.fillMaxSize()) {
        TvCategoryPanel {
            item(key = tvCategoryLazyKey("series", null)) {
                TvRailCategoryItem(
                    name = stringResource(R.string.category_all),
                    isSelected = state.selectedCategory == null,
                    modifier = Modifier
                        .focusRequester(allCategoryFocusRequester)
                        .focusProperties {
                            // LazyGrid can preserve a scrolled viewport across filtering. Use
                            // spatial traversal so RIGHT never targets a detached index-zero row.
                            if (display.isEmpty()) right = FocusRequester.Cancel
                            up = FocusRequester.Cancel
                        },
                    onClick = { viewModel.selectCategory(null) }
                )
            }
            itemsIndexed(
                items = state.categories,
                key = { _, cat -> tvCategoryLazyKey("series", cat) }
            ) { index, cat ->
                TvRailCategoryItem(
                    name = cat,
                    isSelected = state.selectedCategory == cat,
                    modifier = Modifier.focusProperties {
                        if (display.isEmpty()) right = FocusRequester.Cancel
                        if (index == state.categories.lastIndex) {
                            down = FocusRequester.Cancel
                        }
                    },
                    onClick = { viewModel.selectCategory(cat) }
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 32.dp, top = 36.dp, end = 48.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.series),
                    style = MaterialTheme.typography.headlineLarge,
                    color = IdealPlayerColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                CatalogSortMenu(
                    selected = state.sortOption,
                    onSelected = viewModel::selectSortOption,
                    isTv = true
                )
            }
            Spacer(modifier = Modifier.height(24.dp))

            if (state.loadFailed && display.isEmpty()) {
                com.idealplayer.app.ui.catalog.CatalogPageRetry(viewModel::retryPage)
            } else if (display.isEmpty()) {
                EmptyScreen(message = stringResource(R.string.no_content))
            } else {
                val coroutineScope = rememberCoroutineScope()
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(dimens.gridColumns),
                    modifier = Modifier.fillMaxSize().tvRememberedScrollFocus(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    itemsIndexed(display, key = { _, it -> it.id }) { index, item ->
                        SeriesPosterCard(
                            series = item,
                            modifier = Modifier
                                .width(250.dp)
                                .height(403.dp)
                                .onPreviewKeyEvent { keyEvent ->
                                    if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    val jumpStep = (dimens.gridColumns.coerceAtLeast(1) * 3).coerceAtLeast(10)
                                    when (keyEvent.nativeKeyEvent.keyCode) {
                                        AndroidKeyEvent.KEYCODE_PAGE_UP,
                                        AndroidKeyEvent.KEYCODE_CHANNEL_UP,
                                        AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                                            val target = (index - jumpStep).coerceAtLeast(0)
                                            coroutineScope.launch { gridState.scrollToItem(target) }
                                            true
                                        }
                                        AndroidKeyEvent.KEYCODE_PAGE_DOWN,
                                        AndroidKeyEvent.KEYCODE_CHANNEL_DOWN,
                                        AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                            val target = (index + jumpStep).coerceAtMost(display.lastIndex.coerceAtLeast(0))
                                            coroutineScope.launch { gridState.scrollToItem(target) }
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            onPosterError = { viewModel.repairArtwork(item.id) },
                            onClick = { onSeriesClick(item.id) }
                        )
                    }
                    if (state.loadFailed) {
                        item(key = "catalog-page-retry", span = { GridItemSpan(maxLineSpan) }) {
                            com.idealplayer.app.ui.catalog.CatalogPageRetry(viewModel::retryPage)
                        }
                    }
                    if (state.isLoadingMore) {
                        item(span = { GridItemSpan(dimens.gridColumns) }) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = IdealPlayerColors.Primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun FocusRequester.requestFocusSafely() {
    runCatching { requestFocus() }
}

@Composable
private fun MobileSeriesContent(
    state: SeriesState,
    viewModel: SeriesViewModel,
    onSeriesClick: (Long) -> Unit,
    onSearch: () -> Unit
) {
    val dimens = LocalIdealPlayerDimens.current
    var showCategorySheet by remember { mutableStateOf(false) }
    var recentCategories by remember { mutableStateOf(listOf<String>()) }
    var pinnedCategories by remember { mutableStateOf(listOf<String>()) }

    val categoryCounts = state.categoryCounts
    val display = state.series

    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, state.series.size, state.canLoadMore, state.isLoadingMore, state.loadFailed) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                if (lastVisible != null && lastVisible >= state.series.size - 12 && state.canLoadMore && !state.isLoadingMore && !state.loadFailed) {
                    viewModel.loadMore()
                }
            }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = dimens.screenPadding)) {
        MobileSeriesCatalogHeader(
            title = stringResource(R.string.series),
            count = state.totalCount,
            selectedCategory = state.selectedCategory,
            sortOption = state.sortOption,
            onSortSelected = viewModel::selectSortOption,
            onSearch = onSearch,
            modifier = Modifier.padding(horizontal = dimens.screenPadding)
        )
        Spacer(modifier = Modifier.height(12.dp))

        QuickCategoryBar(
            categories = state.categories,
            selectedCategory = state.selectedCategory,
            recentCategories = recentCategories,
            onCategorySelected = { cat ->
                viewModel.selectCategory(cat)
                if (cat != null && cat !in recentCategories) {
                    recentCategories = (listOf(cat) + recentCategories).take(10)
                }
            },
            onBrowseAll = { showCategorySheet = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (state.loadFailed && display.isEmpty()) {
                com.idealplayer.app.ui.catalog.CatalogPageRetry(viewModel::retryPage)
            } else if (display.isEmpty()) {
            EmptyScreen(message = stringResource(R.string.no_content))
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(dimens.gridColumns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = dimens.screenPadding, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
                verticalArrangement = Arrangement.spacedBy(dimens.cardSpacing)
            ) {
                items(display, key = { it.id }) { item ->
                    SeriesPosterCard(
                        series = item,
                        modifier = Modifier.fillMaxWidth(),
                        onPosterError = { viewModel.repairArtwork(item.id) },
                        onClick = { onSeriesClick(item.id) }
                    )
                }
                if (state.loadFailed) {
                    item(key = "catalog-page-retry", span = { GridItemSpan(maxLineSpan) }) {
                        com.idealplayer.app.ui.catalog.CatalogPageRetry(viewModel::retryPage)
                    }
                }
                if (state.isLoadingMore) {
                    item(span = { GridItemSpan(dimens.gridColumns) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = IdealPlayerColors.Primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    CategoryBottomSheet(
        isVisible = showCategorySheet,
        categories = state.categories,
        categoryCounts = categoryCounts,
        selectedCategory = state.selectedCategory,
        recentCategories = recentCategories,
        pinnedCategories = pinnedCategories,
        onCategorySelected = { cat ->
            viewModel.selectCategory(cat)
            if (cat != null && cat !in recentCategories) {
                recentCategories = (listOf(cat) + recentCategories).take(10)
            }
        },
        onDismiss = { showCategorySheet = false },
        onTogglePin = { cat ->
            pinnedCategories = if (cat in pinnedCategories) pinnedCategories - cat else pinnedCategories + cat
        }
    )
}

@Composable
private fun SeriesPosterCard(
    series: Series,
    modifier: Modifier = Modifier,
    onPosterError: () -> Unit,
    onClick: () -> Unit
) {
    val metadata = remember(series.year, series.rating) {
        buildList {
            if (series.year > 0) add(series.year.toString())
            if (series.rating > 0.0) add(String.format(java.util.Locale.US, "%.1f", series.rating))
        }.joinToString(" • ").takeIf { it.isNotBlank() }
    }

    A2ContentCard(
        kind = A2ContentCardKind.Series,
        title = series.name,
        metadata = metadata,
        contentDescription = series.name,
        onClick = onClick,
        modifier = modifier,
        artwork = {
            PosterImage(
                url = series.posterUrl,
                contentDescription = series.name,
                onError = onPosterError,
                modifier = Modifier.matchParentSize()
            )
        }
    )
}

@Composable
private fun MobileSeriesCatalogHeader(
    title: String,
    count: Int,
    selectedCategory: String?,
    sortOption: CatalogSortOption,
    onSortSelected: (CatalogSortOption) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = IdealPlayerColors.TextPrimary)
            Text(
                text = selectedCategory?.let { "$it • ${pluralStringResource(R.plurals.catalog_item_count, count, count)}" }
                    ?: pluralStringResource(R.plurals.catalog_item_count, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = IdealPlayerColors.TextSecondary,
                maxLines = 1
            )
        }
        com.idealplayer.app.ui.components.a2.A2Badge(
            text = count.toString(),
            tone = com.idealplayer.app.ui.components.a2.A2BadgeTone.Selected,
            contentDescription = pluralStringResource(R.plurals.catalog_item_count, count, count)
        )
        Spacer(Modifier.width(8.dp))
        CatalogSortMenu(
            selected = sortOption,
            onSelected = onSortSelected,
            isTv = false
        )
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(
            onClick = onSearch,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = IdealPlayerColors.SurfaceElevated,
                contentColor = IdealPlayerColors.TextPrimary
            )
        ) {
            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
        }
    }
}

package com.idealplayer.app.ui.movies

import com.idealplayer.app.ui.components.tvRememberedScrollFocus
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.idealplayer.app.core.designsystem.theme.IdealPlayerColors
import com.idealplayer.app.core.designsystem.theme.LocalIdealPlayerDimens
import com.idealplayer.app.core.model.Movie
import com.idealplayer.app.data.repository.ContentRepository
import com.idealplayer.app.data.repository.PlaylistRepository
import com.idealplayer.app.ui.components.*
import com.idealplayer.app.ui.components.a2.A2ContentCard
import com.idealplayer.app.ui.components.a2.A2ContentCardKind
import com.idealplayer.app.ui.components.a2.A2Badge
import com.idealplayer.app.ui.components.a2.A2BadgeTone
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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

data class MoviesState(
    val movies: List<Movie> = emptyList(),
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
class MoviesViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val contentRepository: ContentRepository
) : ViewModel() {
    private val _state = MutableStateFlow(MoviesState())
    val state: StateFlow<MoviesState> = _state.asStateFlow()
    private val pager = CatalogPager<Movie>()
    private var activePlaylistId: Long? = null
    private var sourceScope: CoroutineScope? = null

    companion object { const val PAGE_SIZE = 60 }

    init {
        viewModelScope.launch {
            pager.state.collect { page ->
                _state.update { it.copy(
                    movies = page.items, totalCount = page.totalCount,
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
                    _state.value = MoviesState(isLoading = playlist != null)
                    if (playlist == null) return@collectLatest
                    coroutineScope {
                        sourceScope = this
                        launch(Dispatchers.IO) { contentRepository.backfillMissingMovieArtwork(playlist.id) }
                        combine(
                            contentRepository.getMovieCategoryCounts(playlist.id),
                            contentRepository.catalogAccessPolicy
                        ) { counts, policy -> counts to policy }.collect { (counts, policy) ->
                            val retained = _state.value.movies.filter { policy.allows(it.categoryName) }
                            _state.update { it.copy(
                                categories = counts.keys.toList(), categoryCounts = counts,
                                selectedCategory = it.selectedCategory?.takeIf(counts::containsKey),
                                movies = retained
                            ) }
                            loadInitialPage(playlist.id, retained)
                        }
                    }
                }
        }
    }

    private fun loadInitialPage(playlistId: Long, retained: List<Movie> = emptyList()) {
        val scope = sourceScope ?: return
        val selection = _state.value
        pager.reset(scope, retained) { offset, limit ->
            contentRepository.getMoviesPage(playlistId, selection.selectedCategory, selection.sortOption, limit, offset)
        }
    }

    fun selectCategory(category: String?) {
        if (_state.value.selectedCategory == category) return
        _state.update { it.copy(selectedCategory = category, movies = emptyList()) }
        activePlaylistId?.let { loadInitialPage(it) }
    }

    fun selectSortOption(option: CatalogSortOption) {
        if (_state.value.sortOption == option) return
        _state.update { it.copy(sortOption = option, movies = emptyList()) }
        activePlaylistId?.let { loadInitialPage(it) }
    }

    fun loadMore() = pager.append()
    fun retryPage() = pager.retry()

    fun repairArtwork(movieId: Long) {
        viewModelScope.launch(Dispatchers.IO) { contentRepository.repairMovieArtwork(movieId) }
    }

    fun toggleFavorite(movie: Movie) {
        val updatedFavorite = !movie.isFavorite
        _state.update { current ->
            current.copy(
                movies = current.movies.map {
                    if (it.id == movie.id) it.copy(isFavorite = updatedFavorite) else it
                }
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            contentRepository.toggleMovieFavorite(movie.id, updatedFavorite)
        }
    }
}

@Composable
fun MoviesScreen(
    isTv: Boolean,
    onNavigate: (String) -> Unit,
    onMovieClick: (Long) -> Unit,
    viewModel: MoviesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var isDrawerExpanded by remember { mutableStateOf(false) }

    if (isTv) {
        IdealPlayerDrawer(
            isExpanded = isDrawerExpanded,
            selectedRoute = Routes.MOVIES,
            isTv = true,
            onToggle = { isDrawerExpanded = !isDrawerExpanded },
            onNavigate = onNavigate
        ) {
            if (state.isLoading && state.categories.isEmpty()) LoadingScreen()
            else TvMoviesContent(state, viewModel, onMovieClick)
        }
    } else {
        if (state.isLoading && state.categories.isEmpty()) LoadingScreen()
        else MobileMoviesContent(
            state = state,
            viewModel = viewModel,
            onMovieClick = onMovieClick,
            onSearch = { onNavigate(Routes.SEARCH) }
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// TV: Side category panel + grid
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvMoviesContent(
    state: MoviesState,
    viewModel: MoviesViewModel,
    onMovieClick: (Long) -> Unit
) {
    val dimens = LocalIdealPlayerDimens.current
    val allCategoryFocusRequester = remember { FocusRequester() }

    val displayMovies = state.movies
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, state.movies.size, state.canLoadMore, state.isLoadingMore, state.loadFailed) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                if (lastVisible != null && lastVisible >= state.movies.size - 12 && state.canLoadMore && !state.isLoadingMore && !state.loadFailed) {
                    viewModel.loadMore()
                }
            }
    }

    // Category changes are initiated from the focused category item. Requesting focus again
    // here used to pull focus back to "All" every time a user chose a category or the grid
    // refreshed. Only establish the entry focus once, after the first composed frame.
    LaunchedEffect(state.isLoading) {
        if (hasRequestedInitialFocus || state.isLoading) return@LaunchedEffect
        withFrameNanos { }
        allCategoryFocusRequester.requestFocusSafely()
        hasRequestedInitialFocus = true
    }

    Row(modifier = Modifier.fillMaxSize()) {
        TvCategoryPanel {
            item(key = tvCategoryLazyKey("movies", null)) {
                TvRailCategoryItem(
                    name = stringResource(R.string.category_all),
                    isSelected = state.selectedCategory == null,
                    modifier = Modifier
                        .focusRequester(allCategoryFocusRequester)
                        .focusProperties {
                            // Let spatial focus search choose a currently composed grid card.
                            // The grid may retain a non-zero scroll position after filtering,
                            // so its first item is not necessarily attached.
                            if (displayMovies.isEmpty()) right = FocusRequester.Cancel
                            up = FocusRequester.Cancel
                        },
                    onClick = { viewModel.selectCategory(null) }
                )
            }
            itemsIndexed(
                items = state.categories,
                key = { _, cat -> tvCategoryLazyKey("movies", cat) }
            ) { index, cat ->
                TvRailCategoryItem(
                    name = cat,
                    isSelected = state.selectedCategory == cat,
                    modifier = Modifier.focusProperties {
                        if (displayMovies.isEmpty()) right = FocusRequester.Cancel
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.movies),
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

            if (state.loadFailed && displayMovies.isEmpty()) {
                com.idealplayer.app.ui.catalog.CatalogPageRetry(viewModel::retryPage)
            } else if (displayMovies.isEmpty()) {
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
                    itemsIndexed(displayMovies, key = { _, it -> it.id }) { index, movie ->
                        MoviePosterCard(
                            movie = movie,
                            modifier = Modifier
                                .width(250.dp)
                                .height(403.dp)
                                .onPreviewKeyEvent { keyEvent ->
                                    if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    val jumpStep = (dimens.gridColumns.coerceAtLeast(1) * 3).coerceAtLeast(10)
                                    when (keyEvent.nativeKeyEvent.keyCode) {
                                        AndroidKeyEvent.KEYCODE_PROG_YELLOW,
                                        AndroidKeyEvent.KEYCODE_BOOKMARK -> {
                                            viewModel.toggleFavorite(movie)
                                            true
                                        }
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
                                            val target = (index + jumpStep).coerceAtMost(displayMovies.lastIndex.coerceAtLeast(0))
                                            coroutineScope.launch { gridState.scrollToItem(target) }
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            onPosterError = { viewModel.repairArtwork(movie.id) },
                            onClick = { onMovieClick(movie.id) }
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
                                contentAlignment = Alignment.Center
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

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Mobile: Quick category bar + bottom sheet + grid
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun MobileMoviesContent(
    state: MoviesState,
    viewModel: MoviesViewModel,
    onMovieClick: (Long) -> Unit,
    onSearch: () -> Unit
) {
    val dimens = LocalIdealPlayerDimens.current
    var showCategorySheet by remember { mutableStateOf(false) }
    var recentCategories by remember { mutableStateOf(listOf<String>()) }
    var pinnedCategories by remember { mutableStateOf(listOf<String>()) }

    val categoryCounts = state.categoryCounts
    val displayMovies = state.movies

    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, state.movies.size, state.canLoadMore, state.isLoadingMore, state.loadFailed) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                if (lastVisible != null && lastVisible >= state.movies.size - 12 && state.canLoadMore && !state.isLoadingMore && !state.loadFailed) {
                    viewModel.loadMore()
                }
            }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = dimens.screenPadding)) {
        MobileCatalogHeader(
            title = stringResource(R.string.movies),
            count = state.totalCount,
            selectedCategory = state.selectedCategory,
            sortOption = state.sortOption,
            onSortSelected = viewModel::selectSortOption,
            onSearch = onSearch,
            modifier = Modifier.padding(horizontal = dimens.screenPadding)
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Quick category bar with "Browse All" button
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

        if (state.loadFailed && displayMovies.isEmpty()) {
                com.idealplayer.app.ui.catalog.CatalogPageRetry(viewModel::retryPage)
            } else if (displayMovies.isEmpty()) {
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
                items(displayMovies, key = { it.id }) { movie ->
                    MoviePosterCard(
                        movie = movie,
                        modifier = Modifier.fillMaxWidth(),
                        onPosterError = { viewModel.repairArtwork(movie.id) },
                        onClick = { onMovieClick(movie.id) }
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
                            contentAlignment = Alignment.Center
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

    // Category bottom sheet
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
private fun MobileCatalogHeader(
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
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = IdealPlayerColors.TextPrimary
            )
            Text(
                text = selectedCategory?.let { "$it • ${pluralStringResource(R.plurals.catalog_item_count, count, count)}" }
                    ?: pluralStringResource(R.plurals.catalog_item_count, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = IdealPlayerColors.TextSecondary,
                maxLines = 1
            )
        }
        A2Badge(
            text = count.toString(),
            tone = A2BadgeTone.Selected,
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

@Composable
private fun MoviePosterCard(
    movie: Movie,
    modifier: Modifier = Modifier,
    onPosterError: () -> Unit,
    onClick: () -> Unit
) {
    val metadata = remember(movie.year, movie.rating) {
        buildList {
            if (movie.year > 0) add(movie.year.toString())
            if (movie.rating > 0.0) add(String.format(java.util.Locale.US, "%.1f", movie.rating))
        }.joinToString(" • ").takeIf { it.isNotBlank() }
    }

    A2ContentCard(
        kind = A2ContentCardKind.Movie,
        title = movie.name,
        metadata = metadata,
        contentDescription = movie.name,
        onClick = onClick,
        modifier = modifier,
        artwork = {
            PosterImage(
                url = movie.posterUrl,
                contentDescription = movie.name,
                onError = onPosterError,
                modifier = Modifier.matchParentSize()
            )
        }
    )
}

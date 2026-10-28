package com.idealplayer.app.ui.live

import com.idealplayer.app.ui.components.tvRememberedScrollFocus
import android.app.Activity
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.idealplayer.app.ui.catalog.CatalogPager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.idealplayer.app.core.designsystem.theme.IdealPlayerColors
import com.idealplayer.app.core.designsystem.theme.LocalIdealPlayerDimens
import com.idealplayer.app.core.designsystem.theme.A2Motion
import com.idealplayer.app.core.designsystem.theme.A2Shape
import com.idealplayer.app.core.model.Channel
import com.idealplayer.app.core.player.FrameRateMatchingMode
import com.idealplayer.app.core.player.PlaybackProfile
import com.idealplayer.app.core.player.PlaybackState
import com.idealplayer.app.data.repository.ContentRepository
import com.idealplayer.app.data.repository.PlaylistRepository
import com.idealplayer.app.ui.components.*
import com.idealplayer.app.ui.navigation.Routes
import com.idealplayer.app.ui.player.PlayerLifecycleEffect
import com.idealplayer.app.ui.player.PlayerShellMode
import com.idealplayer.app.ui.player.PlayerSurfaceHost
import com.idealplayer.app.ui.player.PlayerSurfaceHostState
import com.idealplayer.app.ui.player.PlayerViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import androidx.compose.ui.res.stringResource
import com.idealplayer.app.R

data class LiveTvState(
    val channels: List<Channel> = emptyList(),
    val mobileChannels: List<Channel> = emptyList(),
    val groups: List<String> = emptyList(),
    val mobileGroups: List<String> = emptyList(),
    val groupCounts: Map<String, Int> = emptyMap(),
    val selectedGroup: String? = null,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val loadFailed: Boolean = false,
    val totalCount: Int = 0,
    val query: String = "",
    val epgPrograms: Map<Long, com.idealplayer.app.data.parser.EpgProgram> = emptyMap()
)

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val contentRepository: ContentRepository,
    private val epgRepository: com.idealplayer.app.data.repository.EpgRepository
) : ViewModel() {
    private val _state = MutableStateFlow(LiveTvState())
    val state: StateFlow<LiveTvState> = _state.asStateFlow()

    private val pager = CatalogPager<Channel>()
    private var activePlaylistId: Long? = null
    private var sourceScope: kotlinx.coroutines.CoroutineScope? = null
    private var visibleEpgJob: kotlinx.coroutines.Job? = null
    private var selectionGeneration = 0L

    init {
        viewModelScope.launch {
            pager.state.collect { page ->
                _state.update { it.copy(
                    channels = page.items, mobileChannels = page.items, totalCount = page.totalCount,
                    isLoading = page.isLoading, isLoadingMore = page.isLoadingMore,
                    canLoadMore = page.canLoadMore, loadFailed = page.failed
                ) }
            }
        }
        viewModelScope.launch {
            playlistRepository.getActivePlaylistSummary().distinctUntilChangedBy { it?.id to it?.sourceRevision }
                .collectLatest { playlist ->
                    pager.clear()
                    selectionGeneration++
                    visibleEpgJob?.cancel()
                    sourceScope = null
                    activePlaylistId = playlist?.id
                    _state.value = LiveTvState(isLoading = playlist != null)
                    if (playlist == null) return@collectLatest
                    kotlinx.coroutines.coroutineScope {
                        sourceScope = this
                        launch { playlistRepository.ensureEpgSynced(playlist.id) }
                        combine(
                            contentRepository.getChannelGroupCounts(playlist.id),
                            contentRepository.catalogAccessPolicy
                        ) { counts, policy -> counts to policy }.collect { (counts, policy) ->
                            val retained = _state.value.channels.filter { policy.allows(it.groupTitle) }
                            _state.update { it.copy(
                                groups = counts.keys.toList(), mobileGroups = counts.keys.toList(), groupCounts = counts,
                                selectedGroup = it.selectedGroup?.takeIf(counts::containsKey),
                                channels = retained, mobileChannels = retained
                            ) }
                            reloadChannels(retained)
                        }
                    }
                }
        }
    }

    private fun reloadChannels(retained: List<Channel> = emptyList()) {
        val scope = sourceScope ?: return
        val playlistId = activePlaylistId ?: return
        val selection = _state.value
        selectionGeneration++
        visibleEpgJob?.cancel()
        _state.update { it.copy(epgPrograms = emptyMap()) }
        pager.reset(scope, retained) { offset, limit ->
            contentRepository.getChannelsPage(playlistId, selection.selectedGroup, limit, offset, selection.query)
        }
    }

    fun selectGroup(group: String?) {
        if (_state.value.selectedGroup == group) return
        _state.update { it.copy(selectedGroup = group) }
        reloadChannels()
    }

    fun search(query: String) {
        if (_state.value.query == query) return
        _state.update { it.copy(query = query) }
        reloadChannels()
    }

    fun loadMore() = pager.append()
    fun retryPage() = pager.retry()

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch { contentRepository.toggleChannelFavorite(channel.id, !channel.isFavorite) }
    }

    fun loadEpgForVisibleChannels(channels: List<Channel>) {
        visibleEpgJob?.cancel()
        val generation = selectionGeneration
        val requested = channels.take(50)
        visibleEpgJob = sourceScope?.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val programs = epgRepository.getCurrentProgramsForChannels(requested)
                if (generation != selectionGeneration) return@launch
                // Keep just the visible EPG window; scrolling does not grow an unbounded cache.
                _state.update { it.copy(epgPrograms = programs) }
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

}

@Composable
fun LiveTvScreen(
    isTv: Boolean,
    onNavigate: (String) -> Unit,
    onPlayChannel: (String, String, Long, String?) -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var isDrawerExpanded by remember { mutableStateOf(false) }

    if (isTv) {
        IdealPlayerDrawer(
            isExpanded = isDrawerExpanded,
            selectedRoute = Routes.LIVE_TV,
            isTv = true,
            onToggle = { isDrawerExpanded = !isDrawerExpanded },
            onNavigate = onNavigate
        ) {
            TvLiveTvContent(state, viewModel, onPlayChannel)
        }
    } else {
        MobileLiveTvContent(state, viewModel, onPlayChannel, onNavigate)
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun TvLiveTvContent(
    state: LiveTvState,
    viewModel: LiveTvViewModel,
    onPlayChannel: (String, String, Long, String?) -> Unit
) {
    val dimens = LocalIdealPlayerDimens.current
    val allCategoryLabel = stringResource(R.string.category_all)
    val displayCategories = remember(state.groups, allCategoryLabel) {
        listOf(
            TvCategoryRailItem(
                id = tvCategoryLazyKey("live", null),
                title = allCategoryLabel,
                group = null
            )
        ) + state.groups.map { group ->
            TvCategoryRailItem(
                id = tvCategoryLazyKey("live", group),
                title = group,
                group = group
            )
        }
    }
    val initialCategoryFocusRequester = remember { FocusRequester() }
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }
    val query = state.query
    var previewChannelId by rememberSaveable { mutableStateOf<Long?>(null) }
    val searchFocusRequester = remember { FocusRequester() }
    val previewFocusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val activity = context as? Activity
    val inlinePlayerViewModel: PlayerViewModel = hiltViewModel(key = "tv-live-inline-preview")
    var activeInlineChannel by remember { mutableStateOf(inlinePlayerViewModel.session.value.currentChannel) }
    val inlinePlayerState by inlinePlayerViewModel.state.collectAsStateWithLifecycle()
    val inlinePlayerReady by inlinePlayerViewModel.playerReady.collectAsStateWithLifecycle()

    if (activeInlineChannel != null) {
        PlayerLifecycleEffect(inlinePlayerViewModel, activity, isTv = true)
    }

    LaunchedEffect(activeInlineChannel?.id, activeInlineChannel?.streamUrl) {
        val channel = activeInlineChannel ?: return@LaunchedEffect
        inlinePlayerViewModel.init(
            url = channel.streamUrl,
            title = channel.name,
            startPos = 0L,
            contentId = channel.id,
            contentType = "LIVE",
            groupContext = state.selectedGroup ?: channel.groupTitle
        )
    }

    Row(modifier = Modifier.fillMaxSize()) {
        val display = state.channels
        val previewChannel = activeInlineChannel
            ?: display.firstOrNull { it.id == previewChannelId }
            ?: display.firstOrNull()

        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val channelKeys = remember(display) { display.map { it.id } }
        val channelFocus = rememberTvLazyListFocus(listState, channelKeys)
        LaunchedEffect(listState, display, state.canLoadMore, state.isLoadingMore, state.loadFailed) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
                .distinctUntilChanged().collect { indices ->
                    viewModel.loadEpgForVisibleChannels(indices.mapNotNull(display::getOrNull))
                    if ((indices.lastOrNull() ?: -1) >= display.size - 12 && state.canLoadMore && !state.loadFailed) {
                        viewModel.loadMore()
                    }
                }
        }

        // Restore the channel after returning from fullscreen. EPG/page updates must not
        // steal focus from the search field or category rail during normal navigation.
        LaunchedEffect(state.isLoading, channelKeys.isEmpty()) {
            if (!hasRequestedInitialFocus && !state.isLoading) {
                val restored = previewChannelId?.takeIf { it in channelKeys }
                    ?.let { channelFocus.navigator.focusItem(it) } ?: false
                if (!restored) {
                    withFrameNanos { }
                    initialCategoryFocusRequester.requestFocusSafely()
                }
                hasRequestedInitialFocus = true
            }
        }

        TvCategoryPanel {
            itemsIndexed(displayCategories, key = { _, category -> category.id }) { index, category ->
                TvRailCategoryItem(
                    name = category.title,
                    isSelected = state.selectedGroup == category.group,
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(initialCategoryFocusRequester) else Modifier)
                        .focusProperties {
                            // Both rails are lazy and retain scroll state. Default spatial search
                            // selects a visible channel; an explicit index-zero requester can be
                            // detached after the channel list has scrolled.
                            if (display.isEmpty()) right = FocusRequester.Cancel
                            // Prevent wrap-around: block UP on first item, DOWN on last item
                            if (index == 0) up = FocusRequester.Cancel
                            if (index == displayCategories.lastIndex) down = FocusRequester.Cancel
                        },
                    onClick = { viewModel.selectGroup(category.group) }
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 24.dp, top = 32.dp, end = 48.dp, bottom = 48.dp)
        ) {
            Text(
                text = stringResource(R.string.live_tv),
                style = MaterialTheme.typography.headlineLarge,
                color = IdealPlayerColors.TextPrimary
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 420.dp, max = 500.dp)
                        .fillMaxHeight()
                        .clip(A2Shape.large)
                        .background(IdealPlayerColors.Surface)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LiveSearchField(
                        query = query,
                        onQueryChange = viewModel::search,
                        isTv = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester)
                    )
                    if (state.loadFailed && display.isEmpty()) {
                        com.idealplayer.app.ui.catalog.CatalogPageRetry(viewModel::retryPage)
                    } else if (display.isEmpty()) {
                        EmptyScreen(
                            message = stringResource(R.string.no_content),
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().tvRememberedScrollFocus(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 2.dp)
                        ) {
                            itemsIndexed(display, key = { _, channel -> channel.id }) { index, channel ->
                                ChannelListItem(
                                    channel = channel,
                                    isTv = true,
                                    selected = activeInlineChannel?.id == channel.id || previewChannel?.id == channel.id,
                                    epgProgram = state.epgPrograms[channel.id],
                                    modifier = Modifier.tvLazyItemFocus(channelFocus, channel.id)
                                        .focusProperties {
                                            left = FocusRequester.Default
                                            right = previewFocusRequester
                                            if (index == 0) up = searchFocusRequester
                                            if (index == display.lastIndex && !state.canLoadMore && !state.loadFailed) down = FocusRequester.Cancel
                                        }
                                        .onPreviewKeyEvent { keyEvent ->
                                            if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            when (keyEvent.nativeKeyEvent.keyCode) {
                                                AndroidKeyEvent.KEYCODE_PROG_YELLOW,
                                                AndroidKeyEvent.KEYCODE_BOOKMARK -> {
                                                    if (keyEvent.nativeKeyEvent.repeatCount == 0) viewModel.toggleFavorite(channel)
                                                    true
                                                }
                                                AndroidKeyEvent.KEYCODE_PAGE_UP,
                                                AndroidKeyEvent.KEYCODE_CHANNEL_UP,
                                                AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                                                    channelFocus.navigator.moveBy(channel.id, -10)
                                                    true
                                                }
                                                AndroidKeyEvent.KEYCODE_PAGE_DOWN,
                                                AndroidKeyEvent.KEYCODE_CHANNEL_DOWN,
                                                AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                                    channelFocus.navigator.moveBy(channel.id, 10)
                                                    if (index + 10 >= display.lastIndex && state.canLoadMore && !state.loadFailed) {
                                                        viewModel.loadMore()
                                                    }
                                                    true
                                                }
                                                else -> false
                                            }
                                        },
                                    onFocused = { previewChannelId = channel.id },
                                    onClick = {
                                        if (activeInlineChannel?.id == channel.id) {
                                            inlinePlayerViewModel.prepareSeamlessHandoff()
                                            onPlayChannel(
                                                channel.streamUrl,
                                                channel.name,
                                                channel.id,
                                                state.selectedGroup ?: channel.groupTitle
                                            )
                                        } else {
                                            activeInlineChannel = channel
                                        }
                                    },
                                    onFavoriteToggle = { viewModel.toggleFavorite(channel) }
                                )
                            }
                            if (state.loadFailed) {
                                item(key = "live-page-retry") {
                                    com.idealplayer.app.ui.catalog.CatalogPageRetry(viewModel::retryPage)
                                }
                            }
                        }
                    }
                }
                TvChannelPreview(
                    channel = previewChannel,
                    epgProgram = previewChannel?.let { state.epgPrograms[it.id] },
                    isInlinePlaying = activeInlineChannel != null && activeInlineChannel?.id == previewChannel?.id,
                    inlinePlayerViewModel = inlinePlayerViewModel,
                    inlinePlayerState = inlinePlayerState,
                    inlinePlayerReady = inlinePlayerReady,
                    previewFocusRequester = previewFocusRequester,
                    onReturnToChannels = {
                        val target = previewChannelId?.takeIf { it in channelKeys } ?: channelKeys.firstOrNull()
                        if (target != null) channelFocus.navigator.moveTo(target)
                        else initialCategoryFocusRequester.requestFocusSafely()
                    },
                    onExpandFullscreen = {
                        val channel = previewChannel ?: return@TvChannelPreview
                        if (activeInlineChannel?.id == channel.id) {
                            inlinePlayerViewModel.prepareSeamlessHandoff()
                        }
                        onPlayChannel(
                            channel.streamUrl,
                            channel.name,
                            channel.id,
                            state.selectedGroup ?: channel.groupTitle
                        )
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun MobileLiveTvContent(
    state: LiveTvState,
    viewModel: LiveTvViewModel,
    onPlayChannel: (String, String, Long, String?) -> Unit,
    onNavigate: (String) -> Unit
) {
    val dimens = LocalIdealPlayerDimens.current
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    var showCategorySheet by remember { mutableStateOf(false) }
    var recentGroups by remember { mutableStateOf(listOf<String>()) }
    var pinnedGroups by remember { mutableStateOf(listOf<String>()) }
    val query = state.query
    val mobileGroups = state.mobileGroups

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IdealPlayerColors.Background)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            color = IdealPlayerColors.Surface,
            shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = dimens.screenPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.live_tv),
                    style = MaterialTheme.typography.titleLarge,
                    color = IdealPlayerColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onNavigate(Routes.TV_GUIDE) },
                    modifier = Modifier.size(dimens.touchTargetMin)
                ) {
                    Icon(
                        Icons.Filled.DateRange,
                        contentDescription = stringResource(R.string.tv_guide),
                        tint = IdealPlayerColors.TextSecondary
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.screenPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.live_tv),
                style = MaterialTheme.typography.headlineMedium,
                color = IdealPlayerColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { viewModel.selectGroup(null) }) {
                Text(
                    text = stringResource(R.string.all_categories),
                    color = IdealPlayerColors.Primary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (isTablet) {
            LiveSearchField(
                query = query,
                onQueryChange = viewModel::search,
                isTv = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimens.screenPadding)
            )
            Spacer(Modifier.height(8.dp))
        }

        QuickCategoryBar(
            categories = mobileGroups,
            selectedCategory = state.selectedGroup,
            recentCategories = recentGroups,
            onCategorySelected = { group ->
                viewModel.selectGroup(group)
                if (group != null && group !in recentGroups) {
                    recentGroups = (listOf(group) + recentGroups).take(10)
                }
            },
            onBrowseAll = { showCategorySheet = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        val display = state.mobileChannels

        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val channelKeys = remember(display) { display.map { it.id } }
        val channelFocus = rememberTvLazyListFocus(listState, channelKeys)
        LaunchedEffect(listState, display, state.canLoadMore, state.isLoadingMore, state.loadFailed) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
                .distinctUntilChanged().collect { indices ->
                    viewModel.loadEpgForVisibleChannels(indices.mapNotNull(display::getOrNull))
                    if ((indices.lastOrNull() ?: -1) >= display.size - 12 && state.canLoadMore && !state.loadFailed) {
                        viewModel.loadMore()
                    }
                }
        }

        if (state.isLoading && display.isEmpty()) {
            ChannelListLoadingPlaceholder()
        } else if (state.loadFailed && display.isEmpty()) {
            com.idealplayer.app.ui.catalog.CatalogPageRetry(viewModel::retryPage)
        } else if (display.isEmpty()) {
            EmptyScreen(message = stringResource(R.string.no_content))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = dimens.screenPadding, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(display, key = { it.id }) { channel ->
                    ChannelListItem(
                        channel = channel,
                        isTv = false,
                        selected = false,
                        epgProgram = state.epgPrograms[channel.id],
                        onClick = { onPlayChannel(channel.streamUrl, channel.name, channel.id, state.selectedGroup) },
                        onFavoriteToggle = { viewModel.toggleFavorite(channel) }
                    )
                }
                if (state.loadFailed) {
                    item(key = "live-page-retry") {
                        com.idealplayer.app.ui.catalog.CatalogPageRetry(viewModel::retryPage)
                    }
                }
            }
        }
    }

    CategoryBottomSheet(
        isVisible = showCategorySheet,
        categories = mobileGroups,
        categoryCounts = state.groupCounts,
        selectedCategory = state.selectedGroup,
        recentCategories = recentGroups,
        pinnedCategories = pinnedGroups,
        onCategorySelected = { group ->
            viewModel.selectGroup(group)
            if (group != null && group !in recentGroups) {
                recentGroups = (listOf(group) + recentGroups).take(10)
            }
        },
        onDismiss = { showCategorySheet = false },
        onTogglePin = { g ->
            pinnedGroups = if (g in pinnedGroups) pinnedGroups - g else pinnedGroups + g
        }
    )
}

private data class ProcessedLiveTvContent(
    val groups: List<String>,
    val mobileGroups: List<String>,
    val mobileChannels: List<Channel>,
    val groupCounts: Map<String, Int>
)

@Composable
private fun ChannelListLoadingPlaceholder() {
    val dimens = LocalIdealPlayerDimens.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = dimens.screenPadding, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(8) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(IdealPlayerColors.CardBackground)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShimmerBox(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.3f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                ShimmerBox(modifier = Modifier.size(24.dp).clip(CircleShape))
            }
        }
    }
}

@Composable
private fun LiveSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    isTv: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.height(if (isTv) 56.dp else 48.dp),
        singleLine = true,
        textStyle = if (isTv) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
        placeholder = {
            Text(
                text = stringResource(R.string.search_hint),
                color = IdealPlayerColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = IdealPlayerColors.TextSecondary,
                modifier = Modifier.size(if (isTv) 24.dp else 20.dp)
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(if (isTv) 56.dp else 48.dp)
                ) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.action_clear_search),
                        tint = IdealPlayerColors.TextSecondary
                    )
                }
            }
        } else {
            null
        },
        shape = A2Shape.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = IdealPlayerColors.TextPrimary,
            unfocusedTextColor = IdealPlayerColors.TextPrimary,
            cursorColor = IdealPlayerColors.Primary,
            focusedContainerColor = IdealPlayerColors.CardBackground,
            unfocusedContainerColor = IdealPlayerColors.CardBackground,
            focusedBorderColor = IdealPlayerColors.FocusBorder,
            unfocusedBorderColor = IdealPlayerColors.CardBorder
        )
    )
}

@Composable
private fun TvChannelPreview(
    channel: Channel?,
    epgProgram: com.idealplayer.app.data.parser.EpgProgram?,
    modifier: Modifier = Modifier,
    isInlinePlaying: Boolean = false,
    inlinePlayerViewModel: PlayerViewModel? = null,
    inlinePlayerState: com.idealplayer.app.core.player.PlayerState = com.idealplayer.app.core.player.PlayerState(),
    inlinePlayerReady: Boolean = false,
    onExpandFullscreen: () -> Unit = {},
    previewFocusRequester: FocusRequester? = null,
    onReturnToChannels: (() -> Unit)? = null
) {
    if (channel == null) {
        EmptyScreen(
            message = stringResource(R.string.no_content),
            modifier = modifier
        )
        return
    }

    var previewFocused by remember { mutableStateOf(false) }
    val expandLabel = stringResource(R.string.player_expand_fullscreen)
    val playbackActive = isInlinePlaying && (
        inlinePlayerState.playbackState == PlaybackState.PLAYING ||
            inlinePlayerState.playbackState == PlaybackState.PAUSED ||
            inlinePlayerState.playbackState == PlaybackState.BUFFERING
        )

    Surface(
        modifier = modifier,
        shape = A2Shape.large,
        color = IdealPlayerColors.CardBackground,
        border = BorderStroke(1.dp, IdealPlayerColors.CardBorder)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Box(
                modifier = Modifier
                    .then(previewFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .focusProperties {
                        up = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                    }
                    .onPreviewKeyEvent { event ->
                        if (onReturnToChannels != null && event.type == KeyEventType.KeyDown &&
                            event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT
                        ) {
                            onReturnToChannels()
                            true
                        } else false
                    }
                    .onFocusChanged { previewFocused = it.isFocused }
                    .semantics { contentDescription = "${channel.name}, $expandLabel" }
                    .border(
                        if (previewFocused) 3.dp else 0.dp,
                        if (previewFocused) IdealPlayerColors.FocusBorder else Color.Transparent,
                        A2Shape.large
                    )
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(A2Shape.large)
                    .background(
                        if (isInlinePlaying) {
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color.Black, Color.Black)
                            )
                        } else {
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(IdealPlayerColors.SurfaceElevated, IdealPlayerColors.SurfaceSelected)
                            )
                        }
                    )
                    .clickable(onClick = onExpandFullscreen),
                contentAlignment = Alignment.Center
            ) {
                if (isInlinePlaying && inlinePlayerViewModel != null) {
                    PlayerSurfaceHost(
                        modifier = Modifier.fillMaxSize(),
                        playerEngine = inlinePlayerViewModel.playerManager.getEngine(),
                        playerState = inlinePlayerState,
                        surfaceState = PlayerSurfaceHostState(
                            playerReady = inlinePlayerReady,
                            playbackActive = playbackActive,
                            shellMode = PlayerShellMode.TV,
                            playbackProfile = PlaybackProfile.LIVE,
                            frameRateMatchingMode = FrameRateMatchingMode.OFF
                        ),
                        placeholder = {
                            CircularProgressIndicator(color = IdealPlayerColors.Primary)
                        }
                    )
                } else {
                    PosterImage(
                        url = channel.logoUrl,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        fallbackStyle = ArtworkFallbackStyle.Channel,
                        modifier = Modifier.fillMaxSize().padding(72.dp)
                    )
                }
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                    shape = A2Shape.small,
                    color = IdealPlayerColors.Primary
                ) {
                    Text(
                        text = stringResource(R.string.player_content_live),
                        color = IdealPlayerColors.TextOnPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = epgProgram?.title ?: channel.name,
                style = MaterialTheme.typography.headlineMedium,
                color = IdealPlayerColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = listOfNotNull(
                    channel.name,
                    channel.groupTitle.takeIf(String::isNotBlank)
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = IdealPlayerColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            epgProgram?.let { program ->
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { program.progressFraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = IdealPlayerColors.Primary,
                    trackColor = IdealPlayerColors.CardBorder
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun ChannelListItem(
    channel: Channel,
    isTv: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    epgProgram: com.idealplayer.app.data.parser.EpgProgram? = null,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onFocused: () -> Unit = {}
) {
    val interactionSource = remember(channel.id) {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val itemShape = RoundedCornerShape(12.dp)
    LaunchedEffect(isFocused, channel.id) {
        if (isFocused) onFocused()
    }

    val targetBackgroundColor = when {
        isTv && isFocused -> IdealPlayerColors.SurfaceFocus
        isTv && selected -> IdealPlayerColors.SurfaceSelected
        else -> IdealPlayerColors.CardBackground
    }
    val backgroundColor by animateColorAsState(
        targetBackgroundColor,
        tween(A2Motion.FastMillis),
        label = "channelBg"
    )

    val targetContentColor = IdealPlayerColors.TextPrimary
    val contentColor by animateColorAsState(
        targetContentColor,
        tween(A2Motion.FastMillis),
        label = "channelText"
    )

    val targetSecondaryColor = if (isTv) IdealPlayerColors.TextSecondary else IdealPlayerColors.TextTertiary
    val secondaryContentColor by animateColorAsState(
        targetSecondaryColor,
        tween(A2Motion.FastMillis),
        label = "channelSecondaryText"
    )

    val targetScale = if (isTv && isFocused) A2Motion.FocusScale else 1f
    val scale by animateFloatAsState(
        targetScale,
        tween(A2Motion.StandardMillis),
        label = "channelScale"
    )

    val borderWidth = when {
        isTv && isFocused -> 4.dp
        isTv && selected -> 2.dp
        else -> 1.dp
    }
    val borderColor = when {
        isTv && isFocused -> IdealPlayerColors.FocusBorder
        isTv && selected -> IdealPlayerColors.SelectedBorder
        else -> IdealPlayerColors.CardBorder
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (isTv) 96.dp else 72.dp)
            .zIndex(if (isFocused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.shape = itemShape
                clip = false
            }
            .clip(itemShape)
            .background(backgroundColor, itemShape)
            .border(borderWidth, borderColor, itemShape)
            .clickable(
                interactionSource = interactionSource,
                indication = if (isTv) null else androidx.compose.foundation.LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = if (isTv) 16.dp else 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PosterImage(
            url = channel.logoUrl,
            contentDescription = channel.name,
            contentScale = ContentScale.Fit,
            fallbackStyle = ArtworkFallbackStyle.Channel,
            modifier = Modifier
                .size(if (isTv) 60.dp else 44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isTv && isFocused) IdealPlayerColors.Primary.copy(alpha = 0.14f) else IdealPlayerColors.Surface)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(channel.name, style = if (isTv) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                color = contentColor,
                fontWeight = if (isTv) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(
                text = epgProgram?.title ?: stringResource(R.string.epg_no_data),
                style = if (isTv) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
                color = secondaryContentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            epgProgram?.let { program ->
                LinearProgressIndicator(
                    progress = { program.progressFraction },
                    modifier = Modifier.fillMaxWidth().height(2.dp).padding(top = 2.dp),
                    color = IdealPlayerColors.Primary,
                    trackColor = if (isTv && isFocused) IdealPlayerColors.CardBorder else IdealPlayerColors.Surface
                )
            }
        }
        IconButton(
            onClick = onFavoriteToggle,
            modifier = Modifier
                .size(if (isTv) 56.dp else 48.dp)
                .then(if (isTv) Modifier.focusProperties { canFocus = false } else Modifier)
        ) {
            Icon(
                imageVector = if (channel.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = stringResource(
                    if (channel.isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites
                ),
                tint = if (channel.isFavorite) IdealPlayerColors.Primary else if (isTv && isFocused) IdealPlayerColors.TextSecondary else IdealPlayerColors.TextTertiary,
                modifier = Modifier.size(if (isTv) 28.dp else 22.dp)
            )
        }
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = stringResource(R.string.action_play),
            tint = IdealPlayerColors.Primary,
            modifier = Modifier.size(if (isTv) 32.dp else 24.dp)
        )
    }
}

private data class TvCategoryRailItem(
    val id: String,
    val title: String,
    val group: String?
)

private fun FocusRequester.requestFocusSafely() {
    runCatching { requestFocus() }
}

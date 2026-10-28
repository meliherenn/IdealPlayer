package com.idealplayer.app.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.idealplayer.app.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.idealplayer.app.core.designsystem.theme.A2Motion
import com.idealplayer.app.core.designsystem.theme.IdealPlayerColors
import com.idealplayer.app.core.designsystem.theme.LocalIdealPlayerDimens
import com.idealplayer.app.ui.navigation.Routes
import timber.log.Timber
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TV_DRAWER_LOG_TAG = "TvDrawer"
private const val TV_DRAWER_COLLAPSE_DELAY_MS = 180L
private const val TV_DRAWER_FALLBACK_FOCUS_DELAY_MS = 420L

data class DrawerItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
)

val tvDrawerItems @Composable get() = listOf(
    DrawerItem(Routes.HOME, stringResource(R.string.nav_home), Icons.Outlined.Home, Icons.Filled.Home),
    DrawerItem(Routes.SEARCH, stringResource(R.string.nav_search), Icons.Outlined.Search, Icons.Filled.Search),
    DrawerItem(Routes.LIVE_TV, stringResource(R.string.nav_live_tv), Icons.Outlined.LiveTv, Icons.Filled.LiveTv),
    DrawerItem(Routes.TV_GUIDE, stringResource(R.string.tv_guide), Icons.Outlined.DateRange, Icons.Filled.DateRange),
    DrawerItem(Routes.MOVIES, stringResource(R.string.nav_movies), Icons.Outlined.Movie, Icons.Filled.Movie),
    DrawerItem(Routes.SERIES, stringResource(R.string.nav_series), Icons.Outlined.Tv, Icons.Filled.Tv),
    DrawerItem(Routes.CONTINUE_WATCHING, stringResource(R.string.nav_continue_watching), Icons.Filled.History, Icons.Filled.History),
    DrawerItem(Routes.FAVORITES, stringResource(R.string.favorites), Icons.Outlined.FavoriteBorder, Icons.Filled.Favorite),
    DrawerItem(Routes.PLAYLISTS, stringResource(R.string.playlists), Icons.AutoMirrored.Filled.PlaylistPlay, Icons.AutoMirrored.Filled.PlaylistPlay),
    DrawerItem(Routes.SETTINGS, stringResource(R.string.nav_settings), Icons.Outlined.Settings, Icons.Filled.Settings)
)

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// TV-specific side drawer
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun TvDrawerLayout(
    isExpanded: Boolean,
    selectedRoute: String,
    onToggle: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val dimens = LocalIdealPlayerDimens.current
    val items = tvDrawerItems
    val exitLabel = stringResource(R.string.nav_exit_playlist)
    val logoPainter = painterResource(id = R.drawable.idealplayer_logo)
    val exitItem = remember(exitLabel) {
        DrawerItem(
            Routes.EXIT,
            exitLabel,
            Icons.AutoMirrored.Outlined.ExitToApp,
            Icons.AutoMirrored.Filled.ExitToApp
        )
    }
    val menuItems = remember(items, exitItem) {
        items + exitItem
    }
    val menuRoutes = remember(menuItems) { menuItems.map { it.route } }
    val listState = rememberLazyListState()
    val focusMoveScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val contentFocusRequester = remember { FocusRequester() }
    val itemFocusRequesters = remember(menuRoutes) {
        menuRoutes.associateWith { FocusRequester() }
    }
    val firstMenuRoute = menuRoutes.firstOrNull()
    val selectedMenuRoute = selectedRoute.takeIf { it in itemFocusRequesters } ?: firstMenuRoute
    var lastFocusedRoute by rememberSaveable {
        mutableStateOf(selectedMenuRoute ?: "")
    }
    var focusedIndex by rememberSaveable {
        mutableStateOf(menuRoutes.indexOf(selectedMenuRoute).takeIf { it >= 0 } ?: 0)
    }
    var focusedDrawerTarget by remember { mutableStateOf<String?>(null) }
    var remoteMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var drawerExpanded by rememberSaveable { mutableStateOf(isExpanded) }
    var layoutHasFocus by remember { mutableStateOf(false) }
    var hasRequestedFallbackFocus by remember { mutableStateOf(false) }
    var verticalFocusJob by remember { mutableStateOf<Job?>(null) }
    val shouldExpandDrawer = isExpanded || remoteMenuExpanded || focusedDrawerTarget != null
    val drawerWidth = animateDpAsState(
        targetValue = if (drawerExpanded) dimens.drawerWidth else dimens.drawerCollapsedWidth,
        animationSpec = tween(durationMillis = A2Motion.StandardMillis, easing = FastOutSlowInEasing),
        label = "tvDrawerWidth"
    )

    fun cancelPendingDrawerFocus() {
        verticalFocusJob?.cancel()
        verticalFocusJob = null
    }

    fun requestDrawerFocus(route: String?) {
        val targetRoute = route?.takeIf { it in itemFocusRequesters }
            ?: selectedMenuRoute ?: firstMenuRoute ?: return
        val targetIndex = menuRoutes.indexOf(targetRoute)
        val requester = itemFocusRequesters.getValue(targetRoute)
        focusedIndex = targetIndex
        cancelPendingDrawerFocus()
        if (listState.isItemFullyVisible(targetIndex)) {
            requester.requestFocusSafely("TV drawer focus $targetRoute")
            return
        }
        verticalFocusJob = focusMoveScope.launch {
            listState.scrollAndRequestFocusSafely(targetIndex, requester, "TV drawer focus $targetRoute")
        }
    }

    fun moveDrawerFocusBy(delta: Int) {
        if (focusedDrawerTarget == null || menuRoutes.isEmpty()) return
        val targetIndex = (focusedIndex + delta).coerceIn(menuRoutes.indices)
        if (targetIndex != focusedIndex) requestDrawerFocus(menuRoutes[targetIndex])
    }

    fun returnFocusToContent(): Boolean {
        // A queued scroll/focus from Up/Down or Menu must never steal focus back after Right.
        cancelPendingDrawerFocus()
        val moved = runCatching { contentFocusRequester.requestFocus() }.getOrDefault(false)
        if (moved) {
            remoteMenuExpanded = false
            if (isExpanded) onToggle()
        }
        return moved
    }

    fun closeDrawerFromRemote(): Boolean {
        val handled = shouldExpandDrawer || drawerExpanded
        if (focusedDrawerTarget != null) {
            // Keep a usable focus target when the destination is empty, rather than clearing
            // bookkeeping while the actual focus remains in a supposedly closed drawer.
            returnFocusToContent()
        } else {
            cancelPendingDrawerFocus()
            remoteMenuExpanded = false
            if (isExpanded) onToggle()
        }
        return handled
    }

    fun toggleDrawerFromRemote() {
        if (shouldExpandDrawer || drawerExpanded) {
            closeDrawerFromRemote()
        } else {
            remoteMenuExpanded = true
            requestDrawerFocus(lastFocusedRoute.takeIf { it in itemFocusRequesters } ?: selectedMenuRoute)
            onToggle()
        }
    }

    LaunchedEffect(menuRoutes, selectedRoute) {
        val fallbackRoute = selectedRoute.takeIf { it in itemFocusRequesters } ?: firstMenuRoute ?: ""
        if (lastFocusedRoute !in itemFocusRequesters) {
            lastFocusedRoute = fallbackRoute
        }
        if (focusedDrawerTarget == null) {
            lastFocusedRoute = fallbackRoute
        }
        if (focusedIndex !in menuRoutes.indices) {
            focusedIndex = menuRoutes.indexOf(lastFocusedRoute).takeIf { it >= 0 } ?: 0
        }
    }

    LaunchedEffect(isExpanded, selectedMenuRoute, menuRoutes) {
        if (isExpanded) requestDrawerFocus(lastFocusedRoute)
    }

    // Content screens normally establish their own first TV focus target. Loading, empty and
    // error states often have no actionable child, though; without a fallback the app receives
    // no D-pad events at all. Give the selected drawer item focus only after content had a
    // chance to attach, and only once for this shell instance.
    LaunchedEffect(selectedMenuRoute) {
        if (!hasRequestedFallbackFocus) {
            delay(TV_DRAWER_FALLBACK_FOCUS_DELAY_MS)
            if (!layoutHasFocus) {
                selectedMenuRoute?.let { route ->
                    val routeIndex = menuRoutes.indexOf(route)
                    val requester = itemFocusRequesters[route]
                    if (routeIndex >= 0 && requester != null) {
                        listState.scrollAndRequestFocusSafely(
                            index = routeIndex,
                            requester = requester,
                            reason = "TV drawer fallback focus $route"
                        )
                    }
                }
            }
            hasRequestedFallbackFocus = true
        }
    }

    // Content entry focus belongs to the destination. Do not run a timed geometric
    // move here: it can override a deliberate menu selection made just after navigation.

    LaunchedEffect(shouldExpandDrawer) {
        if (shouldExpandDrawer) {
            drawerExpanded = true
        } else {
            delay(TV_DRAWER_COLLAPSE_DELAY_MS)
            if (!shouldExpandDrawer) {
                drawerExpanded = false
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(IdealPlayerColors.Background)
            .onFocusChanged { layoutHasFocus = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }

                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (focusedDrawerTarget != null || remoteMenuExpanded) {
                            returnFocusToContent()
                            true
                        } else {
                            false
                        }
                    }

                    AndroidKeyEvent.KEYCODE_MENU -> {
                        // A held Menu key emits repeat KeyDowns. Toggle once and consume the
                        // repeats so a single press cannot immediately reopen/close the drawer.
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            toggleDrawerFromRemote()
                        }
                        true
                    }

                    AndroidKeyEvent.KEYCODE_BACK -> {
                        // Do not let an auto-repeated Back escape to Activity after the first
                        // event closed the drawer.
                        if (event.nativeKeyEvent.repeatCount > 0) {
                            true
                        } else if (shouldExpandDrawer || drawerExpanded) {
                            closeDrawerFromRemote()
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
    ) {
        // Keep catalog constraints stable while the rail animates over it. Resizing the
        // entire destination per frame remeasures grids, text and the live preview.
        Box(
            modifier = Modifier.fillMaxSize()
                .padding(start = dimens.drawerCollapsedWidth + dimens.drawerGutter)
                .focusRequester(contentFocusRequester)
                .focusRestorer()
                .focusGroup()
        ) {
            content()
        }

        Column(
            modifier = Modifier
                .background(IdealPlayerColors.Surface)
                .tvDrawerAnimatedViewport(
                    animatedWidth = drawerWidth,
                    targetWidth = if (drawerExpanded) dimens.drawerWidth else dimens.drawerCollapsedWidth
                )
                .fillMaxHeight()
                .padding(
                    vertical = if (drawerExpanded) 16.dp else 18.dp,
                    horizontal = if (drawerExpanded) 10.dp else 8.dp
                )
        ) {
            TvDrawerBrand(
                isExpanded = drawerExpanded,
                logoPainter = logoPainter
            )

            Spacer(modifier = Modifier.height(if (drawerExpanded) 16.dp else 12.dp))
            HorizontalDivider(color = IdealPlayerColors.DividerColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(if (drawerExpanded) 10.dp else 12.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusProperties {
                        onEnter = {
                            // The collapsed/expanded rail may recompose lazy children. Restore
                            // by stable route, rather than a disposed focus-node identity or
                            // the geometric row nearest the content's vertical position.
                            val route = lastFocusedRoute.takeIf { it in itemFocusRequesters }
                                ?: selectedMenuRoute ?: menuRoutes.first()
                            if (listState.isItemFullyVisible(menuRoutes.indexOf(route))) {
                                itemFocusRequesters.getValue(route).requestFocus()
                            } else {
                                cancelFocusChange()
                                requestDrawerFocus(route)
                            }
                        }
                    }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                itemsIndexed(
                    items = items,
                    key = { _, item -> item.route }
                ) { index, item ->
                    TvDrawerItemRow(
                        item = item,
                        isSelected = selectedRoute == item.route,
                        isExpanded = drawerExpanded,
                        focusRequester = itemFocusRequesters.getValue(item.route),
                        trapUp = index == 0,
                        trapDown = false,
                        onVerticalMove = ::moveDrawerFocusBy,
                        onFocusChanged = { isFocused ->
                            if (isFocused) {
                                focusedDrawerTarget = item.route
                                lastFocusedRoute = item.route
                                focusedIndex = index
                            } else if (focusedDrawerTarget == item.route) {
                                focusedDrawerTarget = null
                            }
                        },
                        onClick = {
                            if (item.route == selectedRoute) {
                                returnFocusToContent()
                            } else {
                                // The destination owns its first-focus policy. Releasing the old
                                // rail node prevents focus restoration from pinning the new route
                                // under an expanded drawer.
                                cancelPendingDrawerFocus()
                                focusManager.clearFocus(force = true)
                                onNavigate(item.route)
                            }
                        }
                    )
                }

                item(key = Routes.EXIT) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = IdealPlayerColors.DividerColor, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    TvDrawerItemRow(
                        item = exitItem,
                        isSelected = false,
                        isExpanded = drawerExpanded,
                        focusRequester = itemFocusRequesters.getValue(Routes.EXIT),
                        trapUp = items.isEmpty(),
                        trapDown = true,
                        onVerticalMove = ::moveDrawerFocusBy,
                        onFocusChanged = { isFocused ->
                            if (isFocused) {
                                focusedDrawerTarget = Routes.EXIT
                                lastFocusedRoute = Routes.EXIT
                                focusedIndex = items.lastIndex + 1
                            } else if (focusedDrawerTarget == Routes.EXIT) {
                                focusedDrawerTarget = null
                            }
                        },
                        onClick = { onNavigate(Routes.EXIT) }
                    )
                }
            }
        }

    }
}

/** Animate the viewport, not the constraints of every lazy item and its text. */
internal fun Modifier.tvDrawerAnimatedViewport(
    animatedWidth: State<Dp>,
    targetWidth: Dp
): Modifier = clipToBounds().layout { measurable, constraints ->
    val contentWidth = targetWidth.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(minWidth = contentWidth, maxWidth = contentWidth))
    val viewportWidth = animatedWidth.value.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(viewportWidth, placeable.height) { placeable.placeRelative(0, 0) }
}

@Composable
private fun TvDrawerBrand(
    isExpanded: Boolean,
    logoPainter: androidx.compose.ui.graphics.painter.Painter
) {
    val containerColor = animateColorAsState(
        targetValue = if (isExpanded) {
            IdealPlayerColors.SurfaceVariant.copy(alpha = 0.36f)
        } else {
            IdealPlayerColors.SurfaceVariant.copy(alpha = 0.24f)
        },
        animationSpec = tween(180),
        label = "tvDrawerBrandBackground"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .drawBehind { drawRect(containerColor.value) }
            .padding(
                horizontal = if (isExpanded) 14.dp else 10.dp,
                vertical = if (isExpanded) 14.dp else 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (isExpanded) Arrangement.Start else Arrangement.Center
    ) {
        Image(
            painter = logoPainter,
            contentDescription = stringResource(R.string.app_name),
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(if (isExpanded) 62.dp else 36.dp)
        )
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(animationSpec = tween(160)),
            exit = fadeOut(animationSpec = tween(120))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = IdealPlayerColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun FocusRequester.requestFocusSafely(reason: String) {
    runCatching { requestFocus() }
        .onFailure { error ->
            Timber.tag(TV_DRAWER_LOG_TAG).w(
                "Unable to request focus for %s: %s",
                reason,
                error.javaClass.simpleName
            )
        }
}

private fun LazyListState.isItemFullyVisible(index: Int): Boolean {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset >= layoutInfo.viewportStartOffset &&
        item.offset + item.size <= layoutInfo.viewportEndOffset
}

private suspend fun LazyListState.scrollAndRequestFocusSafely(
    index: Int,
    requester: FocusRequester,
    reason: String
) {
    // Do not scroll an already-visible row to the top on every key press. That changes
    // the geometry under the remote and invalidates the list layout unnecessarily.
    val visible = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (isItemFullyVisible(index)) {
        // Already attached: apply focus in this key event, not one frame after the
        // user's next direction. Only off-screen targets need the layout barrier.
        requester.requestFocusSafely(reason)
        return
    }
    when {
        visible == null -> scrollToItem(index)
        visible.offset < layoutInfo.viewportStartOffset ->
            scrollBy((visible.offset - layoutInfo.viewportStartOffset).toFloat())
        visible.offset + visible.size > layoutInfo.viewportEndOffset ->
            scrollBy((visible.offset + visible.size - layoutInfo.viewportEndOffset).toFloat())
    }
    withFrameNanos { }
    requester.requestFocusSafely(reason)
}

@Composable
private fun TvDrawerPlate(
    item: DrawerItem,
    isSelected: Boolean,
    isFocused: Boolean,
    isExpanded: Boolean
) {
    val dimens = LocalIdealPlayerDimens.current
    val startDirection = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    val shape = RoundedCornerShape(14.dp)
    val scale = animateFloatAsState(
        if (isFocused) A2Motion.FocusScale else 1f,
        tween(A2Motion.StandardMillis, easing = FastOutSlowInEasing), label = "drawerPlateScale"
    )
    val background = animateColorAsState(
        when {
            isFocused -> IdealPlayerColors.SurfaceFocus
            isSelected -> IdealPlayerColors.SurfaceSelected
            else -> IdealPlayerColors.CardBackground
        }, tween(A2Motion.StandardMillis, easing = FastOutSlowInEasing), label = "drawerPlateBackground"
    )
    val contentColor by animateColorAsState(
        when {
            isFocused || isSelected -> IdealPlayerColors.TextPrimary
            item.route == Routes.EXIT -> IdealPlayerColors.Error
            else -> IdealPlayerColors.TextSecondary
        }, tween(A2Motion.StandardMillis, easing = FastOutSlowInEasing), label = "drawerPlateContent"
    )
    val iconStart = animateDpAsState(
        if (isExpanded) (if (isSelected) 18.dp else 12.dp)
        else (dimens.drawerCollapsedWidth - 16.dp - 32.dp) / 2,
        tween(A2Motion.StandardMillis, easing = FastOutSlowInEasing), label = "drawerIconPosition"
    )
    val labelAlpha = animateFloatAsState(
        if (isExpanded) 1f else 0f, tween(160), label = "drawerLabelAlpha"
    )
    val labelStart = if (isSelected) 64.dp else 58.dp
    val borderWidth = if (isFocused) 4.dp else if (isSelected) 2.dp else 1.dp
    val borderColor = when {
        isFocused -> IdealPlayerColors.FocusBorder
        isSelected -> IdealPlayerColors.SelectedBorder
        else -> IdealPlayerColors.CardBorder
    }
    // Keep the same icon, text and animation nodes across open/close. Replacing every
    // plate caused a large composition/layout burst at both ends of the width animation.
    Box(Modifier.fillMaxWidth().height(64.dp)
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clip(shape)
        .drawBehind { drawRect(background.value) }
        .border(borderWidth, borderColor, shape)) {
        if (isSelected) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 2.dp)
                .width(4.dp).height(28.dp).clip(RoundedCornerShape(2.dp))
                .background(IdealPlayerColors.Secondary))
        }
        Icon(
            imageVector = if (isSelected) item.selectedIcon else item.icon,
            contentDescription = item.label,
            tint = contentColor,
            modifier = Modifier.align(Alignment.CenterStart).size(32.dp)
                .graphicsLayer { translationX = startDirection * iconStart.value.toPx() }
        )
        Text(
            text = item.label,
            modifier = Modifier.align(Alignment.CenterStart)
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .requiredWidth((dimens.drawerWidth - 20.dp - labelStart).coerceAtLeast(1.dp))
                .graphicsLayer { translationX = startDirection * labelStart.toPx(); alpha = labelAlpha.value }
                .then(if (isExpanded) Modifier else Modifier.clearAndSetSemantics { }),
            style = MaterialTheme.typography.titleMedium,
            color = contentColor,
            fontWeight = if (isFocused || isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun TvDrawerItemRow(
    item: DrawerItem,
    isSelected: Boolean,
    isExpanded: Boolean,
    focusRequester: FocusRequester,
    trapUp: Boolean,
    trapDown: Boolean,
    onVerticalMove: (Int) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }

    // Invisible shell that securely connects DPAD interaction states without enforcing layout shape limitations.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .zIndex(if (isFocused) 1f else 0f)
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .focusProperties {
                left = FocusRequester.Cancel
                // Interior movement must use LazyColumn's beyond-bounds search. An explicit
                // requester for an off-screen neighbor is detached and throws from focusSearch.
                if (trapUp) up = FocusRequester.Cancel
                if (trapDown) down = FocusRequester.Cancel
            }
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        onVerticalMove(-1)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        onVerticalMove(1)
                        true
                    }
                    else -> false
                }
            }
            .testTag("tv_drawer_item_${item.route}")
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                selected = isSelected
            },
        contentAlignment = if (isExpanded) Alignment.CenterStart else Alignment.Center
    ) {
        TvDrawerPlate(
            item = item,
            isSelected = isSelected,
            isFocused = isFocused,
            isExpanded = isExpanded
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Mobile bottom navigation and tablet navigation rail
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

val bottomNavItems @Composable get() = listOf(
    DrawerItem(Routes.HOME, stringResource(R.string.nav_home), Icons.Outlined.Home, Icons.Filled.Home),
    DrawerItem(Routes.LIVE_TV, stringResource(R.string.nav_live_tv), Icons.Outlined.LiveTv, Icons.Filled.LiveTv),
    DrawerItem(Routes.MOVIES, stringResource(R.string.nav_movies), Icons.Outlined.Movie, Icons.Filled.Movie),
    DrawerItem(Routes.SERIES, stringResource(R.string.nav_series), Icons.Outlined.Tv, Icons.Filled.Tv),
    DrawerItem(Routes.FAVORITES, stringResource(R.string.favorites), Icons.Outlined.FavoriteBorder, Icons.Filled.Favorite)
)

val tabletRailItems @Composable get() = listOf(
    DrawerItem(Routes.HOME, stringResource(R.string.nav_home), Icons.Outlined.Home, Icons.Filled.Home),
    DrawerItem(Routes.LIVE_TV, stringResource(R.string.nav_live_tv), Icons.Outlined.LiveTv, Icons.Filled.LiveTv),
    DrawerItem(Routes.MOVIES, stringResource(R.string.nav_movies), Icons.Outlined.Movie, Icons.Filled.Movie),
    DrawerItem(Routes.SERIES, stringResource(R.string.nav_series), Icons.Outlined.Tv, Icons.Filled.Tv),
    DrawerItem(Routes.TV_GUIDE, stringResource(R.string.tv_guide), Icons.Outlined.DateRange, Icons.Filled.DateRange),
    DrawerItem(Routes.SEARCH, stringResource(R.string.nav_search), Icons.Outlined.Search, Icons.Filled.Search),
    DrawerItem(Routes.FAVORITES, stringResource(R.string.favorites), Icons.Outlined.FavoriteBorder, Icons.Filled.Favorite),
    DrawerItem(Routes.SETTINGS, stringResource(R.string.nav_settings), Icons.Outlined.Settings, Icons.Filled.Settings)
)

@Composable
fun MobileScaffoldLayout(
    selectedRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit
) {
    val isTablet = LocalConfiguration.current.smallestScreenWidthDp >= 600

    if (isTablet) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .background(IdealPlayerColors.Background)
        ) {
            TabletNavigationRail(
                selectedRoute = selectedRoute,
                onNavigate = onNavigate
            )
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                content(PaddingValues())
            }
        }
    } else {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = IdealPlayerColors.Background,
            bottomBar = {
                MobileBottomNavigation(
                    selectedRoute = selectedRoute,
                    onNavigate = onNavigate
                )
            }
        ) { paddingValues ->
            content(paddingValues)
        }
    }
}

@Composable
private fun MobileBottomNavigation(
    selectedRoute: String,
    onNavigate: (String) -> Unit
) {
    val barHeight = maxOf(76.dp, (34 + 32 * LocalDensity.current.fontScale).dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(barHeight)
            .background(IdealPlayerColors.SurfaceElevated)
            .border(
                width = 1.dp,
                color = IdealPlayerColors.CardBorder,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        bottomNavItems.forEach { item ->
            val isSelected = selectedRoute == item.route
            val iconColor by animateColorAsState(
                targetValue = if (isSelected) IdealPlayerColors.Secondary else IdealPlayerColors.TextSecondary,
                animationSpec = tween(A2Motion.FastMillis),
                label = "mobileNavIconColor"
            )
            val backgroundColor by animateColorAsState(
                targetValue = if (isSelected) IdealPlayerColors.SurfaceSelected else Color.Transparent,
                animationSpec = tween(A2Motion.FastMillis),
                label = "mobileNavBackground"
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(backgroundColor)
                    .clickable(
                        role = Role.Tab,
                        onClick = { onNavigate(item.route) }
                    )
                    .semantics(mergeDescendants = true) {
                        role = Role.Tab
                        selected = isSelected
                    }
                    .testTag("mobile_bottom_nav_${item.route}"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (isSelected) item.selectedIcon else item.icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.label,
                    color = iconColor,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TabletNavigationRail(
    selectedRoute: String,
    onNavigate: (String) -> Unit
) {
    val items = tabletRailItems
    BoxWithConstraints(
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(IdealPlayerColors.Surface)
            .border(
                width = 1.dp,
                color = IdealPlayerColors.CardBorder,
                shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            )
    ) {
        if (maxHeight < 560.dp) {
            val listState = rememberLazyListState()
            LaunchedEffect(selectedRoute, items) {
                val selectedIndex = items.indexOfFirst { it.route == selectedRoute }
                if (selectedIndex >= 0) {
                    // The logo occupies item zero. Keep the selected destination visible after
                    // split-screen, landscape, or other compact-height configuration changes.
                    listState.scrollToItem(selectedIndex + 1)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(TABLET_COMPACT_RAIL_TAG),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item(key = "logo") {
                    TabletRailLogo(modifier = Modifier.padding(bottom = 4.dp))
                }
                itemsIndexed(
                    items = items,
                    key = { _, item -> item.route }
                ) { _, item ->
                    TabletNavigationItem(
                        item = item,
                        isSelected = selectedRoute == item.route,
                        onClick = { onNavigate(item.route) }
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TabletRailLogo()
                Spacer(modifier = Modifier.height(8.dp))

                items.forEachIndexed { index, item ->
                    if (index == items.lastIndex) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    TabletNavigationItem(
                        item = item,
                        isSelected = selectedRoute == item.route,
                        onClick = { onNavigate(item.route) }
                    )
                    if (index < items.lastIndex - 1) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletRailLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.mipmap.ic_launcher_foreground),
        contentDescription = stringResource(R.string.app_name),
        contentScale = ContentScale.Fit,
        modifier = modifier.size(48.dp)
    )
}

internal const val TABLET_COMPACT_RAIL_TAG = "tablet_compact_navigation_rail"

@Composable
private fun TabletNavigationItem(
    item: DrawerItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) IdealPlayerColors.Secondary else IdealPlayerColors.TextSecondary,
        animationSpec = tween(A2Motion.FastMillis),
        label = "tabletRailContent"
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) IdealPlayerColors.SurfaceSelected else Color.Transparent,
        animationSpec = tween(A2Motion.FastMillis),
        label = "tabletRailBackground"
    )

    Column(
        modifier = Modifier
            .size(width = 64.dp, height = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, IdealPlayerColors.SelectedBorder, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            )
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                selected = isSelected
            }
            .testTag("tablet_rail_${item.route}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (isSelected) item.selectedIcon else item.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = item.label,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Backward-compatible wrapper 
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/**
 * Unified wrapper: TV gets side drawer, Mobile gets bottom navigation.
 * Called by all screens that need the standard shell.
 */
@Composable
fun IdealPlayerDrawer(
    isExpanded: Boolean,
    selectedRoute: String,
    isTv: Boolean,
    onToggle: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (isTv) {
        TvDrawerLayout(
            isExpanded = isExpanded,
            selectedRoute = selectedRoute,
            onToggle = onToggle,
            onNavigate = onNavigate,
            modifier = modifier,
            content = content
        )
    } else {
        // For mobile, this just provides content directly.
        // The BottomNavigation is handled by the Navigation scaffold.
        // Individual screens still call IdealPlayerDrawer but on mobile it's a pass-through.
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(IdealPlayerColors.Background)
        ) {
            content()
        }
    }
}

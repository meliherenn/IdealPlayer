package com.idealplayer.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.idealplayer.app.core.common.DeviceUiMode
import com.idealplayer.app.core.model.ContentType
import com.idealplayer.app.core.model.CatchupWindow
import com.idealplayer.app.ui.navigation.mobile.MobileNavGraph
import com.idealplayer.app.ui.navigation.tv.TvNavGraph

object Routes {
    const val PLAYLISTS = "playlists"
    const val ONBOARDING = PLAYLISTS
    const val HOME = "home"
    const val LIVE_TV = "live_tv"
    const val TV_GUIDE = "tv_guide"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val SEARCH = "search"
    const val CONTINUE_WATCHING = "continue_watching"
    const val FAVORITES = "favorites"
    const val SETTINGS = "settings"
    const val EXIT = "exit"
    const val PLAYER = "player/{contentId}/{contentType}?startPos={startPos}&catchupStart={catchupStart}&catchupEnd={catchupEnd}"
    const val DETAIL = "detail/{contentId}/{contentType}"

    fun player(destination: PlaybackDestination): String =
        "player/${destination.contentId}/${destination.contentType.name}" +
            "?startPos=${destination.startPosition.coerceAtLeast(0L)}" +
            (destination.catchup?.let { "&catchupStart=${it.startTimeMs}&catchupEnd=${it.endTimeMs}" }.orEmpty())

    fun player(contentId: Long, contentType: String, startPos: Long = 0L): String = player(
        PlaybackDestination(
            contentId = contentId,
            contentType = ContentType.valueOf(contentType.uppercase()),
            startPosition = startPos
        )
    )

    fun detail(contentId: Long, contentType: String): String = "detail/$contentId/$contentType"

    val MOBILE_MAIN_TABS = setOf(HOME, LIVE_TV, MOVIES, SERIES, FAVORITES)
    val TABLET_MAIN_TABS = setOf(
        HOME,
        LIVE_TV,
        MOVIES,
        SERIES,
        TV_GUIDE,
        SEARCH,
        FAVORITES,
        SETTINGS
    )
    val TV_TOP_LEVEL_DESTINATIONS = setOf(
        HOME,
        SEARCH,
        LIVE_TV,
        TV_GUIDE,
        MOVIES,
        SERIES,
        CONTINUE_WATCHING,
        FAVORITES,
        PLAYLISTS,
        SETTINGS
    )
}

data class PlaybackDestination(
    val contentId: Long,
    val contentType: ContentType,
    val startPosition: Long = 0L,
    val groupId: Long? = null,
    val catchup: CatchupWindow? = null
) {
    init {
        require(contentId > 0L) { "PlaybackDestination requires a persisted content id" }
        require(startPosition >= 0L) { "PlaybackDestination start position cannot be negative" }
        require(catchup == null || contentType == ContentType.LIVE) { "Catchup requires a stored channel" }
    }
}

@Composable
fun IdealPlayerNavHost(
    deviceUiMode: DeviceUiMode,
    navController: NavHostController = rememberNavController()
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (deviceUiMode.isTv) {
            TvNavGraph(navController = navController)
        } else {
            MobileNavGraph(navController = navController)
        }
    }
}

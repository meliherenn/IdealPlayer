package com.idealplayer.app.data.parser

import com.idealplayer.app.core.database.*

/** Small aggregate fixture used only by URL/optional-section contract tests. */
internal suspend fun XtreamClient.loadContent(
    serverUrl: String, username: String, password: String, playlistId: Long
): XtreamContentResult {
    val channels = mutableListOf<ChannelEntity>()
    val movies = mutableListOf<MovieEntity>()
    val series = mutableListOf<SeriesEntity>()
    val categories = mutableListOf<CategoryEntity>()
    val loaded = loadContentStreaming(serverUrl, username, password, playlistId, object : XtreamStreamingSink {
        override fun onChannels(rows: List<ChannelEntity>) { channels += rows }
        override fun onMovies(rows: List<MovieEntity>) { movies += rows }
        override fun onSeries(rows: List<SeriesEntity>) { series += rows }
        override fun onCategories(rows: List<CategoryEntity>) { categories += rows }
    })
    return XtreamContentResult(channels, movies, series, categories, loaded)
}

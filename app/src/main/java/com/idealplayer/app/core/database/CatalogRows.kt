package com.idealplayer.app.core.database

import com.idealplayer.app.core.model.Channel
import com.idealplayer.app.core.model.Movie

/** UI rows have no credential-bearing URL fields. Playback resolves secrets by ID separately. */

data class ChannelCatalogRow(
    val id: Long,
    val playlistId: Long,
    val streamId: Int,
    val name: String,
    val logoUrl: String,
    val groupTitle: String,
    val epgChannelId: String,
    val isFavorite: Boolean,
    val lastWatched: Long,
    val sortOrder: Int,
    val playbackAvailable: Boolean,
    val supportsCatchup: Boolean
)

fun ChannelCatalogRow.toCatalogModel() = Channel(
    id = id, playlistId = playlistId, streamId = streamId, name = name,
    logoUrl = logoUrl, groupTitle = groupTitle, streamUrl = "",
    epgChannelId = epgChannelId, catchupSource = "",
    isFavorite = isFavorite, lastWatched = lastWatched, sortOrder = sortOrder,
    playbackAvailable = playbackAvailable, supportsCatchup = supportsCatchup
)

data class MovieCatalogRow(
    val id: Long,
    val playlistId: Long,
    val streamId: Int,
    val name: String,
    val posterUrl: String,
    val backdropUrl: String,
    val genre: String,
    val plot: String,
    val cast: String,
    val director: String,
    val releaseDate: String,
    val year: Int,
    val duration: Int,
    val rating: Double,
    val imdbId: String,
    val tmdbId: Int,
    val containerExtension: String,
    val categoryId: Int,
    val categoryName: String,
    val isFavorite: Boolean,
    val lastPosition: Long,
    val lastWatched: Long,
    val totalDuration: Long,
    val addedAt: Long,
    val sourceOrder: Int,
    val playbackAvailable: Boolean
)

fun MovieCatalogRow.toCatalogModel() = Movie(
    id = id, playlistId = playlistId, streamId = streamId, name = name,
    posterUrl = posterUrl, backdropUrl = backdropUrl, streamUrl = "",
    genre = genre, plot = plot, cast = cast, director = director,
    releaseDate = releaseDate, year = year, duration = duration, rating = rating,
    imdbId = imdbId, tmdbId = tmdbId, containerExtension = containerExtension,
    categoryId = categoryId, categoryName = categoryName,
    isFavorite = isFavorite, lastPosition = lastPosition,
    lastWatched = lastWatched, totalDuration = totalDuration,
    addedAt = addedAt, sourceOrder = sourceOrder,
    playbackAvailable = playbackAvailable
)

// Kotlin isBlank includes Unicode spaces; SQLite's one-argument TRIM only handles ASCII.
// Compare text to the empty string rather than LENGTH so embedded NUL remains nonblank.
private const val BLANK_CHARS = "char(9,10,11,12,13,28,29,30,31,32,160,5760,8192,8193,8194,8195,8196,8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288)"
internal const val CHANNEL_CATALOG_COLUMNS = "id, playlistId, streamId, name, logoUrl, groupTitle, epgChannelId, isFavorite, lastWatched, sortOrder, " +
    "(trim(streamUrl, $BLANK_CHARS) != '') AS playbackAvailable, " +
    "(trim(catchupSource, $BLANK_CHARS) != '') AS supportsCatchup"
internal const val MOVIE_CATALOG_COLUMNS = "id, playlistId, streamId, name, posterUrl, backdropUrl, genre, plot, `cast`, director, releaseDate, year, duration, rating, imdbId, tmdbId, containerExtension, categoryId, categoryName, isFavorite, lastPosition, lastWatched, totalDuration, addedAt, sourceOrder, " +
    "(trim(streamUrl, $BLANK_CHARS) != '') AS playbackAvailable"

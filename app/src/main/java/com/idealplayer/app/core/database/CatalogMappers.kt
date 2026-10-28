package com.idealplayer.app.core.database

/** Catalog reads never open the Keystore. Legacy URL slots stay blank in UI models. */
fun ChannelEntity.toCatalogModel() = com.idealplayer.app.core.model.Channel(
    id = id, playlistId = playlistId, streamId = streamId, name = name,
    logoUrl = logoUrl, groupTitle = groupTitle, streamUrl = "",
    epgChannelId = epgChannelId, catchupSource = "",
    isFavorite = isFavorite, lastWatched = lastWatched, sortOrder = sortOrder,
    playbackAvailable = streamUrl.isNotBlank(), supportsCatchup = catchupSource.isNotBlank()
)

fun MovieEntity.toCatalogModel() = com.idealplayer.app.core.model.Movie(
    id = id, playlistId = playlistId, streamId = streamId, name = name,
    posterUrl = posterUrl, backdropUrl = backdropUrl, streamUrl = "",
    genre = genre, plot = plot, cast = cast, director = director,
    releaseDate = releaseDate, year = year, duration = duration, rating = rating,
    imdbId = imdbId, tmdbId = tmdbId, containerExtension = containerExtension,
    categoryId = categoryId, categoryName = categoryName,
    isFavorite = isFavorite, lastPosition = lastPosition,
    lastWatched = lastWatched, totalDuration = totalDuration,
    addedAt = addedAt, sourceOrder = sourceOrder,
    playbackAvailable = streamUrl.isNotBlank()
)
fun EpisodeEntity.toCatalogModel() = copy(streamUrl = "").toModel().copy(playbackAvailable = streamUrl.isNotBlank())
fun WatchHistoryEntity.toCatalogModel() = copy(streamUrl = "").toModel()

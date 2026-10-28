package com.idealplayer.app.data.repository

import com.idealplayer.app.core.database.EpisodeEntity

internal const val EPISODE_REFRESH_TTL_MS = 24 * 60 * 60 * 1000L

internal fun episodesNeedRefresh(lastSuccess: Long, now: Long, force: Boolean): Boolean =
    force || lastSuccess <= 0 || now < lastSuccess || now - lastSuccess >= EPISODE_REFRESH_TTL_MS

/** Provider IDs win; the season/episode fallback preserves IDs from pre-v8 imports. */
internal fun mergeRefreshedEpisodes(
    incoming: List<EpisodeEntity>, existing: List<EpisodeEntity>
): List<EpisodeEntity> {
    val bySource = existing.filter { it.sourceEpisodeId.isNotBlank() }.associateBy { it.sourceEpisodeId }
    val byNumber = existing.associateBy { it.seasonNumber to it.episodeNumber }
    val consumedIds = hashSetOf<Long>()
    return incoming.distinctBy { it.sourceEpisodeId.ifBlank { "${it.seasonNumber}:${it.episodeNumber}" } }.map { item ->
        val old = (bySource[item.sourceEpisodeId]
            ?: byNumber[item.seasonNumber to item.episodeNumber]?.takeIf { it.sourceEpisodeId.isBlank() })
            ?.takeIf { consumedIds.add(it.id) }
        if (old == null) item.copy(id = 0) else item.copy(
            id = old.id, isFavorite = old.isFavorite, lastPosition = old.lastPosition,
            totalDuration = old.totalDuration, lastWatched = old.lastWatched
        )
    }
}

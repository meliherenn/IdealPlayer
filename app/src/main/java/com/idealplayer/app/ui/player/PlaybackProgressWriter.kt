package com.idealplayer.app.ui.player

import com.idealplayer.app.core.datastore.SettingsDataStore
import com.idealplayer.app.core.model.ContentType
import com.idealplayer.app.core.model.WatchHistoryItem
import com.idealplayer.app.data.repository.ContentRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

data class PlaybackProgressSnapshot(
    val contentId: Long,
    val contentType: ContentType,
    val position: Long,
    val duration: Long,
    val confirmed: Boolean
)

/** Serial app-owned writes survive route removal; payloads contain no mutable UI/session state. */
@Singleton
class PlaybackProgressWriter @Inject constructor(
    private val content: ContentRepository,
    private val settings: SettingsDataStore
) {
    private data class Write(val snapshot: PlaybackProgressSnapshot, val result: CompletableDeferred<Boolean>)
    private val writes = Channel<Write>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for ((snapshot, result) in writes) {
                val saved = runCatching { save(snapshot) }.fold(
                    onSuccess = { true },
                    onFailure = {
                        Timber.w("Playback checkpoint failed: %s", it.javaClass.simpleName)
                        false
                    }
                )
                result.complete(saved)
            }
        }
    }

    fun enqueue(snapshot: PlaybackProgressSnapshot): Deferred<Boolean> {
        val result = CompletableDeferred<Boolean>()
        check(writes.trySend(Write(snapshot, result)).isSuccess)
        return result
    }

    private suspend fun save(snapshot: PlaybackProgressSnapshot) {
        if (!snapshot.confirmed || snapshot.contentId <= 0L ||
            !content.canPlay(snapshot.contentId, snapshot.contentType.name)) return
        val preferences = settings.settings.first()
        if (snapshot.contentType == ContentType.LIVE) {
            if (preferences.rememberLastChannel) content.updateChannelLastWatched(snapshot.contentId)
            return
        }
        if (snapshot.position <= 0L) return
        when (snapshot.contentType) {
            ContentType.MOVIE -> {
                val movie = content.getMovie(snapshot.contentId) ?: return
                val duration = snapshot.duration.takeIf { it > 0L } ?: movie.totalDuration
                content.updateMovieProgress(movie.id, snapshot.position, duration)
                if (preferences.continueWatching) content.addWatchHistory(WatchHistoryItem(
                    contentId = movie.id, contentType = ContentType.MOVIE, title = movie.name,
                    posterUrl = movie.posterUrl, streamUrl = movie.streamUrl,
                    position = snapshot.position, totalDuration = duration, progress = progress(snapshot.position, duration)
                ))
            }
            ContentType.SERIES -> {
                val episode = content.getEpisode(snapshot.contentId) ?: return
                val series = content.getSeriesById(episode.seriesId)
                val duration = snapshot.duration.takeIf { it > 0L } ?: episode.totalDuration
                content.updateEpisodeProgress(episode.id, snapshot.position, duration)
                content.updateSeriesLastWatchedEpisode(episode.seriesId, episode.id)
                if (preferences.continueWatching) content.addWatchHistory(WatchHistoryItem(
                    contentId = episode.id, contentType = ContentType.SERIES,
                    title = episode.name.ifBlank { "Episode ${episode.episodeNumber}" },
                    posterUrl = episode.posterUrl.ifBlank { series?.posterUrl.orEmpty() }, streamUrl = episode.streamUrl,
                    position = snapshot.position, totalDuration = duration, progress = progress(snapshot.position, duration),
                    seasonNumber = episode.seasonNumber, episodeNumber = episode.episodeNumber, seriesName = series?.name.orEmpty()
                ))
            }
            ContentType.LIVE -> Unit
        }
    }

    private fun progress(position: Long, duration: Long): Float =
        if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

package com.idealplayer.app.data.repository

import com.idealplayer.app.core.catchup.CatchupUrlResolver
import com.idealplayer.app.core.common.rethrowIfCancellation
import com.idealplayer.app.core.database.*
import com.idealplayer.app.core.model.CatchupWindow
import com.idealplayer.app.core.model.ContentType
import com.idealplayer.app.core.model.PlaylistType
import com.idealplayer.app.core.security.SecretEnvelopeCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Short-lived player input. Never put this object in catalog state, routes or saved state. */
data class ResolvedPlaybackSource(val url: String, val title: String, val groupContext: String = "")

@Singleton
class PlaybackSourceResolver @Inject constructor(
    private val channels: ChannelDao,
    private val movies: MovieDao,
    private val episodes: EpisodeDao,
    private val series: SeriesDao,
    private val playlists: PlaylistDao,
    private val codec: SecretEnvelopeCodec,
    private val access: ContentAccessRepository,
    private val catchup: CatchupUrlResolver
) {
    suspend fun resolve(
        contentId: Long, contentType: ContentType, window: CatchupWindow? = null
    ): ResolvedPlaybackSource? = withContext(Dispatchers.IO) {
        if (contentId <= 0 || (window != null && contentType != ContentType.LIVE)) return@withContext null
        if (!access.allows(contentId, contentType, episode = contentType == ContentType.SERIES)) return@withContext null
        try {
            when (contentType) {
                ContentType.LIVE -> channels.getById(contentId)?.let { entity ->
                    val channel = entity.toModel(codec)
                    val url = if (window == null) channel.streamUrl else {
                        if (window.endTimeMs > System.currentTimeMillis()) return@let null
                        val playlist = playlists.getById(channel.playlistId)?.toModel(codec) ?: return@let null
                        val xtream = playlist.type == PlaylistType.XTREAM_CODES
                        catchup.resolve(channel, window.startTimeMs, window.durationMinutes,
                            serverUrl = playlist.serverUrl.takeIf { xtream }.orEmpty(),
                            username = playlist.username.takeIf { xtream }.orEmpty(),
                            password = playlist.password.takeIf { xtream }.orEmpty()) ?: return@let null
                    }
                    ResolvedPlaybackSource(url, channel.name, channel.groupTitle)
                }
                ContentType.MOVIE -> movies.getById(contentId)?.let {
                    ResolvedPlaybackSource(codec.decryptOrEmpty(it.streamUrl), it.name)
                }
                ContentType.SERIES -> episodes.getById(contentId)?.let {
                    val name = series.getById(it.seriesId)?.name.orEmpty()
                    ResolvedPlaybackSource(codec.decryptOrEmpty(it.streamUrl),
                        "$name S${it.seasonNumber}E${it.episodeNumber} • ${it.name}".trim())
                }
            }?.takeIf { it.url.isNotBlank() }
        } catch (error: Exception) {
            error.rethrowIfCancellation()
            null
        }
    }
}

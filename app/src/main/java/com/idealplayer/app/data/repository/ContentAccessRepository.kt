package com.idealplayer.app.data.repository

import com.idealplayer.app.core.database.ChannelDao
import com.idealplayer.app.core.database.MovieDao
import com.idealplayer.app.core.database.SeriesDao
import com.idealplayer.app.core.database.EpisodeDao
import com.idealplayer.app.core.model.ContentType
import com.idealplayer.app.core.security.ParentalAccessState
import com.idealplayer.app.core.security.ParentalControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Resolve category from stored identity. Caller-provided titles/categories cannot grant access. */
@Singleton
class ContentAccessRepository @Inject constructor(
    private val parental: ParentalControlManager,
    private val channels: ChannelDao,
    private val movies: MovieDao,
    private val series: SeriesDao,
    private val episodes: EpisodeDao,
    database: com.idealplayer.app.core.database.IdealPlayerDatabase
) {
    val policy = parental.state
    // A catalog refresh can move an identity to a different category without editing settings.
    val changes = combine(
        parental.state,
        database.invalidationTracker.createFlow("channels", "movies", "series", "episodes")
    ) { policy, _ -> policy }.flowOn(Dispatchers.IO)

    fun <T> filter(source: Flow<List<T>>, category: (T) -> String): Flow<List<T>> =
        // Source queries already observe their own tables. Unrelated table writes must not
        // re-filter/re-emit every open screen (e.g. episode/EPG work while browsing movies).
        combine(source, policy) { items, policy -> items.filter { policy.allows(category(it)) } }
            .flowOn(Dispatchers.IO)

    suspend fun <T> filter(items: List<T>, category: (T) -> String): List<T> {
        val policy = parental.snapshot()
        return items.filter { policy.allows(category(it)) }
    }

    suspend fun snapshot(): ParentalAccessState = parental.snapshot()

    suspend fun allowsCategory(category: String): Boolean = parental.snapshot().allows(category)

    suspend fun allows(id: Long, type: ContentType, episode: Boolean = false): Boolean =
        allows(parental.snapshot(), id, type, episode)

    suspend fun allows(id: Long, type: String, episode: Boolean = false): Boolean =
        runCatching { ContentType.valueOf(type.trim().uppercase(java.util.Locale.ROOT)) }
            .getOrNull()?.let { allows(id, it, episode) } ?: false

    suspend fun allows(policy: ParentalAccessState, id: Long, type: ContentType, episode: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (!policy.ready) return@withContext false
            if (!policy.enabled) return@withContext true
            val category = when (type) {
                ContentType.LIVE -> channels.getById(id)?.groupTitle
                ContentType.MOVIE -> movies.getById(id)?.categoryName
                ContentType.SERIES -> {
                    val seriesId = if (episode) episodes.getById(id)?.seriesId else id
                    seriesId?.let { series.getById(it)?.categoryName }
                }
            }
            category != null && policy.allows(category)
        }
}

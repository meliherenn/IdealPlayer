package com.idealplayer.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.database.*
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EpisodeRefreshTest {
    private lateinit var f: RepositorySecurityFixture
    private val db get() = f.database
    @Before fun before() = runBlocking<Unit> {
        f = RepositorySecurityFixture()
        db.playlistDao().insert(PlaylistEntity(id = 1, name = "Synthetic", type = "XTREAM_CODES",
            serverUrl = "https://example.test", username = "user", password = "secret"))
        db.seriesDao().insertAll(listOf(SeriesEntity(id = 10, playlistId = 1, seriesId = 42, name = "Series")))
        db.episodeDao().insertAll(listOf(episode("old").copy(id = 20, sourceEpisodeId = "", lastPosition = 456,
            isFavorite = true, totalDuration = 900, lastWatched = 789)))
        Unit
    }
    @After fun after() { f.close() }
    private fun episode(source: String, season: Int = 1) = EpisodeEntity(seriesId = 10, sourceEpisodeId = source,
        seasonNumber = season, episodeNumber = 1, name = "Episode", streamUrl = "https://example.test/$source")
    private suspend fun series() = db.seriesDao().getById(10)!!.toModel()

    @Test fun `fresh cache skips network and force reveals new season while preserving progress and ids`() = runBlocking<Unit> {
        coEvery { f.xtream.loadSeriesEpisodes(any(), any(), any(), any(), any()) } returns listOf(episode("provider-1"))
        assertThat(f.content.syncSeriesEpisodes(series())).isTrue()
        assertThat(f.content.syncSeriesEpisodes(series())).isFalse()
        coVerify(exactly = 1) { f.xtream.loadSeriesEpisodes(any(), any(), any(), any(), any()) }
        // Provider changes numbering: its stable ID still identifies the watched episode.
        coEvery { f.xtream.loadSeriesEpisodes(any(), any(), any(), any(), any()) } returns
            listOf(episode("provider-1").copy(episodeNumber = 2), episode("provider-2", season = 2))
        assertThat(f.content.syncSeriesEpisodes(series(), force = true)).isTrue()
        val rows = db.episodeDao().getBySeriesSnapshot(10)
        assertThat(rows).hasSize(2)
        val watched = rows.single { it.sourceEpisodeId == "provider-1" }
        assertThat(watched.id).isEqualTo(20)
        assertThat(watched.lastPosition).isEqualTo(456)
        assertThat(watched.totalDuration).isEqualTo(900)
        assertThat(watched.lastWatched).isEqualTo(789)
        assertThat(watched.isFavorite).isTrue()
        assertThat(watched.streamUrl).startsWith("enc:v2:")
        assertThat(series().seasonCount).isEqualTo(2)
        assertThat(series().episodesRefreshedAt).isGreaterThan(0)
    }

    @Test fun `failed and stale refreshes leave cache and refresh timestamp unchanged`() = runBlocking<Unit> {
        val before = db.episodeDao().getBySeriesSnapshot(10)
        coEvery { f.xtream.loadSeriesEpisodes(any(), any(), any(), any(), any()) } throws java.io.IOException("synthetic")
        assertThat(runCatching { f.content.syncSeriesEpisodes(series(), force = true) }.isFailure).isTrue()
        assertThat(series().episodesRefreshedAt).isEqualTo(0)
        coEvery { f.xtream.loadSeriesEpisodes(any(), any(), any(), any(), any()) } coAnswers {
            db.playlistDao().update(db.playlistDao().getById(1)!!.copy(sourceRevision = 1))
            listOf(episode("late"))
        }
        assertThat(f.content.syncSeriesEpisodes(series(), force = true)).isFalse()
        assertThat(db.episodeDao().getBySeriesSnapshot(10)).isEqualTo(before)
        assertThat(series().episodesRefreshedAt).isEqualTo(0)
    }

    @Test fun `TTL boundary and clock rollback retry while younger cache remains usable`() {
        val now = 100_000_000L
        assertThat(episodesNeedRefresh(now - EPISODE_REFRESH_TTL_MS + 1, now, false)).isFalse()
        assertThat(episodesNeedRefresh(now - EPISODE_REFRESH_TTL_MS, now, false)).isTrue()
        assertThat(episodesNeedRefresh(now + 1, now, false)).isTrue()
        assertThat(episodesNeedRefresh(now, now, true)).isTrue()
    }
}

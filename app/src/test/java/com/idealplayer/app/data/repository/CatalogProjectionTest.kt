package com.idealplayer.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.database.*
import com.idealplayer.app.ui.catalog.CatalogSortOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CatalogProjectionTest {
    private lateinit var f: RepositorySecurityFixture
    @Before fun setup() { f = RepositorySecurityFixture(false) }
    @After fun close() { f.close() }

    @Test fun `catalog cursor excludes secrets and preserves presentation and playback flags`() = runBlocking<Unit> {
        val channel = ChannelEntity(id = 1, playlistId = 1, streamId = 17, name = "Fixture", logoUrl = "logo",
            groupTitle = "Group", streamUrl = "enc:v2:synthetic", epgChannelId = "guide", catchupSource = "enc:v2:catchup",
            isFavorite = true, lastWatched = 12, sortOrder = 9)
        val movie = MovieEntity(id = 1, playlistId = 1, streamId = 18, name = "Film", posterUrl = "poster",
            backdropUrl = "backdrop", streamUrl = "enc:v2:synthetic", genre = "Drama", plot = "Plot", cast = "Cast",
            director = "Director", releaseDate = "2026-01-01", year = 2026, duration = 95, rating = 8.2,
            imdbId = "fixture", tmdbId = 19, containerExtension = "mkv", categoryId = 2, categoryName = "Group",
            isFavorite = true, lastPosition = 1_200, lastWatched = 13, totalDuration = 95_000, addedAt = 14, sourceOrder = 3)
        f.database.channelDao().insertAll(listOf(channel))
        f.database.movieDao().insertAll(listOf(movie))
        val channelExpected = channel.toModel().copy(streamUrl = "", catchupSource = "", playbackAvailable = true, supportsCatchup = true)
        val movieExpected = movie.toModel().copy(streamUrl = "", playbackAvailable = true)
        assertThat(f.content.getChannelsPage(1, null, 60, 0).items.single()).isEqualTo(channelExpected)
        assertThat(f.content.getMoviesPage(1, null, CatalogSortOption.TITLE, 60, 0).items.single()).isEqualTo(movieExpected)
        assertThat(channel.toCatalogModel()).isEqualTo(channelExpected)
        assertThat(movie.toCatalogModel()).isEqualTo(movieExpected)
        for (table in listOf(CatalogTable.CHANNELS, CatalogTable.MOVIES)) {
            f.database.openHelper.readableDatabase.query(catalogQuery(table, 1, null, null)).use { cursor ->
                assertThat(cursor.columnNames.toList()).contains("playbackAvailable")
                assertThat(cursor.columnNames.toList()).doesNotContain("streamUrl")
                assertThat(cursor.columnNames.toList()).doesNotContain("catchupSource")
            }
        }
        // The write/playback entities still retain the complete secret; catalog reading is not a mutation.
        assertThat(f.database.channelDao().getById(1)!!.streamUrl).isEqualTo(channel.streamUrl)
        assertThat(f.database.movieDao().getById(1)!!.streamUrl).isEqualTo(movie.streamUrl)
    }

    @Test fun `channel paging uses ordered indexes for all channels and selected group`() = runBlocking<Unit> {
        val db = f.database.openHelper.readableDatabase
        for (filter in listOf("playlistId = 1", "playlistId = 1 AND groupTitle = 'Group'")) {
            db.query("EXPLAIN QUERY PLAN SELECT $CHANNEL_CATALOG_COLUMNS FROM channels WHERE $filter ORDER BY sortOrder,id LIMIT 60 OFFSET 5000").use { c ->
                val plan = buildList { while (c.moveToNext()) add(c.getString(3)) }.joinToString()
                assertThat(plan).doesNotContain("TEMP B-TREE")
                assertThat(plan).contains("index_channels_playlistId_")
            }
        }
    }

    @Test fun `SQL availability matches Kotlin blank semantics including Unicode and NUL`() = runBlocking<Unit> {
        val values = listOf("", " ", "\t\n\r", "\u00a0\u2007\u202f\u3000", "\u001c", "\u0000", " x ", "enc:v2:fixture")
        f.database.channelDao().insertAll(values.mapIndexed { index, value ->
            ChannelEntity(id = index + 1L, playlistId = 1, name = "Channel", streamUrl = value, catchupSource = value, sortOrder = index)
        })
        f.database.movieDao().insertAll(values.mapIndexed { index, value ->
            MovieEntity(id = index + 1L, playlistId = 1, name = "Movie", streamUrl = value)
        })
        assertThat(f.content.getChannelsPage(1, null, 60, 0).items.map { it.playbackAvailable })
            .containsExactlyElementsIn(values.map { it.isNotBlank() }).inOrder()
        assertThat(f.content.getChannelsPage(1, null, 60, 0).items.map { it.supportsCatchup })
            .containsExactlyElementsIn(values.map { it.isNotBlank() }).inOrder()
        assertThat(f.content.getMoviesPage(1, null, CatalogSortOption.TITLE, 60, 0).items.map { it.playbackAvailable })
            .containsExactlyElementsIn(values.map { it.isNotBlank() }).inOrder()
    }
}

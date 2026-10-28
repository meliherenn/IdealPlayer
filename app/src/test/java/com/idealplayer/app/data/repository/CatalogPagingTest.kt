package com.idealplayer.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.database.*
import com.idealplayer.app.ui.catalog.CatalogSortOption
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CatalogPagingTest {
    private lateinit var f: RepositorySecurityFixture
    @Before fun before() { f = RepositorySecurityFixture(false) }
    @After fun after() { f.close() }

    @Test fun `permissions filter before limit across all catalog pages and counts`() = runBlocking<Unit> {
        val db = f.database
        val category = "İzinli ' [\u0000] ç"
        db.channelDao().insertAll((1L..181L).map { ChannelEntity(id = it, playlistId = 1, name = "Channel $it",
            groupTitle = if (it % 3L == 0L) category else "Restricted", streamUrl = "", sortOrder = it.toInt()) })
        db.movieDao().insertAll((1L..181L).map { MovieEntity(id = it, playlistId = 1, name = "Movie %03d".format(it),
            categoryName = if (it % 3L == 0L) category else "Restricted", streamUrl = "") })
        db.seriesDao().insertAll((1L..181L).map { SeriesEntity(id = it, playlistId = 1, name = "Series %03d".format(it),
            categoryName = if (it % 3L == 0L) category else "Restricted") })
        f.parental.setPin("1234")
        val grant = f.parental.authorize("1234")
        f.parental.setCategoryAllowed(category, true, grant)
        f.parental.setChildLock(true, grant)
        val expected = (1L..181L).filter { it % 3 == 0L }
        assertThat(f.content.getMoviePreview(1).first().map { it.id }).containsExactlyElementsIn(expected.take(50)).inOrder()
        assertThat(f.content.getSeriesPreview(1).first().map { it.id }).containsExactlyElementsIn(expected.take(50)).inOrder()
        val live = mutableListOf<Long>(); val movies = mutableListOf<Long>(); val series = mutableListOf<Long>()
        for (offset in 0 until 60 step 17) {
            val lp = f.content.getChannelsPage(1, null, 17, offset)
            val mp = f.content.getMoviesPage(1, null, CatalogSortOption.TITLE, 17, offset)
            val sp = f.content.getSeriesPage(1, null, CatalogSortOption.TITLE, 17, offset)
            assertThat(listOf(lp.totalCount, mp.totalCount, sp.totalCount)).containsExactly(60, 60, 60)
            live += lp.items.map { it.id }; movies += mp.items.map { it.id }; series += sp.items.map { it.id }
        }
        assertThat(live).containsExactlyElementsIn(expected).inOrder()
        assertThat(movies).containsExactlyElementsIn(expected).inOrder()
        assertThat(series).containsExactlyElementsIn(expected).inOrder()
        f.parental.setCategoryAllowed(category, false, grant)
        assertThat(f.content.getChannelsPage(1, null, 17, 0).items).isEmpty()
        assertThat(f.content.getMoviesPage(1, null, CatalogSortOption.TITLE, 17, 0).totalCount).isEqualTo(0)
        assertThat(f.content.getMoviePreview(1).first()).isEmpty()
        assertThat(f.content.getSeriesPreview(1).first()).isEmpty()
    }

    @Test fun `search treats SQL wildcard characters literally and leaves later pages reachable`() = runBlocking<Unit> {
        f.database.channelDao().insertAll((1L..125L).map { ChannelEntity(id = it, playlistId = 1,
            name = "100%_Channel $it", streamUrl = "", sortOrder = it.toInt()) })
        val first = f.content.getChannelsPage(1, null, 60, 0, "%_")
        val last = f.content.getChannelsPage(1, null, 60, 120, "%_")
        assertThat(first.totalCount).isEqualTo(125)
        assertThat(last.items.map { it.id }).containsExactly(121L, 122L, 123L, 124L, 125L).inOrder()
        assertThat(f.content.getChannelsPage(1, null, 60, 0, "' OR 1=1 --").totalCount).isEqualTo(0)
    }
}

package com.idealplayer.app.ui.catalog

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.model.Movie
import com.idealplayer.app.core.model.Series
import java.util.Locale
import org.junit.Test

class CatalogSortTest {

    @Test
    fun `movies sort alphabetically without case sensitivity`() {
        val movies = listOf(
            movie(id = 1, name = "zebra"),
            movie(id = 2, name = "Alpha"),
            movie(id = 3, name = "beta")
        )

        assertThat(sortMovies(movies, CatalogSortOption.TITLE, Locale.ENGLISH).map(Movie::name))
            .containsExactly("Alpha", "beta", "zebra")
            .inOrder()
    }

    @Test
    fun `rating sort puts highest finite score first and missing scores last`() {
        val movies = listOf(
            movie(id = 1, name = "Missing", rating = Double.NaN),
            movie(id = 2, name = "Eight", rating = 8.0),
            movie(id = 3, name = "Nine", rating = 9.1),
            movie(id = 4, name = "Zero", rating = 0.0)
        )

        assertThat(sortMovies(movies, CatalogSortOption.RATING, Locale.ENGLISH).map(Movie::name))
            .containsExactly("Nine", "Eight", "Missing", "Zero")
            .inOrder()
    }

    @Test
    fun `recent sort follows server added timestamp then source order`() {
        val series = listOf(
            series(id = 1, name = "Old", addedAt = 10, sourceOrder = 0),
            series(id = 2, name = "Second", addedAt = 20, sourceOrder = 2),
            series(id = 3, name = "First", addedAt = 20, sourceOrder = 1)
        )

        assertThat(sortSeries(series, CatalogSortOption.RECENT, Locale.ENGLISH).map(Series::name))
            .containsExactly("First", "Second", "Old")
            .inOrder()
    }

    private fun movie(
        id: Long,
        name: String,
        rating: Double = 0.0
    ) = Movie(
        id = id,
        playlistId = 1,
        name = name,
        streamUrl = "https://example.invalid/$id.mp4",
        rating = rating
    )

    private fun series(
        id: Long,
        name: String,
        addedAt: Long,
        sourceOrder: Int
    ) = Series(
        id = id,
        playlistId = 1,
        name = name,
        addedAt = addedAt,
        sourceOrder = sourceOrder
    )
}

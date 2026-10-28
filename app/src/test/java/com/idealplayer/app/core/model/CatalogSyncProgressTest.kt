package com.idealplayer.app.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatalogSyncProgressTest {
    @Test
    fun loadingSource_isIndeterminateWhenProviderHasNoTotal() {
        val progress = CatalogSyncProgress(playlistId = 7L, stage = CatalogSyncStage.LOADING_SOURCE)

        assertThat(progress.overallFraction).isNull()
    }

    @Test
    fun persistence_mapsItemProgressIntoOverallRefreshRange() {
        val start = CatalogSyncProgress(
            playlistId = 7L,
            stage = CatalogSyncStage.PERSISTING_CATALOG,
            processedItems = 0,
            totalItems = 100
        )
        val halfway = start.copy(processedItems = 50)
        val complete = start.copy(processedItems = 100)

        assertThat(start.overallFraction).isWithin(0.001f).of(0.35f)
        assertThat(halfway.overallFraction).isWithin(0.001f).of(0.60f)
        assertThat(complete.overallFraction).isWithin(0.001f).of(0.85f)
    }

    @Test
    fun persistence_clampsProcessedItemsToKnownTotal() {
        val progress = CatalogSyncProgress(
            playlistId = 7L,
            stage = CatalogSyncStage.PERSISTING_CATALOG,
            processedItems = 125,
            totalItems = 100
        )

        assertThat(progress.overallFraction).isWithin(0.001f).of(0.85f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun playlistId_mustBePersistedLocalId() {
        CatalogSyncProgress(playlistId = 0L, stage = CatalogSyncStage.PREPARING)
    }
}

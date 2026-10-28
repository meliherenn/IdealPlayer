package com.idealplayer.app.core.worker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpgSyncWorkerTest {

    @Test
    fun `work names contain only the owner playlist id`() {
        assertThat(EpgSyncWorker.workNameFor(42L)).isEqualTo("epg_sync_42")
        assertThat(EpgSyncWorker.workNameFor(42L)).doesNotContain("http")
        assertThat(EpgSyncWorker.workNameFor(42L)).doesNotContain("password")
    }

    @Test
    fun `scheduled sync only runs for the current active configured source`() {
        assertThat(
            shouldRunScheduledEpgSync(
                autoSyncEnabled = true,
                activePlaylistId = 42L,
                ownerPlaylistId = 42L,
                sourceAvailable = true
            )
        ).isTrue()
        assertThat(
            shouldRunScheduledEpgSync(
                autoSyncEnabled = false,
                activePlaylistId = 42L,
                ownerPlaylistId = 42L,
                sourceAvailable = true
            )
        ).isFalse()
        assertThat(
            shouldRunScheduledEpgSync(
                autoSyncEnabled = true,
                activePlaylistId = 42L,
                ownerPlaylistId = 7L,
                sourceAvailable = true
            )
        ).isFalse()
    }

    @Test
    fun `legacy ownerless periodic work is discarded but explicit manual refresh is retained`() {
        assertThat(
            isLegacyOwnerlessScheduledEpgWork(
                isManualRun = false,
                requiresActiveSource = false,
                ownerPlaylistId = EpgSyncWorker.NO_PLAYLIST_ID
            )
        ).isTrue()
        assertThat(
            isLegacyOwnerlessScheduledEpgWork(
                isManualRun = true,
                requiresActiveSource = false,
                ownerPlaylistId = EpgSyncWorker.NO_PLAYLIST_ID
            )
        ).isFalse()
    }
}

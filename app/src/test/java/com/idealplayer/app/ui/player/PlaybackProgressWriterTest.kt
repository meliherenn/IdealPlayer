package com.idealplayer.app.ui.player

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.datastore.AppSettings
import com.idealplayer.app.core.datastore.SettingsDataStore
import com.idealplayer.app.core.model.ContentType
import com.idealplayer.app.core.model.Movie
import com.idealplayer.app.data.repository.ContentRepository
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PlaybackProgressWriterTest {
    private val content = mockk<ContentRepository>(relaxed = true)
    private val settings = mockk<SettingsDataStore> {
        every { settings } returns flowOf(AppSettings(continueWatching = false))
    }

    @Test fun `queued checkpoints retain identity and write in capture order`() = runBlocking {
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        coEvery { content.canPlay(any(), any()) } returns true
        coEvery { content.getMovie(any()) } answers {
            Movie(id = firstArg(), playlistId = 1, name = "Synthetic movie", streamUrl = "https://example.test/movie.mp4")
        }
        coEvery { content.updateMovieProgress(1, 12_000, 60_000) } coAnswers {
            firstWriteStarted.complete(Unit)
            releaseFirstWrite.await()
        }
        val writer = PlaybackProgressWriter(content, settings)
        val first = writer.enqueue(PlaybackProgressSnapshot(1, ContentType.MOVIE, 12_000, 60_000, true))
        firstWriteStarted.await()
        val final = writer.enqueue(PlaybackProgressSnapshot(1, ContentType.MOVIE, 18_000, 60_000, true))
        val next = writer.enqueue(PlaybackProgressSnapshot(2, ContentType.MOVIE, 5_000, 90_000, true))
        assertThat(final.isCompleted).isFalse()
        releaseFirstWrite.complete(Unit)
        assertThat(first.await() && final.await() && next.await()).isTrue()
        coVerifyOrder {
            content.updateMovieProgress(1, 12_000, 60_000)
            content.updateMovieProgress(1, 18_000, 60_000)
            content.updateMovieProgress(2, 5_000, 90_000)
        }
    }

    @Test fun `unconfirmed and denied playback never overwrite progress`() = runBlocking {
        coEvery { content.canPlay(any(), any()) } returns false
        val writer = PlaybackProgressWriter(content, settings)
        writer.enqueue(PlaybackProgressSnapshot(1, ContentType.MOVIE, 12_000, 60_000, false)).await()
        writer.enqueue(PlaybackProgressSnapshot(1, ContentType.MOVIE, 12_000, 60_000, true)).await()
        coVerify(exactly = 0) { content.updateMovieProgress(any(), any(), any()) }
        coVerify(exactly = 0) { content.addWatchHistory(any()) }
    }
}

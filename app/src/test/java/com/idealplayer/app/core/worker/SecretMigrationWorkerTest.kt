package com.idealplayer.app.core.worker

import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.database.*
import com.idealplayer.app.data.repository.RepositorySecurityFixture
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecretMigrationWorkerTest {
    private lateinit var f: RepositorySecurityFixture
    private val version = MutableStateFlow(0)
    private val db get() = f.database
    @Before fun before() {
        f = RepositorySecurityFixture()
        every { f.settings.secretMigrationVersion } returns version
        coEvery { f.settings.migrateLegacySecrets(any()) } coAnswers { version.value = firstArg() }
    }
    @After fun after() { f.close() }
    private fun worker() = SecretMigrationWorker(f.context, mockk<WorkerParameters>(relaxed = true), db,
        db.playlistDao(), db.channelDao(), db.movieDao(), db.episodeDao(), db.watchHistoryDao(), db.favoriteDao(), f.settings, f.codec)

    @Test fun `migration retains v2 envelopes and mixed legacy source fields`() = runBlocking<Unit> {
        val envelope = f.codec.encryptBatch(listOf("https://example.test/secret")).single()
        db.channelDao().insertAll(listOf(ChannelEntity(id = 1, playlistId = 1, name = "Synthetic",
            streamUrl = envelope, catchupSource = "https://example.test/catchup")))
        db.movieDao().insertAll(listOf(MovieEntity(id = 1, playlistId = 1, name = "Synthetic", streamUrl = envelope)))
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(db.channelDao().getById(1)!!.streamUrl).isEqualTo(envelope)
        assertThat(f.codec.decryptOrEmpty(db.channelDao().getById(1)!!.catchupSource)).isEqualTo("https://example.test/catchup")
        assertThat(db.movieDao().getById(1)!!.streamUrl).isEqualTo(envelope)
        assertThat(db.movieDao().getLegacySecretPage(0, 100)).isEmpty()
    }

    @Test fun `migration checkpoints committed pages resumes after failure and never rewrites envelopes`() = runBlocking<Unit> {
        db.playlistDao().insert(PlaylistEntity(id = 1, name = "Source", type = "M3U_URL", url = "https://example.test/secret"))
        db.channelDao().insertAll((1L..101L).map { ChannelEntity(id = it, playlistId = 1, name = "C$it",
            streamUrl = "https://example.test/$it", lastWatched = 123, isFavorite = true) })
        val corruptEnvelope = "enc:v1:unreadable-existing-envelope"
        db.movieDao().insertAll(listOf(MovieEntity(id = 1, playlistId = 1, name = "Retained", streamUrl = corruptEnvelope,
            isFavorite = true, lastPosition = 321)))
        db.epgDao().insertAll(listOf(EpgProgramEntity(playlistId = 1, channelId = "same", title = "Retain", startTime = 1, endTime = 2)))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_page BEFORE INSERT ON channels WHEN NEW.id = 101 BEGIN SELECT RAISE(ABORT, 'synthetic interruption'); END")
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(version.value).isEqualTo(0)
        val firstCheckpoint = db.channelDao().getById(1)!!.streamUrl
        assertThat(firstCheckpoint).startsWith("enc:v1:")
        assertThat(db.channelDao().getById(101)!!.streamUrl).isEqualTo("https://example.test/101")
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_page")
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(version.value).isEqualTo(1)
        assertThat(db.channelDao().getById(1)!!.streamUrl).isEqualTo(firstCheckpoint)
        assertThat(db.channelDao().getById(101)!!.lastWatched).isEqualTo(123)
        assertThat(db.channelDao().getById(101)!!.isFavorite).isTrue()
        assertThat(db.movieDao().getById(1)!!.streamUrl).isEqualTo(corruptEnvelope)
        assertThat(db.movieDao().getById(1)!!.lastPosition).isEqualTo(321)
        assertThat(db.epgDao().getCurrentProgram(1, "same", 1)?.title).isEqualTo("Retain")
        clearMocks(f.codec, answers = false)
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        verify(exactly = 0) { f.codec.encrypt(any()) }
    }

    @Test fun `completed version returns before touching even an unavailable database`() = runBlocking<Unit> {
        version.value = 1
        val legacy = f.context.cacheDir.resolve("image_cache").apply { mkdirs() }
        legacy.resolve("metadata").writeText("Set-Cookie: synthetic-secret")
        val current = f.context.cacheDir.resolve("artwork_cache_v2").apply { mkdirs() }
        current.resolve("image").writeText("retained")
        val database = mockk<IdealPlayerDatabase>()
        val worker = SecretMigrationWorker(f.context, mockk(relaxed = true), database,
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), f.settings, f.codec)
        assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(legacy.exists()).isFalse()
        assertThat(current.resolve("image").readText()).isEqualTo("retained")
        verify { database wasNot Called }
    }
}

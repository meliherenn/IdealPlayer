package com.idealplayer.app.data.repository

import androidx.room.withTransaction
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.database.*
import com.idealplayer.app.core.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EpgOwnershipTest {
    private lateinit var f: RepositorySecurityFixture
    private val db get() = f.database
    private val now = System.currentTimeMillis()
    @Before fun before() = runBlocking<Unit> {
        f = RepositorySecurityFixture()
        db.playlistDao().insert(PlaylistEntity(id = 1, name = "A", type = "M3U_URL", isActive = true))
        db.playlistDao().insert(PlaylistEntity(id = 2, name = "B", type = "M3U_URL"))
        db.channelDao().insertAll((1L..2L).map { ChannelEntity(id = it, playlistId = it,
            name = "Same display name", epgChannelId = "same-id", streamUrl = "https://example.test/$it") })
        Unit
    }
    @After fun after() { f.close() }
    private fun program(title: String) = EpgProgramEntity(channelId = "same-id", title = title,
        startTime = now - 60_000, endTime = now + 60_000)
    private suspend fun activate(id: Long) = db.withTransaction { db.playlistDao().deactivateAll(); db.playlistDao().activate(id) }

    @Test fun `identical tvg ids stay isolated offline across activation and deletion`() = runBlocking<Unit> {
        val epg = f.epg()
        assertThat(epg.savePrograms(listOf(program("A programme")), epg.captureOwner(1)!!)).isEqualTo(EpgSyncResult.Success(1))
        activate(2)
        epg.savePrograms(listOf(program("B programme")), epg.captureOwner(2)!!)
        val channels = (1L..2L).map { db.channelDao().getById(it)!!.toCatalogModel() }
        assertThat(epg.getCurrentProgramsForChannels(channels).mapValues { it.value.title })
            .containsExactly(1L, "A programme", 2L, "B programme")
        activate(1)
        assertThat(epg.getCurrentProgram(channels[0])?.title).isEqualTo("A programme")
        db.playlistDao().delete(db.playlistDao().getById(1)!!)
        assertThat(db.epgDao().getCurrentProgramsForIds(1, listOf("same-id"), now)).isEmpty()
        assertThat(db.epgDao().getSyncState(1)).isNull()
        assertThat(epg.getCurrentProgram(channels[1])?.title).isEqualTo("B programme")
    }

    @Test fun `A to B to A source edits and remove readd reject captured commits`() = runBlocking<Unit> {
        val epg = f.epg()
        val original = epg.captureOwner(1)!!
        activate(2); activate(1)
        assertThat(epg.savePrograms(listOf(program("stale activation")), original)).isEqualTo(EpgSyncResult.StaleSource)
        val beforeEdit = epg.captureOwner(1)!!
        val playlists = f.playlists()
        playlists.updatePlaylist(playlists.getPlaylistById(1)!!.copy(url = "https://example.test/new"))
        assertThat(epg.savePrograms(listOf(program("stale source")), beforeEdit)).isEqualTo(EpgSyncResult.StaleSource)
        val removed = epg.captureOwner(1)!!
        db.playlistDao().delete(db.playlistDao().getById(1)!!)
        val replacementId = db.playlistDao().insert(PlaylistEntity(name = "A", type = "M3U_URL", isActive = true))
        assertThat(replacementId).isGreaterThan(2)
        assertThat(epg.savePrograms(listOf(program("removed source")), removed)).isEqualTo(EpgSyncResult.StaleSource)
    }

    @Test fun `malformed feed records safe failure and keeps last successful rows and timestamp`() = runBlocking<Unit> {
        val epg = f.epg(client("<html>secret-token</html>"))
        epg.savePrograms(listOf(program("cached")), epg.captureOwner(1)!!)
        val previous = db.epgDao().getSyncState(1)!!.lastSuccessAt
        assertThat(epg.fetchAndSave("https://example.test/secret.xml", expectedActivePlaylistId = 1))
            .isEqualTo(EpgSyncResult.Failure(EpgSyncFailure.INVALID_DOCUMENT))
        assertThat(db.epgDao().getSyncState(1)!!.lastSuccessAt).isEqualTo(previous)
        assertThat(db.epgDao().getSyncState(1)!!.failure).isEqualTo("INVALID_DOCUMENT")
        assertThat(db.epgDao().getCurrentProgramsForIds(1, listOf("same-id"), now).single().title).isEqualTo("cached")
        assertThat(f.epg(client("<tv/>" )).fetchAndSave("https://example.test/empty.xml", expectedActivePlaylistId = 1))
            .isEqualTo(EpgSyncResult.Success(0))
        assertThat(db.epgDao().getSyncState(1)!!.failure).isNull()
    }

    @Test fun `late chunk storage failure rolls back prior inserts and retains success time`() = runBlocking<Unit> {
        val epg = f.epg()
        val owner = epg.captureOwner(1)!!
        epg.savePrograms(listOf(program("cached")), owner)
        val previous = db.epgDao().getSyncState(1)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_bad_epg BEFORE INSERT ON epg_programs WHEN NEW.title = 'reject' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        val rows = (1..501).map { program(if (it == 501) "reject" else "new").copy(channelId = "new-$it") }
        assertThat(runCatching { epg.savePrograms(rows, owner) }.isFailure).isTrue()
        assertThat(db.epgDao().getCurrentProgramsForIds(1, (1..501).map { "new-$it" }, now)).isEmpty()
        assertThat(db.epgDao().getSyncState(1)).isEqualTo(previous)
    }

    @Test fun `cancelled network response cannot publish a partial guide or success`() = runBlocking<Unit> {
        val reached = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val epg = f.epg(client("<tv/>") { reached.complete(Unit); release.await() })
        val job = launch { epg.fetchAndSave("https://example.test/epg.xml", expectedActivePlaylistId = 1) }
        reached.await()
        job.cancel(); release.countDown(); job.join()
        assertThat(job.isCancelled).isTrue()
        assertThat(db.epgDao().getSyncState(1)).isNull()
    }

    @Test(timeout = 20_000) fun `slow network does not hold Room writer and activation invalidates staged feed`() = runBlocking<Unit> {
        val reached = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val epg = f.epg(client("<tv/>") { reached.complete(Unit); release.await() })
        val pending = async(kotlinx.coroutines.Dispatchers.IO) {
            epg.fetchAndSave("https://example.test/epg.xml", expectedActivePlaylistId = 1)
        }
        try {
            reached.await()
            kotlinx.coroutines.withTimeout(3_000) { activate(2) }
        } finally {
            release.countDown()
        }
        assertThat(pending.await()).isEqualTo(EpgSyncResult.StaleSource)
        assertThat(db.epgDao().getSyncState(1)).isNull()
    }

    @Test(timeout = 20_000) fun `staged publish failure rolls back batches and removes temporary files`() = runBlocking<Unit> {
        val before = db.epgDao().getSyncState(1)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_bad_epg BEFORE INSERT ON epg_programs WHEN NEW.title = 'reject' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        val xml = buildString {
            append("<tv>")
            repeat(501) { index ->
                append("<programme channel=\"epg-$index\" start=\"20260915010000 +0000\" stop=\"20260915020000 +0000\"><title>")
                append(if (index == 500) "reject" else "entry")
                append("</title></programme>")
            }
            append("</tv>")
        }
        assertThat(f.epg(client(xml)).fetchAndSave("https://example.test/epg.xml", expectedActivePlaylistId = 1))
            .isEqualTo(EpgSyncResult.Failure(EpgSyncFailure.STORAGE))
        assertThat(db.epgDao().getProgramsInWindowForIds(1, (0..500).map { "epg-$it" }, 0, Long.MAX_VALUE)).isEmpty()
        assertThat(db.epgDao().getSyncState(1)?.lastSuccessAt ?: 0L).isEqualTo(before?.lastSuccessAt ?: 0L)
        assertThat(java.io.File(f.context.cacheDir, "epg-staging").listFiles().orEmpty().toList()).isEmpty()
    }

    private fun client(xml: String, beforeResponse: () -> Unit = {}) = OkHttpClient.Builder().addInterceptor { chain ->
        beforeResponse()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
            .body(xml.toResponseBody()).build()
    }.build()

    @Test(timeout = 20_000) fun `streaming network batches share the Room transaction and roll back on late failure`() = runBlocking<Unit> {
        val xml = buildString {
            append("<tv>")
            repeat(501) { index ->
                append("<programme channel=\"epg-$index\" start=\"20260915010000 +0000\" stop=\"20260915020000 +0000\"><title>entry-$index</title></programme>")
            }
            append("</tv>")
        }
        val epg = f.epg(client(xml))
        assertThat(epg.fetchAndSave("https://example.test/epg.xml", expectedActivePlaylistId = 1))
            .isEqualTo(EpgSyncResult.Success(501))
        val ids = (0..500).map { "epg-$it" }
        val before = db.epgDao().getProgramsInWindowForIds(1, ids, 0, Long.MAX_VALUE)
        assertThat(before).hasSize(501)
        val invalid = xml.replace("entry-", "replacement-").removeSuffix("</tv>")
        assertThat(f.epg(client(invalid)).fetchAndSave("https://example.test/epg.xml", expectedActivePlaylistId = 1))
            .isInstanceOf(EpgSyncResult.Failure::class.java)
        assertThat(db.epgDao().getProgramsInWindowForIds(1, ids, 0, Long.MAX_VALUE)).isEqualTo(before)
    }
}

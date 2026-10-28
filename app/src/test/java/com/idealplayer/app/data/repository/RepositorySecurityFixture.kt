package com.idealplayer.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.idealplayer.app.core.database.IdealPlayerDatabase
import com.idealplayer.app.core.datastore.AppSettings
import com.idealplayer.app.core.datastore.SettingsDataStore
import com.idealplayer.app.core.security.ParentalControlManager
import com.idealplayer.app.core.security.SecretEnvelopeCodec
import com.idealplayer.app.core.security.SecretKeyProvider
import com.idealplayer.app.data.parser.M3uParser
import io.mockk.every
import io.mockk.spyk
import com.idealplayer.app.data.parser.XtreamClient
import com.idealplayer.app.data.parser.XmltvParser
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient

internal class RepositorySecurityFixture(recordSecrets: Boolean = true) : AutoCloseable {
    val context: Context = ApplicationProvider.getApplicationContext()
    val directory: File = Files.createTempDirectory("idealplayer-security-").toFile()
    private val fileContext = mockk<Context> { every { filesDir } returns directory }
    val database = Room.inMemoryDatabaseBuilder(context, IdealPlayerDatabase::class.java).allowMainThreadQueries().build()
    val codec = SecretEnvelopeCodec(SecretKeyProvider { SecretKeySpec(ByteArray(16) { 7 }, "AES") })
        .let { if (recordSecrets) spyk(it) else it }
    val files = OwnedPlaylistFileStore(fileContext, codec)
    val parser = M3uParser()
    val settings = mockk<SettingsDataStore>(relaxed = true) { every { settings } returns flowOf(AppSettings()) }
    val preferences = context.getSharedPreferences(directory.name, Context.MODE_PRIVATE)
    val parental = ParentalControlManager({ preferences })
    val access = ContentAccessRepository(parental, database.channelDao(), database.movieDao(), database.seriesDao(), database.episodeDao(), database)
    val xtream = mockk<XtreamClient>(relaxed = true)
    val content = ContentRepository(database.channelDao(), database.movieDao(), database.seriesDao(), database.episodeDao(),
        database.watchHistoryDao(), database.favoriteDao(), database.playlistDao(), xtream, mockk(relaxed = true), codec, access, database)

    fun epg(client: OkHttpClient = OkHttpClient()) = EpgRepository(database, database.epgDao(), database.channelDao(),
        database.playlistDao(), XmltvParser(), client, codec, context)

    fun resolver() = PlaybackSourceResolver(database.channelDao(), database.movieDao(), database.episodeDao(),
        database.seriesDao(), database.playlistDao(), codec, access, com.idealplayer.app.core.catchup.CatchupUrlResolver())

    fun playlists(client: OkHttpClient = OkHttpClient()) = PlaylistRepository(
        database.playlistDao(), database.channelDao(), database.movieDao(), database.seriesDao(), database.episodeDao(),
        database.categoryDao(), database, parser, xtream, mockk(relaxed = true), client, settings, codec, files, context
    )

    override fun close() {
        database.close()
        preferences.edit().clear().commit()
        directory.deleteRecursively()
    }
}

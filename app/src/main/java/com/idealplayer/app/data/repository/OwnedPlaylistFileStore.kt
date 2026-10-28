package com.idealplayer.app.data.repository

import android.content.Context
import com.idealplayer.app.core.common.limitedTo
import com.idealplayer.app.core.connected.CONNECTED_SETUP_MAX_PLAYLIST_BYTES
import com.idealplayer.app.core.security.SecretDecodeResult
import com.idealplayer.app.core.security.SecretEnvelopeCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Private source files have an opaque identity independent of the user-supplied filename. */
@Singleton
class OwnedPlaylistFileStore @Inject constructor(
    @ApplicationContext context: Context,
    private val codec: SecretEnvelopeCodec
) {
    private val directory = File(context.filesDir, "playlists/owned-v1")

    fun create(content: String): String {
        require(!codec.isEncrypted(content.trimStart('\uFEFF'))) {
            "Playlist content must not be supplied as an encrypted envelope"
        }
        require(content.toByteArray(Charsets.UTF_8).size <= CONNECTED_SETUP_MAX_PLAYLIST_BYTES)
        check(directory.mkdirs() || directory.isDirectory) { "Playlist storage unavailable" }
        val target = File(directory, "${UUID.randomUUID()}.enc")
        val temporary = File(directory, "${target.name}.partial")
        try {
            val encrypted = codec.encrypt(content)
            check(encrypted.startsWith("enc:v1:")) { "Playlist source encryption unavailable" }
            temporary.outputStream().use { output ->
                output.write(encrypted.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "Playlist file could not be committed" }
            return target.absolutePath
        } finally {
            temporary.delete()
        }
    }

    fun owns(location: String): Boolean {
        if (!location.endsWith(".enc")) return false
        val file = File(location)
        return runCatching {
            file.name.matches(Regex("[0-9a-f-]{36}\\.enc")) &&
                file.absoluteFile == file.canonicalFile && file.parentFile?.canonicalFile == directory.canonicalFile
        }.getOrDefault(false)
    }

    fun open(location: String): InputStream {
        require(owns(location)) { "Not an app-owned playlist" }
        val encoded = File(location).inputStream().limitedTo(MAX_ENVELOPE_BYTES).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        val decoded = codec.decode(encoded)
        check(decoded is SecretDecodeResult.Available && decoded.wasEncrypted) {
            "Playlist source unavailable; import it again"
        }
        return decoded.value.byteInputStream()
    }

    fun delete(location: String) {
        if (owns(location)) File(location).delete()
    }

    private companion object {
        const val MAX_ENVELOPE_BYTES = CONNECTED_SETUP_MAX_PLAYLIST_BYTES * 2L
    }
}

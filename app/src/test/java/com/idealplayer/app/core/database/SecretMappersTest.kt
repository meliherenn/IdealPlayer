package com.idealplayer.app.core.database

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.model.Playlist
import com.idealplayer.app.core.model.PlaylistType
import com.idealplayer.app.core.security.SecretEnvelopeCodec
import com.idealplayer.app.core.security.SecretKeyProvider
import javax.crypto.spec.SecretKeySpec
import org.junit.Test

class SecretMappersTest {
    private val codec = SecretEnvelopeCodec(
        SecretKeyProvider { SecretKeySpec(ByteArray(32) { 3 }, "AES") }
    )

    @Test
    fun `playlist persistence encrypts provider fields and restores the domain model`() {
        val playlist = Playlist(
            id = 1L,
            name = "Synthetic",
            type = PlaylistType.XTREAM_CODES,
            serverUrl = "https://provider.example.test",
            username = "user",
            password = "secret",
            epgUrl = "https://provider.example.test/epg.xml"
        )

        val entity = playlist.toSecureEntity(codec)

        assertThat(entity.serverUrl).startsWith(SecretEnvelopeCodec.PREFIX)
        assertThat(entity.username).startsWith(SecretEnvelopeCodec.PREFIX)
        assertThat(entity.password).startsWith(SecretEnvelopeCodec.PREFIX)
        assertThat(entity.toString()).doesNotContain("provider.example.test")
        assertThat(entity.toModel(codec)).isEqualTo(playlist)
    }

    @Test
    fun `stream source fields use the same backwards compatible envelope`() {
        val legacy = ChannelEntity(
            id = 4L,
            playlistId = 1L,
            name = "Synthetic",
            streamUrl = "https://stream.example.test/live.ts",
            catchupSource = "https://stream.example.test/archive"
        )
        val encrypted = legacy.encryptSecrets(codec)

        assertThat(encrypted.streamUrl).startsWith(SecretEnvelopeCodec.PREFIX)
        assertThat(encrypted.catchupSource).startsWith(SecretEnvelopeCodec.PREFIX)
        assertThat(encrypted.toModel(codec).streamUrl).isEqualTo(legacy.streamUrl)
    }
}

package com.idealplayer.app.core.security

import com.google.common.truth.Truth.assertThat
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Test

class SecretEnvelopeCodecTest {
    private val key = SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
    private val codec = SecretEnvelopeCodec(SecretKeyProvider { key })

    @Test fun `batch performs one key wrap and survives a new codec instance`() {
        var writes = 0
        val writer = SecretEnvelopeCodec(SecretKeyProvider { writes++; key })
        val originals = List(500) { "https://example.test/synthetic/$it" }
        val envelopes = writer.encryptBatch(originals)
        assertThat(writes).isEqualTo(1)
        assertThat(envelopes.distinct()).hasSize(500)
        assertThat(envelopes.all { it.startsWith(SecretEnvelopeCodec.BATCH_PREFIX) }).isTrue()
        var reads = 0
        val reader = SecretEnvelopeCodec(SecretKeyProvider { reads++; key })
        assertThat(envelopes.map(reader::decryptOrEmpty)).containsExactlyElementsIn(originals).inOrder()
        assertThat(reads).isEqualTo(1)
        assertThat(writer.encryptBatch(envelopes)).isEqualTo(envelopes)
        assertThat(writer.encryptBatch(listOf("", " "))).containsExactly("", " ")
    }

    @Test fun `batch authenticates wrapped key payload and version even after key is cached`() {
        val encrypted = codec.encryptBatch(listOf("synthetic-secret", "synthetic-secret"))
        assertThat(encrypted[0]).isNotEqualTo(encrypted[1])
        val fields = encrypted[0].removePrefix(SecretEnvelopeCodec.BATCH_PREFIX).split(':')
        for (index in fields.indices) {
            val tampered = fields.toMutableList()
            tampered[index] = (if (tampered[index][0] == 'A') "B" else "A") + tampered[index].drop(1)
            assertThat(codec.decode(SecretEnvelopeCodec.BATCH_PREFIX + tampered.joinToString(":")))
                .isEqualTo(SecretDecodeResult.CorruptEnvelope)
        }
        assertThat(codec.decode(encrypted[0].replace("enc:v2:", "enc:v1:")))
            .isEqualTo(SecretDecodeResult.CorruptEnvelope)
        val other = SecretEnvelopeCodec(SecretKeyProvider { SecretKeySpec(ByteArray(32) { 9 }, "AES") })
        assertThat(other.requiresReentry(encrypted[0])).isTrue()
        assertThat(codec.decode("enc:v2:broken")).isEqualTo(SecretDecodeResult.CorruptEnvelope)
    }

    @Test fun `batch key cache is bounded and evicted keys can be recovered from keystore`() {
        val batches = List(10) { codec.encryptBatch(listOf("synthetic-$it")).single() }
        var reads = 0
        val reader = SecretEnvelopeCodec(SecretKeyProvider { reads++; key })
        batches.forEachIndexed { index, value -> assertThat(reader.decryptOrEmpty(value)).isEqualTo("synthetic-$index") }
        assertThat(reader.decryptOrEmpty(batches.first())).isEqualTo("synthetic-0")
        assertThat(reads).isEqualTo(11)
        assertThat(runCatching { codec.encryptBatch(List(1_001) { "secret" }) }.isFailure).isTrue()
    }

    @Test
    fun `temporary import keys are independent and never interchangeable with permanent keys`() {
        val firstImport = SecretEnvelopeCodec.forTransientStorage()
        val secondImport = SecretEnvelopeCodec.forTransientStorage()
        val encrypted = firstImport.encrypt("synthetic-temporary-secret")

        assertThat(firstImport.decryptOrEmpty(encrypted)).isEqualTo("synthetic-temporary-secret")
        assertThat(secondImport.decode(encrypted)).isEqualTo(SecretDecodeResult.CorruptEnvelope)
        assertThat(codec.decode(encrypted)).isEqualTo(SecretDecodeResult.CorruptEnvelope)
    }

    @Test
    fun `aes gcm envelope round trips and uses a random iv`() {
        val secret = "https://provider.example.test/live?username=user&password=secret"
        val first = codec.encrypt(secret)
        val second = codec.encrypt(secret)

        assertThat(first).startsWith(SecretEnvelopeCodec.PREFIX)
        assertThat(first).isNotEqualTo(second)
        assertThat(first).doesNotContain("provider")
        assertThat(codec.decode(first))
            .isEqualTo(SecretDecodeResult.Available(secret, wasEncrypted = true))
        assertThat(codec.decryptOrEmpty(second)).isEqualTo(secret)
    }

    @Test
    fun `plaintext is accepted during migration and new encryption is idempotent`() {
        val plaintext = "legacy-value"
        val encrypted = codec.encrypt(plaintext)

        assertThat(codec.decode(plaintext))
            .isEqualTo(SecretDecodeResult.Available(plaintext, wasEncrypted = false))
        assertThat(codec.encrypt(encrypted)).isEqualTo(encrypted)
    }

    @Test
    fun `corrupt or undecryptable envelope becomes reentry state instead of plaintext`() {
        val otherCodec = SecretEnvelopeCodec(
            SecretKeyProvider { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
        )
        val encrypted = codec.encrypt("secret")

        assertThat(codec.decode("enc:v1:not-base64:broken"))
            .isEqualTo(SecretDecodeResult.CorruptEnvelope)
        assertThat(otherCodec.decryptOrEmpty(encrypted)).isEmpty()
        assertThat(otherCodec.requiresReentry(encrypted)).isTrue()
    }

    @Test
    fun `invalid encryption key is replaced when user writes new credentials`() {
        val replacementKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        var resetCount = 0
        val provider = object : ResettableSecretKeyProvider {
            private var currentKey: SecretKey = SecretKeySpec(ByteArray(1), "AES")

            override fun getOrCreate(): SecretKey = currentKey

            override fun reset(): SecretKey {
                resetCount += 1
                currentKey = replacementKey
                return currentKey
            }
        }
        val recoveringCodec = SecretEnvelopeCodec(provider)

        val encrypted = recoveringCodec.encrypt("new-secret")

        assertThat(resetCount).isEqualTo(1)
        assertThat(recoveringCodec.decode(encrypted))
            .isEqualTo(SecretDecodeResult.Available("new-secret", wasEncrypted = true))
    }
}

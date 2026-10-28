package com.idealplayer.app.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SecretDecodeResult {
    data class Available(val value: String, val wasEncrypted: Boolean) : SecretDecodeResult
    data object KeyUnavailable : SecretDecodeResult
    data object CorruptEnvelope : SecretDecodeResult
}

internal fun interface SecretKeyProvider {
    fun getOrCreate(): SecretKey
}

internal interface ResettableSecretKeyProvider : SecretKeyProvider {
    fun reset(): SecretKey
}

@Singleton
internal class AndroidKeystoreSecretKeyProvider @Inject constructor() : ResettableSecretKeyProvider {
    @Synchronized
    override fun getOrCreate(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return generateKey()
    }

    @Synchronized
    override fun reset(): SecretKey {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
        }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "idealplayer_source_secrets_v1"
    }
}

/** AES-GCM envelope for provider credentials and persisted playback source URLs. */
@Singleton
class SecretEnvelopeCodec private constructor(
    private val provider: SecretKeyProvider
) {
    // Bounded, process-only cache of authenticated batch keys; no raw keys reach disk.
    private val batchKeys = object : LinkedHashMap<String, SecretKey>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SecretKey>?) = size > 8
    }
    @Inject internal constructor(keyProvider: AndroidKeystoreSecretKeyProvider) : this(
        keyProvider as SecretKeyProvider
    )

    internal constructor(keyProvider: SecretKeyProvider, testOnly: Unit = Unit) : this(keyProvider)
    fun encrypt(plaintext: String): String {
        if (plaintext.isBlank() || isEncrypted(plaintext)) return plaintext
        return try {
            encryptWithKey(plaintext, provider.getOrCreate())
        } catch (_: java.security.InvalidKeyException) {
            encryptWithReplacementKey(plaintext)
        } catch (_: java.security.UnrecoverableKeyException) {
            encryptWithReplacementKey(plaintext)
        }
    }

    private fun encryptWithReplacementKey(plaintext: String): String {
        val resettableProvider = provider as? ResettableSecretKeyProvider ?: throw IllegalStateException(
            "Secret key is unavailable and cannot be recreated"
        )
        synchronized(batchKeys) { batchKeys.clear() }
        return encryptWithKey(plaintext, resettableProvider.reset())
    }

    private fun encryptWithKey(plaintext: String, key: SecretKey): String =
        PREFIX + seal(plaintext.toByteArray(Charsets.UTF_8), key, AAD)

    /** One Keystore wrap per bounded batch, with fresh AES-GCM nonces for every value. */
    fun encryptBatch(values: List<String>): List<String> {
        require(values.size <= 1_000) { "Secret batch must be bounded" }
        if (values.none { it.isNotBlank() && !isEncrypted(it) }) return values
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        fun wrap(): String = seal(key.encoded, provider.getOrCreate(), KEY_AAD)
        val wrapped = try {
            wrap()
        } catch (error: Exception) {
            if (error !is java.security.InvalidKeyException && error !is java.security.UnrecoverableKeyException) throw error
            val resettable = provider as? ResettableSecretKeyProvider ?: throw error
            synchronized(batchKeys) { batchKeys.clear() }
            seal(key.encoded, resettable.reset(), KEY_AAD)
        }
        synchronized(batchKeys) { batchKeys[wrapped] = key }
        return values.map { value ->
            if (value.isBlank() || isEncrypted(value)) value
            else BATCH_PREFIX + wrapped + ":" + seal(value.toByteArray(Charsets.UTF_8), key, BATCH_AAD)
        }
    }

    private fun seal(bytes: ByteArray, key: SecretKey, aad: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            // Android Keystore keys created with randomized encryption reject caller-provided
            // IVs. Let the provider generate a fresh random GCM IV, then persist it in the
            // envelope for decryption. Software-backed test keys follow the same contract.
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(aad)
        }
        val iv = cipher.iv.also { generatedIv ->
            check(generatedIv.size == IV_BYTES) { "Unexpected AES-GCM IV size" }
        }
        val encrypted = cipher.doFinal(bytes)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "${encoder.encodeToString(iv)}:${encoder.encodeToString(encrypted)}"
    }

    fun decode(value: String): SecretDecodeResult {
        if (!isEncrypted(value)) return SecretDecodeResult.Available(value, wasEncrypted = false)
        return try {
            val plaintext = if (value.startsWith(BATCH_PREFIX)) {
                val parts = value.removePrefix(BATCH_PREFIX).split(':', limit = 4)
                if (parts.size != 4) return SecretDecodeResult.CorruptEnvelope
                val wrapped = parts[0] + ":" + parts[1]
                val key = synchronized(batchKeys) {
                    batchKeys[wrapped] ?: open(wrapped, provider.getOrCreate(), KEY_AAD).let { bytes ->
                        require(bytes.size == 32) { "Invalid batch key" }
                        SecretKeySpec(bytes, "AES").also { batchKeys[wrapped] = it }
                    }
                }
                open(parts[2] + ":" + parts[3], key, BATCH_AAD)
            } else {
                open(value.removePrefix(PREFIX), provider.getOrCreate(), AAD)
            }
            SecretDecodeResult.Available(
                value = plaintext.toString(Charsets.UTF_8),
                wasEncrypted = true
            )
        } catch (_: java.security.InvalidKeyException) {
            SecretDecodeResult.KeyUnavailable
        } catch (_: java.security.UnrecoverableKeyException) {
            SecretDecodeResult.KeyUnavailable
        } catch (_: Exception) {
            SecretDecodeResult.CorruptEnvelope
        }
    }

    private fun open(payload: String, key: SecretKey, aad: ByteArray): ByteArray {
        val parts = payload.split(':', limit = 2)
        require(parts.size == 2)
        val decoder = Base64.getUrlDecoder()
        val iv = decoder.decode(parts[0])
        require(iv.size == IV_BYTES)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad)
            doFinal(decoder.decode(parts[1]))
        }
    }

    fun decryptOrEmpty(value: String): String = when (val result = decode(value)) {
        is SecretDecodeResult.Available -> result.value
        SecretDecodeResult.CorruptEnvelope,
        SecretDecodeResult.KeyUnavailable -> ""
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX) || value.startsWith(BATCH_PREFIX)

    fun requiresReentry(vararg values: String): Boolean = values.any { value ->
        when (decode(value)) {
            is SecretDecodeResult.Available -> false
            SecretDecodeResult.CorruptEnvelope,
            SecretDecodeResult.KeyUnavailable -> true
        }
    }

    companion object {
        /**
         * One import's disposable encrypted cache. The random key is never persisted,
         * so abandoned cache files cannot be recovered after process death. Permanent
         * database/settings secrets must continue to use the injected Keystore codec.
         */
        internal fun forTransientStorage(): SecretEnvelopeCodec {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            return SecretEnvelopeCodec(SecretKeyProvider { key })
        }

        const val PREFIX = "enc:v1:"
        const val BATCH_PREFIX = "enc:v2:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val AAD = "IdealPlayer:source-secret:v1".toByteArray(Charsets.UTF_8)
        private val KEY_AAD = "IdealPlayer:source-batch-key:v2".toByteArray(Charsets.UTF_8)
        private val BATCH_AAD = "IdealPlayer:source-batch-secret:v2".toByteArray(Charsets.UTF_8)
    }
}

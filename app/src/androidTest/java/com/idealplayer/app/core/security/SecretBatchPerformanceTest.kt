package com.idealplayer.app.core.security

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Synthetic crypto only: does not read/change user playlists, settings or the Keystore alias. */
class SecretBatchPerformanceTest {
    @Test fun comparePerRecordAndWrappedBatchEncryption() {
        val codec = SecretEnvelopeCodec(AndroidKeystoreSecretKeyProvider())
        val values = List(100) { "https://example.test/synthetic/$it" }
        codec.encrypt("warmup")
        val started = SystemClock.elapsedRealtime()
        val legacy = values.map(codec::encrypt)
        val legacyMs = SystemClock.elapsedRealtime() - started
        val batchStart = SystemClock.elapsedRealtime()
        val batch = codec.encryptBatch(values)
        val batchMs = SystemClock.elapsedRealtime() - batchStart
        // A fresh instance proves the durable wrapped key is sufficient after process restart.
        val reader = SecretEnvelopeCodec(AndroidKeystoreSecretKeyProvider())
        val readStart = SystemClock.elapsedRealtime()
        assertThat(batch.map(reader::decryptOrEmpty)).containsExactlyElementsIn(values).inOrder()
        val batchReadMs = SystemClock.elapsedRealtime() - readStart
        assertThat(reader.decryptOrEmpty(legacy.first())).isEqualTo(values.first())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\ncrypto_count=100 legacy_write_ms=$legacyMs batch_write_ms=$batchMs batch_read_ms=$batchReadMs\n")
        })
    }
}

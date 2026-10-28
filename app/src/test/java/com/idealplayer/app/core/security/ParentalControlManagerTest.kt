package com.idealplayer.app.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ParentalControlManagerTest {
    private fun preferences() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("parental-${System.nanoTime()}", Context.MODE_PRIVATE)

    @Test fun `all administration requires current PIN and cannot reuse a grant after changing PIN`() = runBlocking<Unit> {
        val manager = ParentalControlManager({ preferences() })
        assertThat(manager.setPin("1234")).isTrue()
        assertThat(manager.setPin("9876")).isFalse()
        assertThat(manager.setChildLock(false, null)).isFalse()
        assertThat(manager.setCategoryAllowed("Kids", true, null)).isFalse()
        assertThat(manager.clearPin(null)).isFalse()
        assertThat(manager.authorize("9876")).isNull()
        val grant = manager.authorize("1234")!!
        assertThat(manager.setCategoryAllowed("Kids", true, grant)).isTrue()
        assertThat(manager.setChildLock(true, grant)).isTrue()
        assertThat(manager.snapshot().allows(" KIDS ")).isTrue()
        assertThat(manager.snapshot().allows("Other")).isFalse()
        assertThat(manager.snapshot().allows("")).isFalse()
        assertThat(manager.setPin("9876", grant)).isTrue()
        assertThat(manager.clearPin(grant)).isFalse()
        assertThat(manager.authorize("1234")).isNull()
        assertThat(manager.clearPin(manager.authorize("9876"))).isTrue()
        assertThat(manager.snapshot().isPinSet).isFalse()
        assertThat(manager.snapshot().enabled).isFalse()
        assertThat(manager.snapshot().allowedCategories).isEmpty()
    }

    @Test fun `expired foreign revoked grants and repeated guesses cannot administer`() = runBlocking<Unit> {
        var now = 1000L
        val manager = ParentalControlManager({ preferences() }, { now }, { now })
        manager.setPin("1234")
        val grant = manager.authorize("1234")!!
        assertThat(manager.setChildLock(true, ParentalControlManager.Authorization(grant.issuedAt))).isFalse()
        now += 300_000
        assertThat(manager.setChildLock(true, grant)).isFalse()
        val renewed = manager.authorize("1234")!!
        manager.revokeAuthorization()
        assertThat(manager.clearPin(renewed)).isFalse()
        repeat(5) { assertThat(manager.authorize("0000")).isNull() }
        assertThat(manager.authorize("1234")).isNull()
        now += 30_001
        assertThat(manager.authorize("1234")).isNotNull()
    }

    @Test fun `secure storage failure denies browsing and administration`() = runBlocking<Unit> {
        val manager = ParentalControlManager({ error("synthetic keystore failure") })
        assertThat(manager.snapshot().ready).isFalse()
        assertThat(manager.state.first().allows("Kids")).isFalse()
        assertThat(manager.setPin("1234")).isFalse()
        assertThat(manager.authorize("1234")).isNull()
    }

    @Test fun `category comparison and stored legacy hash survive Turkish locale`() = runBlocking<Unit> {
        val prefs = preferences()
        // Existing format remains readable and is upgraded only after successful verification.
        val legacy = java.security.MessageDigest.getInstance("SHA-256").digest("1234".toByteArray()).joinToString("") { "%02x".format(it) }
        prefs.edit().putBoolean("pin_set", true).putString("pin_hash", legacy).commit()
        val manager = ParentalControlManager({ prefs })
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val grant = manager.authorize("1234")!!
            manager.setCategoryAllowed(" KIDS ", true, grant)
            manager.setChildLock(true, grant)
            assertThat(manager.snapshot().allows("kids")).isTrue()
            assertThat(PinHasher.needsRehash(prefs.getString("pin_hash", "")!!)).isFalse()
        } finally { Locale.setDefault(previousLocale) }
    }
}

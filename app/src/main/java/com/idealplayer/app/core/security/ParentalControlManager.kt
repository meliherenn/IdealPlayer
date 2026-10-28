package com.idealplayer.app.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ParentalAccessState(
    val ready: Boolean = false,
    val isPinSet: Boolean = false,
    val enabled: Boolean = true,
    val allowedCategories: Set<String> = emptySet()
) {
    fun allows(category: String): Boolean = ready &&
        (!enabled || (category.isNotBlank() && normalizeCategory(category) in allowedCategories))
}

internal fun normalizeCategory(category: String): String = category.trim().lowercase(Locale.ROOT)

/** All administration requires a short-lived, in-memory grant issued by this manager. */
@Singleton
class ParentalControlManager internal constructor(
    private val preferences: () -> SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    @Inject constructor(@ApplicationContext context: Context) : this({
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "idealplayer_parental_control", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    })

    class Authorization internal constructor(internal val issuedAt: Long)

    private val prefs by lazy(preferences)
    private val mutex = Mutex()
    private var currentAuthorization: Authorization? = null

    val state: Flow<ParentalAccessState> = callbackFlow {
        val storage = runCatching { prefs }.getOrNull()
        if (storage == null) {
            trySend(Unit)
            awaitClose { }
        } else {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
            storage.registerOnSharedPreferenceChangeListener(listener)
            trySend(Unit)
            awaitClose { storage.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }.map { readState() }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun snapshot(): ParentalAccessState = withContext(Dispatchers.IO) { readState() }

    private fun readState(): ParentalAccessState = runCatching {
        val isPinSet = prefs.getBoolean(KEY_PIN_SET, false)
        val enabled = prefs.getBoolean(KEY_CHILD_LOCK, false)
        check(!isPinSet || !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank())
        check(!enabled || isPinSet)
        ParentalAccessState(
            ready = true,
            isPinSet = isPinSet,
            enabled = enabled,
            allowedCategories = prefs.all.filter { it.key.startsWith(KEY_WHITELIST_PREFIX) && it.value == true }
                .keys.map { normalizeCategory(it.removePrefix(KEY_WHITELIST_PREFIX)) }.toSet()
        )
    }.getOrDefault(ParentalAccessState())

    suspend fun authorize(pin: String): Authorization? = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentAuthorization = null
            val state = readState()
            if (!state.ready || !state.isPinSet || prefs.getLong(KEY_LOCKED_UNTIL, 0) > clock()) return@withLock null
            val hash = prefs.getString(KEY_PIN_HASH, null) ?: return@withLock null
            if (pin.length != 4 || pin.any { it !in '0'..'9' } || !PinHasher.verify(pin, hash)) {
                val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
                prefs.edit().putInt(KEY_FAILED_ATTEMPTS, if (attempts >= 5) 0 else attempts)
                    .putLong(KEY_LOCKED_UNTIL, if (attempts >= 5) clock() + 30_000 else 0).commit()
                return@withLock null
            }
            val editor = prefs.edit().remove(KEY_FAILED_ATTEMPTS).remove(KEY_LOCKED_UNTIL)
            if (PinHasher.needsRehash(hash)) editor.putString(KEY_PIN_HASH, PinHasher.hash(pin))
            if (!editor.commit()) return@withLock null
            Authorization(monotonicClock()).also { currentAuthorization = it }
        }
    }

    suspend fun setPin(pin: String, authorization: Authorization? = null): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = readState()
            if (!state.ready || (state.isPinSet && !authorized(authorization)) ||
                pin.length != 4 || pin.any { it !in '0'..'9' }
            ) return@withLock false
            val saved = prefs.edit().putString(KEY_PIN_HASH, PinHasher.hash(pin)).putBoolean(KEY_PIN_SET, true)
                .remove(KEY_FAILED_ATTEMPTS).remove(KEY_LOCKED_UNTIL).commit()
            currentAuthorization = null
            saved
        }
    }

    suspend fun setChildLock(enabled: Boolean, authorization: Authorization?): Boolean = edit(authorization) {
        putBoolean(KEY_CHILD_LOCK, enabled)
    }

    suspend fun setCategoryAllowed(category: String, allowed: Boolean, authorization: Authorization?): Boolean =
        edit(authorization) {
            val key = KEY_WHITELIST_PREFIX + normalizeCategory(category)
            if (allowed && category.isNotBlank()) putBoolean(key, true) else remove(key)
        }

    suspend fun clearPin(authorization: Authorization?): Boolean = edit(authorization) {
        remove(KEY_PIN_HASH).putBoolean(KEY_PIN_SET, false).putBoolean(KEY_CHILD_LOCK, false)
            .remove(KEY_FAILED_ATTEMPTS).remove(KEY_LOCKED_UNTIL)
        prefs.all.keys.filter { it.startsWith(KEY_WHITELIST_PREFIX) }.forEach(::remove)
        currentAuthorization = null
    }

    suspend fun revokeAuthorization() = withContext(Dispatchers.IO) {
        mutex.withLock { currentAuthorization = null }
    }

    private suspend fun edit(authorization: Authorization?, action: SharedPreferences.Editor.() -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!readState().ready || !authorized(authorization)) return@withLock false
                prefs.edit().apply(action).commit()
            }
        }

    private fun authorized(grant: Authorization?): Boolean = grant != null && grant === currentAuthorization &&
        monotonicClock() - grant.issuedAt in 0 until 300_000L

    private companion object {
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_PIN_SET = "pin_set"
        const val KEY_CHILD_LOCK = "child_lock_enabled"
        const val KEY_WHITELIST_PREFIX = "whitelist_"
        const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKED_UNTIL = "locked_until"
    }
}

package com.idealplayer.app.core.player

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.CaptioningManager
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
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

data class SystemCaptionPreferenceSnapshot(
    val enabled: Boolean,
    val language: String?,
    val fontScale: Float,
    val foregroundColor: Int,
    val backgroundColor: Int,
    val edgeType: Int,
    val edgeColor: Int,
    val typefaceName: String? = null,
    val windowColor: Int = 0,
    val audioDescriptionRequested: Boolean = false
)

@Singleton
class SystemCaptionPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Observed only while a playback session needs captions; collection owns the listener. */
    fun changes(): Flow<SystemCaptionPreferenceSnapshot> = callbackFlow {
        val manager = context.getSystemService(CaptioningManager::class.java)
        val listener = object : CaptioningManager.CaptioningChangeListener() {
            override fun onEnabledChanged(enabled: Boolean) { trySend(snapshot()) }
            override fun onLocaleChanged(locale: Locale?) { trySend(snapshot()) }
            override fun onFontScaleChanged(fontScale: Float) { trySend(snapshot()) }
            override fun onUserStyleChanged(userStyle: CaptioningManager.CaptionStyle) { trySend(snapshot()) }
        }
        manager?.addCaptioningChangeListener(listener)
        // Do not capture an API 33 listener type in the coroutine/awaitClose classes: older ART
        // versions resolve that field type even when listener creation is behind an SDK check.
        val removeAudioDescriptionListener = if (Build.VERSION.SDK_INT >= 33) {
            AudioDescriptionApi33.observe(context) { trySend(snapshot()) }
        } else ({})
        trySend(snapshot())
        awaitClose {
            manager?.removeCaptioningChangeListener(listener)
            removeAudioDescriptionListener()
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Main.immediate)

    fun snapshot(): SystemCaptionPreferenceSnapshot {
        val manager = context.getSystemService(CaptioningManager::class.java)
        if (manager == null) return defaultSystemCaptionPreferences()
        val style = manager.userStyle
        val fallback = defaultSystemCaptionPreferences()
        return SystemCaptionPreferenceSnapshot(
            enabled = manager.isEnabled,
            language = manager.locale?.language?.takeIf(String::isNotBlank),
            fontScale = manager.fontScale.takeIf(Float::isFinite)?.coerceIn(0.5f, 2f) ?: 1f,
            foregroundColor = if (style.hasForegroundColor()) style.foregroundColor else fallback.foregroundColor,
            backgroundColor = if (style.hasBackgroundColor()) style.backgroundColor else fallback.backgroundColor,
            edgeType = if (style.hasEdgeType()) style.edgeType else fallback.edgeType,
            edgeColor = if (style.hasEdgeColor()) style.edgeColor else fallback.edgeColor,
            typefaceName = Settings.Secure.getString(context.contentResolver, "accessibility_captioning_typeface"),
            windowColor = if (style.hasWindowColor()) style.windowColor else 0,
            audioDescriptionRequested = Build.VERSION.SDK_INT >= 33 &&
                AudioDescriptionApi33.isRequested(context)
        )
    }
}

@RequiresApi(33)
private object AudioDescriptionApi33 {
    @DoNotInline
    fun observe(context: Context, onChange: () -> Unit): () -> Unit {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val listener = AccessibilityManager.AudioDescriptionRequestedChangeListener { onChange() }
        manager?.addAudioDescriptionRequestedChangeListener(context.mainExecutor, listener)
        return { manager?.removeAudioDescriptionRequestedChangeListener(listener); Unit }
    }

    @DoNotInline
    fun isRequested(context: Context): Boolean =
        context.getSystemService(AccessibilityManager::class.java)?.isAudioDescriptionRequested == true
}

internal fun defaultSystemCaptionPreferences(): SystemCaptionPreferenceSnapshot =
    SystemCaptionPreferenceSnapshot(
        enabled = false,
        language = Locale.getDefault().language.takeIf(String::isNotBlank),
        fontScale = 1f,
        foregroundColor = 0xFFFFFFFF.toInt(),
        backgroundColor = 0xFF000000.toInt(),
        edgeType = CaptioningManager.CaptionStyle.EDGE_TYPE_NONE,
        edgeColor = 0xFF000000.toInt()
    )

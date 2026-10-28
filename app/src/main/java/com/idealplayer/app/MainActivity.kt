package com.idealplayer.app

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Build
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.idealplayer.app.core.common.DeviceUtils
import com.idealplayer.app.core.common.PlaybackWindowPolicy
import com.idealplayer.app.core.datastore.SettingsDataStore
import com.idealplayer.app.core.designsystem.theme.IdealPlayerTheme
import com.idealplayer.app.core.worker.CatalogRefreshWorker
import com.idealplayer.app.ui.navigation.IdealPlayerNavHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
open class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    // Shared PiP state: observed by PlayerScreen to hide overlays
    private val _isPipMode = MutableStateFlow(false)
    val isPipMode: StateFlow<Boolean> = _isPipMode.asStateFlow()

    // Set to true by PlayerScreen via a side-effect when player is active
    var isPlayerScreenActive: Boolean = false
        set(value) {
            field = value
            updatePictureInPictureParams()
        }

    private var pipSourceRect: Rect? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            val appLanguage = settingsDataStore.settings.first().appLanguage
            val localeList = if (appLanguage == "system") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(appLanguage)
            }
            AppCompatDelegate.setApplicationLocales(localeList)
        }

        val deviceUiMode = DeviceUtils.resolveUiMode(this)
        if (PlaybackWindowPolicy.shouldLockLandscape(deviceUiMode.isTv)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        setContent {
            IdealPlayerTheme(isTv = deviceUiMode.isTv) {
                IdealPlayerNavHost(deviceUiMode = deviceUiMode)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        CatalogRefreshWorker.enqueueOnAppEntry(this)
    }

    // ─── PiP ───────────────────────────────────────────────────────────────────

    /**
     * Called when the user presses Home while in-app.
     * If the player is active, enter PiP instead of backgrounding.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PlaybackWindowPolicy.shouldEnterPictureInPictureOnUserLeave(
                isPlayerScreenActive = isPlayerScreenActive,
                isTv = DeviceUtils.resolveUiMode(this).isTv,
                sdkInt = Build.VERSION.SDK_INT
            )
        ) {
            enterPipMode()
        }
    }

    fun enterPipMode() {
        if (!supportsPictureInPicture()) return
        enterPictureInPictureMode(buildPictureInPictureParams())
    }

    fun updatePipSourceRect(sourceRect: Rect) {
        pipSourceRect = Rect(sourceRect)
        updatePictureInPictureParams()
    }

    private fun updatePictureInPictureParams() {
        if (!supportsPictureInPicture()) return
        setPictureInPictureParams(buildPictureInPictureParams())
    }

    private fun buildPictureInPictureParams(): PictureInPictureParams {
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .apply {
                pipSourceRect?.takeIf { !it.isEmpty }?.let(::setSourceRectHint)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(isPlayerScreenActive)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    private fun supportsPictureInPicture(): Boolean =
        PlaybackWindowPolicy.supportsPictureInPicture(
            hasPictureInPictureFeature = packageManager.hasSystemFeature(
                PackageManager.FEATURE_PICTURE_IN_PICTURE
            ),
            isTv = DeviceUtils.resolveUiMode(this).isTv
        )

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _isPipMode.value = isInPictureInPictureMode
    }
}

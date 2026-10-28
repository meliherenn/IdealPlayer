package com.idealplayer.app.core.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.idealplayer.app.core.common.SensitiveLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.interfaces.IMedia
import timber.log.Timber
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean

internal enum class VlcAudioFocusAction {
    NONE,
    PAUSE_RETAINING_FOCUS,
    PAUSE_AND_ABANDON,
    DUCK,
    RESTORE,
    RESTORE_AND_RESUME
}

internal fun vlcAudioFocusAction(
    focusChange: Int,
    pausedByFocus: Boolean
): VlcAudioFocusAction = when (focusChange) {
    AudioManager.AUDIOFOCUS_LOSS -> VlcAudioFocusAction.PAUSE_AND_ABANDON
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> VlcAudioFocusAction.PAUSE_RETAINING_FOCUS
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> VlcAudioFocusAction.DUCK
    AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) {
        VlcAudioFocusAction.RESTORE_AND_RESUME
    } else {
        VlcAudioFocusAction.RESTORE
    }
    else -> VlcAudioFocusAction.NONE
}

@Singleton
class VlcPlayerEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : PlayerEngine {

    override val engineName: String = "VLC"

    override fun isAvailable(): Boolean {
        // VLC is shipped via libvlc-all and supports main architectures
        return true
    }

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var scope: CoroutineScope? = null
    private var progressJob: Job? = null
    private var subtitleRefreshJob: Job? = null
    private var subtitleDecoderReloading = false
    private var hasAudioFocus = false
    private var pausedByAudioFocus = false
    private var volumeBeforeDucking: Int? = null
    private var noisyReceiverRegistered = false

    private val audioManager: AudioManager by lazy {
        context.getSystemService(AudioManager::class.java)
    }
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        scope?.launch { handleAudioFocusChange(focusChange) }
    }
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener(audioFocusChangeListener)
        .build()
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                scope?.launch { pauseForAudioFocus(abandonFocus = true) }
            }
        }
    }

    private var configuredBufferMs: Long = 30_000L
    private var configuredLatencyMode: String = LiveLatencyMode.BALANCED.name
    private var configuredPreferHw: Boolean = true
    private var currentPlaybackSpeed = 1f
    private var currentAspectMode = AspectRatioMode.FIT
    private var currentVideoQualityMode = VideoQualityMode.AUTO
    private var preferredAudioLanguage: String? = null
    private var preferredSubtitleLanguage: String? = null
    private var subtitlesDisabled = false
    private var preferAudioDescription = false
    private var manualAudioTrackId: Int? = null
    private var manualSubtitleTrackId: Int? = null
    private var configuredSubtitleAppearance = SubtitleAppearance()
    private var activeSubtitleAppearance: SubtitleAppearance? = null
    private var currentProfile = PlaybackProfile.VOD

    private var currentSurfaceView: SurfaceView? = null
    private var activeRequestId = 0L
    private var playbackGeneration = 0L
    internal var surfaceAttachmentCount = 0L
        private set
    private var playWhenReady = false
    private var surfaceReady = false
    private var firstFrameRendered = false
    private var playbackConfirmationBaselinePositionMs = Long.MIN_VALUE
    private var playbackProgressConfirmed = false
    private var pendingUrl: String? = null
    private var pendingStartPos: Long = 0L
    private var pendingInitialSeekPosition: Long? = null
    private var activePlaybackUrl: String? = null
    private var pendingSeekPosition: Long? = null
    private var pendingSeekElapsedMs: Long = 0L

    private var playbackState: PlaybackState = PlaybackState.IDLE
    private var hasVideoTrack = false
    private var hasAudioTrack = false
    private var currentVideoWidth = 0
    private var currentVideoHeight = 0
    private var currentVideoCodec = ""
    private var currentVideoResolution = ""
    private var currentVideoBitrate = ""
    private var currentVideoFps = ""
    private val networkThroughputSampler = NetworkThroughputSampler()
    private var currentAudioTracks: List<TrackInfo> = emptyList()
    private var currentSubtitleTracks: List<TrackInfo> = emptyList()
    private var selectedAudioTrack = -1
    private var selectedSubtitleTrack = -1
    private var currentErrorMessage: String? = null

    private val _state = MutableStateFlow(
        PlayerState(
            aspectRatioMode = currentAspectMode,
            playbackSpeed = currentPlaybackSpeed,
            videoQualityMode = currentVideoQualityMode
        )
    )
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    override fun initialize() {
        if (libVlc != null && mediaPlayer != null) {
            return
        }

        scope?.cancel()
        scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

        try {
            val hwOption = if (configuredPreferHw) "--avcodec-hw=any" else "--avcodec-hw=none"
            val options = arrayListOf(
                // Native errors include the full MRL (provider credentials). App diagnostics are redacted.
                "--verbose=-1",
                "--no-drop-late-frames",
                "--no-skip-frames",
                hwOption,
                "--subsdec-encoding=UTF-8",
                "--aout=opensles",
                "--audio-time-stretch"
            )

            options.addAll(configuredSubtitleAppearance.vlcOptions())
            val vlc = LibVLC(context, options)
            libVlc = vlc
            mediaPlayer = MediaPlayer(vlc).apply {
                setEventListener(::handleVlcEvent)
            }
            activeSubtitleAppearance = configuredSubtitleAppearance
            publishState()
            Timber.d("VLC engine initialized")
        } catch (throwable: Throwable) {
            Timber.e("VLC init failed: %s", throwable.javaClass.simpleName)
            currentErrorMessage = "VLC initialization failed"
            playbackState = PlaybackState.ERROR
            publishState()
        }
    }

    override fun release() {
        prepareRelease().invoke()
    }

    /** Detach UI/state on main before the returned native cleanup runs on IO. */
    @androidx.annotation.MainThread
    internal fun prepareRelease(): () -> Unit {
        playWhenReady = false
        playbackGeneration++
        subtitleRefreshJob?.cancel()
        subtitleRefreshJob = null
        subtitleDecoderReloading = false
        try {
            mediaPlayer?.setEventListener(null)
        } catch (throwable: Throwable) {
            // Generation invalidation above still rejects callbacks. A broken native
            // listener must not prevent the remaining handles from being retired.
            Timber.w("VLC listener removal failed during release: %s", throwable.javaClass.simpleName)
        }
        stopProgressTracking()
        abandonAudioFocus()
        pausedByAudioFocus = false
        pendingUrl = null
        pendingStartPos = 0L
        pendingInitialSeekPosition = null
        activePlaybackUrl = null
        pendingSeekPosition = null
        pendingSeekElapsedMs = 0L
        cleanupSurface()
        val retiringPlayer = mediaPlayer
        val retiringLibVlc = libVlc
        mediaPlayer = null
        libVlc = null
        activeSubtitleAppearance = null
        scope?.cancel()
        scope = null

        playbackState = PlaybackState.IDLE
        hasVideoTrack = false
        hasAudioTrack = false
        firstFrameRendered = false
        playbackConfirmationBaselinePositionMs = Long.MIN_VALUE
        playbackProgressConfirmed = false
        currentVideoWidth = 0
        currentVideoHeight = 0
        currentVideoCodec = ""
        currentVideoResolution = ""
        currentVideoBitrate = ""
        currentVideoFps = ""
        networkThroughputSampler.reset()
        currentAudioTracks = emptyList()
        currentSubtitleTracks = emptyList()
        selectedAudioTrack = -1
        selectedSubtitleTrack = -1
        currentErrorMessage = null

        _state.value = PlayerState(
            requestId = activeRequestId,
            aspectRatioMode = currentAspectMode,
            playbackSpeed = currentPlaybackSpeed,
            videoQualityMode = currentVideoQualityMode
        )
        val destroyed = AtomicBoolean(false)
        return destroy@{
            if (!destroyed.compareAndSet(false, true)) return@destroy
            try {
                retiringPlayer?.stop()
            } catch (throwable: Throwable) {
                Timber.w("VLC stop failed during release: %s", throwable.javaClass.simpleName)
            }
            try {
                retiringPlayer?.release()
            } catch (throwable: Throwable) {
                Timber.w("VLC player release failed: %s", throwable.javaClass.simpleName)
            }
            try {
                retiringLibVlc?.release()
            } catch (throwable: Throwable) {
                Timber.w("LibVLC release failed: %s", throwable.javaClass.simpleName)
            }
        }
    }

    override fun play(url: String, startPosition: Long, profile: PlaybackProfile, requestId: Long) {
        initialize()
        // A native player has one media event queue. Give each request its own queue so late
        // Stopped/Vout/Playing events can never be attributed to the next channel.
        playbackGeneration++
        subtitleRefreshJob?.cancel()
        subtitleRefreshJob = null
        subtitleDecoderReloading = false
        stopProgressTracking()
        pausedByAudioFocus = false
        mediaPlayer?.setEventListener(null)
        detachVlcFromSurface()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        val vlc = libVlc ?: return
        val nextPlayer = MediaPlayer(vlc)
        mediaPlayer = nextPlayer
        val generation = playbackGeneration
        nextPlayer.setEventListener { event ->
            if (mediaPlayer === nextPlayer && playbackGeneration == generation) handleVlcEvent(event)
        }
        playWhenReady = true
        currentProfile = profile
        pendingSeekPosition = null
        pendingSeekElapsedMs = 0L
        currentErrorMessage = null
        playbackState = PlaybackState.BUFFERING
        firstFrameRendered = false
        playbackConfirmationBaselinePositionMs = Long.MIN_VALUE
        playbackProgressConfirmed = false
        currentVideoWidth = 0
        currentVideoHeight = 0
        currentVideoCodec = ""
        currentVideoResolution = ""
        currentVideoBitrate = ""
        currentVideoFps = ""
        networkThroughputSampler.reset()
        currentAudioTracks = emptyList()
        currentSubtitleTracks = emptyList()
        selectedAudioTrack = -1
        selectedSubtitleTrack = -1
        hasVideoTrack = false
        hasAudioTrack = false

        activeRequestId = requestId
        activePlaybackUrl = url
        pendingUrl = null
        pendingStartPos = 0L
        pendingInitialSeekPosition = null
        currentSurfaceView?.takeIf { it.holder.surface?.isValid == true }?.let(::attachVlcToSurface)
        if (!surfaceReady) {
            pendingUrl = url
            pendingStartPos = startPosition
            publishState()
            Timber.d("VLC playback queued until surface is ready: %s", SensitiveLog.redactUrl(url))
            return
        }

        startPlaybackInternal(url, startPosition)
    }

    override fun pause() {
        playWhenReady = false
        mediaPlayer?.pause()
        pausedByAudioFocus = false
        abandonAudioFocus()
        playbackState = PlaybackState.PAUSED
        publishState()
    }

    override fun resume() {
        playWhenReady = true
        if (!surfaceReady) return
        if (requestAudioFocus()) {
            pausedByAudioFocus = false
            mediaPlayer?.play()
            playbackState = PlaybackState.PLAYING
        }
        publishState()
    }

    override fun stop() {
        playWhenReady = false
        playbackGeneration++
        subtitleRefreshJob?.cancel()
        subtitleRefreshJob = null
        subtitleDecoderReloading = false
        mediaPlayer?.setEventListener(null)
        try {
            mediaPlayer?.stop()
        } catch (throwable: Throwable) {
            Timber.w("VLC stop failed: %s", throwable.javaClass.simpleName)
        }
        stopProgressTracking()
        abandonAudioFocus()
        pausedByAudioFocus = false
        pendingUrl = null
        pendingStartPos = 0L
        pendingInitialSeekPosition = null
        activePlaybackUrl = null
        pendingSeekPosition = null
        pendingSeekElapsedMs = 0L
        playbackState = PlaybackState.IDLE
        firstFrameRendered = false
        playbackConfirmationBaselinePositionMs = Long.MIN_VALUE
        playbackProgressConfirmed = false
        hasVideoTrack = false
        hasAudioTrack = false
        currentVideoWidth = 0
        currentVideoHeight = 0
        currentVideoCodec = ""
        currentVideoResolution = ""
        currentVideoBitrate = ""
        currentVideoFps = ""
        networkThroughputSampler.reset()
        currentAudioTracks = emptyList()
        currentSubtitleTracks = emptyList()
        selectedAudioTrack = -1
        selectedSubtitleTrack = -1
        currentErrorMessage = null
        publishState()
    }

    override fun seekTo(position: Long) {
        val player = mediaPlayer ?: return
        val target = resolveSeekPosition(
            requestedPosition = position,
            duration = player.length,
            isSeekable = isCurrentMediaSeekable(player)
        ) ?: run {
            Timber.d("Ignoring LibVLC seek because the current stream is not seekable")
            return
        }
        rememberPendingSeek(target)
        player.time = target
        publishState()
    }

    override fun seekForward(ms: Long) {
        val player = mediaPlayer ?: return
        val target = resolveRelativeSeekPosition(
            currentPosition = currentVlcSeekBase(player.time),
            deltaMs = ms.coerceAtLeast(0L),
            duration = player.length,
            isSeekable = isCurrentMediaSeekable(player)
        ) ?: return
        rememberPendingSeek(target)
        player.time = target
        publishState()
    }

    override fun seekBackward(ms: Long) {
        val player = mediaPlayer ?: return
        val target = resolveRelativeSeekPosition(
            currentPosition = currentVlcSeekBase(player.time),
            deltaMs = -ms.coerceAtLeast(0L),
            duration = player.length,
            isSeekable = isCurrentMediaSeekable(player)
        ) ?: return
        rememberPendingSeek(target)
        player.time = target
        publishState()
    }

    override fun setPlaybackSpeed(speed: Float) {
        currentPlaybackSpeed = speed
        mediaPlayer?.rate = speed
        publishState()
    }

    override fun selectAudioTrack(index: Int) {
        val player = mediaPlayer ?: return
        val tracks = player.audioTracks ?: return
        val trackId = resolveSelectableTrackId(
            selectableTrackIds = tracks.filter { it.id >= 0 }.map { it.id },
            requestedTrackId = index
        ) ?: run {
            Timber.w("Ignoring unknown LibVLC audio track id=%d", index)
            return
        }
        manualAudioTrackId = trackId
        player.audioTrack = trackId
        updateTrackInfo()
        publishState()
    }

    override fun selectSubtitleTrack(index: Int) {
        val player = mediaPlayer ?: return
        val tracks = player.spuTracks ?: return
        val trackId = resolveSelectableTrackId(
            selectableTrackIds = tracks.filter { it.id >= 0 }.map { it.id },
            requestedTrackId = index
        ) ?: run {
            Timber.w("Ignoring unknown LibVLC subtitle track id=%d", index)
            return
        }
        subtitlesDisabled = false
        manualSubtitleTrackId = trackId
        selectSubtitleTrackOnPlayer(player, trackId)
        updateTrackInfo()
        publishState()
    }

    override fun disableSubtitles() {
        subtitleRefreshJob?.cancel()
        subtitleRefreshJob = null
        subtitlesDisabled = true
        if (mediaPlayer?.spuTrack != -1) mediaPlayer?.spuTrack = -1
        selectedSubtitleTrack = -1
        updateTrackInfo()
        publishState()
    }

    override fun setAspectRatio(mode: AspectRatioMode) {
        currentAspectMode = mode
        applyAspectRatioMode()
        publishState()
    }

    override fun setVideoQualityMode(mode: VideoQualityMode) {
        currentVideoQualityMode = mode
        publishState()
    }

    override fun setPlaybackConfiguration(
        bufferDurationMs: Long,
        liveLatencyMode: String,
        preferHwDecoding: Boolean,
        allowQualityFallback: Boolean
    ) {
        configuredBufferMs = bufferDurationMs
        configuredLatencyMode = liveLatencyMode
        configuredPreferHw = preferHwDecoding
    }

    fun setPreferredAudioLanguage(langCode: String) {
        manualAudioTrackId = null
        preferredAudioLanguage = langCode.trim().takeIf { it.isNotBlank() }
        applyPreferredTracks()
    }

    fun setPreferredSubtitleLanguage(langCode: String) {
        manualSubtitleTrackId = null
        val normalized = langCode.trim()
        if (normalized.equals("off", ignoreCase = true) ||
            normalized.equals("none", ignoreCase = true)
        ) {
            subtitlesDisabled = true
            preferredSubtitleLanguage = null
            disableSubtitles()
            return
        }

        subtitlesDisabled = false
        preferredSubtitleLanguage = normalized.takeIf { it.isNotBlank() }
        applyPreferredTracks()
    }

    override fun setAudioDescriptionEnabled(enabled: Boolean) {
        preferAudioDescription = enabled
        applyPreferredTracks()
    }

    fun setSubtitleAppearance(appearance: SubtitleAppearance) {
        configuredSubtitleAppearance = appearance
    }

    fun requiresSubtitleAppearanceRebuild(appearance: SubtitleAppearance): Boolean =
        mediaPlayer != null && activeSubtitleAppearance != appearance

    fun attachSurface(surfaceView: SurfaceView) {
        if (currentSurfaceView === surfaceView) {
            if (!surfaceReady && surfaceView.holder.surface?.isValid == true) attachVlcToSurface(surfaceView)
            return
        }
        cleanupSurface()
        currentSurfaceView?.holder?.removeCallback(surfaceHolderCallback)
        currentSurfaceView = surfaceView
        surfaceReady = false
        firstFrameRendered = false
        surfaceView.holder.addCallback(surfaceHolderCallback)

        val holder = surfaceView.holder
        if (holder.surface?.isValid == true && surfaceView.width > 0 && surfaceView.height > 0) {
            attachVlcToSurface(surfaceView)
        } else {
            publishState()
        }
    }

    fun detachSurface(expectedView: SurfaceView? = null) {
        if (expectedView != null && currentSurfaceView !== expectedView) return
        cleanupSurface()
        publishState()
    }

    fun updateSurfaceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        try {
            mediaPlayer?.vlcVout?.let { vout ->
                if (vout.areViewsAttached()) {
                    vout.setWindowSize(width, height)
                }
            }
        } catch (throwable: Throwable) {
            Timber.w("VLC window size update failed: %s", throwable.javaClass.simpleName)
        }
        applyAspectRatioMode()
        publishState()
    }

    private fun startPlaybackInternal(url: String, startPosition: Long) {
        val player = mediaPlayer ?: return
        val vlc = libVlc ?: return
        val source = parsePlaybackSource(url)

        try {
            stopProgressTracking()
            playbackState = PlaybackState.BUFFERING

            val media = Media(vlc, Uri.parse(source.url))
            try {
            media.setHWDecoderEnabled(configuredPreferHw, false)
            // LibVLC 3.7.2's MP4 demuxer can leave audio-only M4A time at 250 ms
            // while audio keeps playing. Its bundled avformat demuxer advances time
            // and supports seeking; keep normal module discovery as a fallback.
            if (source.url.toUri().path?.endsWith(".m4a", ignoreCase = true) == true) {
                media.addOption(":demux=avformat,any")
            }

            val isLive = currentProfile == PlaybackProfile.LIVE ||
                source.url.contains(".m3u8", ignoreCase = true) ||
                source.url.contains(".ts", ignoreCase = true)
            val cacheMs = if (isLive) {
                when (configuredLatencyMode.uppercase()) {
                    LiveLatencyMode.LOW_LATENCY.name -> 500L
                    LiveLatencyMode.STABLE.name -> 3_000L
                    else -> 1_500L
                }
            } else {
                configuredBufferMs
            }

            media.addOption(":network-caching=$cacheMs")
            media.addOption(":live-caching=$cacheMs")
            media.addOption(
                if (configuredLatencyMode.uppercase() == LiveLatencyMode.LOW_LATENCY.name && isLive) {
                    ":clock-jitter=0"
                } else {
                    ":clock-jitter=500"
                }
            )
            media.addOption(":clock-synchro=0")
            media.addOption(":http-user-agent=${source.userAgent}")
            source.headers["Referer"]?.let { media.addOption(":http-referrer=$it") }
            source.headers["Origin"]?.let { media.addOption(":http-origin=$it") }
            source.headers["Cookie"]?.let { media.addOption(":http-cookie=$it") }
            media.addOption(":input-repeat=0")

            player.media = media
            } finally {
                media.release()
            }
            source.subtitleUrls.forEachIndexed { index, subUrl ->
                runCatching {
                    player.addSlave(
                        IMedia.Slave.Type.Subtitle,
                        subUrl.toUri(),
                        index == 0 && !subtitlesDisabled && !preferredSubtitleLanguage.isNullOrBlank()
                    )
                }
            }
            pendingInitialSeekPosition = startPosition.takeIf { it > 0L }
            player.rate = currentPlaybackSpeed
            if (!playWhenReady) {
                playbackState = PlaybackState.PAUSED
                publishState()
                return
            }
            if (!requestAudioFocus()) {
                playbackState = PlaybackState.PAUSED
                currentErrorMessage = "Audio focus is unavailable"
                publishState()
                return
            }
            pausedByAudioFocus = false
            player.play()


            publishState()
            Timber.d("VLC playback started: %s", SensitiveLog.redactUrl(source.url))
        } catch (throwable: Throwable) {
            // A player/HTTP exception can retain provider headers or credentials. Keep the
            // diagnostic useful without sending the raw throwable to the debug log.
            subtitleDecoderReloading = false
            subtitleRefreshJob = null
            Timber.e(
                "VLC play failed for %s: %s",
                SensitiveLog.redactUrl(source.url),
                throwable.javaClass.simpleName
            )
            abandonAudioFocus()
            pausedByAudioFocus = false
            currentErrorMessage = "VLC playback failed"
            playbackState = PlaybackState.ERROR
            publishState()
        }
    }

    private fun attachVlcToSurface(surfaceView: SurfaceView) {
        if (surfaceView !== currentSurfaceView) return
        val player = mediaPlayer ?: return
        try {
            val vout = player.vlcVout
            if (surfaceReady && vout.areViewsAttached()) return
            if (vout.areViewsAttached()) {
                vout.removeCallback(vlcVoutCallback)
                vout.detachViews()
            }
            vout.setVideoView(surfaceView)
            val width = surfaceView.holder.surfaceFrame.width()
            val height = surfaceView.holder.surfaceFrame.height()
            if (width > 0 && height > 0) {
                vout.setWindowSize(width, height)
            }
            vout.addCallback(vlcVoutCallback)
            vout.attachViews()
            surfaceAttachmentCount++
            surfaceReady = true
            applyAspectRatioMode()
            publishState()

            val queuedUrl = pendingUrl
            if (queuedUrl != null) {
                val queuedPosition = pendingStartPos
                pendingUrl = null
                pendingStartPos = 0L
                pendingInitialSeekPosition = null
                startPlaybackInternal(queuedUrl, queuedPosition)
            }
        } catch (throwable: Throwable) {
            surfaceReady = false
            Timber.e("Failed to attach VLC surface: %s", throwable.javaClass.simpleName)
            publishState()
        }
    }

    private fun detachVlcFromSurface() {
        surfaceReady = false
        firstFrameRendered = false
        try {
            mediaPlayer?.vlcVout?.let { vout ->
                if (vout.areViewsAttached()) {
                    vout.removeCallback(vlcVoutCallback)
                    vout.detachViews()
                }
            }
        } catch (throwable: Throwable) {
            Timber.w("Failed to detach VLC surface: %s", throwable.javaClass.simpleName)
        }
    }

    private fun cleanupSurface() {
        try {
            currentSurfaceView?.holder?.removeCallback(surfaceHolderCallback)
        } catch (throwable: Throwable) {
            Timber.w("Failed to remove SurfaceHolder callback: %s", throwable.javaClass.simpleName)
        }
        detachVlcFromSurface()
        currentSurfaceView = null
    }

    private val surfaceHolderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (holder !== currentSurfaceView?.holder) return
            currentSurfaceView?.let(::attachVlcToSurface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (holder !== currentSurfaceView?.holder) return
            updateSurfaceSize(width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (holder !== currentSurfaceView?.holder) return
            detachVlcFromSurface()
            publishState()
        }
    }

    private val vlcVoutCallback = object : IVLCVout.Callback {
        override fun onSurfacesCreated(vlcVout: IVLCVout) {
            if (vlcVout !== mediaPlayer?.vlcVout || currentSurfaceView == null) return
            surfaceReady = true
            publishState()
        }

        override fun onSurfacesDestroyed(vlcVout: IVLCVout) {
            if (vlcVout !== mediaPlayer?.vlcVout) return
            surfaceReady = false
            firstFrameRendered = false
            publishState()
        }
    }

    private fun handleVlcEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                if (playWhenReady) {
                    playbackState = PlaybackState.BUFFERING
                    currentErrorMessage = null
                }
            }

            MediaPlayer.Event.Buffering -> {
                if (playWhenReady && event.buffering < 100f && playbackState != PlaybackState.PLAYING) {
                    playbackState = PlaybackState.BUFFERING
                }
            }

            MediaPlayer.Event.Playing -> {
                // Opening/buffering/playing events already queued by a subtitle reload
                // must not undo a newer pause command or trigger a caption rebuild.
                if (!playWhenReady) {
                    mediaPlayer?.pause()
                    return
                }
                playbackState = PlaybackState.PLAYING
                currentErrorMessage = null
                ensureDefaultAudioTrackSelected()
                updateTrackInfo()
                val reloadingSubtitleDecoder = subtitleDecoderReloading
                applyPreferredTracks()
                if (reloadingSubtitleDecoder) {
                    scheduleReloadedSubtitleActivation(mediaPlayer)
                } else {
                    subtitleDecoderReloading = false
                }
                applyPendingInitialSeek()
                startProgressTracking()
            }

            MediaPlayer.Event.MediaChanged -> {
                currentAudioTracks = emptyList()
                currentSubtitleTracks = emptyList()
                selectedAudioTrack = -1
                selectedSubtitleTrack = -1
            }

            MediaPlayer.Event.Paused -> {
                playbackState = PlaybackState.PAUSED
                stopProgressTracking()
            }

            MediaPlayer.Event.Stopped -> {
                // A native failure is followed by Stopped; keep it visible until explicit stop/new play.
                playbackState = if (currentErrorMessage != null) PlaybackState.ERROR else PlaybackState.STOPPED
                stopProgressTracking()
                // startPlaybackInternal calls stop before replacing media. The explicit stop()
                // and release() paths abandon focus themselves, so doing it here would race a
                // new request and accidentally release its freshly acquired focus.
            }

            MediaPlayer.Event.EndReached -> {
                playbackState = PlaybackState.ENDED
                stopProgressTracking()
                abandonAudioFocus()
                pausedByAudioFocus = false
            }

            MediaPlayer.Event.EncounteredError -> {
                playbackState = PlaybackState.ERROR
                currentErrorMessage = "VLC playback error"
                stopProgressTracking()
                abandonAudioFocus()
                pausedByAudioFocus = false
            }

            MediaPlayer.Event.TimeChanged -> {
                val pending = pendingSeekPosition
                if (pending != null && kotlin.math.abs(event.timeChanged - pending) <= 1_500L) {
                    pendingSeekPosition = null
                    pendingSeekElapsedMs = 0L
                }
            }

            MediaPlayer.Event.SeekableChanged,
            MediaPlayer.Event.LengthChanged -> {
                // The input may become seekable after Playing, especially on a slow source.
                applyPendingInitialSeek()
            }

            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted -> {
                updateTrackInfo()
                applyPreferredTracks()
            }

            MediaPlayer.Event.ESSelected -> {
                // HLS/DASH and some containers publish elementary streams asynchronously. The
                // Playing event is too early to be the only source of truth for track panels.
                updateTrackInfo()
            }

            MediaPlayer.Event.Vout -> {
                updateVideoTrackInfo()
                if (currentVideoWidth > 0 && currentVideoHeight > 0) {
                    firstFrameRendered = true
                }
                applyAspectRatioMode()
            }
        }
        publishState()
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val granted = audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasAudioFocus = granted
        if (granted) registerBecomingNoisyReceiver()
        return granted
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            try {
                audioManager.abandonAudioFocusRequest(audioFocusRequest)
            } catch (exception: Exception) {
                Timber.w("VLC audio focus abandonment failed: %s", exception.javaClass.simpleName)
            }
        }
        hasAudioFocus = false
        volumeBeforeDucking?.let { volume ->
            runCatching { mediaPlayer?.volume = volume }
        }
        volumeBeforeDucking = null
        unregisterBecomingNoisyReceiver()
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (vlcAudioFocusAction(focusChange, pausedByAudioFocus)) {
            VlcAudioFocusAction.PAUSE_RETAINING_FOCUS -> pauseForAudioFocus(abandonFocus = false)
            VlcAudioFocusAction.PAUSE_AND_ABANDON -> pauseForAudioFocus(abandonFocus = true)
            VlcAudioFocusAction.DUCK -> duckAudio()
            VlcAudioFocusAction.RESTORE -> restoreAudioVolume()
            VlcAudioFocusAction.RESTORE_AND_RESUME -> {
                restoreAudioVolume()
                // Only a transient loss keeps our focus request. A late gain after a permanent
                // loss/noisy-route event must never restart playback without reacquiring focus.
                if (hasAudioFocus && pausedByAudioFocus) {
                    pausedByAudioFocus = false
                    mediaPlayer?.play()
                    playbackState = PlaybackState.PLAYING
                    publishState()
                }
            }
            VlcAudioFocusAction.NONE -> Unit
        }
    }

    private fun pauseForAudioFocus(abandonFocus: Boolean) {
        val playbackWasActive = mediaPlayer?.isPlaying == true ||
            playbackState == PlaybackState.PLAYING ||
            playbackState == PlaybackState.BUFFERING
        if (playbackWasActive) {
            pausedByAudioFocus = true
            mediaPlayer?.pause()
            playbackState = PlaybackState.PAUSED
            stopProgressTracking()
            publishState()
        }
        if (abandonFocus) {
            // Permanent/noisy loss must not be treated as an automatically resumable pause.
            pausedByAudioFocus = false
            abandonAudioFocus()
        }
    }

    private fun duckAudio() {
        val player = mediaPlayer ?: return
        if (!player.isPlaying || volumeBeforeDucking != null) return
        val currentVolume = player.volume
        volumeBeforeDucking = currentVolume
        player.volume = (currentVolume * DUCK_VOLUME_MULTIPLIER).roundToInt().coerceAtLeast(1)
    }

    private fun restoreAudioVolume() {
        val volume = volumeBeforeDucking ?: return
        runCatching { mediaPlayer?.volume = volume }
        volumeBeforeDucking = null
    }

    private fun registerBecomingNoisyReceiver() {
        if (noisyReceiverRegistered) return
        noisyReceiverRegistered = runCatching {
            ContextCompat.registerReceiver(
                context,
                becomingNoisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            true
        }.getOrElse { error ->
            Timber.w("Unable to register VLC noisy-audio receiver: %s", error.javaClass.simpleName)
            false
        }
    }

    private fun unregisterBecomingNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        runCatching { context.unregisterReceiver(becomingNoisyReceiver) }
        noisyReceiverRegistered = false
    }

    private fun updateTrackInfo() {
        val player = mediaPlayer ?: return
        updateVideoTrackInfo()
        val mediaTracksById = readRetainedMediaTracks(player.media)
        val audioTracks = player.audioTracks
            ?.filter { it.id >= 0 }
            ?.mapIndexed { index, track ->
                val metadata = mediaTracksById[track.id]
                val descriptor = listOfNotNull(track.name, metadata?.description)
                    .joinToString(" ")
                TrackInfo(
                    index = track.id,
                    name = buildTrackTitle(
                        kind = TrackKind.AUDIO,
                        ordinal = index + 1,
                        label = track.name ?: metadata?.description,
                        language = metadata?.language,
                        isHearingImpaired = descriptor.hasHearingImpairedMarker()
                    ),
                    language = metadata?.language.orEmpty(),
                    isSelected = track.id == player.audioTrack,
                    codec = metadata?.codec.orEmpty(),
                    channelCount = metadata?.channelCount?.coerceAtLeast(0) ?: 0,
                    isHearingImpaired = descriptor.hasHearingImpairedMarker(),
                    isAudioDescription = descriptor.hasAudioDescriptionMarker()
                )
            }.orEmpty()
        val subtitleTracks = player.spuTracks
            ?.filter { it.id >= 0 }
            ?.mapIndexed { index, track ->
                val metadata = mediaTracksById[track.id]
                val descriptor = listOfNotNull(track.name, metadata?.description)
                    .joinToString(" ")
                TrackInfo(
                    index = track.id,
                    name = buildTrackTitle(
                        kind = TrackKind.SUBTITLE,
                        ordinal = index + 1,
                        label = track.name ?: metadata?.description,
                        language = metadata?.language,
                        isForced = descriptor.contains("forced", ignoreCase = true),
                        isHearingImpaired = descriptor.hasHearingImpairedMarker()
                    ),
                    language = metadata?.language.orEmpty(),
                    isSelected = track.id == player.spuTrack,
                    codec = metadata?.codec.orEmpty(),
                    isForced = descriptor.contains("forced", ignoreCase = true),
                    isHearingImpaired = descriptor.hasHearingImpairedMarker()
                )
            }.orEmpty()

        currentAudioTracks = disambiguateTrackNames(audioTracks)
        currentSubtitleTracks = disambiguateTrackNames(subtitleTracks)
        selectedAudioTrack = currentAudioTracks.firstOrNull { it.isSelected }?.index ?: -1
        selectedSubtitleTrack = if (subtitlesDisabled || player.spuTrack == -1) {
            -1
        } else {
            currentSubtitleTracks.firstOrNull { it.isSelected }?.index ?: -1
        }
        hasAudioTrack = currentAudioTracks.isNotEmpty()
    }

    private fun ensureDefaultAudioTrackSelected() {
        val player = mediaPlayer ?: return
        val tracks = player.audioTracks ?: return
        if (tracks.isEmpty()) {
            return
        }
        val selectableTracks = tracks.filter { it.id >= 0 }
        if (selectableTracks.isNotEmpty() && selectableTracks.none { it.id == player.audioTrack }) {
            player.audioTrack = selectableTracks.first().id
        }
    }

    private fun applyPreferredTracks() {
        val subtitleLanguage = preferredSubtitleLanguage
        applyPreferredAudioTrack()

        when {
            subtitlesDisabled -> disableSubtitles()
            manualSubtitleTrackId != null -> {
                val id = manualSubtitleTrackId
                if (currentSubtitleTracks.any { it.index == id }) {
                    mediaPlayer?.let { selectSubtitleTrackOnPlayer(it, checkNotNull(id)) }
                }
            }
            !subtitleLanguage.isNullOrBlank() -> selectSubtitleTrackByLanguage(subtitleLanguage)
            else -> currentSubtitleTracks.firstOrNull()?.let { track ->
                mediaPlayer?.let { selectSubtitleTrackOnPlayer(it, track.index) }
            }
        }
    }

    private fun applyPreferredAudioTrack() {
        val player = mediaPlayer ?: return
        updateTrackInfo()
        val manual = currentAudioTracks.firstOrNull { it.index == manualAudioTrackId }
        val matching = currentAudioTracks.filter { track ->
            preferredAudioLanguage?.let { matchesLanguage("${track.language} ${track.name}", it) } ?: true
        }.ifEmpty { currentAudioTracks }
        val preferred = manual ?: matching.firstOrNull { it.isAudioDescription == preferAudioDescription }
            ?: matching.firstOrNull() ?: return
        if (player.audioTrack != preferred.index) player.audioTrack = preferred.index
    }

    private fun selectSubtitleTrackByLanguage(langCode: String): Boolean {
        val player = mediaPlayer ?: return false
        updateTrackInfo()
        for (track in currentSubtitleTracks) {
            if (matchesLanguage("${track.language} ${track.name}", langCode)) {
                selectSubtitleTrackOnPlayer(player, track.index)
                updateTrackInfo()
                return true
            }
        }
        return false
    }

    /**
     * LibVLC applies the elementary-stream switch asynchronously. Set the track immediately so
     * state observers see the user's choice, then give the native decoder one redraw opportunity
     * for an already-running cue. The initial selection is left to the normal decoder startup;
     * forcing a seek before the first VOUT frame can suppress the first subtitle on some builds.
     */
    private fun selectSubtitleTrackOnPlayer(player: MediaPlayer, trackId: Int) {
        val changed = player.spuTrack != trackId
        if (!changed) return
        subtitleRefreshJob?.cancel()
        subtitleRefreshJob = null
        player.spuTrack = trackId
        if (!firstFrameRendered || !playWhenReady || subtitleDecoderReloading) return
        val generation = playbackGeneration
        subtitleRefreshJob = scope?.launch {
            // Let the elementary-stream switch settle before re-opening the media. LibVLC 3 can
            // keep the previous embedded subtitle decoder alive after a runtime SPU switch, so
            // a same-request media reload is the only reliable way to render the new cue.
            delay(120L)
            if (mediaPlayer !== player || playbackGeneration != generation ||
                player.spuTrack != trackId || !playWhenReady
            ) return@launch
            val url = activePlaybackUrl ?: return@launch
            val currentTime = player.time.coerceAtLeast(0L)
            subtitleDecoderReloading = true
            startPlaybackInternal(url, currentTime)
            subtitleRefreshJob = null
        }
    }

    private fun scheduleReloadedSubtitleActivation(player: MediaPlayer?) {
        val target = player ?: run {
            subtitleDecoderReloading = false
            return
        }
        val generation = playbackGeneration
        subtitleRefreshJob?.cancel()
        subtitleRefreshJob = scope?.launch {
            // The first Playing callback can precede LibVLC's subtitle decoder attachment. A
            // second, delayed elementary-stream assignment makes the selected cue deterministic
            // after a same-request language refresh.
            delay(250L)
            if (mediaPlayer !== target || playbackGeneration != generation || !playWhenReady) {
                subtitleDecoderReloading = false
                return@launch
            }
            val selected = target.spuTrack
            if (selected >= 0) {
                target.spuTrack = -1
                delay(120L)
                if (mediaPlayer !== target || playbackGeneration != generation || !playWhenReady) {
                    subtitleDecoderReloading = false
                    return@launch
                }
                target.spuTrack = selected
                delay(120L)
                val currentTime = target.time
                if (currentTime > 0L && target.length > 0L) {
                    target.time = currentTime
                }
            }
            subtitleDecoderReloading = false
            subtitleRefreshJob = null
        }
    }

    private fun matchesLanguage(trackName: String, langCode: String): Boolean {
        val normalizedName = trackName.lowercase()
        val normalizedCode = langCode.lowercase()
        if (normalizedName.split(Regex("[^\\p{L}]+" )).any { it == normalizedCode }) return true
        return when (normalizedCode) {
            "tur", "tr", "turkish" ->
                normalizedName.contains("tur") || normalizedName.contains("turkish") || normalizedName.contains("türk")

            "eng", "en", "english" ->
                normalizedName.contains("eng") || normalizedName.contains("english")

            "ara", "ar", "arabic" ->
                normalizedName.contains("ara") || normalizedName.contains("arabic") || normalizedName.contains("عرب")

            "deu", "de", "german" ->
                normalizedName.contains("deu") || normalizedName.contains("ger") || normalizedName.contains("german")

            "fra", "fr", "french" ->
                normalizedName.contains("fra") || normalizedName.contains("fre") || normalizedName.contains("french")

            "spa", "es", "spanish" ->
                normalizedName.contains("spa") || normalizedName.contains("spanish")

            else -> normalizedName.contains(normalizedCode)
        }
    }

    private fun applyAspectRatioMode() {
        val player = mediaPlayer ?: return
        val surfaceView = currentSurfaceView
        val surfaceWidth = surfaceView?.width?.takeIf { it > 0 }
            ?: surfaceView?.holder?.surfaceFrame?.width()?.takeIf { it > 0 }
            ?: 0
        val surfaceHeight = surfaceView?.height?.takeIf { it > 0 }
            ?: surfaceView?.holder?.surfaceFrame?.height()?.takeIf { it > 0 }
            ?: 0

        when (currentAspectMode) {
            AspectRatioMode.AUTO,
            AspectRatioMode.FIT -> {
                player.aspectRatio = null
                player.scale = 0f
            }

            AspectRatioMode.FILL -> {
                player.aspectRatio = null
                player.scale = calculateFillScale(surfaceWidth, surfaceHeight) ?: 0f
            }

            AspectRatioMode.ZOOM -> {
                player.aspectRatio = null
                player.scale = calculateFillScale(surfaceWidth, surfaceHeight)?.times(1.1f) ?: 1.2f
            }

            AspectRatioMode.STRETCH -> {
                if (surfaceWidth > 0 && surfaceHeight > 0) {
                    player.aspectRatio = "${surfaceWidth}:${surfaceHeight}"
                    player.scale = 0f
                } else {
                    player.aspectRatio = null
                    player.scale = 0f
                }
            }

            AspectRatioMode.ORIGINAL -> {
                player.aspectRatio = null
                player.scale = 1f
            }

            AspectRatioMode.FORCE_16_9 -> {
                player.aspectRatio = "16:9"
                player.scale = 0f
            }

            AspectRatioMode.FORCE_4_3 -> {
                player.aspectRatio = "4:3"
                player.scale = 0f
            }
        }
    }

    private fun calculateFillScale(surfaceWidth: Int, surfaceHeight: Int): Float? {
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || currentVideoWidth <= 0 || currentVideoHeight <= 0) {
            return null
        }
        val widthScale = surfaceWidth.toFloat() / currentVideoWidth.toFloat()
        val heightScale = surfaceHeight.toFloat() / currentVideoHeight.toFloat()
        return maxOf(widthScale, heightScale)
    }

    private fun startProgressTracking() {
        stopProgressTracking()
        progressJob = scope?.launch {
            while (isActive) {
                applyPendingInitialSeek()
                // Live inputs may announce Vout before the selected track has dimensions.
                // Retry until metadata arrives so the GL surface can leave MATCH_PARENT.
                if (currentVideoWidth <= 0 || currentVideoHeight <= 0) {
                    updateVideoTrackInfo()
                }
                publishState()
                delay(500L)
            }
        }
    }

    private fun updateVideoTrackInfo() {
        val track = mediaPlayer?.currentVideoTrack ?: return
        // A transient empty native snapshot must not expand an already sized surface.
        if (track.width <= 0 || track.height <= 0) return
        val sizeChanged = currentVideoWidth != track.width || currentVideoHeight != track.height
        currentVideoWidth = track.width
        currentVideoHeight = track.height
        currentVideoResolution = "${track.width}x${track.height}"
        currentVideoCodec = track.codec.orEmpty()
        currentVideoBitrate = track.bitrate.takeIf { it > 0 }?.let { "${it / 1_000} kbps" }.orEmpty()
        currentVideoFps = if (track.frameRateNum > 0 && track.frameRateDen > 0) {
            formatFrameRate(track.frameRateNum.toDouble() / track.frameRateDen.toDouble())
        } else {
            ""
        }
        hasVideoTrack = true
        if (sizeChanged) applyAspectRatioMode()
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    private companion object {
        const val DUCK_VOLUME_MULTIPLIER = 0.2f
        const val PLAYBACK_CONFIRMATION_PROGRESS_MS = 750L
    }

    private fun publishState() {
        val player = mediaPlayer
        val currentPosition = player?.time?.coerceAtLeast(0L) ?: 0L
        val duration = player?.length?.takeIf { it > 0L } ?: 0L
        val isPlaying = player?.isPlaying == true && playbackState == PlaybackState.PLAYING
        if (player != null && !hasVideoTrack) {
            hasVideoTrack = runCatching { player.videoTracksCount > 0 }.getOrDefault(false)
        }
        updatePlaybackProgressConfirmation(currentPosition, playbackState)
        val estimatedBufferedPosition = when {
            playbackState == PlaybackState.BUFFERING || playbackState == PlaybackState.PLAYING ->
                currentPosition + configuredBufferMs.coerceAtLeast(1_500L)

            else -> currentPosition
        }
        val confirmed = when {
            playbackState != PlaybackState.PLAYING -> false
            hasVideoTrack -> surfaceReady &&
                (firstFrameRendered || playbackProgressConfirmed) &&
                (playbackProgressConfirmed || (currentVideoWidth > 0 && currentVideoHeight > 0))

            hasAudioTrack -> currentPosition >= 250L
            else -> false
        }
        val networkSpeedKbps = if (
            playbackState == PlaybackState.PLAYING || playbackState == PlaybackState.BUFFERING
        ) {
            networkThroughputSampler.sample()
        } else {
            networkThroughputSampler.reset()
            0L
        }

        _state.value = PlayerState(
            requestId = activeRequestId,
            playbackState = playbackState,
            isPlaying = isPlaying,
            currentPosition = currentPosition,
            duration = duration,
            bufferedPosition = estimatedBufferedPosition,
            isSeekable = isCurrentMediaSeekable(player),
            playbackSpeed = currentPlaybackSpeed,
            audioTracks = currentAudioTracks,
            subtitleTracks = currentSubtitleTracks,
            selectedAudioTrack = selectedAudioTrack,
            selectedSubtitleTrack = selectedSubtitleTrack,
            videoWidth = currentVideoWidth,
            videoHeight = currentVideoHeight,
            errorMessage = currentErrorMessage,
            aspectRatioMode = currentAspectMode,
            availableQualities = emptyList(),
            videoQualityMode = currentVideoQualityMode,
            currentVideoResolution = currentVideoResolution,
            currentVideoBitrate = currentVideoBitrate,
            currentVideoCodec = currentVideoCodec,
            currentVideoFps = currentVideoFps,
            networkSpeedKbps = networkSpeedKbps,
            isAdaptiveStream = false,
            hasVideoTrack = hasVideoTrack,
            hasAudioTrack = hasAudioTrack,
            isSurfaceReady = surfaceReady,
            hasRenderedFirstFrame = firstFrameRendered,
            isPlaybackConfirmed = confirmed,
            audioSessionId = if (hasAudioTrack && playbackState == PlaybackState.PLAYING) 1 else 0
        )
    }

    private fun isCurrentMediaSeekable(player: MediaPlayer?): Boolean {
        return player != null && player.length > 0L && runCatching { player.isSeekable }
            .getOrDefault(false)
    }

    private fun updatePlaybackProgressConfirmation(positionMs: Long, state: PlaybackState) {
        if (state != PlaybackState.PLAYING) {
            playbackConfirmationBaselinePositionMs = Long.MIN_VALUE
            playbackProgressConfirmed = false
            return
        }
        val baseline = playbackConfirmationBaselinePositionMs
        if (baseline == Long.MIN_VALUE) {
            playbackConfirmationBaselinePositionMs = positionMs
            return
        }
        if (!playbackProgressConfirmed && positionMs >= baseline + PLAYBACK_CONFIRMATION_PROGRESS_MS) {
            playbackProgressConfirmed = true
            Timber.d(
                "VLC playback progress confirmed: baseline=%d current=%d video=%s firstFrame=%s",
                baseline,
                positionMs,
                hasVideoTrack,
                firstFrameRendered
            )
        }
    }

    private fun applyPendingInitialSeek() {
        val requested = pendingInitialSeekPosition ?: return
        val player = mediaPlayer ?: return
        if (!playWhenReady || playbackState != PlaybackState.PLAYING) return
        val target = resolveSeekPosition(requested, player.length, isCurrentMediaSeekable(player)) ?: return
        // Consume once, only when the native input can seek. A fixed startup delay can expire
        // before HTTP preparation and silently lose the saved VOD position.
        rememberPendingSeek(target)
        player.time = target
    }

    private fun rememberPendingSeek(position: Long) {
        pendingInitialSeekPosition = null
        pendingSeekPosition = position
        pendingSeekElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun currentVlcSeekBase(nativePosition: Long): Long {
        val ageMs = SystemClock.elapsedRealtime() - pendingSeekElapsedMs
        return vlcSeekBasePosition(
            nativePosition = nativePosition,
            pendingPosition = pendingSeekPosition,
            pendingAgeMs = ageMs
        ).also { resolved ->
            if (resolved == nativePosition && pendingSeekPosition != null) {
                pendingSeekPosition = null
                pendingSeekElapsedMs = 0L
            }
        }
    }
}

internal fun vlcSeekBasePosition(
    nativePosition: Long,
    pendingPosition: Long?,
    pendingAgeMs: Long,
    pendingTimeoutMs: Long = 3_000L
): Long = pendingPosition
    ?.takeIf { pendingAgeMs in 0L..pendingTimeoutMs }
    ?: nativePosition

internal fun resolveSelectableTrackId(
    selectableTrackIds: List<Int>,
    requestedTrackId: Int
): Int? = requestedTrackId.takeIf { it >= 0 && it in selectableTrackIds }

private fun String.hasHearingImpairedMarker(): Boolean {
    val normalized = lowercase()
    return normalized.contains("hearing impaired") ||
        normalized.contains("sdh") ||
        normalized.contains("hoh") ||
        Regex("(^|[^a-z])cc([^a-z]|$)").containsMatchIn(normalized)
}

internal fun String.hasAudioDescriptionMarker(): Boolean {
    val normalized = lowercase(java.util.Locale.ROOT)
    return listOf("audio description", "descriptive audio", "visually impaired", "sesli betimleme")
        .any(normalized::contains)
}

internal data class VlcMediaTrackMetadata(
    val id: Int,
    val description: String?,
    val language: String?,
    val codec: String?,
    val channelCount: Int = 0
)

/** MediaPlayer.media returns a retained reference, including when metadata is incomplete. */
internal fun readRetainedMediaTracks(media: IMedia?): Map<Int, VlcMediaTrackMetadata> {
    if (media == null) return emptyMap()
    return try {
        buildMap {
            for (index in 0 until media.trackCount) {
                media.getTrack(index)?.let { track ->
                    put(
                        track.id,
                        VlcMediaTrackMetadata(
                            id = track.id,
                            description = track.description,
                            language = track.language,
                            codec = track.codec,
                            channelCount = (track as? IMedia.AudioTrack)?.channels ?: 0
                        )
                    )
                }
            }
        }
    } finally {
        media.release()
    }
}

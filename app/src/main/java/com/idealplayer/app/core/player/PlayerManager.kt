package com.idealplayer.app.core.player

import android.os.SystemClock
import com.idealplayer.app.core.datastore.SettingsDataStore
import com.idealplayer.app.core.model.PlayerEngineType
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class PlaybackRecoveryState(
    val isRecovering: Boolean = false,
    val automaticFallbackUsed: Boolean = false,
    val fromEngine: String = "",
    val toEngine: String = "",
    val lastErrorCategory: String = ""
)

data class PlaybackDiagnostics(
    val engineName: String = "—",
    val streamProtocol: String = "unknown",
    val streamHost: String = "unknown",
    val requestHeaderNames: Set<String> = emptySet(),
    val recovery: PlaybackRecoveryState = PlaybackRecoveryState(),
    val metrics: PlaybackMetricsSnapshot = PlaybackMetricsSnapshot()
)

@Singleton
class PlayerManager @Inject constructor(
    private val exoPlayerProvider: Provider<ExoPlayerEngine>,
    private val vlcPlayerProvider: Provider<VlcPlayerEngine>,
    private val settingsDataStore: SettingsDataStore,
    private val systemCaptionPreferences: SystemCaptionPreferences
) {
    private data class PlaybackRequest(
        val id: Long,
        val url: String,
        val startPosition: Long,
        val profile: PlaybackProfile
    )

    private val managerScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val switchMutex = Mutex() // Prevents concurrent engine switching

    private var currentEngine: PlayerEngine? = null
    private var stateCollectionJob: Job? = null
    private var captionCollectionJob: Job? = null
    private var lastPlaybackRequest: PlaybackRequest? = null
    private var currentPlaybackProfileKey: String? = null
    private var automaticFallbackAttempted = false
    private var profilePersistedForRequest = false
    private var hasConfirmedCurrentRequest = false
    private val playbackRequestSequence = AtomicLong(0L)
    private var playbackOwner: Any? = null
    private data class Handoff(
        val requestId: Long,
        val from: Any,
        val destination: Any? = null,
        val expiresAt: Long = SystemClock.elapsedRealtime() + 5_000L,
        var sourceHidden: Boolean = false
    )
    private var handoff: Handoff? = null
    private var returnOwner: Any? = null
    private var returnUrl: String? = null

    fun prepareSeamlessHandoff(owner: Any? = null): Boolean {
        val from = owner ?: return false
        val request = lastPlaybackRequest ?: return false
        if (!acceptsOwner(from) || currentEngine == null || request.profile != PlaybackProfile.LIVE) return false
        val transfer = Handoff(request.id, from)
        handoff = transfer
        expireUnclaimedHandoff(transfer)
        return true
    }

    fun prepareReturnHandoff(owner: Any): Boolean {
        val destination = returnOwner ?: return false
        val request = lastPlaybackRequest ?: return false
        if (!acceptsOwner(owner) || request.profile != PlaybackProfile.LIVE || request.url != returnUrl) return false
        val transfer = Handoff(request.id, owner, destination)
        handoff = transfer
        expireUnclaimedHandoff(transfer)
        returnOwner = null
        returnUrl = null
        return true
    }

    private fun expireUnclaimedHandoff(transfer: Handoff) {
        managerScope.launch {
            kotlinx.coroutines.delay(5_000L)
            if (handoff === transfer && playbackOwner === transfer.from) {
                handoff = null
                if (transfer.sourceHidden) release(transfer.from)
            }
        }
    }

    fun markHandoffSourceHidden(owner: Any) {
        handoff?.takeIf { it.from === owner }?.sourceHidden = true
    }

    private fun validHandoff(): Handoff? = handoff?.takeIf {
        it.expiresAt >= SystemClock.elapsedRealtime() &&
            it.requestId == lastPlaybackRequest?.id && currentEngine != null &&
            _playerState.value.playbackState !in setOf(PlaybackState.IDLE, PlaybackState.ERROR, PlaybackState.STOPPED)
    }

    fun isHandoffPendingFrom(owner: Any): Boolean = validHandoff()?.from === owner

    fun canResumeHandoff(url: String, profile: PlaybackProfile, owner: Any): Boolean =
        acceptsOwner(owner) && validHandoff() != null &&
            lastPlaybackRequest?.let { it.url == url && it.profile == profile } == true

    fun claimOwnership(owner: Any) {
        if (playbackOwner === owner) return
        val transfer = validHandoff()
        playbackOwner = owner
        if (transfer != null && (transfer.destination == null || transfer.destination === owner)) {
            if (transfer.destination == null) {
                returnOwner = transfer.from
                returnUrl = lastPlaybackRequest?.url
            }
            handoff = transfer.copy(destination = owner)
            return
        }
        handoff = null
        returnOwner = null
        returnUrl = null
        playbackRequestSequence.incrementAndGet()
        currentEngine?.stop()
        lastPlaybackRequest = null
        _playerState.value = PlayerState()
        _sessionState.value = PlaybackSessionState.Idle
    }

    fun isOwner(owner: Any): Boolean = playbackOwner === owner
    private fun acceptsOwner(owner: Any?): Boolean = owner == null || isOwner(owner)
    private val metricsTracker = PlaybackMetricsTracker(SystemClock::elapsedRealtime)

    private val _playerState = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _activeEngineName = MutableStateFlow<String?>(null)
    val activeEngineName: StateFlow<String?> = _activeEngineName.asStateFlow()

    private val _switchState = MutableStateFlow(EngineSwitchState.IDLE)
    val switchState: StateFlow<EngineSwitchState> = _switchState.asStateFlow()

    private val _recoveryState = MutableStateFlow(PlaybackRecoveryState())
    val recoveryState: StateFlow<PlaybackRecoveryState> = _recoveryState.asStateFlow()

    private val _diagnostics = MutableStateFlow(PlaybackDiagnostics())
    val diagnostics: StateFlow<PlaybackDiagnostics> = _diagnostics.asStateFlow()

    private val _sessionState = MutableStateFlow<PlaybackSessionState>(PlaybackSessionState.Idle)
    val sessionState: StateFlow<PlaybackSessionState> = _sessionState.asStateFlow()

    private val _metrics = MutableStateFlow(PlaybackMetricsSnapshot())
    val metrics: StateFlow<PlaybackMetricsSnapshot> = _metrics.asStateFlow()

    private var defaultAudioLang: String = "eng"
    private var defaultSubtitleLang: String = "eng"
    private var autoEnableSubtitles: Boolean = false
    private var systemCaptionPreference = defaultSystemCaptionPreferences()
    private var hasManualSubtitleSelection = false
    private var hasManualAudioSelection = false
    private var cachedSubtitleAppearance = SubtitleAppearance()
    private var preferredAspectRatio: AspectRatioMode = AspectRatioMode.FIT
    private var preferredVideoQualityMode: VideoQualityMode = VideoQualityMode.AUTO
    private var defaultPlaybackSpeed: Float = 1f

    private var cachedBufferMs: Long = 30000L
    private var cachedLatencyMode: String = "BALANCED"
    private var cachedPreferHw: Boolean = true
    private var cachedAllowQualityFallback: Boolean = true
    private var cachedAutoPlayerFallback: Boolean = true

    fun getEngine(): PlayerEngine? = currentEngine
    fun getActiveEngineType(): PlayerEngineType? = activeEngineTypeFor(currentEngine)

    fun reportPlaybackFailure(failure: PlaybackFailure, owner: Any? = null) {
        if (!acceptsOwner(owner)) return
        val requestId = playbackRequestSequence.incrementAndGet()
        lastPlaybackRequest = null
        _playerState.value = PlayerState(
            playbackState = PlaybackState.ERROR,
            errorMessage = "Content is unavailable",
            aspectRatioMode = preferredAspectRatio,
            videoQualityMode = preferredVideoQualityMode,
            playbackSpeed = defaultPlaybackSpeed
        )
        _sessionState.value = PlaybackSessionState.Failed(requestId, failure)
        _metrics.value = PlaybackMetricsSnapshot(
            requestId = requestId,
            engineName = currentEngine?.engineName.orEmpty(),
            failureCategory = failure.category
        )
        updateDiagnostics()
    }

    suspend fun initializeWithSettings(owner: Any? = null) {
        if (!acceptsOwner(owner)) return
        if (validHandoff()?.destination === owner && owner != null) return
        // A player route owns a new playback session. Invalidate any release queued by the
        // previous route before touching the shared singleton engine.
        val sessionRequestId = playbackRequestSequence.incrementAndGet()
        val settings = refreshSettingsCache()
        switchEngineInternal(
            targetType = PlayerEngineType.fromStoredValue(settings.playerEngine),
            replayRequest = false,
            expectedRequestId = sessionRequestId
        )
    }

    suspend fun updatePreferredEngine(targetType: PlayerEngineType) {
        settingsDataStore.updatePlayerEngine(targetType.name)
        if (activeEngineTypeFor(currentEngine) == targetType) {
            _activeEngineName.value = currentEngine?.engineName
            _switchState.value = EngineSwitchState.SUCCESS
            return
        }

        if (currentEngine != null) {
            switchEngine(targetType)
        }
    }

    private fun startCollectingState() {
        stateCollectionJob?.cancel()
        val engine = currentEngine ?: return
        stateCollectionJob = managerScope.launch {
            engine.state.collect { engineState ->
                if (engine !== currentEngine) return@collect
                val requestId = lastPlaybackRequest?.id
                if (requestId == null || requestId != playbackRequestSequence.get() || engineState.requestId != requestId) return@collect
                val hadConfirmedBeforeUpdate = hasConfirmedCurrentRequest
                if (engineState.isPlaybackConfirmed) hasConfirmedCurrentRequest = true

                if (shouldAttemptAutomaticFallback(
                        enabled = cachedAutoPlayerFallback,
                        activeEngineType = activeEngineTypeFor(engine),
                        state = engineState,
                        hasPreviouslyConfirmed = hasConfirmedCurrentRequest,
                        alreadyAttempted = automaticFallbackAttempted,
                        hasPlaybackRequest = lastPlaybackRequest != null
                    )
                ) {
                    automaticFallbackAttempted = true
                    val failure = classifyPlaybackFailure(engineState.errorMessage, engineState)
                    val errorCategory = failure.category
                    _recoveryState.value = PlaybackRecoveryState(
                        isRecovering = true,
                        automaticFallbackUsed = true,
                        fromEngine = engine.engineName,
                        toEngine = PlayerEngineType.VLC.name,
                        lastErrorCategory = errorCategory
                    )
                    _playerState.value = engineState.copy(
                        playbackState = PlaybackState.BUFFERING,
                        isPlaying = false,
                        errorMessage = null
                    )
                    if (requestId != null) {
                        _sessionState.value = PlaybackSessionState.Recovering(
                            requestId = requestId,
                            fromEngine = engine.engineName,
                            toEngine = PlayerEngineType.VLC.name,
                            failure = failure
                        )
                        _metrics.value = metricsTracker.markFallback(engine.engineName, failure)
                    }
                    updateDiagnostics(engine.engineName)
                    val failedRequestId = lastPlaybackRequest?.id ?: return@collect
                    managerScope.launch {
                        Timber.w("Media3 playback failed before confirmation; attempting VLC recovery (%s)", errorCategory)
                        switchEngineInternal(
                            targetType = PlayerEngineType.VLC,
                            replayRequest = true,
                            expectedRequestId = failedRequestId
                        )
                    }
                    return@collect
                }

                _playerState.value = engineState
                if (requestId != null) {
                    _sessionState.value = resolvePlaybackSessionState(
                        requestId = requestId,
                        state = engineState,
                        recovery = _recoveryState.value,
                        hadConfirmedPlayback = hasConfirmedCurrentRequest
                    )
                    _metrics.value = metricsTracker.observe(
                        requestId = requestId,
                        state = engineState,
                        engineName = engine.engineName,
                        hadConfirmedPlayback = hadConfirmedBeforeUpdate
                    )
                }
                if (engineState.isPlaybackConfirmed) {
                    val currentType = activeEngineTypeFor(engine)
                    val profileKey = currentPlaybackProfileKey
                    if (
                        _recoveryState.value.automaticFallbackUsed &&
                        currentType == PlayerEngineType.VLC &&
                        profileKey != null &&
                        !profilePersistedForRequest
                    ) {
                        profilePersistedForRequest = true
                        managerScope.launch(Dispatchers.IO) {
                            settingsDataStore.updatePlaybackEngineProfile(profileKey, PlayerEngineType.VLC.name)
                        }
                    }
                    if (_recoveryState.value.isRecovering) {
                        _recoveryState.value = _recoveryState.value.copy(isRecovering = false)
                    }
                } else if (
                    engineState.playbackState == PlaybackState.ERROR &&
                    _recoveryState.value.isRecovering
                ) {
                    _recoveryState.value = _recoveryState.value.copy(
                        isRecovering = false,
                        lastErrorCategory = classifyPlaybackError(engineState.errorMessage)
                    )
                }
                updateDiagnostics(engine.engineName)
            }
        }
    }

    private fun startObservingSystemCaptions() {
        if (captionCollectionJob?.isActive == true) return
        captionCollectionJob = managerScope.launch {
            systemCaptionPreferences.changes().collect { preference ->
                var rebuildForRequest: Long? = null
                // Serialize with engine replacement/release; a system callback must never use an
                // engine whose native resources are being torn down on another dispatcher.
                switchMutex.withLock {
                    val selectionChanged = preference.enabled != systemCaptionPreference.enabled ||
                        preference.language != systemCaptionPreference.language
                    val audioDescriptionChanged = preference.audioDescriptionRequested !=
                        systemCaptionPreference.audioDescriptionRequested
                    systemCaptionPreference = preference
                    val followsSystem = defaultSubtitleLang.equals("system", ignoreCase = true)
                    if (followsSystem) cachedSubtitleAppearance = preference.appearance()
                    if (selectionChanged && followsSystem &&
                        !hasManualSubtitleSelection
                    ) {
                        currentEngine?.let(::applyPreferredSubtitleTrack)
                    }
                    if (audioDescriptionChanged && !hasManualAudioSelection) {
                        currentEngine?.let(::applyPreferredAudioTrack)
                    }
                    if (followsSystem && lastPlaybackRequest != null &&
                        _playerState.value.playbackState != PlaybackState.PAUSED &&
                        (currentEngine as? VlcPlayerEngine)
                            ?.requiresSubtitleAppearanceRebuild(cachedSubtitleAppearance) == true
                    ) rebuildForRequest = playbackRequestSequence.get()
                }
                // LibVLC 3 has no runtime freetype-style setter. Use the existing serialized
                // replacement path, preserving request, VOD position, pause and manual choices.
                rebuildForRequest?.let { requestId ->
                    managerScope.launch {
                        switchEngineInternal(PlayerEngineType.VLC, replayRequest = true,
                            expectedRequestId = requestId, captionAppearanceRebuild = true)
                    }
                }
            }
        }
    }

    /**
     * Safely switches the playback engine.
     * Guarantees old engine is released before the new one is instantiated.
     */
    suspend fun switchEngine(targetType: PlayerEngineType) {
        switchEngineInternal(targetType, replayRequest = true, expectedRequestId = playbackRequestSequence.get())
    }

    private suspend fun switchEngineInternal(
        targetType: PlayerEngineType,
        replayRequest: Boolean,
        expectedRequestId: Long? = null,
        captionAppearanceRebuild: Boolean = false,
        resumeAfterCaptionRebuild: Boolean = false
    ) {
        switchMutex.withLock {
            if (expectedRequestId != null && expectedRequestId != playbackRequestSequence.get()) {
                Timber.d("Skipping stale playback pipeline request=%d", expectedRequestId)
                return@withLock
            }
            if (captionAppearanceRebuild && (currentEngine as? VlcPlayerEngine)
                    ?.requiresSubtitleAppearanceRebuild(cachedSubtitleAppearance) != true
            ) return@withLock
            // A queued style event must not restart a session the user has since paused.
            if (captionAppearanceRebuild && !resumeAfterCaptionRebuild &&
                _playerState.value.playbackState == PlaybackState.PAUSED
            ) return@withLock
            if (expectedRequestId != null && !replayRequest) {
                // A new route must never replay the previous route's last stream while its own
                // request is being prepared.
                lastPlaybackRequest = null
                currentPlaybackProfileKey = null
                automaticFallbackAttempted = false
                profilePersistedForRequest = false
                hasConfirmedCurrentRequest = false
            }
            _switchState.value = EngineSwitchState.SWITCHING
            var engineBeingPrepared: PlayerEngine? = null

            try {
                val previousState = _playerState.value
                val pendingPlaybackRequest = lastPlaybackRequest?.takeIf {
                    replayRequest &&
                    shouldReplayAfterEngineSwitch(previousState.playbackState)
                }?.let { request ->
                    request.copy(
                        startPosition = engineSwitchStartPosition(
                            profile = request.profile,
                            currentPosition = previousState.currentPosition.takeIf { it > 0L } ?: request.startPosition
                        )
                    )
                }

                // 1. Stop collecting state
                stateCollectionJob?.cancel()
                stateCollectionJob = null

                // 2. Safely release current engine completely
                currentEngine?.let { oldEngine ->
                    safelyReleaseEngine(oldEngine)
                }
                currentEngine = null
                _activeEngineName.value = null
                _playerState.value = PlayerState(
                    aspectRatioMode = preferredAspectRatio,
                    videoQualityMode = preferredVideoQualityMode,
                    playbackSpeed = defaultPlaybackSpeed
                )

                // 3. Create new engine via Provider (Lazy Init)
                val newEngine = when (targetType) {
                    PlayerEngineType.EXOPLAYER -> exoPlayerProvider.get()
                    PlayerEngineType.VLC -> vlcPlayerProvider.get()
                }

                // Verify availability, fallback if needed
                val finalEngine = if (!newEngine.isAvailable() && targetType == PlayerEngineType.EXOPLAYER) {
                    Timber.w("Requested EXOPLAYER but it's unavailable. Falling back to VLC.")
                    vlcPlayerProvider.get()
                } else {
                    newEngine
                }
                engineBeingPrepared = finalEngine

                // 4. Initialize new engine
                withContext(Dispatchers.Main.immediate) {
                    (finalEngine as? VlcPlayerEngine)?.setSubtitleAppearance(cachedSubtitleAppearance)
                    finalEngine.initialize()
                    applyCachedSettingsToEngine(finalEngine)
                }
                if (finalEngine.state.value.playbackState == PlaybackState.ERROR) {
                    throw IllegalStateException(
                        finalEngine.state.value.errorMessage ?: "Playback engine initialization failed"
                    )
                }

                currentEngine = finalEngine
                _activeEngineName.value = finalEngine.engineName
                updateDiagnostics(finalEngine.engineName)
                startCollectingState()
                startObservingSystemCaptions()

                pendingPlaybackRequest
                    ?.takeIf { request ->
                        request.id == playbackRequestSequence.get() &&
                            lastPlaybackRequest?.id == request.id
                    }
                    ?.let { request ->
                        withContext(Dispatchers.Main.immediate) {
                            finalEngine.play(
                                url = request.url,
                                startPosition = request.startPosition,
                                profile = request.profile,
                                requestId = request.id
                            )
                            if (previousState.playbackState == PlaybackState.PAUSED &&
                                !resumeAfterCaptionRebuild
                            ) finalEngine.pause()
                        }
                    }

                engineBeingPrepared = null
                _switchState.value = EngineSwitchState.SUCCESS
                Timber.d("Successfully switched to engine: ${finalEngine.engineName}")

            } catch (cancellation: CancellationException) {
                withContext(NonCancellable) {
                    stateCollectionJob?.cancel()
                    stateCollectionJob = null
                    captionCollectionJob?.cancel()
                    captionCollectionJob = null
                    (currentEngine ?: engineBeingPrepared)?.let { safelyReleaseEngine(it) }
                    currentEngine = null
                    _activeEngineName.value = null
                    _switchState.value = EngineSwitchState.IDLE
                }
                throw cancellation
            } catch (e: Exception) {
                Timber.e("Failed to switch engine: %s", e.javaClass.simpleName)
                stateCollectionJob?.cancel()
                stateCollectionJob = null
                captionCollectionJob?.cancel()
                captionCollectionJob = null
                (currentEngine ?: engineBeingPrepared)?.let { safelyReleaseEngine(it) }
                currentEngine = null
                _activeEngineName.value = null
                _switchState.value = EngineSwitchState.ERROR
                _recoveryState.value = _recoveryState.value.copy(isRecovering = false)
                _playerState.value = PlayerState(
                    playbackState = PlaybackState.ERROR,
                    errorMessage = "Playback engine switch failed",
                    aspectRatioMode = preferredAspectRatio,
                    videoQualityMode = preferredVideoQualityMode,
                    playbackSpeed = defaultPlaybackSpeed
                )
                (expectedRequestId ?: lastPlaybackRequest?.id)?.let { requestId ->
                    _sessionState.value = PlaybackSessionState.Failed(
                        requestId = requestId,
                        failure = PlaybackFailure.Unknown
                    )
                }
                updateDiagnostics()
            }
        }
    }

    suspend fun play(
        url: String,
        startPosition: Long = 0L,
        profile: PlaybackProfile = PlaybackProfile.VOD,
        owner: Any? = null
    ) {
        if (!acceptsOwner(owner)) return
        if (owner != null && startPosition == 0L && canResumeHandoff(url, profile, owner)) {
            handoff = null
            return
        }
        handoff = null
        val requestId = playbackRequestSequence.incrementAndGet()
        val settings = refreshSettingsCache()
        val profileKey = playbackProfileKey(url)
        val rememberedEngine = settingsDataStore.getPlaybackEngineProfile(profileKey)
            ?.let(PlayerEngineType::fromStoredValue)
        val preferredEngine = rememberedEngine
            ?: PlayerEngineType.fromStoredValue(settings.playerEngine)
        if (requestId != playbackRequestSequence.get()) return

        val source = parsePlaybackSource(url)
        val (protocol, host) = playbackSourceSummary(url)
        val request = PlaybackRequest(
            id = requestId,
            url = url,
            startPosition = startPosition,
            profile = profile
        )
        _sessionState.value = PlaybackSessionState.Preparing(requestId)
        _metrics.value = metricsTracker.begin(requestId, profile)

        var engineSwitchAttempted = false
        while (requestId == playbackRequestSequence.get()) {
            val shouldSwitchEngine = switchMutex.withLock {
                if (requestId != playbackRequestSequence.get()) {
                    return@withLock false
                }

                val engine = currentEngine
                if (shouldPreparePlaybackEngine(
                        activeEngineType = activeEngineTypeFor(engine),
                        preferredEngineType = preferredEngine,
                        engineSwitchAttempted = engineSwitchAttempted
                    ) || (engine as? VlcPlayerEngine)?.requiresSubtitleAppearanceRebuild(cachedSubtitleAppearance) == true
                ) {
                    return@withLock true
                }

                currentPlaybackProfileKey = profileKey
                automaticFallbackAttempted = false
                profilePersistedForRequest = false
                hasConfirmedCurrentRequest = false
                _recoveryState.value = PlaybackRecoveryState()
                _diagnostics.value = PlaybackDiagnostics(
                    engineName = engine?.engineName ?: "—",
                    streamProtocol = protocol,
                    streamHost = host,
                    requestHeaderNames = source.headers.keys,
                    recovery = _recoveryState.value,
                    metrics = _metrics.value
                )
                lastPlaybackRequest = request
                hasManualSubtitleSelection = false
                hasManualAudioSelection = false

                withContext(Dispatchers.Main.immediate) {
                    if (requestId != playbackRequestSequence.get()) return@withContext

                    if (engine == null) {
                        _playerState.value = PlayerState(
                            playbackState = PlaybackState.ERROR,
                            errorMessage = "Playback engine is not initialized",
                            aspectRatioMode = preferredAspectRatio,
                            videoQualityMode = preferredVideoQualityMode,
                            playbackSpeed = defaultPlaybackSpeed
                        )
                        _sessionState.value = PlaybackSessionState.Failed(
                            requestId = requestId,
                            failure = PlaybackFailure.Unknown
                        )
                    } else {
                        applyCachedSettingsToEngine(engine)
                        engine.play(url, startPosition, profile, requestId)
                    }
                }
                false
            }

            if (!shouldSwitchEngine) return
            if (requestId != playbackRequestSequence.get()) return
            engineSwitchAttempted = true
            switchEngineInternal(
                targetType = preferredEngine,
                replayRequest = false,
                expectedRequestId = requestId
            )
        }
    }

    suspend fun pause(owner: Any? = null) {
        withContext(Dispatchers.Main.immediate) {
            if (!acceptsOwner(owner)) return@withContext
            currentEngine?.pause()
        }
    }

    suspend fun resume(owner: Any? = null) {
        if (!acceptsOwner(owner)) return
        val requestId = playbackRequestSequence.get()
        if ((currentEngine as? VlcPlayerEngine)?.requiresSubtitleAppearanceRebuild(cachedSubtitleAppearance) == true) {
            // LibVLC applies text style when its native renderer is created. Keep paused media
            // untouched until resume, then reuse the serialized VOD-position-preserving path.
            switchEngineInternal(
                PlayerEngineType.VLC, replayRequest = true, expectedRequestId = requestId,
                captionAppearanceRebuild = true, resumeAfterCaptionRebuild = true
            )
        }
        withContext(Dispatchers.Main.immediate) {
            if (!acceptsOwner(owner) || requestId != playbackRequestSequence.get()) return@withContext
            currentEngine?.resume()
        }
    }

    suspend fun stop(owner: Any? = null) {
        if (!acceptsOwner(owner)) return
        handoff = null
        val stopRequestId = playbackRequestSequence.incrementAndGet()
        switchMutex.withLock {
            if (!acceptsOwner(owner) || stopRequestId != playbackRequestSequence.get()) return@withLock
            lastPlaybackRequest = null
            currentPlaybackProfileKey = null
            withContext(Dispatchers.Main.immediate) {
                currentEngine?.stop()
            }
            _sessionState.value = PlaybackSessionState.Idle
            _playerState.value = currentEngine?.state?.value ?: PlayerState()
            _metrics.value = metricsTracker.reset()
            updateDiagnostics()
        }
    }

    suspend fun seekTo(position: Long, owner: Any? = null) {
        if (!acceptsOwner(owner)) return
        withContext(Dispatchers.Main.immediate) {
            if (!acceptsOwner(owner)) return@withContext
            // The active engine owns the authoritative timeline/seekability state. Manager state
            // collection can be one callback behind when a manifest publishes its duration.
            currentEngine?.seekTo(position)
        }
    }

    suspend fun seekForward(ms: Long, owner: Any? = null) {
        if (!acceptsOwner(owner)) return
        withContext(Dispatchers.Main.immediate) {
            if (!acceptsOwner(owner)) return@withContext
            currentEngine?.seekForward(ms.coerceAtLeast(0L))
        }
    }

    suspend fun seekBackward(ms: Long, owner: Any? = null) {
        if (!acceptsOwner(owner)) return
        withContext(Dispatchers.Main.immediate) {
            if (!acceptsOwner(owner)) return@withContext
            currentEngine?.seekBackward(ms.coerceAtLeast(0L))
        }
    }

    suspend fun setPlaybackSpeed(speed: Float, owner: Any? = null) {
        if (!acceptsOwner(owner)) return
        defaultPlaybackSpeed = speed
        withContext(Dispatchers.Main.immediate) {
            if (!acceptsOwner(owner)) return@withContext
            currentEngine?.setPlaybackSpeed(speed)
        }
    }

    suspend fun selectAudioTrack(index: Int, owner: Any? = null) {
        if (!acceptsOwner(owner)) return
        withContext(Dispatchers.Main.immediate) {
            if (!acceptsOwner(owner)) return@withContext
            if (_playerState.value.audioTracks.any { it.index == index }) {
                hasManualAudioSelection = true
                currentEngine?.selectAudioTrack(index)
            } else {
                Timber.w("Ignoring unavailable audio track id=%d", index)
            }
        }
    }

    suspend fun selectSubtitleTrack(index: Int, owner: Any? = null) {
        if (!acceptsOwner(owner)) return
        withContext(Dispatchers.Main.immediate) {
            if (!acceptsOwner(owner)) return@withContext
            if (_playerState.value.subtitleTracks.any { it.index == index }) {
                hasManualSubtitleSelection = true
                currentEngine?.selectSubtitleTrack(index)
            } else {
                Timber.w("Ignoring unavailable subtitle track id=%d", index)
            }
        }
    }

    suspend fun disableSubtitles(owner: Any? = null) {
        if (!acceptsOwner(owner)) return
        withContext(Dispatchers.Main.immediate) {
            if (!acceptsOwner(owner)) return@withContext
            hasManualSubtitleSelection = true
            currentEngine?.disableSubtitles()
        }
    }

    suspend fun setAspectRatio(mode: AspectRatioMode, owner: Any? = null) {
        if (!acceptsOwner(owner)) return
        preferredAspectRatio = mode
        withContext(Dispatchers.Main.immediate) {
            if (!acceptsOwner(owner)) return@withContext
            currentEngine?.setAspectRatio(mode)
        }
        settingsDataStore.updateAspectRatio(mode.name.lowercase())
    }

    suspend fun setVideoQualityMode(mode: VideoQualityMode, owner: Any? = null) {
        if (!acceptsOwner(owner)) return
        preferredVideoQualityMode = mode
        withContext(Dispatchers.Main.immediate) {
            if (!acceptsOwner(owner)) return@withContext
            currentEngine?.setVideoQualityMode(mode)
        }
        settingsDataStore.updateVideoQualityMode(mode.name)
    }

    suspend fun selectVideoTrack(index: Int, owner: Any? = null) {
        if (!acceptsOwner(owner)) return
        withContext(Dispatchers.Main.immediate) {
            if (!acceptsOwner(owner)) return@withContext
            if (index < 0) {
                currentEngine?.setVideoQualityMode(VideoQualityMode.AUTO)
                return@withContext
            }
            currentEngine?.selectVideoTrack(index)
        }
    }

    suspend fun releaseAndAwait(owner: Any) {
        release(owner)?.join()
    }

    fun release(owner: Any? = null): Job? {
        if (!acceptsOwner(owner)) return null
        handoff = null
        returnOwner = null
        returnUrl = null
        val releaseRequestId = playbackRequestSequence.incrementAndGet()
        return managerScope.launch(Dispatchers.Main.immediate) {
            try {
                switchMutex.withLock {
                    // Navigation may create a new player route while an old route's asynchronous
                    // cleanup is waiting for this mutex. Never let stale cleanup tear down the
                    // newly initialized engine.
                    if (releaseRequestId != playbackRequestSequence.get()) {
                        Timber.d("Skipping stale playback release request=%d", releaseRequestId)
                        return@withLock
                    }
                    withContext(NonCancellable) {
                        stateCollectionJob?.cancel()
                        stateCollectionJob = null
                        captionCollectionJob?.cancel()
                        captionCollectionJob = null
                        lastPlaybackRequest = null
                        currentPlaybackProfileKey = null
                        automaticFallbackAttempted = false
                        profilePersistedForRequest = false
                        hasConfirmedCurrentRequest = false

                        val engineToRelease = currentEngine
                        if (engineToRelease != null) {
                            safelyReleaseEngine(engineToRelease)
                        }

                        currentEngine = null
                        _activeEngineName.value = null
                        _switchState.value = EngineSwitchState.IDLE
                        _recoveryState.value = PlaybackRecoveryState()
                        _diagnostics.value = PlaybackDiagnostics()
                        _sessionState.value = PlaybackSessionState.Idle
                        _metrics.value = metricsTracker.reset()
                        _playerState.value = PlayerState(
                            aspectRatioMode = preferredAspectRatio,
                            videoQualityMode = preferredVideoQualityMode,
                            playbackSpeed = defaultPlaybackSpeed
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                Timber.w("Error releasing playback pipeline: %s", exception.javaClass.simpleName)
            }
        }
    }

    private fun activeEngineTypeFor(engine: PlayerEngine?): PlayerEngineType? {
        return when (engine) {
            is ExoPlayerEngine -> PlayerEngineType.EXOPLAYER
            is VlcPlayerEngine -> PlayerEngineType.VLC
            else -> null
        }
    }

    private suspend fun safelyReleaseEngine(engine: PlayerEngine) = withContext(NonCancellable) {
        // Once the native handles have been detached, cancellation must not skip their
        // destruction while a route change or a newer playback request takes over.
        var releaseNative: (() -> Unit)? = null
        withContext(Dispatchers.Main.immediate) {
            if (engine is VlcPlayerEngine) {
                // prepareRelease handles listeners, focus and surfaces without native stop.
                // Calling stop() here first would block main before the IO cleanup below.
                releaseNative = engine.prepareRelease()
            } else {
                try {
                    engine.stop()
                } catch (exception: Exception) {
                    Timber.w("Error stopping playback engine: %s", exception.javaClass.simpleName)
                }
            }
        }

        // LibVLC native release can block; Android view/surface cleanup above remains on main.
        withContext(if (engine is VlcPlayerEngine) Dispatchers.IO else Dispatchers.Main.immediate) {
            try {
                if (engine is VlcPlayerEngine) releaseNative?.invoke() else engine.release()
            } catch (exception: Exception) {
                Timber.w("Error releasing playback engine: %s", exception.javaClass.simpleName)
            }
        }
    }

    private suspend fun refreshSettingsCache(): com.idealplayer.app.core.datastore.AppSettings {
        val settings = settingsDataStore.settings.first()

        defaultAudioLang = settings.defaultAudioLanguage
        defaultSubtitleLang = settings.defaultSubtitleLanguage
        autoEnableSubtitles = settings.autoEnableSubtitles
        systemCaptionPreference = systemCaptionPreferences.snapshot()
        cachedSubtitleAppearance = if (defaultSubtitleLang.equals("system", ignoreCase = true)) {
            systemCaptionPreference.appearance()
        } else {
            SubtitleAppearance(
                fontScale = settings.subtitleSize / 18f,
                foregroundColor = settings.subtitleColor.toInt(),
                backgroundColor = settings.subtitleBackground.toInt()
            )
        }
        preferredAspectRatio = AspectRatioMode.entries.firstOrNull {
            it.name.equals(settings.aspectRatio, ignoreCase = true)
        } ?: AspectRatioMode.FIT
        preferredVideoQualityMode = VideoQualityMode.entries.firstOrNull {
            it.name.equals(settings.videoQualityMode, ignoreCase = true)
        } ?: VideoQualityMode.AUTO
        defaultPlaybackSpeed = settings.defaultPlaybackSpeed

        cachedBufferMs = settings.bufferDurationMs.toLong()
        cachedLatencyMode = settings.liveLatencyMode
        cachedPreferHw = settings.preferHwDecoding
        cachedAllowQualityFallback = settings.allowQualityFallback
        cachedAutoPlayerFallback = settings.autoPlayerFallback
        return settings
    }

    private fun applyCachedSettingsToEngine(engine: PlayerEngine) {
        engine.setPlaybackConfiguration(
            cachedBufferMs,
            cachedLatencyMode,
            cachedPreferHw,
            cachedAllowQualityFallback
        )
        engine.setAspectRatio(preferredAspectRatio)
        engine.setVideoQualityMode(preferredVideoQualityMode)
        engine.setPlaybackSpeed(defaultPlaybackSpeed)
        applyPreferredTracks(engine)
    }

    private fun applyPreferredTracks(engine: PlayerEngine) {
        if (!hasManualAudioSelection) applyPreferredAudioTrack(engine)
        if (!hasManualSubtitleSelection) applyPreferredSubtitleTrack(engine)
    }

    private fun applyPreferredAudioTrack(engine: PlayerEngine) {
        val audioLanguage = resolvePreferredLanguage(defaultAudioLang, Locale.getDefault().language)
        when (engine) {
            is ExoPlayerEngine -> engine.setPreferredAudioLanguage(audioLanguage.orEmpty())
            is VlcPlayerEngine -> engine.setPreferredAudioLanguage(audioLanguage.orEmpty())
        }
        engine.setAudioDescriptionEnabled(systemCaptionPreference.audioDescriptionRequested)
    }

    private fun applyPreferredSubtitleTrack(engine: PlayerEngine) {
        val subtitleLanguage = resolvePreferredLanguage(defaultSubtitleLang, systemCaptionPreference.language)
        val subtitlesDisabled = defaultSubtitleLang.isExplicitlyDisabledLanguage() ||
            (defaultSubtitleLang.equals("system", ignoreCase = true) &&
                !systemCaptionPreference.enabled)

        when (engine) {
            is ExoPlayerEngine -> {
                when {
                    subtitlesDisabled -> engine.disableSubtitles()
                    subtitleLanguage != null -> engine.setPreferredSubtitleLanguage(subtitleLanguage)
                    !autoEnableSubtitles -> engine.disableSubtitles()
                    else -> engine.setPreferredSubtitleLanguage("")
                }
            }

            is VlcPlayerEngine -> {
                when {
                    subtitlesDisabled -> engine.disableSubtitles()
                    subtitleLanguage != null -> engine.setPreferredSubtitleLanguage(subtitleLanguage)
                    !autoEnableSubtitles -> engine.disableSubtitles()
                    else -> engine.setPreferredSubtitleLanguage("")
                }
            }
        }
    }

    private fun resolvePreferredLanguage(language: String, systemLanguage: String?): String? {
        val normalized = language.trim()
        if (normalized.isBlank() || normalized.isExplicitlyDisabledLanguage()) {
            return null
        }

        return when (normalized.lowercase(Locale.ROOT)) {
            "auto", "default" -> null
            "system" -> systemLanguage
                ?: Locale.getDefault().language.takeIf { it.isNotBlank() }
            else -> normalized
        }
    }

    private fun String.isExplicitlyDisabledLanguage(): Boolean {
        return lowercase(Locale.ROOT).trim() in listOf("off", "none")
    }

    private fun updateDiagnostics(engineName: String = currentEngine?.engineName ?: "—") {
        _diagnostics.value = _diagnostics.value.copy(
            engineName = engineName,
            recovery = _recoveryState.value,
            metrics = _metrics.value
        )
    }
}

internal fun shouldReplayAfterEngineSwitch(playbackState: PlaybackState): Boolean =
    playbackState !in listOf(
        PlaybackState.IDLE,
        PlaybackState.STOPPED,
        PlaybackState.ENDED
    )

internal fun engineSwitchStartPosition(
    profile: PlaybackProfile,
    currentPosition: Long
): Long = if (profile == PlaybackProfile.LIVE) 0L else currentPosition.coerceAtLeast(0L)

internal fun shouldPreparePlaybackEngine(
    activeEngineType: PlayerEngineType?,
    preferredEngineType: PlayerEngineType,
    engineSwitchAttempted: Boolean
): Boolean = !engineSwitchAttempted && activeEngineType != preferredEngineType

internal fun shouldAttemptAutomaticFallback(
    enabled: Boolean,
    activeEngineType: PlayerEngineType?,
    state: PlayerState,
    hasPreviouslyConfirmed: Boolean,
    alreadyAttempted: Boolean,
    hasPlaybackRequest: Boolean
): Boolean = enabled &&
    activeEngineType == PlayerEngineType.EXOPLAYER &&
    state.playbackState == PlaybackState.ERROR &&
    !state.isPlaybackConfirmed &&
    !hasPreviouslyConfirmed &&
    !alreadyAttempted &&
    hasPlaybackRequest

internal fun classifyPlaybackError(message: String?): String {
    return classifyPlaybackFailure(message).category
}

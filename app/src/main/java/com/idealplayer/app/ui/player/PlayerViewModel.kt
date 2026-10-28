package com.idealplayer.app.ui.player

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idealplayer.app.BuildConfig
import com.idealplayer.app.core.common.SensitiveLog
import com.idealplayer.app.core.common.rethrowIfCancellation
import com.idealplayer.app.core.datastore.AppSettings
import com.idealplayer.app.core.datastore.SettingsDataStore
import com.idealplayer.app.core.model.Channel
import com.idealplayer.app.core.model.ContentType
import com.idealplayer.app.core.model.Episode
import com.idealplayer.app.core.player.AspectRatioMode
import com.idealplayer.app.core.player.LiveStreamFormat
import com.idealplayer.app.core.player.PlaybackVisibility
import com.idealplayer.app.core.player.PlaybackLifecycleAction
import com.idealplayer.app.core.player.playbackLifecycleAction
import com.idealplayer.app.core.player.playbackRetryPosition
import com.idealplayer.app.core.player.PlaybackState
import com.idealplayer.app.core.player.PlaybackProfile
import com.idealplayer.app.core.player.PlaybackDiagnostics
import com.idealplayer.app.core.player.PlaybackMetricsSnapshot
import com.idealplayer.app.core.player.PlaybackSessionState
import com.idealplayer.app.core.player.buildPlaybackDiagnosticsReport
import com.idealplayer.app.core.player.PlayerManager
import com.idealplayer.app.core.player.PlayerState
import com.idealplayer.app.core.player.parsePlaybackSource
import com.idealplayer.app.core.player.withPlaybackHeaders
import com.idealplayer.app.core.player.RetryState
import com.idealplayer.app.core.player.SleepTimerManager
import com.idealplayer.app.core.player.SleepTimerState
import com.idealplayer.app.core.player.StreamRetryManager
import com.idealplayer.app.core.player.TvMediaSessionBridge
import com.idealplayer.app.core.player.VideoQualityMode
import com.idealplayer.app.data.repository.ContentRepository
import com.idealplayer.app.data.repository.EpgRepository
import com.idealplayer.app.data.repository.PlaylistRepository
import com.idealplayer.app.data.repository.PlaybackSourceResolver
import com.idealplayer.app.data.repository.ResolvedPlaybackSource
import com.idealplayer.app.core.model.CatchupWindow

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

data class PlayerSessionState(
    val title: String = "",
    val movie: com.idealplayer.app.core.model.Movie? = null,
    val series: com.idealplayer.app.core.model.Series? = null,
    val currentEpisode: Episode? = null,
    val previousEpisode: Episode? = null,
    val nextEpisode: Episode? = null,
    val seasonEpisodes: List<Episode> = emptyList(),
    val currentChannel: Channel? = null,
    val previousChannel: Channel? = null,
    val nextChannel: Channel? = null,
    val availableChannels: List<Channel> = emptyList(),
    val liveGroups: List<String> = emptyList(),
    val liveGroup: String = "",
    val isCatchup: Boolean = false
)

data class LiveChannelSwitchState(
    val isSwitching: Boolean = false,
    val targetChannelId: Long? = null,
    val targetTitle: String = "",
    val errorMessage: String? = null
)

private data class PlaybackRecoverySnapshot(
    val requestId: Long,
    val playbackState: PlaybackState,
    val isPlaybackConfirmed: Boolean,
    val errorMessage: String?
)

private data class AutoPlayNextSnapshot(
    val playbackState: PlaybackState,
    val currentPosition: Long,
    val duration: Long
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    val playerManager: PlayerManager,
    private val settingsDataStore: SettingsDataStore,
    private val contentRepository: ContentRepository,
    private val playlistRepository: PlaylistRepository,
    val retryManager: StreamRetryManager,
    val sleepTimer: SleepTimerManager,
    private val epgRepository: EpgRepository,
    private val tvMediaSessionBridge: TvMediaSessionBridge,
    private val progressWriter: PlaybackProgressWriter,
    private val sourceResolver: PlaybackSourceResolver
) : ViewModel() {
    val state: StateFlow<PlayerState> = playerManager.state
    val diagnostics: StateFlow<PlaybackDiagnostics> = playerManager.diagnostics
    val playbackSessionState: StateFlow<PlaybackSessionState> = playerManager.sessionState
    val playbackMetrics: StateFlow<PlaybackMetricsSnapshot> = playerManager.metrics
    val settings: StateFlow<AppSettings> = settingsDataStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    val retryState: StateFlow<RetryState> = retryManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RetryState())
    val sleepTimerState = sleepTimer.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SleepTimerState())
    val sleepTimerOptions: List<Int> = sleepTimer.availableOptions
    private val _session = MutableStateFlow(PlayerSessionState())
    val session: StateFlow<PlayerSessionState> = _session.asStateFlow()
    private val _liveChannelSwitch = MutableStateFlow(LiveChannelSwitchState())
    val liveChannelSwitch: StateFlow<LiveChannelSwitchState> = _liveChannelSwitch.asStateFlow()
    private val _playerReady = MutableStateFlow(false)
    val playerReady: StateFlow<Boolean> = _playerReady.asStateFlow()
    private val _currentEpgProgram = MutableStateFlow<com.idealplayer.app.data.parser.EpgProgram?>(null)
    val currentEpgProgram: StateFlow<com.idealplayer.app.data.parser.EpgProgram?> = _currentEpgProgram.asStateFlow()
    private val _nextEpgProgram = MutableStateFlow<com.idealplayer.app.data.parser.EpgProgram?>(null)
    val nextEpgProgram: StateFlow<com.idealplayer.app.data.parser.EpgProgram?> = _nextEpgProgram.asStateFlow()
    private val _channelListEpgPrograms =
        MutableStateFlow<Map<Long, com.idealplayer.app.data.parser.EpgProgram>>(emptyMap())
    val channelListEpgPrograms: StateFlow<Map<Long, com.idealplayer.app.data.parser.EpgProgram>> =
        _channelListEpgPrograms.asStateFlow()
    private val _currentChannelEpgPrograms =
        MutableStateFlow<List<com.idealplayer.app.data.parser.EpgProgram>>(emptyList())
    val currentChannelEpgPrograms: StateFlow<List<com.idealplayer.app.data.parser.EpgProgram>> =
        _currentChannelEpgPrograms.asStateFlow()
    private val _channelBrowserChannels = MutableStateFlow<List<Channel>>(emptyList())
    val channelBrowserChannels: StateFlow<List<Channel>> = _channelBrowserChannels.asStateFlow()

    private var currentCatchup: CatchupWindow? = null
    private var currentUrl = ""
    private var currentOriginalUrl = ""
    private var currentTitle = ""
    private var currentContentId = 0L
    private var currentContentType = ""
    private var currentGroupContext = ""
    private var isInitialized = false
    private var enableTvPlaybackWorkarounds = false
    private var currentPlaybackCandidates: List<String> = emptyList()
    private var currentPlaybackCandidateIndex = 0
    private var currentStartPosition = 0L
    private var hasConfirmedCurrentContent = false
    private var contentRequestId: Long? = null
    private var livePlaybackJob: Job? = null
    private var liveHistoryDwellJob: Job? = null
    private var contentPlaybackJob: Job? = null
    private var httpRetryJob: Job? = null
    private var destinationResolutionJob: Job? = null
    private var destinationResolutionSequence = 0L
    private val playbackOperationMutex = Mutex()
    private var liveNavigationAnchorChannelId: Long? = null
    private var liveNavigationChannels: List<Channel> = emptyList()
    private var liveEpgAutoDiscoveryPlaylistId: Long? = null
    private var autoPlayNextTriggeredContentId: Long? = null
    private var playerReleaseRequested = false
    private var accessDenied = false
    private val playbackOwner = Any()
    private var playbackAllowed = true
    private var exitRequested = false
    private var resumeAfterVisibility = false
    private var lifecycleJob: Job? = null
    private var visibilityGeneration = 0L
    private var lastProgressSnapshot: PlaybackProgressSnapshot? = null


    private var httpRetryCount = 0
    private val maxHttpRetries = 3

    private companion object {
        private const val LIVE_WARMUP_EXTENSION_MS = 2_500L
        private const val LIVE_WARMUP_MIN_PROGRESS_MS = 2_000L
        private const val LIVE_WARMUP_MIN_BUFFER_AHEAD_MS = 500L
        private const val LIVE_PRIMARY_CANDIDATE_TIMEOUT_MS = 6_000L
        private const val LIVE_FALLBACK_CANDIDATE_TIMEOUT_MS = 5_000L
        private const val LIVE_CONFIRMATION_WINDOW_MS = 300L
        internal const val LIVE_HISTORY_DWELL_MS = 15_000L
        private const val AUTO_PLAY_NEXT_MIN_DURATION_MS = 30_000L
        private const val AUTO_PLAY_NEXT_END_TOLERANCE_MS = 2_500L
    }

    init {
        playerManager.claimOwnership(playbackOwner)
        viewModelScope.launch {
            state.collect { value ->
                if (playerManager.isOwner(playbackOwner) && playbackAllowed && !accessDenied && value.requestId == contentRequestId && value.isPlaybackConfirmed) {
                    hasConfirmedCurrentContent = true
                    captureProgress()?.let { lastProgressSnapshot = it }
                }
            }
        }
        viewModelScope.launch {
            contentRepository.accessChanges.collect {
                if (currentContentType.isNotBlank()) {
                    val id = currentContentId
                    val type = currentContentType
                    if (!contentRepository.canPlay(id, type) && id == currentContentId && type == currentContentType) {
                        accessDenied = true
                        destinationResolutionJob?.cancelAndJoin()
                        livePlaybackJob?.cancelAndJoin()
                        contentPlaybackJob?.cancelAndJoin()
                        httpRetryJob?.cancelAndJoin()
                        blockContentAccess()
                    } else if (!accessDenied) {
                        // Drop cached browser/neighbour data on policy changes as well.
                        refreshSessionContext(currentOriginalUrl, currentTitle, currentContentId, currentContentType, currentGroupContext)
                        loadChannelBrowser(currentGroupContext)
                    }
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(15_000)
                // Progress is also persisted on pause/stop/lifecycle events. Avoid waking the
                // database every fifteen seconds while the player is idle, buffering, or paused.
                if (state.value.playbackState == PlaybackState.PLAYING) {
                    saveProgressInternal()
                }
            }
        }

        viewModelScope.launch {
            state
                .map { playerState ->
                    PlaybackRecoverySnapshot(
                        requestId = playerState.requestId,
                        playbackState = playerState.playbackState,
                        isPlaybackConfirmed = playerState.isPlaybackConfirmed,
                        errorMessage = playerState.errorMessage
                    )
                }
                .distinctUntilChanged()
                .collect { recovery ->
                    if (accessDenied || !playbackAllowed || exitRequested || !playerManager.isOwner(playbackOwner) || recovery.requestId != contentRequestId) return@collect
                    if (recovery.playbackState == PlaybackState.ERROR &&
                        !retryManager.state.value.isRetrying &&
                        !isManagedLivePlaybackRunning()
                    ) {
                        if (tryNextSeriesPlaybackCandidate()) {
                            return@collect
                        }

                        val errorMessage = recovery.errorMessage
                        val isHttpError = errorMessage?.contains("503") == true ||
                            errorMessage?.contains("HTTP_STATUS") == true ||
                            errorMessage?.contains("BAD_HTTP") == true
                        val isNonRetryablePlaybackError = isNonRetryablePlaybackError(errorMessage)
                        
                        if (isHttpError && httpRetryCount < maxHttpRetries) {
                            httpRetryCount++
                            val failedContentId = currentContentId
                            val failedUrl = currentUrl
                            val failedType = currentContentType
                            val failedPosition = retryPosition()
                            httpRetryJob?.cancel()
                            httpRetryJob = viewModelScope.launch {
                                delay(2000L * httpRetryCount)
                                Timber.d("HTTP error retry $httpRetryCount/$maxHttpRetries")
                                if (
                                    currentContentId == failedContentId && currentContentType == failedType &&
                                    currentUrl == failedUrl &&
                                    failedUrl.isNotBlank()
                                ) {
                                    playAuthorized(
                                        url = failedUrl,
                                        startPosition = failedPosition,
                                        profile = currentPlaybackProfile()
                                    )
                                }
                            }
                            return@collect
                        } else {
                            httpRetryCount = 0
                            handlePlaybackError(errorMessage)

                            val autoRetry = settings.value.liveReconnectOnFailure
                            if (autoRetry &&
                                currentContentType == ContentType.LIVE.name && currentCatchup == null &&
                                !isNonRetryablePlaybackError
                            ) {
                                retryManager.startRetry(
                                    errorMessage = errorMessage,
                                    onRetry = { retryCurrent() },
                                    onExhausted = { Timber.w("Auto-retry exhausted") }
                                )
                            } else if (isNonRetryablePlaybackError &&
                                currentContentType == ContentType.LIVE.name && currentCatchup == null
                            ) {
                                _liveChannelSwitch.value = LiveChannelSwitchState(
                                    errorMessage = errorMessage ?: buildPlaybackFailureMessage(
                                        currentTitle.ifBlank { "This channel" }
                                    )
                                )
                            }
                        }
                    } else if (recovery.isPlaybackConfirmed) {
                        hasConfirmedCurrentContent = true
                        httpRetryCount = 0
                        httpRetryJob?.cancel()
                        httpRetryJob = null
                        retryManager.onPlaybackSuccess()
                    }
                }
        }

        viewModelScope.launch {
            state
                .map { playerState ->
                    AutoPlayNextSnapshot(
                        playbackState = playerState.playbackState,
                        currentPosition = playerState.currentPosition,
                        duration = playerState.duration
                    )
                }
                .distinctUntilChanged()
                .collect(::maybeAutoPlayNextEpisode)
        }
    }

    private fun maybeAutoPlayNextEpisode(snapshot: AutoPlayNextSnapshot) {
        if (!playbackAllowed || exitRequested || !playerManager.isOwner(playbackOwner)) return
        val contentId = currentContentId.takeIf { it > 0L } ?: return
        val nextEpisode = session.value.nextEpisode ?: return
        if (!shouldTriggerAutoPlayNextEpisode(
                isSeriesPlayback = currentContentType.equals(ContentType.SERIES.name, ignoreCase = true),
                autoPlayEnabled = settings.value.autoPlayNextEpisode,
                transitionInProgress = contentPlaybackJob?.isActive == true,
                contentId = contentId,
                alreadyTriggeredContentId = autoPlayNextTriggeredContentId,
                playbackState = snapshot.playbackState,
                currentPosition = snapshot.currentPosition,
                duration = snapshot.duration,
                minimumDuration = AUTO_PLAY_NEXT_MIN_DURATION_MS,
                endTolerance = AUTO_PLAY_NEXT_END_TOLERANCE_MS
            )
        ) {
            return
        }

        autoPlayNextTriggeredContentId = contentId
        Timber.d(
            "Auto-playing next episode current=%d next=%d state=%s position=%d duration=%d",
            contentId,
            nextEpisode.id,
            snapshot.playbackState,
            snapshot.currentPosition,
            snapshot.duration
        )
        playEpisode(nextEpisode)
    }

    private fun handlePlaybackError(errorMessage: String?) {
        // Engine error text can embed a stream URL or request headers; the state remains
        // available to the UI, but debug logging must not expose it.
        Timber.w("Playback error handled")
        // Engine selection is explicit; playback recovery is handled by the
        // currently active engine plus retryManager when enabled.
    }

    private fun isNonRetryablePlaybackError(errorMessage: String?): Boolean {
        val normalized = errorMessage?.lowercase() ?: return false
        return normalized.contains("not supported on this device") ||
            normalized.contains("no_exceeds_capabilities") ||
            normalized.contains("video/hevc") ||
            normalized.contains("hvc1") ||
            normalized.contains("hev1") ||
            normalized.contains("10bit")
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimer.start(minutes) {
            if (playerManager.isOwner(playbackOwner)) {
                resumeAfterVisibility = false
                visibilityGeneration++
                cancelPlaybackWork()
                saveProgress()
                viewModelScope.launch { playerManager.pause(playbackOwner) }
            }
        }
    }

    fun cancelSleepTimer() = sleepTimer.cancel()

    fun extendSleepTimer(minutes: Int) = sleepTimer.extend(minutes)

    fun loadEpgForChannel(channel: Channel?) {
        if (channel == null) {
            _currentEpgProgram.value = null
            _nextEpgProgram.value = null
            _currentChannelEpgPrograms.value = emptyList()
            return
        }

        viewModelScope.launch {
            _currentEpgProgram.value = epgRepository.getCurrentProgram(channel)
            _nextEpgProgram.value = epgRepository.getNextProgram(channel)
        }
    }

    fun loadEpgProgramsForChannel(channel: Channel?) {
        if (channel == null) {
            _currentChannelEpgPrograms.value = emptyList()
            return
        }

        viewModelScope.launch {
            _currentChannelEpgPrograms.value = epgRepository.getProgramsForChannel(channel)
        }
    }

    fun loadEpgForChannels(channels: List<Channel>) {
        if (channels.isEmpty()) {
            _channelListEpgPrograms.value = emptyMap()
            return
        }

        viewModelScope.launch {
            _channelListEpgPrograms.value = epgRepository.getCurrentProgramsForChannels(channels)
        }
    }

    fun loadChannelBrowser(group: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val playlist = playlistRepository.getActivePlaylistSummary().first()
            if (playlist == null) {
                _channelBrowserChannels.value = emptyList()
                _channelListEpgPrograms.value = emptyMap()
                return@launch
            }

            val normalizedGroup = group?.takeIf { it.isNotBlank() }
            val channels = if (normalizedGroup == null) {
                contentRepository.getChannels(playlist.id).first()
            } else {
                contentRepository.getChannelsByGroup(playlist.id, normalizedGroup).first()
            }

            _channelBrowserChannels.value = channels
            _channelListEpgPrograms.value = if (channels.isEmpty()) {
                emptyMap()
            } else {
                epgRepository.getCurrentProgramsForChannels(channels)
            }
        }
    }

    private fun ensureLiveEpgSynced() {
        viewModelScope.launch {
            val playlist = playlistRepository.getActivePlaylistSummary().first() ?: return@launch
            if (liveEpgAutoDiscoveryPlaylistId == playlist.id) return@launch

            liveEpgAutoDiscoveryPlaylistId = playlist.id
            val synced = playlistRepository.ensureEpgSynced(playlist.id)
            if (synced) {
                loadEpgForChannel(session.value.currentChannel)
                loadEpgForChannels(session.value.availableChannels)
            }
        }
    }

    fun init(
        url: String,
        title: String,
        startPos: Long,
        contentId: Long,
        contentType: String,
        groupContext: String,
        catchupStart: Long = 0,
        catchupEnd: Long = 0
    ) {
        val catchupWindow = if (catchupStart == 0L && catchupEnd == 0L) null else
            runCatching { CatchupWindow(catchupStart, catchupEnd) }.getOrNull()
        if ((catchupStart != 0L || catchupEnd != 0L) &&
            (catchupWindow == null || contentType != ContentType.LIVE.name)) {
            rejectPlaybackDestination()
            return
        }
        if (url.isBlank()) {
            val resolutionId = ++destinationResolutionSequence
            destinationResolutionJob?.cancel()
            destinationResolutionJob = viewModelScope.launch {
                val resolved = resolvePlaybackDestination(contentId, contentType, catchupWindow)
                if (resolutionId != destinationResolutionSequence) return@launch
                if (resolved == null || resolved.url.isBlank()) {
                    rejectPlaybackDestination()
                    return@launch
                }
                init(
                    url = resolved.url,
                    title = resolved.title,
                    startPos = startPos,
                    contentId = contentId,
                    contentType = contentType,
                    groupContext = resolved.groupContext,
                    catchupStart = catchupStart, catchupEnd = catchupEnd
                )
            }
            return
        }

        if (
            currentOriginalUrl == url &&
            currentTitle == title &&
            currentContentId == contentId &&
            currentContentType == contentType &&
            currentCatchup == catchupWindow &&
            currentGroupContext == groupContext &&
            state.value.playbackState != PlaybackState.IDLE
        ) {
            return
        }

        currentCatchup = catchupWindow
        val playbackCandidates = resolvePlaybackCandidates(url, contentType)
        val resolvedUrl = playbackCandidates.firstOrNull().orEmpty()

        currentOriginalUrl = url
        currentUrl = resolvedUrl
        currentPlaybackCandidates = playbackCandidates
        currentPlaybackCandidateIndex = 0
        currentStartPosition = 0L
        hasConfirmedCurrentContent = false
        contentRequestId = null
        currentTitle = title
        currentContentId = contentId
        currentContentType = contentType
        currentGroupContext = groupContext
        autoPlayNextTriggeredContentId = null

        val isLiveContent = contentType.equals(ContentType.LIVE.name, ignoreCase = true) && catchupWindow == null
        liveNavigationAnchorChannelId = contentId.takeIf { isLiveContent && it > 0L }
        retryManager.cancel()
        httpRetryJob?.cancel()
        httpRetryJob = null

        val initializeAndPlay: suspend () -> Unit = initializeAndPlay@{
            // A previous live switch may finish its cancellation cleanup before this block
            // acquires the operation mutex. Re-apply the latest route request afterwards so
            // stale rollback state cannot become the active content identity.
            currentOriginalUrl = url
            currentUrl = resolvedUrl
            currentPlaybackCandidates = playbackCandidates
            currentPlaybackCandidateIndex = 0
            currentStartPosition = 0L
            hasConfirmedCurrentContent = false
            contentRequestId = null
            currentTitle = title
            currentContentId = contentId
            currentContentType = contentType
            currentGroupContext = groupContext
            autoPlayNextTriggeredContentId = null
            liveNavigationAnchorChannelId = contentId.takeIf { isLiveContent && it > 0L }

            val playbackSettings = settingsDataStore.settings.first()
            val preferredFormat = LiveStreamFormat.fromStoredValue(playbackSettings.preferredLiveStreamFormat)
            val resolvedCandidates = resolvePlaybackCandidates(url, contentType, preferredFormat)
            val effectiveResolvedUrl = resolvedCandidates.firstOrNull().orEmpty()
            currentUrl = effectiveResolvedUrl
            currentPlaybackCandidates = resolvedCandidates
            val effectiveStartPosition = playbackStartPosition(
                requestedPosition = startPos,
                autoResumeEnabled = playbackSettings.autoResumePlayback
            )
            currentStartPosition = effectiveStartPosition
            clearLiveChannelSwitchError()
            if (!isInitialized) {
                _playerReady.value = false
                playerManager.initializeWithSettings(playbackOwner)
                isInitialized = true
                _playerReady.value = true
            }
            if (!checkContentAccess()) return@initializeAndPlay
            refreshSessionContext(url, title, contentId, contentType, groupContext)

            val playbackStarted = if (isLiveContent) {
                startLiveChannelPlayback(resolvedCandidates, effectiveStartPosition)
            } else {
                playAuthorized(
                    url = effectiveResolvedUrl,
                    startPosition = effectiveStartPosition,
                    profile = playbackProfileFor(contentType)
                )
            }

            if (
                playbackStarted &&
                isLiveContent &&
                isCurrentPlaybackRequest(contentId, contentType, url) &&
                contentId > 0L
            ) {
                if (playbackSettings.rememberLastChannel) {
                    scheduleLiveHistoryDwell(contentId, url)
                }
                ensureLiveEpgSynced()
            } else if (!accessDenied && !playbackStarted && isCurrentPlaybackRequest(contentId, contentType, url)) {
                publishLivePlaybackFailure(
                    channelId = contentId,
                    title = title,
                    errorMessage = state.value.errorMessage,
                    allowAutoRetry = playbackSettings.liveReconnectOnFailure
                )
            }
        }

        if (isLiveContent) {
            contentPlaybackJob?.cancel()
            contentPlaybackJob = null
            launchLatestLivePlayback(initializeAndPlay)
        } else {
            livePlaybackJob?.cancel()
            contentPlaybackJob?.cancel()
            contentPlaybackJob = viewModelScope.launch {
                playbackOperationMutex.withLock { initializeAndPlay() }
            }
        }
    }

    fun configureTvPlayback(enabled: Boolean) {
        enableTvPlaybackWorkarounds = enabled
        if (enabled) {
            tvMediaSessionBridge.activate(
                playerManager = playerManager,
                title = { currentTitle },
                onPlay = ::resumePlayback,
                onNext = ::playNextMediaItem,
                onPrevious = ::playPreviousMediaItem,
                owner = playbackOwner
            )
        } else {
            tvMediaSessionBridge.deactivate(playbackOwner)
        }
    }

    fun clearLiveChannelSwitchError() {
        if (_liveChannelSwitch.value.errorMessage != null && !_liveChannelSwitch.value.isSwitching) {
            _liveChannelSwitch.value = LiveChannelSwitchState()
        }
    }

    fun togglePlayPause() {
        if (!playbackAllowed || exitRequested) return
        val snapshot = state.value
        viewModelScope.launch {
            if (snapshot.isPlaying) {
                playerManager.pause(playbackOwner)
            } else if (checkContentAccess()) {
                resumePlayback()
            }
        }
    }

    private fun resumePlayback() {
        if (!playbackAllowed || exitRequested) return
        if (state.value.playbackState in listOf(PlaybackState.IDLE, PlaybackState.STOPPED)) {
            retryCurrent()
        } else viewModelScope.launch {
            if (checkContentAccess()) playerManager.resume(playbackOwner)
        }
    }

    fun seekForward() {
        viewModelScope.launch {
            playerManager.seekForward(settings.value.seekForwardMs, playbackOwner)
        }
    }

    fun seekBackward() {
        viewModelScope.launch {
            playerManager.seekBackward(settings.value.seekBackwardMs, playbackOwner)
        }
    }

    fun seekTo(pos: Long) {
        viewModelScope.launch {
            playerManager.seekTo(pos, playbackOwner)
        }
    }

    fun setSpeed(speed: Float) {
        viewModelScope.launch {
            playerManager.setPlaybackSpeed(speed, playbackOwner)
        }
    }

    fun selectAudio(index: Int) {
        viewModelScope.launch {
            playerManager.selectAudioTrack(index, playbackOwner)
        }
    }

    fun selectSubtitle(index: Int) {
        viewModelScope.launch {
            playerManager.selectSubtitleTrack(index, playbackOwner)
        }
    }

    fun disableSubtitles() {
        viewModelScope.launch {
            playerManager.disableSubtitles(playbackOwner)
        }
    }

    fun setAspectRatio(mode: AspectRatioMode) {
        viewModelScope.launch {
            playerManager.setAspectRatio(mode, playbackOwner)
        }
    }

    fun setVideoQualityMode(mode: VideoQualityMode) {
        viewModelScope.launch {
            playerManager.setVideoQualityMode(mode, playbackOwner)
        }
    }

    fun selectVideoTrack(index: Int) {
        viewModelScope.launch {
            playerManager.selectVideoTrack(index, playbackOwner)
        }
    }

    fun buildDiagnosticsReport(): String = buildPlaybackDiagnosticsReport(
        appVersion = BuildConfig.VERSION_NAME,
        device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        androidSdk = Build.VERSION.SDK_INT,
        contentType = currentContentType,
        diagnostics = diagnostics.value,
        state = state.value
    )

    fun saveProgress() {
        captureProgress()?.let { progressWriter.enqueue(it) }
    }

    private fun retryPosition(): Long = playbackRetryPosition(
        currentPlaybackProfile(), state.value.currentPosition,
        lastProgressSnapshot?.takeIf { it.contentId == currentContentId && it.contentType.name == currentContentType }?.position
            ?: currentStartPosition
    )

    fun retryCurrent() {
        if (!playbackAllowed || exitRequested || !playerManager.isOwner(playbackOwner)) return
        val failedChannel = if (currentContentType == ContentType.LIVE.name && currentCatchup == null) {
            val failedTargetId = _liveChannelSwitch.value.targetChannelId
            liveNavigationChannels.firstOrNull { it.id == failedTargetId }
                ?: session.value.availableChannels.firstOrNull { it.id == failedTargetId }
        } else {
            null
        }

        if (failedChannel != null) {
            requestLiveChannel(failedChannel, cancelAutomaticRetry = false)
            return
        }

        if (currentContentType == ContentType.LIVE.name && currentCatchup == null) {
            launchLatestLivePlayback {
                clearLiveChannelSwitchError()
                val candidates = currentPlaybackCandidates.ifEmpty {
                    resolvePlaybackCandidates(currentOriginalUrl.ifBlank { currentUrl }, currentContentType)
                }
                val started = startLiveChannelPlayback(candidates, startPosition = 0L)
                if (!started) {
                    publishLivePlaybackFailure(
                        channelId = currentContentId,
                        title = currentTitle,
                        errorMessage = state.value.errorMessage,
                        allowAutoRetry = false
                    )
                }
            }
        } else {
            contentPlaybackJob?.cancel()
            val position = retryPosition()
            contentPlaybackJob = viewModelScope.launch {
                playbackOperationMutex.withLock {
                    playAuthorized(
                        url = currentUrl,
                        startPosition = position,
                        profile = currentPlaybackProfile()
                    )
                }
            }
        }
    }

    fun playNextEpisode() {
        session.value.nextEpisode?.let(::playEpisode)
    }

    fun playPreviousEpisode() {
        session.value.previousEpisode?.let(::playEpisode)
    }

    fun selectEpisode(episode: Episode) {
        if (session.value.currentEpisode?.id == episode.id) return
        playEpisode(episode)
    }

    fun playNextChannel() {
        adjacentLiveChannel(offset = 1)?.let(::playChannel)
    }

    fun playPreviousChannel() {
        adjacentLiveChannel(offset = -1)?.let(::playChannel)
    }

    private fun playNextMediaItem() {
        when (currentContentType) {
            ContentType.LIVE.name -> if (currentCatchup == null) playNextChannel()
            ContentType.SERIES.name -> playNextEpisode()
        }
    }

    private fun playPreviousMediaItem() {
        when (currentContentType) {
            ContentType.LIVE.name -> if (currentCatchup == null) playPreviousChannel()
            ContentType.SERIES.name -> playPreviousEpisode()
        }
    }

    fun playChannel(channel: Channel) {
        requestLiveChannel(channel, cancelAutomaticRetry = true)
    }

    private fun requestLiveChannel(channel: Channel, cancelAutomaticRetry: Boolean) {
        val switchState = _liveChannelSwitch.value
        if (
            currentContentType == ContentType.LIVE.name && currentCatchup == null &&
            !switchState.isSwitching &&
            switchState.errorMessage == null &&
            currentContentId == channel.id &&
            session.value.currentChannel?.id == channel.id
        ) {
            Timber.d("LivePlayback playChannel ignored; already on local channel id=%d", channel.id)
            return
        }

        if (cancelAutomaticRetry) {
            retryManager.cancel()
        }
        httpRetryJob?.cancel()
        httpRetryJob = null
        liveNavigationAnchorChannelId = channel.id
        val navigationChannels = liveNavigationChannelsFor(channel)
        applyLiveNavigationContext(channel, navigationChannels)
        clearLiveChannelSwitchError()
        Timber.d("LivePlayback scheduling latest channel switch to local id=%d", channel.id)
        launchLatestLivePlayback {
            applyLiveNavigationContext(channel, navigationChannels)
            switchLiveChannel(channel)
        }
    }

    private var seamlessHandoffPrepared: Boolean = false

    fun prepareSeamlessHandoff() {
        seamlessHandoffPrepared = playerManager.prepareSeamlessHandoff(playbackOwner)
    }

    fun onPlaybackVisible() {
        val generation = ++visibilityGeneration
        seamlessHandoffPrepared = false
        playerManager.claimOwnership(playbackOwner)
        playerReleaseRequested = false
        exitRequested = false
        playbackAllowed = true
        if (playerManager.canResumeHandoff(currentUrl, playbackProfileFor(currentContentType), playbackOwner)) {
            lifecycleJob = viewModelScope.launch {
                if (generation != visibilityGeneration) return@launch
                if (playAuthorized(currentUrl, 0L, playbackProfileFor(currentContentType))) {
                    scheduleLiveHistoryDwell(currentContentId, currentOriginalUrl)
                }
            }
        }
        val shouldResume = resumeAfterVisibility
        resumeAfterVisibility = false
        if (shouldResume && currentUrl.isNotBlank()) {
            val pendingStop = lifecycleJob
            lifecycleJob = viewModelScope.launch {
                pendingStop?.join()
                if (generation == visibilityGeneration) retryCurrent()
            }
        }
    }

    fun onPlaybackHidden(visibility: PlaybackVisibility, ownerRemoved: Boolean = false) {
        if (seamlessHandoffPrepared && playerManager.isHandoffPendingFrom(playbackOwner)) {
            playerManager.markHandoffSourceHidden(playbackOwner)
            playbackAllowed = false
            cancelPlaybackWork()
            return
        }
        when (playbackLifecycleAction(visibility, ownerRemoved)) {
            PlaybackLifecycleAction.KEEP -> return
            PlaybackLifecycleAction.SUSPEND, PlaybackLifecycleAction.RELEASE -> Unit
        }
        if (!playerManager.isOwner(playbackOwner) || (!playbackAllowed && !ownerRemoved)) return
        val generation = ++visibilityGeneration
        val snapshot = captureProgress()
        resumeAfterVisibility = !ownerRemoved && (state.value.isPlaying || state.value.playbackState == PlaybackState.BUFFERING)
        playbackAllowed = false
        cancelPlaybackWork()
        if (ownerRemoved) sleepTimer.cancel()
        val checkpoint = snapshot?.let(progressWriter::enqueue)
        lifecycleJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            playerManager.stop(playbackOwner)
            checkpoint?.await()
            if (ownerRemoved && generation == visibilityGeneration && !playbackAllowed) {
                playerManager.releaseAndAwait(playbackOwner)
                isInitialized = false
                _playerReady.value = false
            }
        }
    }

    fun exitPlayer(onBack: () -> Unit) {
        if (exitRequested) return
        if (playerManager.prepareReturnHandoff(playbackOwner)) {
            seamlessHandoffPrepared = true
            exitRequested = true
            playbackAllowed = false
            cancelPlaybackWork()
            onBack()
            return
        }
        val checkpoint = captureProgress()?.let(progressWriter::enqueue)
        exitRequested = true
        playbackAllowed = false
        cancelPlaybackWork()
        sleepTimer.cancel()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            playerManager.pause(playbackOwner)
            checkpoint?.await()
            playerManager.releaseAndAwait(playbackOwner)
            playerReleaseRequested = true
            isInitialized = false
            onBack()
        }
    }

    private fun rejectPlaybackDestination() {
        cancelPlaybackWork()
        currentUrl = ""
        currentOriginalUrl = ""
        currentPlaybackCandidates = emptyList()
        currentContentId = 0
        currentContentType = ""
        currentCatchup = null
        _session.value = PlayerSessionState()
        destinationResolutionJob = viewModelScope.launch {
            playerManager.stop(playbackOwner)
            _playerReady.value = true
            playerManager.reportPlaybackFailure(com.idealplayer.app.core.player.PlaybackFailure.NotFound, playbackOwner)
        }
    }

    private fun cancelPlaybackWork() {
        livePlaybackJob?.cancel()
        liveHistoryDwellJob?.cancel()
        liveHistoryDwellJob = null
        contentPlaybackJob?.cancel()
        httpRetryJob?.cancel()
        destinationResolutionJob?.cancel()
        destinationResolutionSequence++
        retryManager.cancel()
    }

    override fun onCleared() {
        (captureProgress() ?: lastProgressSnapshot)?.let(progressWriter::enqueue)
        cancelPlaybackWork()
        if (playerManager.isOwner(playbackOwner) && !playerManager.isHandoffPendingFrom(playbackOwner)) {
            tvMediaSessionBridge.deactivate(playbackOwner)
            sleepTimer.cancel()
        }
        releasePlayerOnce()
        super.onCleared()
    }

    private fun releasePlayerOnce() {
        if (playerReleaseRequested || playerManager.isHandoffPendingFrom(playbackOwner)) return
        playerReleaseRequested = true
        playerManager.release(playbackOwner)
    }

    private suspend fun resolvePlaybackDestination(
        contentId: Long, contentType: String, window: CatchupWindow? = null
    ): ResolvedPlaybackSource? {
        val type = ContentType.entries.firstOrNull { it.name == contentType.uppercase() } ?: return null
        return sourceResolver.resolve(contentId, type, window)
    }

    private fun playEpisode(episode: Episode) {
        livePlaybackJob?.cancel()
        contentPlaybackJob?.cancel()
        contentPlaybackJob = viewModelScope.launch {
            playbackOperationMutex.withLock {
                val series = session.value.series ?: contentRepository.getSeriesById(episode.seriesId)
                val source = sourceResolver.resolve(episode.id, ContentType.SERIES) ?: return@withLock
                currentCatchup = null
                switchToContent(
                    url = source.url,
                    title = buildEpisodePlayerTitle(series?.name.orEmpty(), episode),
                    contentId = episode.id,
                    contentType = ContentType.SERIES.name,
                    startPosition = episode.lastPosition,
                    groupContext = ""
                )
            }
        }
    }

    private suspend fun switchToContent(
        url: String,
        title: String,
        contentId: Long,
        contentType: String,
        startPosition: Long,
        groupContext: String
    ) {
        saveProgressInternal()

        val playbackSettings = settingsDataStore.settings.first()
        val effectiveStartPosition = playbackStartPosition(
            requestedPosition = startPosition,
            autoResumeEnabled = playbackSettings.autoResumePlayback
        )
        val preferredFormat = LiveStreamFormat.fromStoredValue(playbackSettings.preferredLiveStreamFormat)
        val playbackCandidates = resolvePlaybackCandidates(url, contentType, preferredFormat)
        val resolvedUrl = playbackCandidates.firstOrNull().orEmpty()
        currentOriginalUrl = url
        currentUrl = resolvedUrl
        currentPlaybackCandidates = playbackCandidates
        currentPlaybackCandidateIndex = 0
        currentStartPosition = effectiveStartPosition
        hasConfirmedCurrentContent = false
        contentRequestId = null
        currentTitle = title
        currentContentId = contentId
        currentContentType = contentType
        currentGroupContext = groupContext
        liveNavigationAnchorChannelId = contentId.takeIf {
            contentType.equals(ContentType.LIVE.name, ignoreCase = true) && it > 0L
        }
        retryManager.cancel()
        httpRetryJob?.cancel()
        httpRetryJob = null
        liveHistoryDwellJob?.cancel()
        liveHistoryDwellJob = null

        if (!checkContentAccess()) return
        refreshSessionContext(resolvedUrl, title, contentId, contentType, groupContext)
        playAuthorized(
            url = resolvedUrl,
            startPosition = effectiveStartPosition,
            profile = playbackProfileFor(contentType)
        )
        autoPlayNextTriggeredContentId = null

        if (
            contentType == ContentType.LIVE.name &&
            contentId > 0L &&
            playbackSettings.rememberLastChannel
        ) {
            scheduleLiveHistoryDwell(contentId, url)
        }
    }

    private suspend fun switchLiveChannel(channel: Channel) {
        if (!contentRepository.canPlay(channel.id, ContentType.LIVE.name)) {
            blockContentAccess()
            return
        }
        val resolvedSource = sourceResolver.resolve(channel.id, ContentType.LIVE) ?: return
        val previousCatchup = currentCatchup
        currentCatchup = null
        val previousUrl = currentUrl
        val previousOriginalUrl = currentOriginalUrl
        val previousTitle = currentTitle
        val previousContentId = currentContentId
        val previousContentType = currentContentType
        val previousGroupContext = currentGroupContext
        val previousPlaybackCandidates = currentPlaybackCandidates
        val previousPlaybackCandidateIndex = currentPlaybackCandidateIndex
        val previousStartPosition = currentStartPosition
        val previousPlaybackWasConfirmed = hasConfirmedCurrentContent || state.value.isPlaybackConfirmed
        val previousSession = session.value
        val targetGroupContext = channel.groupTitle.ifBlank { currentGroupContext }
        val playbackSettings = settingsDataStore.settings.first()
        val preferredFormat = LiveStreamFormat.fromStoredValue(playbackSettings.preferredLiveStreamFormat)
        val playbackCandidates = resolvePlaybackCandidates(resolvedSource.url, ContentType.LIVE.name, preferredFormat)

        liveHistoryDwellJob?.cancel()
        liveHistoryDwellJob = null

        _liveChannelSwitch.value = LiveChannelSwitchState(
            isSwitching = true,
            targetChannelId = channel.id,
            targetTitle = channel.name
        )

        var switchErrorMessage: String? = null

        try {
            currentOriginalUrl = resolvedSource.url
            currentUrl = playbackCandidates.firstOrNull().orEmpty()
            currentPlaybackCandidates = playbackCandidates
            currentPlaybackCandidateIndex = 0
            currentStartPosition = 0L
            hasConfirmedCurrentContent = false
            contentRequestId = null
            currentTitle = channel.name
            currentContentId = channel.id
            currentContentType = ContentType.LIVE.name
            currentGroupContext = targetGroupContext

            val started = startLiveChannelPlayback(playbackCandidates)
            if (!started) {
                throw IllegalStateException("Live channel switch did not reach a stable playback state")
            }

            refreshSessionContext(
                url = resolvedSource.url,
                title = channel.name,
                contentId = channel.id,
                contentType = ContentType.LIVE.name,
                groupContext = targetGroupContext
            )
            liveNavigationAnchorChannelId = channel.id
            if (playbackSettings.rememberLastChannel) {
                scheduleLiveHistoryDwell(channel.id, resolvedSource.url)
            }
        } catch (cancelled: CancellationException) {
            currentCatchup = previousCatchup
            currentOriginalUrl = previousOriginalUrl
            currentUrl = previousUrl
            currentTitle = previousTitle
            currentContentId = previousContentId
            currentContentType = previousContentType
            currentGroupContext = previousGroupContext
            currentPlaybackCandidates = previousPlaybackCandidates
            currentPlaybackCandidateIndex = previousPlaybackCandidateIndex
            currentStartPosition = previousStartPosition
            hasConfirmedCurrentContent = previousPlaybackWasConfirmed
            _session.value = previousSession
            throw cancelled
        } catch (_: Exception) {
            currentCatchup = previousCatchup
            currentOriginalUrl = previousOriginalUrl
            currentUrl = previousUrl
            currentTitle = previousTitle
            currentContentId = previousContentId
            currentContentType = previousContentType
            currentGroupContext = previousGroupContext
            currentPlaybackCandidates = previousPlaybackCandidates
            currentPlaybackCandidateIndex = previousPlaybackCandidateIndex
            currentStartPosition = previousStartPosition
            hasConfirmedCurrentContent = previousPlaybackWasConfirmed
            _session.value = previousSession
            switchErrorMessage = state.value.errorMessage ?: buildPlaybackFailureMessage(channel.name)

            if (previousPlaybackWasConfirmed && previousUrl.isNotBlank()) {
                playAuthorized(
                    url = previousUrl,
                    startPosition = 0L,
                    profile = PlaybackProfile.LIVE
                )
            }
        } finally {
            _liveChannelSwitch.value = if (switchErrorMessage.isNullOrBlank()) {
                LiveChannelSwitchState()
            } else {
                LiveChannelSwitchState(
                    targetChannelId = channel.id,
                    targetTitle = channel.name,
                    errorMessage = switchErrorMessage
                )
            }
        }
    }

    private fun launchLatestLivePlayback(block: suspend () -> Unit) {
        livePlaybackJob?.cancel()
        val nextJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            playbackOperationMutex.withLock { block() }
        }
        livePlaybackJob = nextJob
        nextJob.start()
    }

    private fun isManagedLivePlaybackRunning(): Boolean {
        return currentContentType.equals(ContentType.LIVE.name, ignoreCase = true) &&
            livePlaybackJob?.isActive == true
    }

    private fun isCurrentPlaybackRequest(
        contentId: Long,
        contentType: String,
        originalUrl: String
    ): Boolean {
        return currentContentId == contentId &&
            currentContentType.equals(contentType, ignoreCase = true) &&
            currentOriginalUrl == originalUrl
    }

    private fun adjacentLiveChannel(offset: Int): Channel? {
        val currentChannelId = session.value.currentChannel?.id
            ?: currentContentId.takeIf { currentContentType == ContentType.LIVE.name }
        val anchorChannelId = liveNavigationAnchor(
            currentChannelId = currentChannelId,
            switchingTargetChannelId = _liveChannelSwitch.value.targetChannelId,
            latestRequestedChannelId = liveNavigationAnchorChannelId
        )
        val channels = liveNavigationChannels.takeIf { navigationChannels ->
            navigationChannels.any { it.id == anchorChannelId }
        } ?: session.value.availableChannels
        return adjacentLiveChannel(
            channels = channels,
            anchorChannelId = anchorChannelId,
            offset = offset
        )
    }

    private fun liveNavigationChannelsFor(channel: Channel): List<Channel> {
        return liveNavigationChannelsForTarget(
            targetChannel = channel,
            browserChannels = _channelBrowserChannels.value,
            retainedChannels = liveNavigationChannels,
            sessionChannels = session.value.availableChannels
        )
    }

    private fun applyLiveNavigationContext(channel: Channel, channels: List<Channel>) {
        val targetIndex = channels.indexOfFirst { it.id == channel.id }
        if (targetIndex < 0) return

        liveNavigationChannels = channels
        _session.value = _session.value.copy(
            previousChannel = channels.getOrNull(targetIndex - 1),
            nextChannel = channels.getOrNull(targetIndex + 1),
            availableChannels = channels,
            liveGroup = channel.groupTitle.ifBlank { _session.value.liveGroup }
        )
    }

    private fun publishLivePlaybackFailure(
        channelId: Long,
        title: String,
        errorMessage: String?,
        allowAutoRetry: Boolean
    ) {
        val resolvedTitle = title.ifBlank { "This channel" }
        val resolvedError = errorMessage ?: buildPlaybackFailureMessage(resolvedTitle)
        liveNavigationAnchorChannelId = channelId.takeIf { it > 0L }
        _liveChannelSwitch.value = LiveChannelSwitchState(
            targetChannelId = channelId.takeIf { it > 0L },
            targetTitle = title,
            errorMessage = resolvedError
        )

        if (allowAutoRetry && !isNonRetryablePlaybackError(resolvedError)) {
            retryManager.startRetry(
                errorMessage = resolvedError,
                onRetry = { retryCurrent() },
                onExhausted = { Timber.w("Auto-retry exhausted") }
            )
        }
    }

    private suspend fun startLiveChannelPlayback(
        candidates: List<String>,
        startPosition: Long = 0L
    ): Boolean {
        val uniqueCandidates = candidates
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            // The preview may already have fallen back from HLS to TS (or vice versa).
            // Adopt that working candidate before retrying the configured first choice.
            .sortedByDescending { playerManager.canResumeHandoff(it, PlaybackProfile.LIVE, playbackOwner) }

        uniqueCandidates.forEachIndexed { candidateIndex, candidate ->
            Timber.d(
                "LivePlayback: trying candidate[%d] url=%s",
                candidateIndex,
                SensitiveLog.redactUrl(candidate)
            )
            if (!playerManager.canResumeHandoff(candidate, PlaybackProfile.LIVE, playbackOwner)) {
                resetPlaybackAttempt()
            }
            if (!playAuthorized(
                    url = candidate,
                    startPosition = startPosition,
                    profile = PlaybackProfile.LIVE
                )) return false

            val playbackConfirmed = awaitConfirmedPlaybackReady(
                timeoutMs = if (candidateIndex == 0) {
                    LIVE_PRIMARY_CANDIDATE_TIMEOUT_MS
                } else {
                    LIVE_FALLBACK_CANDIDATE_TIMEOUT_MS
                },
                confirmationWindowMs = LIVE_CONFIRMATION_WINDOW_MS
            )

            if (playbackConfirmed) {
                currentUrl = candidate
                clearLiveChannelSwitchError()
                return true
            }

            Timber.w("LivePlayback candidate failed: %s", SensitiveLog.redactUrl(candidate))
            if (isUnavailableHttpStatus(state.value.errorMessage)) {
                Timber.w("LivePlayback HTTP unavailable, moving to the next candidate")
            } else if (!shouldRetryCurrentLiveCandidate(candidate, state.value)) {
                Timber.w(
                    "LivePlayback non-rendering candidate rejected: %s",
                    SensitiveLog.redactUrl(candidate)
                )
            }
        }

        resetPlaybackAttempt()
        return false
    }

    private suspend fun refreshSessionContext(
        url: String,
        title: String,
        contentId: Long,
        contentType: String,
        groupContext: String
    ) {
        val updatedSession = withContext(Dispatchers.IO) {
            when (contentType.uppercase()) {
                ContentType.MOVIE.name -> {
                    val movie = contentRepository.getMovie(contentId)
                    PlayerSessionState(
                        title = movie?.name ?: title,
                        movie = movie
                    )
                }

                ContentType.SERIES.name -> {
                    val episode = contentRepository.getEpisode(contentId)
                    val series = episode?.let { contentRepository.getSeriesById(it.seriesId) }
                    val episodes = when {
                        episode == null -> emptyList()
                        else -> {
                            val localEpisodes = contentRepository.getAllEpisodes(episode.seriesId)
                            if (localEpisodes.isNotEmpty()) {
                                localEpisodes
                            } else if (series != null) {
                                try {
                                    contentRepository.syncSeriesEpisodes(series)
                                } catch (error: Exception) {
                                    error.rethrowIfCancellation()
                                }
                                contentRepository.getAllEpisodes(series.id)
                            } else {
                                emptyList()
                            }
                        }
                    }
                    val currentIndex = episodes.indexOfFirst { it.id == episode?.id }
                    val seasonEpisodes = if (episode != null) {
                        episodes.filter { it.seasonNumber == episode.seasonNumber }.ifEmpty { episodes }
                    } else {
                        episodes
                    }

                    PlayerSessionState(
                        title = if (episode != null) {
                            buildEpisodePlayerTitle(series?.name.orEmpty(), episode)
                        } else {
                            title
                        },
                        series = series,
                        currentEpisode = episode,
                        previousEpisode = episodes.getOrNull(currentIndex - 1),
                        nextEpisode = episodes.getOrNull(currentIndex + 1),
                        seasonEpisodes = seasonEpisodes
                    )
                }

                ContentType.LIVE.name -> {
                    val currentChannel = contentRepository.getChannel(contentId)
                    val playlist = playlistRepository.getActivePlaylistSummary().first()
                    val liveGroups = playlist?.let {
                        contentRepository.getChannelGroups(it.id).first()
                    } ?: emptyList()
                    val scopedChannels = if (playlist != null && groupContext.isNotBlank()) {
                        contentRepository.getChannelsByGroup(playlist.id, groupContext).first()
                    } else {
                        emptyList()
                    }
                    val primaryChannels = when {
                        scopedChannels.isNotEmpty() -> scopedChannels
                        playlist != null -> contentRepository.getChannels(playlist.id).first()
                        else -> emptyList()
                    }
                    val currentIndex = primaryChannels.indexOfFirst { channel ->
                        channel.id == contentId
                    }.takeIf { it >= 0 }

                    PlayerSessionState(
                        title = currentChannel?.name ?: title,
                        currentChannel = currentChannel,
                        previousChannel = currentIndex?.let { primaryChannels.getOrNull(it - 1) },
                        nextChannel = currentIndex?.let { primaryChannels.getOrNull(it + 1) },
                        availableChannels = primaryChannels,
                        liveGroups = liveGroups,
                        liveGroup = groupContext
                    )
                }

                else -> PlayerSessionState(title = title)
            }
        }

        _session.value = updatedSession.copy(isCatchup = currentCatchup != null)
        if (contentType.equals(ContentType.LIVE.name, ignoreCase = true)) {
            liveNavigationChannels = updatedSession.availableChannels
        }
    }

    private fun scheduleLiveHistoryDwell(channelId: Long, originalUrl: String) {
        liveHistoryDwellJob?.cancel()
        if (channelId <= 0L || currentCatchup != null) return
        val requestId = contentRequestId ?: return
        liveHistoryDwellJob = viewModelScope.launch {
            awaitLiveHistoryDwell(state, requestId, LIVE_HISTORY_DWELL_MS)
            if (playbackAllowed &&
                !exitRequested &&
                !accessDenied &&
                playerManager.isOwner(playbackOwner) &&
                isCurrentPlaybackRequest(channelId, ContentType.LIVE.name, originalUrl) &&
                contentRequestId == requestId && state.value.requestId == requestId &&
                state.value.isPlaybackConfirmed && state.value.isPlaying &&
                state.value.playbackState == PlaybackState.PLAYING &&
                settingsDataStore.settings.first().rememberLastChannel
            ) {
                contentRepository.updateChannelLastWatched(channelId)
            }
        }
    }

    private fun captureProgress(): PlaybackProgressSnapshot? {
        if (accessDenied || currentContentId <= 0L || !playerManager.isOwner(playbackOwner)) return null
        val type = ContentType.entries.firstOrNull { it.name == currentContentType } ?: return null
        // Live channel watch state is governed by the zapping dwell timer so rapid channel
        // surfing does not pollute Recent Channels on immediate exit or 15s ticks.
        if (type == ContentType.LIVE && currentCatchup == null) return null
        val value = state.value
        val retained = lastProgressSnapshot?.takeIf { it.contentId == currentContentId && it.contentType == type }
        if (value.requestId != contentRequestId || (!hasConfirmedCurrentContent && !value.isPlaybackConfirmed)) return retained
        return PlaybackProgressSnapshot(
            contentId = currentContentId, contentType = type,
            position = value.currentPosition.takeIf { it > 0L } ?: retained?.position ?: 0L,
            duration = value.duration.takeIf { it > 0L } ?: retained?.duration ?: 0L,
            confirmed = true
        )
    }

    private suspend fun saveProgressInternal() {
        captureProgress()?.let { progressWriter.enqueue(it).await() }
    }

    internal fun resolvePlaybackUrl(url: String): String {
        val source = parsePlaybackSource(url)
        return withPlaybackHeaders(source.url, source.headers, source.subtitleUrls)
    }

    internal fun resolvePlaybackCandidates(
        url: String,
        contentType: String,
        preferredLiveFormat: LiveStreamFormat = LiveStreamFormat.fromStoredValue(settings.value.preferredLiveStreamFormat)
    ): List<String> {
        val source = parsePlaybackSource(url)
        val trimmedUrl = source.url
        val defaultResolvedUrl = parsePlaybackSource(resolvePlaybackUrl(url)).url

        if (contentType.equals(ContentType.SERIES.name, ignoreCase = true)) {
            return seriesPlaybackCandidates(
                withPlaybackHeaders(defaultResolvedUrl, source.headers, source.subtitleUrls)
            )
        }

        if (!contentType.equals(ContentType.LIVE.name, ignoreCase = true) || currentCatchup != null) {
            return listOf(withPlaybackHeaders(defaultResolvedUrl, source.headers, source.subtitleUrls))
        }

        return buildLiveStreamCandidates(
            trimmedUrl = trimmedUrl,
            defaultResolvedUrl = defaultResolvedUrl,
            preferredFormat = preferredFormatForLiveUrl(trimmedUrl, preferredLiveFormat),
            enableTvWorkarounds = enableTvPlaybackWorkarounds
        ).map { candidate ->
            withPlaybackHeaders(candidate.trim(), source.headers, source.subtitleUrls)
        }
            .filter(String::isNotBlank)
            .distinct()
    }

    private fun tryNextSeriesPlaybackCandidate(): Boolean {
        if (hasConfirmedCurrentContent ||
            !currentContentType.equals(ContentType.SERIES.name, ignoreCase = true)
        ) {
            return false
        }

        val nextIndex = currentPlaybackCandidateIndex + 1
        val nextCandidate = currentPlaybackCandidates.getOrNull(nextIndex) ?: return false
        currentPlaybackCandidateIndex = nextIndex
        currentUrl = nextCandidate
        httpRetryCount = 0

        Timber.w(
            "Series playback candidate failed; trying fallback %d/%d: %s",
            nextIndex + 1,
            currentPlaybackCandidates.size,
            SensitiveLog.redactUrl(nextCandidate)
        )
        viewModelScope.launch {
            resetPlaybackAttempt()
            playAuthorized(
                url = nextCandidate,
                startPosition = currentStartPosition,
                profile = PlaybackProfile.VOD
            )
        }
        return true
    }

    private fun buildPlaybackFailureMessage(title: String): String {
        return "$title could not reach confirmed playback."
    }

    private suspend fun awaitConfirmedPlaybackReady(
        timeoutMs: Long,
        confirmationWindowMs: Long = 900L
    ): Boolean {
        var remainingTimeoutMs = timeoutMs
        var warmupExtensionApplied = false
        var reachedCandidateState: PlayerState? = null

        while (reachedCandidateState == null) {
            val candidateState = withTimeoutOrNull(remainingTimeoutMs) {
                state.first { snapshot ->
                    snapshot.playbackState == PlaybackState.ERROR || snapshot.isPlaybackConfirmed
                }
            }

            if (candidateState != null) {
                reachedCandidateState = candidateState
                continue
            }

            val snapshot = state.value

            if (!warmupExtensionApplied && shouldExtendLiveWarmup(snapshot)) {
                warmupExtensionApplied = true
                remainingTimeoutMs = LIVE_WARMUP_EXTENSION_MS
                Timber.w(
                    "LivePlayback confirmation warmup extended: state=%s video=%sx%s firstFrame=%s surfaceReady=%s audioSession=%d position=%d buffered=%d",
                    snapshot.playbackState,
                    snapshot.videoWidth,
                    snapshot.videoHeight,
                    snapshot.hasRenderedFirstFrame,
                    snapshot.isSurfaceReady,
                    snapshot.audioSessionId,
                    snapshot.currentPosition,
                    snapshot.bufferedPosition
                )
                continue
            }

            Timber.w(
                "LivePlayback confirmation timed out: state=%s playing=%s confirmed=%s video=%sx%s firstFrame=%s surfaceReady=%s audioSession=%d position=%d buffered=%d",
                snapshot.playbackState,
                snapshot.isPlaying,
                snapshot.isPlaybackConfirmed,
                snapshot.videoWidth,
                snapshot.videoHeight,
                snapshot.hasRenderedFirstFrame,
                snapshot.isSurfaceReady,
                snapshot.audioSessionId,
                snapshot.currentPosition,
                snapshot.bufferedPosition
            )
            return false
        }

        val confirmedState = reachedCandidateState ?: return false
        if (confirmedState.playbackState == PlaybackState.ERROR) {
            Timber.w("LivePlayback failed with player error before confirmation")
            return false
        }

        delay(confirmationWindowMs)
        val confirmationSnapshot = state.value
        val stablePlayback = isStableLivePlaybackAfterConfirmation(
            confirmationSnapshot = confirmationSnapshot,
            initiallyConfirmedSnapshot = confirmedState
        )
        if (!stablePlayback) {
            Timber.w(
                "LivePlayback confirmation window expired without stable playback: state=%s confirmed=%s video=%sx%s firstFrame=%s surfaceReady=%s audioSession=%d position=%d buffered=%d",
                confirmationSnapshot.playbackState,
                confirmationSnapshot.isPlaybackConfirmed,
                confirmationSnapshot.videoWidth,
                confirmationSnapshot.videoHeight,
                confirmationSnapshot.hasRenderedFirstFrame,
                confirmationSnapshot.isSurfaceReady,
                confirmationSnapshot.audioSessionId,
                confirmationSnapshot.currentPosition,
                confirmationSnapshot.bufferedPosition
            )
        }
        return stablePlayback
    }

    private fun shouldExtendLiveWarmup(snapshot: PlayerState): Boolean {
        if (!snapshot.hasVideoTrack ||
            !snapshot.hasAudioTrack ||
            snapshot.playbackState == PlaybackState.ERROR
        ) {
            return false
        }

        if (snapshot.hasRenderedFirstFrame ||
            snapshot.videoWidth > 0 ||
            snapshot.videoHeight > 0 ||
            !snapshot.isSurfaceReady ||
            snapshot.audioSessionId <= 0
        ) {
            return false
        }

        val bufferedAheadMs =
            (snapshot.bufferedPosition - snapshot.currentPosition).coerceAtLeast(0L)

        return snapshot.currentPosition >= LIVE_WARMUP_MIN_PROGRESS_MS &&
            bufferedAheadMs >= LIVE_WARMUP_MIN_BUFFER_AHEAD_MS
    }

    private fun isUnavailableHttpStatus(errorMessage: String?): Boolean {
        val message = errorMessage.orEmpty()
        return listOf(403, 404, 410, 429, 503).any { code ->
            message.contains("Response code: $code") ||
                message.contains("HTTP $code") ||
                message.contains("code: $code")
        }
    }

    private fun shouldRetryCurrentLiveCandidate(candidate: String, snapshot: PlayerState): Boolean {
        if (!candidate.contains("/live/")) {
            return true
        }

        val videoPipelineStalled = snapshot.hasVideoTrack &&
            snapshot.hasAudioTrack &&
            snapshot.isPlaying &&
            snapshot.isSurfaceReady &&
            snapshot.audioSessionId > 0 &&
            !snapshot.hasRenderedFirstFrame &&
            snapshot.videoWidth == 0 &&
            snapshot.videoHeight == 0 &&
            snapshot.currentPosition >= LIVE_WARMUP_MIN_PROGRESS_MS

        return !videoPipelineStalled
    }

    private fun isStableLivePlaybackAfterConfirmation(
        confirmationSnapshot: PlayerState,
        initiallyConfirmedSnapshot: PlayerState
    ): Boolean {
        if (confirmationSnapshot.playbackState == PlaybackState.PLAYING &&
            confirmationSnapshot.isPlaybackConfirmed
        ) {
            return true
        }

        val wasConfirmed = initiallyConfirmedSnapshot.isPlaybackConfirmed
        if (!wasConfirmed || confirmationSnapshot.playbackState != PlaybackState.BUFFERING) {
            return false
        }

        val hasRenderableVideo = confirmationSnapshot.hasRenderedFirstFrame &&
            confirmationSnapshot.isSurfaceReady &&
            confirmationSnapshot.videoWidth > 0 &&
            confirmationSnapshot.videoHeight > 0
        val hasAudiblePlayback = confirmationSnapshot.audioSessionId > 0
        val advancedPlayback = confirmationSnapshot.currentPosition >= 250L
        val bufferedAheadMs =
            (confirmationSnapshot.bufferedPosition - confirmationSnapshot.currentPosition).coerceAtLeast(0L)

        val transientRebufferAccepted = advancedPlayback &&
            bufferedAheadMs >= 1_500L &&
            ((confirmationSnapshot.hasVideoTrack && hasRenderableVideo) ||
                (!confirmationSnapshot.hasVideoTrack && hasAudiblePlayback))

        if (transientRebufferAccepted) {
            Timber.d(
                "LivePlayback accepted after confirmed start despite transient buffering: position=%d bufferedAhead=%d video=%s audio=%s",
                confirmationSnapshot.currentPosition,
                bufferedAheadMs,
                confirmationSnapshot.hasVideoTrack,
                confirmationSnapshot.hasAudioTrack
            )
        }

        return transientRebufferAccepted
    }

    /** Every start/retry/fallback route and transport resume uses the same stored-identity policy. */
    private suspend fun checkContentAccess(): Boolean {
        val id = currentContentId
        val type = currentContentType
        val allowed = contentRepository.canPlay(id, type)
        if (id != currentContentId || type != currentContentType) return false
        if (!allowed) blockContentAccess() else accessDenied = false
        return allowed
    }

    private suspend fun playAuthorized(url: String, startPosition: Long, profile: PlaybackProfile): Boolean {
        if (!playbackAllowed || exitRequested || !playerManager.isOwner(playbackOwner)) return false
        val contentId = currentContentId
        val contentType = currentContentType
        if (!checkContentAccess()) return false
        if (!playbackAllowed || exitRequested || !playerManager.isOwner(playbackOwner)) return false
        accessDenied = false
        contentRequestId = null
        playerManager.play(url, startPosition, profile, playbackOwner)
        if (!playerManager.isOwner(playbackOwner) || contentId != currentContentId || contentType != currentContentType) return false
        contentRequestId = playbackMetrics.value.requestId
        if (state.value.requestId == contentRequestId && state.value.isPlaybackConfirmed) {
            hasConfirmedCurrentContent = true
            captureProgress()?.let { lastProgressSnapshot = it }
        }
        return true
    }

    private suspend fun blockContentAccess() {
        accessDenied = true
        retryManager.cancel()
        _session.value = PlayerSessionState()
        _channelBrowserChannels.value = emptyList()
        _channelListEpgPrograms.value = emptyMap()
        _currentChannelEpgPrograms.value = emptyList()
        _currentEpgProgram.value = null
        liveNavigationChannels = emptyList()
        currentTitle = ""
        _liveChannelSwitch.value = LiveChannelSwitchState()
        playerManager.stop(playbackOwner)
        playerManager.reportPlaybackFailure(com.idealplayer.app.core.player.PlaybackFailure.Authorization, playbackOwner)
        _playerReady.value = true
    }

    private suspend fun resetPlaybackAttempt() {
        playerManager.stop(playbackOwner)
        delay(30L)
    }

    private fun playbackProfileFor(contentType: String): PlaybackProfile {
        return if (contentType.equals(ContentType.LIVE.name, ignoreCase = true) && currentCatchup == null) {
            PlaybackProfile.LIVE
        } else {
            PlaybackProfile.VOD
        }
    }

    private fun currentPlaybackProfile(): PlaybackProfile {
        return playbackProfileFor(currentContentType)
    }

    private fun buildEpisodePlayerTitle(seriesName: String, episode: Episode): String {
        val prefix = buildString {
            if (seriesName.isNotBlank()) {
                append(seriesName)
                append(" ")
            }
            append("S${episode.seasonNumber}E${episode.episodeNumber}")
        }

        return if (episode.name.isNotBlank()) {
            "$prefix • ${episode.name}"
        } else {
            prefix
        }
    }
}

internal fun liveNavigationAnchor(
    currentChannelId: Long?,
    switchingTargetChannelId: Long?,
    latestRequestedChannelId: Long?
): Long? = latestRequestedChannelId ?: switchingTargetChannelId ?: currentChannelId

internal fun adjacentLiveChannel(
    channels: List<Channel>,
    anchorChannelId: Long?,
    offset: Int
): Channel? {
    if (anchorChannelId == null || offset == 0) return null
    val anchorIndex = channels.indexOfFirst { it.id == anchorChannelId }
    if (anchorIndex < 0) return null
    return channels.getOrNull(anchorIndex + offset)
}

internal fun liveNavigationChannelsForTarget(
    targetChannel: Channel,
    browserChannels: List<Channel>,
    retainedChannels: List<Channel>,
    sessionChannels: List<Channel>
): List<Channel> = when {
    browserChannels.any { it.id == targetChannel.id } -> browserChannels
    retainedChannels.any { it.id == targetChannel.id } -> retainedChannels
    sessionChannels.any { it.id == targetChannel.id } -> sessionChannels
    else -> listOf(targetChannel)
}

internal fun shouldTriggerAutoPlayNextEpisode(
    isSeriesPlayback: Boolean,
    autoPlayEnabled: Boolean,
    transitionInProgress: Boolean,
    contentId: Long,
    alreadyTriggeredContentId: Long?,
    playbackState: PlaybackState,
    currentPosition: Long,
    duration: Long,
    minimumDuration: Long,
    endTolerance: Long
): Boolean {
    if (!isSeriesPlayback || !autoPlayEnabled || transitionInProgress || contentId <= 0L) return false
    if (alreadyTriggeredContentId == contentId) return false
    if (playbackState == PlaybackState.ENDED) return true

    val activeNearEndState = playbackState == PlaybackState.PLAYING ||
        playbackState == PlaybackState.BUFFERING
    return duration >= minimumDuration &&
        currentPosition >= duration - endTolerance &&
        activeNearEndState
}

internal fun preferredFormatForLiveUrl(
    url: String,
    preferredFormat: LiveStreamFormat
): LiveStreamFormat {
    return if (url.contains("/live/", ignoreCase = true)) preferredFormat else LiveStreamFormat.AUTO
}

internal fun buildLiveStreamCandidates(
    trimmedUrl: String,
    defaultResolvedUrl: String,
    preferredFormat: LiveStreamFormat,
    enableTvWorkarounds: Boolean = false
): List<String> {
    val isLivePath = trimmedUrl.contains("/live/", ignoreCase = true)
    val baseWithoutExt = when {
        trimmedUrl.endsWith(".m3u8", ignoreCase = true) -> trimmedUrl.dropLast(5)
        trimmedUrl.endsWith(".ts", ignoreCase = true) -> trimmedUrl.dropLast(3)
        else -> null
    }
    return buildList {
        if (isLivePath && baseWithoutExt != null) {
            when (preferredFormat) {
                LiveStreamFormat.TS -> {
                    add("$baseWithoutExt.ts")
                    add("$baseWithoutExt.m3u8")
                }
                LiveStreamFormat.HLS -> {
                    add("$baseWithoutExt.m3u8")
                    add("$baseWithoutExt.ts")
                }
                LiveStreamFormat.AUTO -> {
                    add(defaultResolvedUrl)
                    if (trimmedUrl != defaultResolvedUrl) add(trimmedUrl)
                    if (trimmedUrl.endsWith(".m3u8", ignoreCase = true)) {
                        add("$baseWithoutExt.ts")
                    } else if (trimmedUrl.endsWith(".ts", ignoreCase = true)) {
                        add("$baseWithoutExt.m3u8")
                    }
                }
            }
        } else {
            add(defaultResolvedUrl)
            if (trimmedUrl != defaultResolvedUrl) add(trimmedUrl)
        }
        if (enableTvWorkarounds && trimmedUrl != defaultResolvedUrl) {
            add(defaultResolvedUrl)
        }
    }
}

internal fun seriesPlaybackCandidates(url: String): List<String> {
    val source = parsePlaybackSource(url)
    val trimmedUrl = source.url
    if (trimmedUrl.isBlank() || !trimmedUrl.contains("/series/", ignoreCase = true)) {
        return listOf(withPlaybackHeaders(trimmedUrl, source.headers, source.subtitleUrls))
            .filter(String::isNotBlank)
    }

    val suffixIndex = sequenceOf(trimmedUrl.indexOf('?'), trimmedUrl.indexOf('#'))
        .filter { it >= 0 }
        .minOrNull()
        ?: trimmedUrl.length
    val baseUrl = trimmedUrl.substring(0, suffixIndex)
    val suffix = trimmedUrl.substring(suffixIndex)
    val streamId = baseUrl.substringAfterLast('/')

    if (streamId.contains('.')) {
        return listOf(withPlaybackHeaders(trimmedUrl, source.headers, source.subtitleUrls))
    }

    return buildList {
        add(withPlaybackHeaders(trimmedUrl, source.headers, source.subtitleUrls))
        listOf("mkv", "mp4", "ts").forEach { extension ->
            add(withPlaybackHeaders("$baseUrl.$extension$suffix", source.headers, source.subtitleUrls))
        }
    }.distinct()
}

internal fun playbackStartPosition(requestedPosition: Long, autoResumeEnabled: Boolean): Long =
    if (autoResumeEnabled) requestedPosition.coerceAtLeast(0L) else 0L

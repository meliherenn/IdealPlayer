package com.idealplayer.app.ui.player

import com.idealplayer.app.core.player.PlaybackState
import com.idealplayer.app.core.player.PlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/** Pausing, buffering or replacing the request resets the uninterrupted viewing interval. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun awaitLiveHistoryDwell(states: Flow<PlayerState>, requestId: Long, dwellMs: Long) {
    states.map {
        it.requestId == requestId && it.isPlaybackConfirmed && it.isPlaying &&
            it.playbackState == PlaybackState.PLAYING
    }.distinctUntilChanged().transformLatest { playing ->
        if (playing) {
            delay(dwellMs)
            emit(Unit)
        }
    }.first()
}

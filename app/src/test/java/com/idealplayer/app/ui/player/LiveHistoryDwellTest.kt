package com.idealplayer.app.ui.player

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.player.PlaybackState
import com.idealplayer.app.core.player.PlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveHistoryDwellTest {
    private val playing = PlayerState(requestId = 7, playbackState = PlaybackState.PLAYING,
        isPlaying = true, isPlaybackConfirmed = true)

    @Test fun `pause and buffering each require a fresh uninterrupted interval`() = runTest {
        val states = MutableStateFlow(playing)
        val dwell = backgroundScope.async { awaitLiveHistoryDwell(states, 7, 15_000) }
        runCurrent()
        advanceTimeBy(14_000)
        states.value = playing.copy(isPlaying = false, playbackState = PlaybackState.PAUSED)
        runCurrent()
        advanceTimeBy(30_000)
        assertThat(dwell.isCompleted).isFalse()
        states.value = playing
        runCurrent()
        advanceTimeBy(14_000)
        states.value = playing.copy(playbackState = PlaybackState.BUFFERING)
        runCurrent()
        advanceTimeBy(30_000)
        assertThat(dwell.isCompleted).isFalse()
        states.value = playing
        runCurrent()
        advanceTimeBy(14_999)
        assertThat(dwell.isCompleted).isFalse()
        advanceTimeBy(1)
        runCurrent()
        assertThat(dwell.isCompleted).isTrue()
    }

    @Test fun `confirmation and matching request are required and progress ticks do not reset dwell`() = runTest {
        val states = MutableStateFlow(playing.copy(isPlaybackConfirmed = false))
        val dwell = backgroundScope.async { awaitLiveHistoryDwell(states, 7, 15_000) }
        runCurrent()
        advanceTimeBy(20_000)
        assertThat(dwell.isCompleted).isFalse()
        states.value = playing.copy(requestId = 8)
        runCurrent()
        advanceTimeBy(20_000)
        assertThat(dwell.isCompleted).isFalse()
        states.value = playing
        runCurrent()
        repeat(15) {
            advanceTimeBy(1_000)
            states.value = playing.copy(currentPosition = it * 1_000L)
            runCurrent()
        }
        assertThat(dwell.isCompleted).isTrue()
    }

    @Test fun `changing request during dwell cannot record the previous channel`() = runTest {
        val states = MutableStateFlow(playing)
        val dwell = backgroundScope.async { awaitLiveHistoryDwell(states, 7, 15_000) }
        runCurrent()
        advanceTimeBy(14_000)
        states.value = playing.copy(requestId = 8)
        runCurrent()
        advanceTimeBy(60_000)
        assertThat(dwell.isCompleted).isFalse()
        dwell.cancel()
    }
}

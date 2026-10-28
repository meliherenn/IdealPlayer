package com.idealplayer.app.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioOutputReadinessTest {
    @Test fun replacementDecoderCannotInheritCounterBaselinesEvenWithIdenticalCounts() {
        val readiness = AudioOutputReadiness()
        val old = Any()
        val replacement = Any()
        readiness.reset(rendered = 100, skipped = 200, decoder = old)
        readiness.observe(100, 200, true, 0, decoder = replacement)
        assertThat(readiness.hasRenderedOutput).isTrue()
        readiness.reset(skipped = 200, decoder = old)
        readiness.observe(0, 200, true, 100, decoder = replacement)
        assertThat(readiness.observe(0, 200, true, 3_100, decoder = replacement)).isTrue()
    }
    @Test fun oldCountersCannotConfirmNewRequestAndNewDecoderCanStartFromZero() {
        val readiness = AudioOutputReadiness()
        readiness.reset(rendered = 100, skipped = 200)
        assertThat(readiness.observe(100, 200, true, 10_000)).isFalse()
        assertThat(readiness.hasRenderedOutput).isFalse()
        readiness.observe(101, 200, true, 10_100)
        assertThat(readiness.hasRenderedOutput).isTrue()
        readiness.reset(rendered = 101, skipped = 200)
        readiness.observe(1, 0, true, 20_000)
        assertThat(readiness.hasRenderedOutput).isTrue()
    }
    @Test fun repeatedDiscardedPacketsFailWithoutAcceptingVideoProgressAsAudio() {
        val readiness = AudioOutputReadiness()
        assertThat(readiness.observe(0, 100, true, 100)).isFalse()
        assertThat(readiness.observe(0, 200, true, 3_099)).isFalse()
        assertThat(readiness.observe(0, 200, true, 3_100)).isTrue()
        assertThat(readiness.hasRenderedOutput).isFalse()
    }

    @Test fun bufferingOrDelayedAudioWithoutDiscardsDoesNotFail() {
        val readiness = AudioOutputReadiness()
        assertThat(readiness.observe(0, 0, true, 0)).isFalse()
        assertThat(readiness.observe(0, 0, true, 60_000)).isFalse()
        assertThat(readiness.observe(0, 100, false, 90_000)).isFalse()
    }

    @Test fun validOutputDuringStartupClearsTheDiscardWindow() {
        val readiness = AudioOutputReadiness()
        readiness.observe(0, 100, true, 0)
        assertThat(readiness.observe(1, 100, true, 2_900)).isFalse()
        assertThat(readiness.observe(1, 200, true, 10_000)).isFalse()
        assertThat(readiness.hasRenderedOutput).isTrue()
    }

    @Test fun pauseAndDecoderCounterResetRestartTheObservationWindow() {
        val readiness = AudioOutputReadiness()
        readiness.observe(0, 100, true, 0)
        readiness.observe(0, 100, false, 2_000)
        assertThat(readiness.observe(0, 100, true, 10_000)).isFalse()
        readiness.observe(0, 0, true, 11_000)
        assertThat(readiness.observe(0, 100, true, 20_000)).isFalse()
    }

    @Test fun newRequestCannotInheritSuccessfulAudioOrAnOldFailureWindow() {
        val readiness = AudioOutputReadiness()
        readiness.observe(1, 0, true, 0)
        readiness.reset()
        assertThat(readiness.hasRenderedOutput).isFalse()
        readiness.observe(0, 100, true, 100)
        readiness.reset()
        assertThat(readiness.observe(0, 100, true, 10_000)).isFalse()
    }
}

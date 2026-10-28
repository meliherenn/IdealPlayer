package com.idealplayer.app.ui.components

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvLazyListFocusTest {
    @Test fun `rapid page keys accumulate from pending target and only focus latest row`() = runTest {
        val focused = mutableListOf<Int>()
        val scrolled = mutableListOf<Int>()
        val focus = TvLazyListFocus(this, { (0..99).toList() }, { scrolled += it },
            { delay(16) }, { focused += it; true })
        focus.moveBy(0, 10)
        runCurrent()
        focus.moveBy(0, 10)
        focus.moveBy(0, 10)
        advanceUntilIdle()
        assertThat(focused).containsExactly(30)
        assertThat(scrolled.last()).isEqualTo(30)
        focus.moveBy(30, -100)
        advanceUntilIdle()
        assertThat(focused.last()).isEqualTo(0)
        focus.moveBy(0, 1000)
        advanceUntilIdle()
        assertThat(focused.last()).isEqualTo(99)
    }

    @Test fun `removed target cannot receive focus after a filter replacement`() = runTest {
        var keys = listOf(1L, 2L, 3L)
        var requested = false
        val focus = TvLazyListFocus(this, { keys }, {}, { keys = listOf(4L, 5L) },
            { requested = true; true })
        assertThat(focus.focusItem(2L)).isFalse()
        assertThat(requested).isFalse()
        focus.moveBy(2L, 10)
        advanceUntilIdle()
        assertThat(requested).isFalse()
    }

    @Test fun `offscreen row waits for attachment and uses its stable id after append`() = runTest {
        var keys = listOf(30L, 40L)
        var frames = 0
        var focused: Long? = null
        val indices = mutableListOf<Int>()
        val focus = TvLazyListFocus(this, { keys }, { indices += it }, {
            frames++
            keys = listOf(20L, 30L, 40L, 50L)
        }, { if (frames >= 2) { focused = it; true } else false })
        assertThat(focus.focusItem(40L)).isTrue()
        assertThat(indices).containsExactly(1, 2).inOrder()
        assertThat(focused).isEqualTo(40L)
    }

    @Test fun `unattached target retries are bounded and empty list is harmless`() = runTest {
        var attempts = 0
        var keys = listOf(1)
        val focus = TvLazyListFocus(this, { keys }, {}, {}, { attempts++; false })
        assertThat(focus.focusItem(1)).isFalse()
        assertThat(attempts).isEqualTo(4)
        keys = emptyList()
        focus.moveBy(1, 10)
        advanceUntilIdle()
        assertThat(focus.focusItem(1)).isFalse()
        assertThat(attempts).isEqualTo(4)
    }
}

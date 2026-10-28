package com.idealplayer.app.core.worker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatalogRefreshWorkerTest {
    @org.junit.Test fun `fresh catalog survives brief app switches but stale missing and clock reset refresh`() {
        val now = 2_000_000L
        assertThat(catalogNeedsForegroundRefresh(now - 1, true, now)).isFalse()
        assertThat(catalogNeedsForegroundRefresh(now - 899_999, true, now)).isFalse()
        assertThat(catalogNeedsForegroundRefresh(now - 900_000, true, now)).isTrue()
        assertThat(catalogNeedsForegroundRefresh(now, false, now)).isTrue()
        assertThat(catalogNeedsForegroundRefresh(0, true, now)).isTrue()
        assertThat(catalogNeedsForegroundRefresh(now + 1, true, now)).isTrue()
    }

    @Test
    fun `foreground catalog refresh waits for the first UI render`() {
        assertThat(catalogRefreshInitialDelaySeconds(isTv = false)).isAtLeast(1L)
        assertThat(catalogRefreshInitialDelaySeconds(isTv = true)).isAtMost(5L)
        assertThat(catalogRefreshInitialDelaySeconds(isTv = true))
            .isGreaterThan(catalogRefreshInitialDelaySeconds(isTv = false))
    }
}

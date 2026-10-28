package com.idealplayer.app.ui.catalog

import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.core.model.CatalogPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogPagerTest {
    @Test fun `selection change rejects a late result even when its loader ignores cancellation`() = runTest {
        val old = CompletableDeferred<Unit>()
        val pager = CatalogPager<Int>(2)
        pager.reset(backgroundScope) { _, _ -> withContext(NonCancellable) { old.await(); CatalogPage(listOf(1, 2), 4) } }
        runCurrent()
        pager.reset(backgroundScope) { _, _ -> CatalogPage(listOf(10), 1) }
        runCurrent()
        old.complete(Unit)
        runCurrent()
        assertThat(pager.state.value.items).containsExactly(10)
        assertThat(pager.state.value.canLoadMore).isFalse()
    }

    @Test fun `failed append retries the same offset and retains earlier rows`() = runTest {
        val offsets = mutableListOf<Int>()
        var fail = true
        val pager = CatalogPager<Int>(2)
        pager.reset(backgroundScope) { offset, _ ->
            offsets += offset
            if (offset == 2 && fail) { fail = false; error("transient") }
            CatalogPage(if (offset == 0) listOf(1, 2) else listOf(3), 3)
        }
        runCurrent(); pager.append(); runCurrent()
        assertThat(pager.state.value.failed).isTrue()
        assertThat(pager.state.value.items).containsExactly(1, 2).inOrder()
        pager.retry(); runCurrent()
        assertThat(offsets).containsExactly(0, 2, 2).inOrder()
        assertThat(pager.state.value.items).containsExactly(1, 2, 3).inOrder()
        pager.clear()
        assertThat(pager.state.value.items).isEmpty()
    }
}

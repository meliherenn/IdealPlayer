package com.idealplayer.app.ui.catalog

import com.idealplayer.app.core.common.rethrowIfCancellation
import com.idealplayer.app.core.model.CatalogPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class CatalogPageState<T>(
    val items: List<T> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val failed: Boolean = false
)

/** One source/selection owns every page, including refreshes and append retries. */
internal class CatalogPager<T>(private val pageSize: Int = 60) {
    private val mutable = MutableStateFlow(CatalogPageState<T>())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private var fetch: (suspend (Int, Int) -> CatalogPage<T>)? = null
    private var retryAppend = false
    private var retryTarget = pageSize

    fun clear() {
        generation++
        job?.cancel()
        scope = null
        fetch = null
        mutable.value = CatalogPageState()
    }

    fun reset(
        scope: CoroutineScope,
        retained: List<T> = emptyList(),
        fetch: suspend (offset: Int, limit: Int) -> CatalogPage<T>
    ) {
        generation++
        job?.cancel()
        this.scope = scope
        this.fetch = fetch
        mutable.value = CatalogPageState(items = retained, isLoading = retained.isEmpty())
        load(append = false, target = maxOf(pageSize, retained.size))
    }

    fun append() {
        val current = mutable.value
        if (job?.isActive == true || current.isLoading || !current.canLoadMore || current.failed) return
        load(append = true)
    }

    fun retry() {
        if (job?.isActive == true || !mutable.value.failed) return
        load(append = retryAppend, target = retryTarget)
    }

    private fun load(append: Boolean, target: Int = pageSize) {
        val owner = generation
        val loader = fetch ?: return
        val previous = mutable.value
        retryAppend = append
        retryTarget = target
        mutable.value = previous.copy(
            isLoading = !append && previous.items.isEmpty(), isLoadingMore = append, failed = false
        )
        job = scope?.launch {
            try {
                val result = mutableListOf<T>()
                if (append) result += previous.items
                var total: Int
                do {
                    val page = loader(result.size, pageSize)
                    currentCoroutineContext().ensureActive()
                    if (owner != generation) return@launch
                    result += page.items
                    total = page.totalCount
                    if (page.items.isEmpty()) break
                } while (!append && result.size < target && result.size < total)
                if (owner == generation) {
                    mutable.value = CatalogPageState(
                        items = result, totalCount = total, canLoadMore = result.size < total
                    )
                }
            } catch (error: Exception) {
                error.rethrowIfCancellation()
                if (owner == generation) mutable.value = previous.copy(
                    isLoading = false, isLoadingMore = false, failed = true
                )
            }
        }
    }
}

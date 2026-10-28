package com.idealplayer.app.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Scroll and focus form one operation: the destination may not yet be composed. */
internal class TvLazyListFocus<K : Any>(
    private val scope: CoroutineScope,
    private val keys: () -> List<K>,
    private val scrollTo: suspend (Int) -> Unit,
    private val awaitLayout: suspend () -> Unit,
    private val requestFocus: (K) -> Boolean
) {
    private var moveJob: Job? = null
    private var pendingKey: K? = null
    private var moveGeneration = 0L

    fun moveBy(from: K, delta: Int) {
        val items = keys()
        if (items.isEmpty()) return
        val index = items.indexOf(pendingKey ?: from)
        if (index < 0) return
        moveTo(items[(index + delta).coerceIn(items.indices)])
    }

    fun moveTo(key: K) {
        moveJob?.cancel()
        val generation = ++moveGeneration
        pendingKey = key
        moveJob = scope.launch {
            try {
                focusItem(key)
            } finally {
                if (moveGeneration == generation) pendingKey = null
            }
        }
    }

    suspend fun focusItem(key: K): Boolean {
        repeat(4) {
            val index = keys().indexOf(key)
            if (index < 0) return false
            scrollTo(index)
            awaitLayout()
            // A filter, refresh or page replacement can occur while layout is pending.
            if (key !in keys()) return false
            if (requestFocus(key)) return true
        }
        return false
    }
}

internal class TvLazyFocusRegistry<K : Any>(val navigator: TvLazyListFocus<K>) {
    internal val attached = mutableMapOf<K, FocusRequester>()
    fun requester(key: K?): FocusRequester? = attached[key]
}

@Composable
internal fun <K : Any> rememberTvLazyListFocus(
    state: LazyListState,
    keys: List<K>
): TvLazyFocusRegistry<K> {
    val currentKeys = rememberUpdatedState(keys)
    val scope = rememberCoroutineScope()
    return remember(state, scope) {
        lateinit var registry: TvLazyFocusRegistry<K>
        val navigator = TvLazyListFocus(
            scope = scope,
            keys = { currentKeys.value },
            scrollTo = { state.scrollToItem(it) },
            awaitLayout = { withFrameNanos { } },
            requestFocus = { key ->
                runCatching { registry.requester(key)?.requestFocus() == true }.getOrDefault(false)
            }
        )
        registry = TvLazyFocusRegistry(navigator)
        registry
    }
}

/** Only composed rows retain requesters; page appends do not replace existing ones. */
@Composable
internal fun <K : Any> Modifier.tvLazyItemFocus(
    registry: TvLazyFocusRegistry<K>,
    key: K
): Modifier {
    val requester = remember(registry, key) { FocusRequester() }
    DisposableEffect(registry, key, requester) {
        registry.attached[key] = requester
        onDispose { registry.attached.remove(key, requester) }
    }
    return this.focusRequester(requester)
}

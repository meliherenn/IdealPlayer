package com.idealplayer.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idealplayer.app.core.common.rethrowIfCancellation
import com.idealplayer.app.core.security.ParentalAccessState
import com.idealplayer.app.core.security.ParentalControlManager
import com.idealplayer.app.data.repository.ContentRepository
import com.idealplayer.app.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal enum class ParentalAdminAction { TOGGLE_LOCK, CLEAR_PIN, CHANGE_PIN, CATEGORIES }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ParentalControlViewModel @Inject constructor(
    private val manager: ParentalControlManager,
    playlists: PlaylistRepository,
    content: ContentRepository
) : ViewModel() {
    val policy = manager.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ParentalAccessState())
    val categories = playlists.getActivePlaylistSummary().flatMapLatest { playlist ->
        playlist?.let { content.getParentalCategories(it.id) } ?: flowOf(emptyList())
    }.combine(policy) { names, state ->
        (names + state.allowedCategories.filter { allowed -> names.none { it.equals(allowed, ignoreCase = true) } })
            .filter(String::isNotBlank).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    private var authorization: ParentalControlManager.Authorization? = null
    private var administrationGeneration = 0L

    internal fun verify(pin: String, action: ParentalAdminAction, complete: (Boolean) -> Unit) = execute(complete) {
        authorization = manager.authorize(pin)
        if (authorization == null) return@execute false
        when (action) {
            ParentalAdminAction.TOGGLE_LOCK -> manager.setChildLock(!manager.snapshot().enabled, authorization)
            ParentalAdminAction.CLEAR_PIN -> manager.clearPin(authorization)
            ParentalAdminAction.CHANGE_PIN, ParentalAdminAction.CATEGORIES -> true
        }
    }

    fun setPin(pin: String, complete: (Boolean) -> Unit) = execute(complete) { manager.setPin(pin, authorization) }

    fun setCategoryAllowed(category: String, allowed: Boolean) = execute { manager.setCategoryAllowed(category, allowed, authorization) }

    fun finishAdministration() {
        administrationGeneration++
        authorization = null
        _failed.value = false
        viewModelScope.launch { manager.revokeAuthorization() }
    }

    private fun execute(complete: (Boolean) -> Unit = {}, action: suspend () -> Boolean) {
        if (_busy.value) return
        _busy.value = true
        val generation = administrationGeneration
        _failed.value = false
        viewModelScope.launch {
            try {
                val ok = runCatching { action() }.getOrElse { it.rethrowIfCancellation(); false }
                if (generation != administrationGeneration) {
                    authorization = null
                    manager.revokeAuthorization()
                    return@launch
                }
                _failed.value = !ok
                complete(ok)
            } finally {
                _busy.value = false
            }
        }
    }
}

package com.idealplayer.app.core.model

enum class EpgSyncFailure { INVALID_DOCUMENT, TRANSPORT, SIZE_LIMIT, STORAGE }

sealed interface EpgSyncResult {
    data class Success(val programCount: Int) : EpgSyncResult
    data class Failure(val reason: EpgSyncFailure) : EpgSyncResult
    data object StaleSource : EpgSyncResult
}

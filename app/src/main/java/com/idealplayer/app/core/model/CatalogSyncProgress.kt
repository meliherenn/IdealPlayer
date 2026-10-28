package com.idealplayer.app.core.model

/** User-visible, provider-agnostic phases of a catalog refresh. */
enum class CatalogSyncStage {
    PREPARING,
    LOADING_SOURCE,
    PERSISTING_CATALOG,
    FINALIZING,
    SCHEDULING_EPG,
    COMPLETED
}

/**
 * Privacy-safe progress for one playlist refresh. It deliberately carries only a local id and
 * item counts; source URLs and credentials must never cross into UI state or diagnostics.
 */
data class CatalogSyncProgress(
    val playlistId: Long,
    val stage: CatalogSyncStage,
    val processedItems: Int = 0,
    val totalItems: Int? = null
) {
    init {
        require(playlistId > 0L) { "Playlist id must be positive" }
        require(processedItems >= 0) { "Processed item count cannot be negative" }
        require(totalItems == null || totalItems >= 0) { "Total item count cannot be negative" }
    }

    /**
     * Loading is indeterminate because providers rarely expose a trustworthy byte/item total.
     * Persistence becomes determinate once the parsed catalog size is known.
     */
    val overallFraction: Float?
        get() = when (stage) {
            CatalogSyncStage.PREPARING -> 0.05f
            CatalogSyncStage.LOADING_SOURCE -> null
            CatalogSyncStage.PERSISTING_CATALOG -> {
                val total = totalItems?.takeIf { it > 0 } ?: return null
                0.35f + (processedItems.coerceAtMost(total).toFloat() / total) * 0.5f
            }
            CatalogSyncStage.FINALIZING -> 0.9f
            CatalogSyncStage.SCHEDULING_EPG -> 0.96f
            CatalogSyncStage.COMPLETED -> 1f
        }
}

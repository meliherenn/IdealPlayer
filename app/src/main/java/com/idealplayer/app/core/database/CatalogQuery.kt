package com.idealplayer.app.core.database

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

internal enum class CatalogTable(val sqlName: String, val categoryColumn: String) {
    CHANNELS("channels", "groupTitle"), MOVIES("movies", "categoryName"), SERIES("series", "categoryName")
}

/** Permissions are applied before both COUNT and LIMIT. Values never become SQL syntax. */
internal fun catalogQuery(
    table: CatalogTable,
    playlistId: Long,
    category: String?,
    allowedCategories: List<String>?,
    sort: Int = 0,
    query: String = "",
    limit: Int = 60,
    offset: Int = 0,
    count: Boolean = false
): SupportSQLiteQuery {
    val args = mutableListOf<Any>(playlistId)
    val conditions = mutableListOf("playlistId = ?")
    if (category != null) {
        conditions += "${table.categoryColumn} = ?"
        args += category
    }
    if (allowedCategories != null) {
        // UTF-8 blob literals handle provider names (including NUL) without SQL interpolation
        // or Android 8's 999-bind limit. These names were policy-matched in Kotlin.
        conditions += if (allowedCategories.isEmpty()) "0" else
            "${table.categoryColumn} IN (${allowedCategories.joinToString(",") { name ->
                val hex = name.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
                "CAST(X'$hex' AS TEXT)"
            }})"
    }
    if (query.isNotBlank()) {
        conditions += "name LIKE ? ESCAPE '\\'"
        args += "%${query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
    }
    val titleOrder = "TRIM(name) COLLATE LOCALIZED ASC, id ASC"
    val order = when {
        table == CatalogTable.CHANNELS -> "sortOrder ASC, id ASC"
        sort == 1 -> "MAX(rating, 0) DESC, $titleOrder"
        sort == 2 -> "addedAt DESC, sourceOrder ASC, id DESC"
        else -> titleOrder
    }
    val suffix = if (count) "" else {
        args += limit.coerceIn(1, 500)
        args += offset.coerceAtLeast(0)
        " ORDER BY $order LIMIT ? OFFSET ?"
    }
    val columns = when {
        count -> "COUNT(*)"
        table == CatalogTable.CHANNELS -> CHANNEL_CATALOG_COLUMNS
        table == CatalogTable.MOVIES -> MOVIE_CATALOG_COLUMNS
        else -> "*" // Series entities contain no playback credential fields.
    }
    return SimpleSQLiteQuery(
        "SELECT ${columns} FROM ${table.sqlName} WHERE ${conditions.joinToString(" AND ")}$suffix",
        args.toTypedArray()
    )
}

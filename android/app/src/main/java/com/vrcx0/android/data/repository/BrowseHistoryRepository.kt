package com.vrcx0.android.data.repository

import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.wireJson
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Reads and records the browse history the server accumulates.
 *
 * Mirrors the desktop app: opening a world / user / avatar / group detail
 * records one row (`app__browse_history_record`), and a history view reads them
 * back (`app__browse_history_query`). The record is fire-and-forget on the
 * client side -- a failed write must never block opening the thing the user
 * actually tapped.
 *
 * Both commands are INPUT_COMMANDS (the whole body travels under `input`), so
 * the repository passes the fields at the TOP LEVEL of the args map and
 * [com.vrcx0.android.data.remote.CommandRunner]'s encode wraps them under
 * `input` on the wire. Passing `mapOf("input" to ...)` here would double-wrap.
 */
class BrowseHistoryRepository(private val runner: CommandRunner) {

    /** Records one view. Never throws; a failed write is silently dropped. */
    suspend fun record(
        userId: String,
        kind: String,
        entityId: String,
        title: String = "",
        imageUrl: String = ""
    ) {
        if (entityId.isBlank()) return
        runCatching {
            runner(
                "app__browse_history_record",
                mapOf(
                    "ownerUserId" to userId,
                    "entityKind" to kind,
                    "entityId" to entityId,
                    "title" to title,
                    "imageUrl" to imageUrl
                )
            )
        }
    }

    /** Newest history, optionally scoped to one kind. */
    suspend fun query(
        userId: String,
        kind: String? = null,
        limit: Int = 100
    ): List<BrowseHistoryItem> {
        // INPUT_COMMAND: fields at the top level, encode wraps them under
        // `input`. The server reads the whole input as BrowseHistoryQueryInput.
        val reply = runner(
            "app__browse_history_query",
            mapOf(
                "ownerUserId" to userId,
                "limit" to limit,
                "entityKind" to kind,
                "search" to "",
                "dateFrom" to "",
                "dateTo" to ""
            )
        )
        return runCatching {
            val page = wireJson.decodeFromJsonElement<BrowseHistoryPage>(reply)
            page.items
        }.getOrDefault(emptyList())
    }
}

@kotlinx.serialization.Serializable
data class BrowseHistoryItem(
    val entityKind: String = "",
    val entityId: String = "",
    val title: String = "",
    val imageUrl: String = "",
    val firstViewedAt: String = "",
    val lastViewedAt: String = "",
    val viewCount: Long = 0
)

@kotlinx.serialization.Serializable
data class BrowseHistoryPage(
    val items: List<BrowseHistoryItem> = emptyList()
)

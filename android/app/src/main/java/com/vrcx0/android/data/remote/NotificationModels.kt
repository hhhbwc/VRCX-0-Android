package com.vrcx0.android.data.remote

import kotlinx.serialization.Serializable

/**
 * Wire models for the notifications store (`app__notification_list_query` and
 * `app__notification_mark_seen_batch`).
 *
 * Shapes mirror the desktop bindings (`NotificationListItemOutput` /
 * `NotificationListQueryInput` / `NotificationMarkSeenBatchItem` in
 * `src/platform/tauri/bindings.ts`); [wireJson] is lenient so new server
 * fields decode without touching this file.
 */
@Serializable
data class NotificationListQueryInput(
    val userId: String,
    val search: String? = null,
    val filters: List<String>? = null,
    val perTableLimit: Int? = null,
    val limit: Int? = null,
    val includeUnseen: Boolean? = null
)

@Serializable
data class NotificationListItemOutput(
    val id: String,
    val version: Int = 0,
    val type: String = "",
    val title: String = "",
    val message: String = "",
    val imageUrl: String = "",
    val seen: Boolean = false,
    val expired: Boolean = false,
    val senderUserId: String = "",
    val senderUsername: String = "",
    val receiverUserId: String = "",
    val link: String = "",
    val linkText: String = "",
    val location: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val expiresAt: String = ""
)

@Serializable
data class NotificationMarkSeenBatchItem(
    val id: String,
    val version: Int,
    // The store keeps both VRChat-fetched ("remote") and locally-generated
    // ("local") rows; the row's own `location` field carries which table it
    // lives in, echoed back here.
    val location: String
)

@Serializable
data class NotificationMarkSeenBatchInput(
    val items: List<NotificationMarkSeenBatchItem>? = null
)

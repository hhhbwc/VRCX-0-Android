package com.vrcx0.android.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrcx0.android.data.SessionServices
import com.vrcx0.android.data.repository.NotificationRepository
import com.vrcx0.android.data.remote.NotificationListItemOutput
import com.vrcx0.android.ui.i18n.tr
import kotlinx.coroutines.launch

/**
 * The notification list behind the top-bar bell. Loads on open; tapping a row
 * (or the "mark all" action) acknowledges it through
 * `app__notification_mark_seen_batch` and updates the unread badge via
 * [onChanged].
 *
 * This is the feature the P4 placeholder stubbed out (`{ /* P4 */ }` in
 * VrcxRoot) -- the server side was already there, only the client UI was
 * missing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSheet(
    services: SessionServices?,
    userId: String,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var rows by remember { mutableStateOf<List<NotificationListItemOutput>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(services, userId) {
        val runner = services?.runner ?: return@LaunchedEffect
        runCatching {
            NotificationRepository(runner).list(userId)
        }.onSuccess {
            rows = it
            failed = false
        }.onFailure {
            failed = true
        }
    }

    fun acknowledge(target: List<NotificationListItemOutput>) {
        val repo = services?.runner?.let { NotificationRepository(it) } ?: return
        scope.launch {
            val ok = repo.markSeen(target)
            if (ok) {
                rows = rows?.map { row ->
                    if (target.any { it.id == row.id }) row.copy(seen = true) else row
                }
                onChanged()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "通知".tr(),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.weight(1f))
                val unseen = rows?.count { !it.seen } ?: 0
                if (unseen > 0) {
                    TextButton(onClick = {
                        rows?.let { acknowledge(it) }
                    }) { Text("全部已读".tr()) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        val list = rows
        when {
            failed -> Text(
                text = "加载失败".tr(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
            )
            list == null -> LoadingHint()
            list.isEmpty() -> Text(
                text = "没有通知".tr(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
            )
            else -> LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(list, key = { it.id }) { row ->
                    NotificationRow(
                        row = row,
                        onClick = { if (!row.seen) acknowledge(listOf(row)) }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(row: NotificationListItemOutput, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Unread dot: the one cue a dense list needs; seen rows lose it.
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp),
        ) {
            if (!row.seen) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val title = row.title.ifBlank {
                row.senderUsername.ifBlank { row.type }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (row.seen) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (row.message.isNotBlank()) {
                Text(
                    text = row.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val time = shortTime(row.createdAt)
            if (time.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LoadingHint() {
    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp)) {
        CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center).size(24.dp),
            strokeWidth = 2.dp
        )
    }
}

/**
 * "2026-09-19T02:39:12.345Z" -> "09-19 02:39".
 *
 * Deliberately substring surgery rather than java.time: it keeps this file off
 * the desugaring question entirely and the input is always the server's ISO-8601.
 */
private fun shortTime(iso: String): String {
    if (iso.length < 16) return iso
    return iso.substring(5, 10) + " " + iso.substring(11, 16)
}

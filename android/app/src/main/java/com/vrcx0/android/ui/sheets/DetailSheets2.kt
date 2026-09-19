package com.vrcx0.android.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vrcx0.android.data.LocalAppSettings
import com.vrcx0.android.data.SessionServices
import com.vrcx0.android.data.remote.GroupSummary
import com.vrcx0.android.ui.components.RemoteImage
import com.vrcx0.android.ui.components.rememberAvatarNames
import com.vrcx0.android.ui.components.rememberGroups
import com.vrcx0.android.ui.i18n.tr

/**
 * A group's details, as a bottom sheet.
 *
 * The group name/icon come from the shared group cache. Member lists and posts
 * are deliberately not pulled here -- both are per-group live round trips, and
 * neither is requested by the current scope; the summary is what the home tab
 * already resolved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailSheet(
    services: SessionServices?,
    groupId: String,
    onDismiss: () -> Unit
) {
    val groups = rememberGroups(services)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val group: GroupSummary? = groups[groupId]

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(
                    services = services,
                    rawUrl = group?.iconUrl,
                    label = group?.name,
                    size = 64.dp,
                    cornerRadius = 12.dp
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = group?.name?.takeIf { it.isNotBlank() } ?: "群组".tr(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val caption = buildString {
                        if ((group?.memberCount ?: 0) > 0) {
                            append("${group?.memberCount} 成员")
                            if ((group?.onlineMemberCount ?: 0) > 0) {
                                append(" · ${group?.onlineMemberCount} 在线")
                            }
                        }
                    }
                    if (caption.isNotBlank()) {
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!group?.description.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = group!!.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * An avatar's detail card, opened from the profile page (current avatar, or a
 * row of "my avatars").
 *
 * The summary comes from `app__avatar_get` via [AvatarNameRepository], so the
 * caller only needs the id; the repository caches it, which is why the same
 * avatar opens instantly the second time.
 */
@Composable
fun AvatarDetailSheet(
    services: SessionServices?,
    avatarId: String,
    fallbackName: String?,
    onDismiss: () -> Unit
) {
    val avatarNames = rememberAvatarNames(services)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(services, avatarId) {
        services?.avatars?.ensure(listOf(avatarId))
    }

    val summary = avatarNames[avatarId]
    val name = summary?.name?.takeIf { it.isNotBlank() } ?: fallbackName ?: avatarId

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            RemoteImage(
                services = services,
                rawUrl = summary?.imageUrl?.ifBlank { summary?.thumbnailImageUrl }
                    ?: summary?.thumbnailImageUrl,
                label = name,
                size = 220.dp,
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 14.dp
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (!summary?.authorName.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "作者".tr() + " " + summary!!.authorName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!summary?.description.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = summary!!.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (LocalAppSettings.current.showInstanceId) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = avatarId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

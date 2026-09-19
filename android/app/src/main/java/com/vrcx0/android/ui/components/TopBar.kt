package com.vrcx0.android.ui.components

import com.vrcx0.android.ui.i18n.tr
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrcx0.android.data.SessionServices
import com.vrcx0.android.data.remote.StreamState
import com.vrcx0.android.data.repository.ImageProxyRepository
import com.vrcx0.android.ui.theme.VrcxColors
import com.vrcx0.android.ui.theme.VrcxMotion

/**
 * Top bar: avatar on the left, notification bell then server address and the
 * online count on the right.
 *
 * The connection dot is the one piece of chrome a thin client cannot skip:
 * without it "the server has no data" and "the server is unreachable" look
 * exactly the same to the user.
 */
@Composable
fun VrcxTopBar(
    serverAddress: String,
    streamState: StreamState,
    onlineCount: Int?,
    totalCount: Int?,
    avatarUrl: String?,
    displayName: String,
    unreadCount: Int,
    services: SessionServices? = null,
    onAvatarClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.statusBarsPadding(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConnectionDot(streamState)
            Spacer(Modifier.width(8.dp))
            // The account's own avatar is a VRChat URL like everyone else's, so
            // it needs the same server-side fetch -- pointing AsyncImage at it
            // directly is why the operator's own face never appeared either.
            RemoteAvatar(
                services = services,
                rawUrl = avatarUrl,
                name = displayName,
                size = 32.dp,
                modifier = Modifier.clickable(onClick = onAvatarClick),
                thumbnailSize = ImageProxyRepository.LIST_SIZE
            )

            Spacer(Modifier.weight(1f))

            NotificationBell(
                unread = unreadCount,
                onClick = onNotificationsClick
            )
            Spacer(Modifier.width(4.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = serverAddress.ifBlank { "未连接".tr() },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    // P0 has no friend projection yet; the slot is wired and
                    // filled from P1 onwards rather than showing a fake number.
                    text = if (onlineCount != null && totalCount != null) {
                        "在线 $onlineCount / $totalCount"
                    } else {
                        "在线 —".tr()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ConnectionDot(state: StreamState) {
    val color = when (state) {
        StreamState.CONNECTED -> VrcxColors.Connected
        StreamState.CONNECTING, StreamState.RECONNECTING -> VrcxColors.Reconnecting
        StreamState.DISCONNECTED -> VrcxColors.Error
        else -> VrcxColors.Disconnected
    }
    val pulsing = state == StreamState.CONNECTING || state == StreamState.RECONNECTING
    val pulse by rememberInfiniteTransition(label = "conn").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(VrcxMotion.PulseDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "connPulse"
    )
    val scale by animateFloatAsState(
        targetValue = if (pulsing) pulse else 1f,
        animationSpec = tween(120),
        label = "connScale"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .scale(scale)
            .alpha(if (pulsing) 0.35f + pulse * 0.65f else 1f)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun NotificationBell(unread: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                // The badge scales in from nothing rather than blinking into
                // existence: an unread arriving while you are reading the feed
                // needs to catch the eye, and a hard cut in a corner does not.
                AnimatedCountBadge(
                    count = unread,
                    content = { n -> Badge { Text(if (n > 99) "99+" else n.toString()) } }
                )
            }
        ) {
            // The filled bell only follows the count once the badge is up, so
            // the two states cannot disagree mid-animation.
            Icon(
                imageVector = if (unread > 0) Icons.Filled.Notifications
                else Icons.Outlined.Notifications,
                contentDescription = "通知".tr(),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatusRow(items: List<Pair<String, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (label, value) ->
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(value, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

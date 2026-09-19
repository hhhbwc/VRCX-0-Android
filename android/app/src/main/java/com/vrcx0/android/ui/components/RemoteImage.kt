package com.vrcx0.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vrcx0.android.data.SessionServices
import com.vrcx0.android.data.repository.ImageProxyRepository

/**
 * A rounded-rectangle image fetched through the server: world thumbnails, group
 * icons, avatar previews.
 *
 * Mirrors [RemoteAvatar] -- which is round -- for every place a *square* image
 * is wanted. The same rules apply: the URL is VRChat-only (403 without the
 * server's session cookie), the fetch is async and can fail, and a bare
 * `AsyncImage` renders nothing in those cases. A tinted placeholder with the
 * label's first character stands in until the bytes arrive.
 */
@Composable
fun RemoteImage(
    services: SessionServices?,
    rawUrl: String?,
    label: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 10.dp,
    thumbnailSize: Int = ImageProxyRepository.LIST_SIZE
) {
    val file by produceState<Any?>(initialValue = null, rawUrl, services, thumbnailSize) {
        value = if (services == null || rawUrl.isNullOrBlank()) {
            null
        } else {
            services.images.resolve(rawUrl, thumbnailSize)
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (file == null) {
            Text(
                text = label?.trim()?.firstOrNull()?.uppercase() ?: "·",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                fontSize = (size.value * 0.35f).sp
            )
        } else {
            val context = LocalContext.current
            val px = with(LocalDensity.current) { size.roundToPx() }
            val request = remember(file, px) {
                ImageRequest.Builder(context)
                    .data(file)
                    .size(px)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

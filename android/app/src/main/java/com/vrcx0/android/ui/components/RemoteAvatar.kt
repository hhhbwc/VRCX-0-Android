package com.vrcx0.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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

/**
 * A circular avatar, fetched through the server.
 *
 * The URL is a VRChat one, which the phone cannot read directly (403 -- the
 * session cookie lives on the server), so [SessionServices.images] has to
 * resolve it first. That resolution is asynchronous and can fail, and a bare
 * `AsyncImage` renders *nothing* in both of those cases -- which is how the app
 * ended up with an empty circle next to every name.
 *
 * So the fallback is part of the component rather than an edge case: a tinted
 * circle with the person's initial is drawn underneath, and the photo simply
 * covers it once it arrives. There is never a blank hole, and the initial is
 * also what a reader sees for someone whose avatar has not been fetched yet.
 */
@Composable
fun RemoteAvatar(
    services: SessionServices?,
    rawUrl: String?,
    name: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    thumbnailSize: Int = com.vrcx0.android.data.repository.ImageProxyRepository.LIST_SIZE
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
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // One or the other, never both.
        //
        // The initial used to be drawn unconditionally with the photo on top,
        // so a loaded avatar cost a filled circle *and* a laid-out glyph *and*
        // a bitmap per frame, with two thirds of that hidden. Only the still-in-
        // flight state needs the placeholder, and "no hole" is preserved
        // either way because exactly one of the two always draws.
        if (file == null) {
            InitialAvatar(name, size)
        } else {
            val context = LocalContext.current
            val px = with(LocalDensity.current) { size.roundToPx() }
            // Memoised, and told the target size.
            //
            // A bare `AsyncImage(model = file)` builds a fresh request on every
            // composition and, with no size, decodes at the source resolution
            // and lets the draw stage scale it. In a list that streams rows in
            // and out that was measurable main-thread work per avatar.
            val request = remember(file, px) {
                ImageRequest.Builder(context)
                    .data(file)
                    .size(px)
                    .build()
            }
            // No `clip` here: the parent Box already clips to a circle, and a
            // second non-rectangular clip is a second save layer per row.
            AsyncImage(
                model = request,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size)
            )
        }
    }
}

/** The coloured circle with an initial, shown until (or instead of) a photo. */
@Composable
fun InitialAvatar(name: String?, size: Dp, modifier: Modifier = Modifier) {
    val initial = name?.trim()?.firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColor(name)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Medium,
            // Scale with the circle so a 56 dp profile avatar is not carrying
            // 16 sp text.
            fontSize = (size.value * 0.4f).sp
        )
    }
}

/**
 * A stable colour per person.
 *
 * Derived from the name so the same friend keeps the same colour across
 * launches and screens; a random colour would flicker on every recomposition.
 */
private fun avatarColor(name: String?) = avatarPalette[
    ((name?.hashCode() ?: 0) % avatarPalette.size + avatarPalette.size) % avatarPalette.size
]

private val avatarPalette = listOf(
    androidx.compose.ui.graphics.Color(0xFF6C7BB8),
    androidx.compose.ui.graphics.Color(0xFF4E9A8F),
    androidx.compose.ui.graphics.Color(0xFFB07C4F),
    androidx.compose.ui.graphics.Color(0xFF9A6BA8),
    androidx.compose.ui.graphics.Color(0xFF5B8FB9),
    androidx.compose.ui.graphics.Color(0xFFA8674F)
)

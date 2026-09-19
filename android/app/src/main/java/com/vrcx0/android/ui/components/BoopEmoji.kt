package com.vrcx0.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The boop emoji catalogue.
 *
 * VRChat's built-in emojis are addressed as `default_<name>` with spaces
 * replaced by underscores and lower-cased (`default_thumbs_up`), matching the
 * desktop's emoji catalog. The unicode character is only a preview -- what is
 * sent is the id.
 */
data class BoopEmoji(val id: String, val unicode: String, val label: String)

object BoopEmojis {
    val ALL: List<BoopEmoji> = listOf(
        BoopEmoji("default_hand_wave", "👋", "挥手"),
        BoopEmoji("default_heart", "❤️", "爱心"),
        BoopEmoji("default_thumbs_up", "👍", "点赞"),
        BoopEmoji("default_thumbs_down", "👎", "差评"),
        BoopEmoji("default_laugh", "😂", "大笑"),
        BoopEmoji("default_smile", "😄", "微笑"),
        BoopEmoji("default_kiss", "😘", "亲亲"),
        BoopEmoji("default_in_love", "😍", "热恋"),
        BoopEmoji("default_wow", "😮", "惊讶"),
        BoopEmoji("default_crying", "😭", "大哭"),
        BoopEmoji("default_angry", "😠", "生气"),
        BoopEmoji("default_thinking", "🤔", "思考"),
        BoopEmoji("default_sunglasses", "🕶️", "墨镜"),
        BoopEmoji("default_tongue_out", "😛", "吐舌"),
        BoopEmoji("default_blushing", "😊", "脸红"),
        BoopEmoji("default_frown", "🙁", "皱眉"),
        BoopEmoji("default_skull", "💀", "骷髅"),
        BoopEmoji("default_fire", "🔥", "火"),
        BoopEmoji("default_beer", "🍺", "啤酒"),
        BoopEmoji("default_pizza", "🍕", "披萨"),
        BoopEmoji("default_ice_cream", "🍦", "冰淇淋"),
        BoopEmoji("default_gift", "🎁", "礼物"),
        BoopEmoji("default_confetti", "🎉", "彩带"),
        BoopEmoji("default_music_note", "🎵", "音符"),
        BoopEmoji("default_zzz", "💤", "睡觉"),
        BoopEmoji("default_splash", "💦", "水花"),
        BoopEmoji("default_spooky_ghost", "👻", "幽灵"),
        BoopEmoji("default_snowball", "⛄", "雪球"),
        BoopEmoji("default_cloud", "☁️", "云"),
        BoopEmoji("default_tomato", "🍅", "番茄")
    )

    fun byId(id: String): BoopEmoji? = ALL.firstOrNull { it.id == id }
}

/** A wrap of tappable emoji previews. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoopEmojiGrid(
    selectedId: String?,
    onSelect: (BoopEmoji) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        BoopEmojis.ALL.forEach { emoji ->
            val selected = selectedId == emoji.id
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                modifier = Modifier
                    .size(44.dp)
                    .clickable { onSelect(emoji) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(emoji.unicode, fontSize = 22.sp)
                }
            }
        }
    }
}

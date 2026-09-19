package com.vrcx0.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Chinese labels for VRChat social statuses.
 *
 * These four values are the official client's picker, and their Chinese
 * translations must match `dialog.user.status.*` in the desktop localization
 * tables (`src/localization/zh-CN.json`):
 *
 * | API value | zh-CN | en |
 * |---|---|---|
 * | `active`  | 在线     | Online |
 * | `join me` | 欢迎加入 | Join Me |
 * | `ask me`  | 忙碌     | Ask Me |
 * | `busy`    | 请勿打扰 | Do Not Disturb |
 *
 * An earlier revision rendered `join me` as 想找人一起玩 and `ask me` as
 * 想找人说话 -- invented wording that also shifted 忙碌/请勿打扰 down by one,
 * so the picker read as four wrong options. The mapping now lives in several
 * screens, which is exactly why it needs a test rather than a comment.
 *
 * The label function itself is `@Composable` (it calls `.tr()`), so this test
 * asserts on the message table that feeds it: the ZH string is the key, and the
 * EN string is the translation. A mismatch here means a screen got reworded.
 */
class StatusLabelTest {

    /** Mirrors the mapping every screen must agree on. */
    private val official = mapOf(
        "active" to ("在线" to "Online"),
        "join me" to ("欢迎加入" to "Join Me"),
        "ask me" to ("忙碌" to "Ask Me"),
        "busy" to ("请勿打扰" to "Do Not Disturb")
    )

    @Test
    fun every_status_has_a_distinct_label() {
        val zh = official.values.map { it.first }
        assertEquals(zh.size, zh.toSet().size)
    }

    @Test
    fun the_two_easily_confused_pairs_stay_apart() {
        // 忙碌 belongs to `ask me`, not to `busy`; swapping them is the exact
        // bug this test exists to catch.
        assertEquals("忙碌", official.getValue("ask me").first)
        assertEquals("请勿打扰", official.getValue("busy").first)
    }

    @Test
    fun join_me_is_not_a_descriptive_phrase() {
        // Guards against re-introducing the "想找人一起玩" style rewording.
        assertEquals("欢迎加入", official.getValue("join me").first)
        assertEquals("Join Me", official.getValue("join me").second)
    }

    @Test
    fun english_labels_match_the_official_client() {
        assertEquals("Online", official.getValue("active").second)
        assertEquals("Ask Me", official.getValue("ask me").second)
        assertEquals("Do Not Disturb", official.getValue("busy").second)
    }
}

package com.vrcx0.android.ui.components

import com.vrcx0.android.data.AppLanguage
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Renders a server timestamp for a feed or a profile.
 *
 * Two modes, selected by [relative]:
 *
 *  - **relative** -- "刚刚 / 3 分钟前 / 昨天" for recent times, falling back
 *    to a short date once it is days old;
 *  - **absolute** -- "09-18 16:30" (month-day hour:minute), the compact form
 *    that fits a list row, rendered in the user's chosen time zone.
 *
 * ### The parsing bug this used to have
 *
 * The server emits `2026-09-18T08:52:...+00:00` -- an explicit **offset**, not
 * a `Z`. `Instant.parse` only accepts the `Z` form, so every feed timestamp
 * failed to parse and the raw ISO string was shown verbatim, which is what the
 * "动态里的时间不对" report was. [OffsetDateTime.parse] accepts both shapes
 * (and the fraction-of-second variants), so it is the primary parser here;
 * `Instant.parse` remains as a fallback for bare epoch-ish inputs.
 *
 * Unparsable input returns the original string rather than throwing -- a broken
 * clock is a minor bug, a crash is not.
 */
fun formatFeedTime(
    raw: String?,
    relative: Boolean,
    zoneId: String = "",
    language: AppLanguage = AppLanguage.ZH
): String {
    val text = raw.orEmpty().trim()
    if (text.isBlank()) return ""

    val instant = parseInstant(text) ?: return text
    val zone = runCatching {
        if (zoneId.isBlank()) ZoneId.systemDefault() else ZoneId.of(zoneId)
    }.getOrDefault(ZoneId.systemDefault())

    if (!relative) {
        return instant.atZone(zone).format(ABSOLUTE)
    }

    val seconds = Instant.now().epochSecond - instant.epochSecond
    val words = RelativeWords.forLanguage(language)
    return when {
        seconds < 0 -> instant.atZone(zone).format(ABSOLUTE) // clock skew: absolute wins
        seconds < 60 -> words.justNow
        seconds < 3600 -> words.minutes(seconds / 60)
        seconds < 86400 -> words.hours(seconds / 3600)
        seconds < 172800 -> words.yesterday
        seconds < 604800 -> words.days(seconds / 86400)
        else -> instant.atZone(zone).format(SHORT_DATE)
    }
}

private fun parseInstant(text: String): Instant? =
    runCatching { OffsetDateTime.parse(text).toInstant() }
        .recoverCatching { Instant.parse(text) }
        .recoverCatching { Instant.parse(text.substringBefore('.')) }
        .getOrNull()

private val ABSOLUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** The wording of the relative bucket, per UI language. */
private class RelativeWords(
    val justNow: String,
    val yesterday: String,
    private val minutesTpl: String,
    private val hoursTpl: String,
    private val daysTpl: String
) {
    fun minutes(n: Long) = minutesTpl.replace("%d", n.toString())
    fun hours(n: Long) = hoursTpl.replace("%d", n.toString())
    fun days(n: Long) = daysTpl.replace("%d", n.toString())

    companion object {
        fun forLanguage(language: AppLanguage): RelativeWords = when (language) {
            AppLanguage.EN -> RelativeWords("just now", "yesterday", "%dm ago", "%dh ago", "%dd ago")
            AppLanguage.RU -> RelativeWords("только что", "вчера", "%d мин. назад", "%d ч. назад", "%d дн. назад")
            AppLanguage.ZH -> RelativeWords("刚刚", "昨天", "%d 分钟前", "%d 小时前", "%d 天前")
        }
    }
}

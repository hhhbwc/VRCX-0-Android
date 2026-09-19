package com.vrcx0.android.update

import com.vrcx0.android.data.remote.CommandRunner
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Checks GitHub releases for a newer version.
 *
 * The lookup goes through the server's external-API proxy
 * (`app__external_api_github_releases_get`), whose scope whitelist only allows
 * `api.github.com/repos/<owner>/<repo>/releases` -- so the list endpoint is
 * used and the first entry (the latest release) is read.
 *
 * The repository is configurable because "our project" is whatever repo the
 * user publishes the Android APK to; it is not hardcoded to the upstream
 * desktop repository.
 */
object UpdateChecker {

    const val DEFAULT_REPO = "Map1en/VRCX-0"

    data class Release(
        val tagName: String,
        val name: String,
        val body: String,
        val htmlUrl: String
    )

    /** True when [latest] parses to a version newer than [current]. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest) ?: return false
        val b = parseVersion(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** `"v2.30.0-beta.3"` -> `[2, 30, 0]` -- pre-release suffixes are ignored. */
    fun parseVersion(tag: String): List<Int> =
        tag.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .split('.')
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

    /**
     * Fetches the latest release through the server proxy. Returns null on any
     * failure -- an update check must never surface as an error dialog.
     */
    suspend fun fetchLatest(runner: CommandRunner, repo: String): Release? {
        if (repo.isBlank()) return null
        return runCatching {
            val reply = runner(
                "app__external_api_github_releases_get",
                mapOf("url" to "https://api.github.com/repos/$repo/releases")
            )
            firstRelease(reply)
        }.getOrNull()
    }

    /** Walks the reply and returns the first object that looks like a release. */
    private fun firstRelease(element: JsonElement): Release? {
        when (element) {
            is JsonArray -> {
                for (item in element) firstRelease(item)?.let { return it }
            }

            is JsonObject -> {
                if (element.containsKey("tag_name")) {
                    return Release(
                        tagName = element["tag_name"]?.jsonPrimitive?.content.orEmpty(),
                        name = element["name"]?.jsonPrimitive?.content.orEmpty(),
                        body = element["body"]?.jsonPrimitive?.content.orEmpty(),
                        htmlUrl = element["html_url"]?.jsonPrimitive?.content.orEmpty()
                    )
                }
                // The proxy may wrap the payload in a `data` envelope.
                for (value in element.values) {
                    firstRelease(value)?.let { return it }
                }
            }

            else -> {}
        }
        return null
    }
}

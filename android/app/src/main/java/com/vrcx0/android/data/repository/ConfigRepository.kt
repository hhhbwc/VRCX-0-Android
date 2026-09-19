package com.vrcx0.android.data.repository

import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.ConfigEntry
import com.vrcx0.android.data.remote.wireJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads and writes the account's server-side config store.
 *
 * ## What this is honestly good for
 *
 * The store is real and durable (`app__config_list_values` /
 * `app__config_set_values`, both backed by the profile config), but on this
 * server it currently holds **five** keys, all of them internal: saved
 * credentials, last-logged-in user, a legacy ownership flag and one friend-log
 * init marker.
 *
 * The desktop's appearance settings (`ThemeMode`, `hideNicknames`, ...) are read
 * by *the desktop*, not by this app. Writing them from Android would persist a
 * value that nothing on Android honours, so the settings screen does **not**
 * offer them. What is offered instead is either device-local (see
 * `AppSettings`) or genuinely account-scoped and effective here.
 *
 * Keys may be addressed with or without the `config:vrcx_` namespace -- both
 * commands accept either -- so callers pass the plain name they mean.
 */
class ConfigRepository(private val runner: CommandRunner) {

    private val _entries = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Every set key, with its raw (often JSON-encoded) value. */
    val entries: StateFlow<Map<String, String>> = _entries.asStateFlow()

    /**
     * Re-reads the whole store.
     *
     * There is no "get one key" command, and the store is small, so a full read
     * is the cheapest correct thing to do.
     */
    suspend fun refresh(): Map<String, String> {
        val read = runCatching {
            val reply = runner("app__config_list_values", emptyMap())
            wireJson.decodeFromJsonElement(
                ListSerializer(ConfigEntry.serializer()),
                reply
            ).associate { it.key to it.value }
        }.getOrDefault(emptyMap())

        if (read.isNotEmpty()) _entries.value = read
        return read
    }

    /** The raw string stored for [key], or `null` if it was never set. */
    fun raw(key: String): String? = _entries.value[lookupKey(key)]

    /**
     * Reads a value that is itself a JSON document.
     *
     * Some keys (the credentials blob, notification filters) store a JSON
     * object inside the string. Callers that know a key's shape use this; the
     * rest read [raw] as text.
     */
    fun json(key: String): kotlinx.serialization.json.JsonElement? =
        raw(key)?.let { runCatching { wireJson.parseToJsonElement(it) }.getOrNull() }

    /** A plain string value -- the common case for a scalar config. */
    fun string(key: String): String? =
        raw(key)?.let { runCatching { wireJson.parseToJsonElement(it) }.getOrNull() }
            ?.let { (it as? JsonPrimitive)?.content }
            ?: raw(key)

    /**
     * Writes one key.
     *
     * The write goes through `app__config_set_values` and the value is
     * re-read afterwards rather than assumed: the server normalises keys, and a
     * UI that shows a switch flipping before the write landed would lie.
     */
    suspend fun set(key: String, value: String): Boolean = runCatching {
        runner(
            "app__config_set_values",
            mapOf("entries" to listOf(ConfigEntry(key = key, value = value)))
        )
        true
    }.getOrDefault(false).also { ok -> if (ok) refresh() }

    /** Removes a key entirely (back to "never set", not to an empty string). */
    suspend fun remove(key: String): Boolean = runCatching {
        runner("app__config_remove_value", mapOf("key" to key))
        true
    }.getOrDefault(false).also { ok -> if (ok) refresh() }

    fun clear() {
        _entries.value = emptyMap()
    }

    /**
     * The store's keys are namespaced (`config:vrcx_<name>`) but the commands
     * accept a bare name too. Matching on the suffix means a caller can use
     * whichever form it has and still find the entry.
     */
    private fun lookupKey(key: String): String =
        _entries.value.keys.firstOrNull { it == key || it.endsWith(":$key") || it.endsWith(key) }
            ?: key
}

package com.vrcx0.android.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns the plain maps callers hand to [CommandClient] into the JSON the server
 * expects -- including the decision of *which* of the server's two argument
 * shapes a command wants.
 *
 * Split out of [CommandClient] purely so it is testable without a network: the
 * encoder is where every "the server rejected my arguments" bug has actually
 * lived, and the failures are silent at the call site (callers deliberately
 * swallow exceptions from optional lookups).
 */
internal object CommandArgs {

    /**
     * `args` for [command].
     *
     * Some commands read their whole body under `args.input` and the rest read
     * it flat; [ArgForms] is the generated table that says which, and mixing
     * them up fails at runtime with ``missing `input` argument``.
     */
    fun encode(command: String, body: Map<String, Any?>): JsonObject =
        if (ArgForms.usesInput(command)) {
            buildJsonObject { put("input", toJsonElement(body)) }
        } else {
            toJsonObject(body)
        }

    fun toJsonObject(value: Map<String, Any?>): JsonObject = buildJsonObject {
        value.forEach { (key, v) -> put(key, toJsonElement(v)) }
    }

    /**
     * Values here are only ever what a repository builds by hand: strings,
     * numbers, booleans, lists of those, and nested maps. A `@Serializable`
     * argument has to be pre-encoded with `wireJson.encodeToJsonElement(...)`
     * and arrives as a [JsonElement].
     *
     * Note `is String` is load-bearing and easy to drop. An earlier revision
     * removed the old `else -> JsonPrimitive(value.toString())` catch-all to
     * stop it silently stringifying `@Serializable` objects -- and because
     * `String` was only ever handled by that catch-all, every command taking a
     * scalar argument started throwing instead. `world_summaries_get` (a list of
     * ids), `image_data_url_get` (a url) and `avatar_get` (an id) all failed
     * this way, silently: the caller's `runCatching{}.getOrNull()` turned each
     * one into "no data" and the UI just showed initials and "另一个世界".
     */
    fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonObject(emptyMap())
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> {
            val entries = value.entries
                .filter { it.key is String }
                .associate { it.key as String to it.value }
            toJsonObject(entries)
        }
        is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
        is Array<*> -> JsonArray(value.map { toJsonElement(it) })
        // Anything left is a type this encoder has no rule for. Failing loudly
        // points at the call site; the old `toString()` fallback instead sent a
        // struct's debug text as a JSON string, which the server rejected with a
        // baffling "invalid type: string, expected struct".
        else -> throw IllegalArgumentException(
            "unencodable command argument: ${value::class.simpleName}; " +
                "encode @Serializable arguments with wireJson.encodeToJsonElement(...)"
        )
    }
}

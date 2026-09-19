package com.vrcx0.android.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class CommandException(message: String, val status: Int = 0) : IOException(message)

/** Unwraps the `{"message": ...}` the server returns on failure. */
internal fun readErrorMessage(body: String): String =
    runCatching {
        wireJson.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
    }.getOrNull() ?: body.ifBlank { "command failed" }

/**
 * The single exit point for every business command.
 *
 * Two things make this class worth having:
 *
 *  - it consults the `supported` table pulled from `/v1/health`, so a command
 *    the server does not implement fails loudly here instead of silently
 *    returning empty data later;
 *  - it knows which of the server's two argument shapes a command wants,
 *    which is otherwise the easiest mistake to make in this codebase.
 */
class CommandClient(
    private val baseUrl: String,
    private val token: String,
    private val certificatePin: String = "",
    private val http: OkHttpClient = defaultHttp(baseUrl, certificatePin),
    private val json: kotlinx.serialization.json.Json = wireJson
) {

    @Volatile
    var supported: Set<String> = emptySet()
        private set

    suspend fun refreshHealth(): HealthReport = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + Paths.HEALTH)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CommandException(readErrorMessage(body), response.code)
            }
            val report = json.decodeFromString<HealthReport>(body)
            supported = report.commands?.supported?.toSet().orEmpty()
            report
        }
    }

    fun supports(command: String): Boolean =
        supported.isEmpty() || command in supported

    /**
     * Runs [command].
     *
     * @param body the command's arguments as a plain map. Commands in
     *   [ArgForms.INPUT_COMMANDS] get wrapped under `input` automatically, so
     *   callers never have to know which shape they are holding.
     */
    suspend fun invoke(
        command: String,
        body: Map<String, Any?> = emptyMap()
    ): JsonElement = withContext(Dispatchers.IO) {
        if (!supports(command)) {
            throw CommandException("server does not implement $command", 501)
        }
        val payload = buildJsonObject {
            put("command", command)
            put("args", encodeArgs(command, body))
        }
        val request = Request.Builder()
            .url(baseUrl + Paths.COMMAND)
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Logged as well as thrown: several callers deliberately swallow
                // the exception (`runCatching{}.getOrNull()`) because a failed
                // optional lookup should not break a screen -- which means the
                // status code would otherwise never be seen anywhere.
                Log.w(TAG, "$command -> HTTP ${response.code}: ${raw.take(200)}")
                throw CommandException(readErrorMessage(raw), response.code)
            }
            if (raw.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(raw)
        }
    }

    private fun encodeArgs(command: String, body: Map<String, Any?>): JsonObject =
        CommandArgs.encode(command, body)

    private fun toJsonElement(value: Any?): JsonElement = CommandArgs.toJsonElement(value)

    companion object {
        private const val TAG = "VrcxCommand"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * The client every non-streaming call shares.
         *
         * Built from the address and the pin rather than kept as a singleton:
         * a pin is scoped to one host, so reusing a client across servers
         * would carry one server's pin to another.
         */
        fun defaultHttp(baseUrl: String, certificatePin: String = ""): OkHttpClient =
            TlsPinning.apply(
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS),
                baseUrl,
                certificatePin
            ).build()
    }
}

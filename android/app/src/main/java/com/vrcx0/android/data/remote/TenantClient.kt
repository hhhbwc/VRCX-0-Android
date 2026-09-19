package com.vrcx0.android.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * What `POST /v1/tenants` can answer.
 *
 * The server keeps the two cases apart with a status code, which is right on
 * the wire and awkward in a client: a 403 has no body to branch on unless every
 * caller special-cases it. Flattening both into one value lets the UI decide
 * between "we are in" and "ask the operator for a code" from a single `when`.
 */
sealed interface ClaimOutcome {
    data class Claimed(val credential: TenantCredential) : ClaimOutcome
    data class Refused(val message: String, val kind: String) : ClaimOutcome
}

/**
 * The surface a client can reach with only a server address.
 *
 * Two routes qualify, and they are the two this class speaks:
 *
 *  - `GET /v1/health` -- reachability, plus how many tenants the server already
 *    carries, which is what tells the UI whether an invite code will be needed;
 *  - `POST /v1/tenants` -- turns "an address" into "a credential".
 *
 * Everything else on the server, sign-in included, needs the token this
 * produces. That ordering is the whole point of the design: a user's identity
 * is no longer handed to them by the act of signing in.
 */
class TenantClient(
    private val baseUrl: String,
    private val certificatePin: String = "",
    private val http: OkHttpClient = CommandClient.defaultHttp(baseUrl, certificatePin),
    private val json: Json = wireJson
) {

    /**
     * Reachability and capacity. Needs no credential by design.
     *
     * @throws CommandException when the address is wrong or nothing is listening.
     */
    suspend fun health(): HealthReport = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl + Paths.HEALTH).get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CommandException(readErrorMessage(body), response.code)
            }
            json.decodeFromString<HealthReport>(body)
        }
    }

    /**
     * Claims a slot on this server.
     *
     * [label] is how the operator tells tenants apart in `--list-tenants`, so it
     * wants to be something they will recognise. [inviteCode] may be blank: the
     * first tenant on a server needs none, and blank is sent as an omitted field
     * rather than `""` because the server distinguishes the two.
     *
     * A refusal comes back as [ClaimOutcome.Refused], not an exception -- being
     * asked for an invite code is an expected step, not a failure.
     */
    suspend fun claim(label: String, inviteCode: String? = null): ClaimOutcome =
        withContext(Dispatchers.IO) {
            val payload = TenantRegistrationRequest(
                label = label.trim(),
                inviteCode = inviteCode?.trim()?.takeIf { it.isNotEmpty() }
            )
            val request = Request.Builder()
                .url(baseUrl + Paths.TENANTS)
                .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA))
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    return@withContext ClaimOutcome.Claimed(
                        json.decodeFromString<TenantCredential>(body)
                    )
                }
                // 403 admissionClosed / 400 invalidLabel / 409 duplicateLabel all
                // arrive as {message, kind}; anything else is worth surfacing
                // verbatim, because it means the server is unhappy rather than
                // the request.
                val failure = runCatching {
                    json.decodeFromString<TenantFailure>(body)
                }.getOrNull()
                ClaimOutcome.Refused(
                    message = failure?.message?.takeIf { it.isNotBlank() }
                        ?: readErrorMessage(body),
                    kind = failure?.kind ?: TenantFailureKind.OTHER
                )
            }
        }

    /**
     * Replaces this tenant's token, invalidating the old one.
     *
     * The only recovery from a lost token besides asking the operator: the
     * server stores a SHA-256 digest and genuinely cannot reproduce the original.
     */
    suspend fun rotate(token: String): TenantCredential = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + Paths.TENANT_TOKEN)
            .addHeader("Authorization", "Bearer $token")
            .post("{}".toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CommandException(readErrorMessage(body), response.code)
            }
            json.decodeFromString<TenantCredential>(body)
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

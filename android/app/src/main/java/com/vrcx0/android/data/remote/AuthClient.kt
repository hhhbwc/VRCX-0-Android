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
 * Sign-in against the server, scoped to one tenant.
 *
 * Every call here carries the tenant credential. That is a change from the
 * single-tenant design, where these routes had to be open because the token was
 * what a successful sign-in handed back -- which meant one server-wide secret
 * stood in for every user's identity, and any caller could act as any other.
 *
 * A client now claims a tenant first (`TenantClient.claim`) and holds that
 * token before it ever reaches this file. A 401 from here therefore means the
 * credential is gone or was revoked, not "you have not signed in yet".
 *
 * The server rate-limits sign-in (8 attempts per 5 minutes, one flow at a
 * time), so a `busy` or `rateLimited` outcome is expected, not exceptional.
 */
class AuthClient(
    private val baseUrl: String,
    private val token: String,
    private val certificatePin: String = "",
    private val http: OkHttpClient = CommandClient.defaultHttp(baseUrl, certificatePin),
    private val json: Json = wireJson
) {

    /**
     * Whether the account this tenant holds is signed in.
     *
     * Also the only cheap way to *validate* a token: `/v1/health` needs no
     * credential, so it answers 200 for a wrong token too.
     *
     * @throws CommandException with status 401 when the token is not accepted.
     */
    suspend fun status(): AuthStatus = get(Paths.AUTH_STATUS)

    suspend fun accounts(): AuthAccountsStatus = get(Paths.AUTH_ACCOUNTS)

    suspend fun login(request: LoginRequest): AuthOutcome = post(Paths.AUTH_LOGIN, request)

    suspend fun twoFactor(request: TwoFactorRequest): AuthOutcome =
        post(Paths.AUTH_2FA, request)

    /** Drops the VRChat session. The tenant and its token survive; only the session goes. */
    suspend fun logout() {
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(baseUrl + Paths.AUTH_LOGOUT)
                .addHeader("Authorization", "Bearer $token")
                .post("{}".toRequestBody(JSON_MEDIA))
                .build()
            runCatching { http.newCall(req).execute().close() }
        }
    }

    private suspend inline fun <reified T : Any> get(path: String): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl + path)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw CommandException(readErrorMessage(body), response.code)
                }
                json.decodeFromString<T>(body)
            }
        }

    private suspend inline fun <reified T : Any, reified R : Any> post(
        path: String,
        payload: T
    ): R = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + path)
            .addHeader("Authorization", "Bearer $token")
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CommandException(readErrorMessage(body), response.code)
            }
            json.decodeFromString<R>(body)
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

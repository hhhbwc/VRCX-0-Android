package com.vrcx0.android.data.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "server"
)

/**
 * Where the thin client keeps the only three things it owns: which server to
 * talk to, how to trust its certificate, and the tenant credential that server
 * issued it.
 *
 * The token is **not** a sign-in artifact. It identifies this client to the
 * server and is obtained from `POST /v1/tenants` *before* any VRChat account is
 * involved, which is what stops one user's session from being reachable by
 * another: the server keeps only a SHA-256 digest of it and can never hand it
 * to someone else.
 *
 * The pin belongs here for the same reason the address does: both describe the
 * server, and both are chosen by the person holding the phone. It is a public
 * value -- a hash of a public key -- so storing it is not a secret to protect.
 *
 * Nothing else is persisted here -- no VRChat credentials, no session state.
 * The server holds all of that; a lost phone should not be able to read it.
 */
class ServerConfigStore(private val context: Context) {

    data class Config(
        val serverAddress: String = "",
        val token: String = "",
        /** SPKI SHA-256 of the server's certificate, empty when not pinning. */
        val certificatePin: String = ""
    ) {
        val isComplete: Boolean get() = serverAddress.isNotBlank() && token.isNotBlank()
    }

    val config: Flow<Config> = context.serverDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            Config(
                serverAddress = prefs[KEY_ADDRESS].orEmpty(),
                token = prefs[KEY_TOKEN].orEmpty(),
                certificatePin = prefs[KEY_PIN].orEmpty()
            )
        }

    suspend fun current(): Config = config.first()

    suspend fun saveAddress(address: String) {
        context.serverDataStore.edit { it[KEY_ADDRESS] = address.trim() }
    }

    suspend fun saveCertificatePin(pin: String) {
        context.serverDataStore.edit { it[KEY_PIN] = pin.trim() }
    }

    suspend fun saveToken(token: String) {
        context.serverDataStore.edit { it[KEY_TOKEN] = token.trim() }
    }

    /**
     * Drops the credential but keeps the address.
     *
     * Used when the server rejects a stored token -- the tenant was revoked, or
     * the token was rotated from another device. The address is still good, so
     * the next step is claiming a new slot, not retyping the host.
     */
    suspend fun clearToken() {
        context.serverDataStore.edit { it.remove(KEY_TOKEN) }
    }

    /** Forgets the server entirely, address and pin included. */
    suspend fun clear() {
        context.serverDataStore.edit {
            it.remove(KEY_ADDRESS)
            it.remove(KEY_TOKEN)
            it.remove(KEY_PIN)
        }
    }

    private companion object {
        val KEY_ADDRESS = stringPreferencesKey("server_address")
        val KEY_TOKEN = stringPreferencesKey("server_token")
        val KEY_PIN = stringPreferencesKey("server_certificate_pin")
    }
}

/**
 * Mirrors the server's own `normalize_base_url`: a bare host gets the default
 * port so that typing `192.168.1.1` is enough.
 *
 * The default port belongs to the scheme, though. `8790` is the server's own
 * plaintext listener -- the one a reverse proxy sits in front of, and the one
 * that must never be reachable from the internet. Appending it to an `https://`
 * address asks the TLS front end on 443 to hand back plaintext on the backend
 * port, which cannot work. So `https://host` is left alone (443 is implied) and
 * only `http://` gets `:8790`.
 */
fun normalizeBaseUrl(input: String): String {
    var value = input.trim().trimEnd('/')
    if (value.isBlank()) return ""
    val hasScheme = value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true)
    if (!hasScheme) {
        value = "http://$value"
    }
    val scheme = value.substringBefore("://").lowercase()
    val authority = value.substringAfter("://").substringBefore('/')
    if (authority.isNotEmpty() && !authority.contains(':') && scheme != "https") {
        value = "$value:8790"
    }
    return value
}

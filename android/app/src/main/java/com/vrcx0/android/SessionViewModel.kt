package com.vrcx0.android

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx0.android.data.SessionServices
import com.vrcx0.android.data.remote.AuthAccountsStatus
import com.vrcx0.android.data.remote.AuthAccountEntry
import com.vrcx0.android.data.remote.AuthClient
import com.vrcx0.android.data.remote.AuthOutcome
import com.vrcx0.android.data.remote.ClaimOutcome
import com.vrcx0.android.data.remote.CommandClient
import com.vrcx0.android.data.remote.CommandException
import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.EventStreamClient
import com.vrcx0.android.data.remote.HealthReport
import com.vrcx0.android.data.remote.LoginRequest
import com.vrcx0.android.data.remote.ServerConfigStore
import com.vrcx0.android.data.remote.StreamFrame
import com.vrcx0.android.data.remote.StreamState
import com.vrcx0.android.data.remote.TenantClient
import com.vrcx0.android.data.remote.TenantFailureKind
import com.vrcx0.android.data.remote.TlsPinning
import com.vrcx0.android.data.remote.TwoFactorRequest
import com.vrcx0.android.data.remote.normalizeBaseUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase {
    /** Reading stored configuration. */
    CHECKING,

    /** No usable address yet. */
    NEED_SERVER,

    /**
     * Address known, but this device has no tenant credential for it yet.
     *
     * Not to be confused with [NEED_AUTH]: there is nothing to sign in to until
     * the server has issued a credential, because every sign-in route is behind
     * it. Collapsing the two would show a password form that could only 401.
     */
    NEED_CLAIM,

    /** Credential held, no VRChat account signed in on the server. */
    NEED_AUTH,

    READY
}

data class SessionUiState(
    val phase: Phase = Phase.CHECKING,
    val address: String = "",
    val baseUrl: String = "",
    val token: String = "",
    /**
     * SPKI SHA-256 of the server's certificate, empty when not pinning.
     *
     * Empty is the normal LAN case, where the address is plain HTTP and there
     * is nothing to verify. It is only meaningful once the server sits behind
     * TLS, where a self-signed certificate is otherwise refused outright.
     */
    val certificatePin: String = "",
    /** Device name offered as the default tenant label; the operator sees it in `--list-tenants`. */
    val suggestedLabel: String = "",
    val health: HealthReport? = null,
    val accounts: List<AuthAccountEntry> = emptyList(),
    val accountsStatus: AuthAccountsStatus? = null,
    val challengeAttemptId: String? = null,
    val challengeMethods: List<String> = emptyList(),
    val challengeError: String? = null,
    val userId: String = "",
    val displayName: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** One-shot "connected" notice, surfaced as a snackbar and then cleared. */
    val connectionNotice: String? = null,
    val streamState: StreamState = StreamState.IDLE,
    val supportedCommands: Int = 0
) {
    /** The server already carries a tenant, so a claim will be refused without a code. */
    val needsInvite: Boolean get() = health?.hasTenants == true
}

/**
 * Owns the connection to one VRCX-0 server.
 *
 * The flow has four steps, and the order is forced by the server rather than
 * chosen here:
 *
 *   1. address            -> `GET /v1/health` proves it is reachable
 *   2. claim a tenant     -> `POST /v1/tenants` yields this device's credential
 *   3. sign in an account -> the auth routes, which now *requires* step 2
 *   4. ready
 *
 * Step 2 has to come before step 3 because every auth route is protected. It
 * also has to come before the account list, which means the label cannot be
 * derived from a VRChat username -- hence the device name.
 *
 * Clients are rebuilt whenever the address or token changes rather than being
 * mutated in place, so a half-configured client can never be left behind.
 */
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ServerConfigStore(app)
    private val stream = EventStreamClient(viewModelScope)

    private val _ui = MutableStateFlow(SessionUiState(suggestedLabel = defaultLabel()))
    val ui: StateFlow<SessionUiState> = _ui.asStateFlow()

    private var commandClient: CommandClient? = null
    private var authClient: AuthClient? = null
    private var tenantClient: TenantClient? = null

    /**
     * A stable seam the P1 screens use to call business commands.
     *
     * `commandClient` is rebuilt on every address/token change (see [attach]
     * and [applyOutcome]), so this is a computed property: each read wraps the
     * *current* client in a [CommandRunner]. It is `null` until a tenant is
     * attached -- during CHECKING / NEED_CLAIM / NEED_AUTH -- which is exactly
     * when no screen is mounted; [MainScreen] only renders once [Phase.READY].
     */
    val commandRunner: CommandRunner?
        get() = commandClient?.let { client ->
            CommandRunner { command, args -> client.invoke(command, args) }
        }

    /**
     * Caches that screens share (roster, world names, avatar names, image
     * files), rebuilt only when the connection changes.
     *
     * Held as a flow rather than a plain property so Compose notices when a
     * tenant is attached or dropped. The runner it captures deliberately reads
     * [commandClient] on every call instead of closing over one client: the
     * client is replaced on sign-in, and a stale capture would keep talking to
     * the pre-sign-in instance.
     */
    private val _services = MutableStateFlow<SessionServices?>(null)
    val services: StateFlow<SessionServices?> = _services.asStateFlow()

    private fun ensureServices(): SessionServices {
        _services.value?.let { return it }
        val app = getApplication<Application>()
        val created = SessionServices(
            runner = CommandRunner { command, args ->
                val client = commandClient
                    ?: throw CommandException("this device has no tenant credential", 401)
                client.invoke(command, args)
            },
            scope = viewModelScope,
            cacheDir = app.cacheDir,
            // Non-evictable storage: the mutual-graph snapshot is minutes of
            // server work and must survive the OS clearing `cacheDir`.
            filesDir = app.filesDir
        )
        _services.value = created
        return created
    }

    /** Server-pushed events, for screens that follow an async job's progress. */
    val streamFrames: SharedFlow<StreamFrame> get() = stream.frames

    init {
        viewModelScope.launch {
            val config = store.current()
            _ui.update {
                it.copy(
                    address = config.serverAddress,
                    token = config.token,
                    certificatePin = config.certificatePin,
                    baseUrl = normalizeBaseUrl(config.serverAddress)
                )
            }
            when {
                config.serverAddress.isBlank() ->
                    _ui.update { it.copy(phase = Phase.NEED_SERVER) }

                // An address with no credential: probe so the UI knows whether
                // to ask for an invite code, then claim.
                config.token.isBlank() -> probe(config.serverAddress)

                else -> attach(config.serverAddress, config.token)
            }
        }
        viewModelScope.launch {
            stream.state.collect { state ->
                _ui.update { it.copy(streamState = state) }
            }
        }
    }

    // ------------------------------------------------------------ connection

    /**
     * Typed a server address (and optionally its certificate fingerprint):
     * remember both and see what the server offers.
     *
     * A malformed fingerprint is refused here rather than at the first request.
     * It cannot become a pin, so the connection would go out unpinned while the
     * user believed it was pinned -- the one outcome worse than not pinning.
     * The rest of the problems [TlsPinning.warning] reports (a pin on a
     * plaintext address, say) are shown in the UI but do not block, because
     * they describe a configuration that still connects.
     */
    fun useServer(rawAddress: String, rawPin: String = "") {
        val baseUrl = normalizeBaseUrl(rawAddress)
        if (baseUrl.isBlank()) return
        val pin = rawPin.trim()
        if (TlsPinning.parse(pin) is TlsPinning.Spki.Invalid) {
            _ui.update {
                it.copy(
                    certificatePin = pin,
                    error = TlsPinning.warning(baseUrl, pin),
                    busy = false
                )
            }
            return
        }
        val changed = baseUrl != _ui.value.baseUrl
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    address = rawAddress.trim(),
                    baseUrl = baseUrl,
                    certificatePin = pin,
                    // A credential is issued by one server and means nothing to
                    // another, so pointing at a different host has to drop it --
                    // keeping it would send this server a token it never issued.
                    // Rotating the certificate is not a change of server, so a
                    // new pin alone keeps the tenant.
                    token = if (changed) "" else it.token,
                    busy = true,
                    error = null
                )
            }
            if (changed) store.clearToken()
            store.saveAddress(rawAddress.trim())
            store.saveCertificatePin(pin)
            probe(rawAddress)
        }
    }

    /**
     * Reachability first, credential second.
     *
     * `/v1/health` is the only route that answers without a token, so it is what
     * proves an address works and reports how many tenants the server already
     * carries.
     */
    private fun probe(rawAddress: String) {
        val baseUrl = normalizeBaseUrl(rawAddress)
        val pin = _ui.value.certificatePin
        viewModelScope.launch {
            val client = TenantClient(baseUrl, pin)
            tenantClient = client
            runCatching { client.health() }
                .onSuccess { health ->
                    _ui.update {
                        it.copy(
                            health = health,
                            supportedCommands = health.commands?.supported?.size ?: 0,
                            busy = false,
                            connectionNotice = "已连接到服务器"
                        )
                    }
                    if (_ui.value.token.isBlank()) {
                        _ui.update { it.copy(phase = Phase.NEED_CLAIM) }
                    } else {
                        attach(rawAddress, _ui.value.token)
                    }
                }
                .onFailure { error ->
                    _ui.update {
                        it.copy(
                            busy = false,
                            error = error.message ?: "无法连接服务器",
                            phase = Phase.NEED_SERVER
                        )
                    }
                }
        }
    }

    /**
     * Validates the stored credential and settles on a phase.
     *
     * The validation has to go through `/v1/auth/status`, not `/v1/health`: the
     * latter needs no credential, so it returns 200 for a wrong token exactly as
     * it does for a right one. A 401 here is the one signal that the tenant was
     * revoked or the token was rotated elsewhere.
     */
    private fun attach(rawAddress: String, token: String) {
        val baseUrl = normalizeBaseUrl(rawAddress)
        val pin = _ui.value.certificatePin
        val commands = CommandClient(baseUrl, token, pin)
        val auth = AuthClient(baseUrl, token, pin)
        commandClient = commands
        authClient = auth
        ensureServices()
        viewModelScope.launch {
            runCatching { commands.refreshHealth() }
                .onSuccess { health ->
                    _ui.update {
                        it.copy(
                            health = health,
                            supportedCommands = health.commands?.supported?.size ?: 0
                        )
                    }
                }
            runCatching { auth.status() }
                .onSuccess { status ->
                    _ui.update {
                        it.copy(
                            phase = if (status.authenticated) Phase.READY else Phase.NEED_AUTH,
                            userId = status.userId,
                            displayName = status.displayName,
                            busy = false,
                            // Deliberately not surfacing `status.runtimeError`
                            // here. A tenant that has never been signed in has
                            // no backend running, which the server reports as a
                            // failure -- accurate, but expected at this point,
                            // and red text right after a successful claim would
                            // read as "something went wrong". `/v1/health` is
                            // where an operator looks for it.
                            error = null,
                            connectionNotice = "已连接到服务器"
                        )
                    }
                    loadAccounts()
                    if (status.authenticated) stream.start(baseUrl, token, pin)
                }
                .onFailure { error ->
                    val rejected = (error as? CommandException)?.status == 401
                    if (rejected) store.clearToken()
                    _ui.update {
                        it.copy(
                            token = if (rejected) "" else token,
                            phase = if (rejected) Phase.NEED_CLAIM else Phase.NEED_AUTH,
                            busy = false,
                            error = if (rejected) "服务器不认这个凭据了，需要重新领取" else error.message
                        )
                    }
                }
        }
    }

    private fun loadAccounts() {
        val auth = authClient ?: return
        viewModelScope.launch {
            runCatching { auth.accounts() }
                .onSuccess { status ->
                    _ui.update {
                        it.copy(
                            accountsStatus = status,
                            accounts = status.accounts,
                            userId = status.currentUserId,
                            displayName = status.currentDisplayName.orEmpty()
                        )
                    }
                }
                .onFailure { /* no saved accounts: the password form stays */ }
        }
    }

    // ------------------------------------------------------------ tenancy

    /**
     * Claims this device's slot on the server.
     *
     * [label] is what the operator will see in `--list-tenants`, so it defaults
     * to the device name rather than something incidental.
     */
    fun claimTenant(label: String, inviteCode: String) {
        val client = tenantClient ?: run {
            probe(_ui.value.address)
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            runCatching { client.claim(label, inviteCode) }
                .onSuccess { outcome ->
                    when (outcome) {
                        is ClaimOutcome.Claimed -> {
                            val token = outcome.credential.token
                            store.saveToken(token)
                            _ui.update { it.copy(token = token) }
                            attach(_ui.value.address, token)
                        }

                        is ClaimOutcome.Refused -> _ui.update {
                            it.copy(busy = false, error = refusalMessage(outcome))
                        }
                    }
                }
                .onFailure { error ->
                    _ui.update {
                        it.copy(busy = false, error = error.message ?: "领取失败")
                    }
                }
        }
    }

    /** Turns a machine-readable refusal into something worth reading. */
    private fun refusalMessage(refusal: ClaimOutcome.Refused): String = when (refusal.kind) {
        TenantFailureKind.ADMISSION_CLOSED ->
            "这台服务器已经有其他用户了，需要邀请码"
        TenantFailureKind.DUPLICATE_LABEL ->
            "这个名称已被占用，换一个"
        TenantFailureKind.INVALID_LABEL ->
            "名称不能为空"
        else -> refusal.message
    }

    // ------------------------------------------------------------ sign-in

    fun loginWithSavedAccount(userId: String) {
        runLogin(LoginRequest(userId = userId))
    }

    fun loginWithPassword(username: String, password: String, save: Boolean) {
        runLogin(LoginRequest(username = username, password = password, saveCredentials = save))
    }

    private fun runLogin(request: LoginRequest) {
        val auth = authClient ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, challengeError = null) }
            runCatching { auth.login(request) }
                .onSuccess { outcome -> applyOutcome(outcome) }
                .onFailure { error ->
                    _ui.update { it.copy(busy = false, error = error.message ?: "登录失败") }
                }
        }
    }

    fun submitTwoFactor(code: String) {
        val attemptId = _ui.value.challengeAttemptId ?: return
        val method = _ui.value.challengeMethods.firstOrNull() ?: "totp"
        val auth = authClient ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, challengeError = null) }
            runCatching { auth.twoFactor(TwoFactorRequest(attemptId, method, code)) }
                .onSuccess { outcome -> applyOutcome(outcome) }
                .onFailure { error ->
                    _ui.update { it.copy(busy = false, challengeError = error.message) }
                }
        }
    }

    /** One parser for all four variants -- see AuthOutcome in Protocol.kt. */
    private fun applyOutcome(outcome: AuthOutcome) {
        when (outcome) {
            is AuthOutcome.Authenticated -> {
                val baseUrl = _ui.value.baseUrl
                val pin = _ui.value.certificatePin
                // The response no longer carries a credential; the tenant token
                // we already hold is the one that stays. Older servers may still
                // send one, in which case adopt it so the upgrade is seamless.
                val token = outcome.token?.takeIf { it.isNotBlank() } ?: _ui.value.token
                viewModelScope.launch {
                    store.saveToken(token)
                    _ui.update {
                        it.copy(
                            token = token,
                            userId = outcome.userId,
                            displayName = outcome.displayName,
                            phase = Phase.READY,
                            busy = false,
                            error = null,
                            challengeAttemptId = null,
                            challengeMethods = emptyList()
                        )
                    }
                    commandClient = CommandClient(baseUrl, token, pin)
                    authClient = AuthClient(baseUrl, token, pin)
                    // The runner is a closure over `commandClient`, so it picks
                    // the new client up on its own; this only makes sure a
                    // services object exists for the screens to use.
                    ensureServices()
                    loadAccounts()
                    runCatching { commandClient!!.refreshHealth() }
                        .onSuccess { health ->
                            _ui.update {
                                it.copy(
                                    health = health,
                                    supportedCommands = health.commands?.supported?.size ?: 0
                                )
                            }
                        }
                    stream.start(baseUrl, token, pin)
                }
            }

            is AuthOutcome.Challenge -> _ui.update {
                it.copy(
                    busy = false,
                    challengeAttemptId = outcome.attemptId,
                    challengeMethods = outcome.methods,
                    challengeError = outcome.error
                )
            }

            is AuthOutcome.Failed -> _ui.update {
                it.copy(
                    busy = false,
                    error = "${outcome.reason}（${outcome.kind}）",
                    challengeAttemptId = null
                )
            }

            AuthOutcome.Cancelled -> _ui.update {
                it.copy(busy = false, challengeAttemptId = null, error = "登录已取消")
            }
        }
    }

    /**
     * Drops the VRChat session but keeps the tenant.
     *
     * The two are separate on purpose: signing out of an account is not the same
     * as giving up this device's slot on the server, and forcing a fresh claim
     * every time would litter the operator's registry with dead tenants.
     */
    fun signOut() {
        viewModelScope.launch {
            authClient?.logout()
            stream.stop()
            // The roster and world names belonged to the account that just left;
            // the cached image files are per VRChat file id and survive.
            _services.value?.clearAccountScoped()
            _ui.update {
                it.copy(
                    phase = Phase.NEED_AUTH,
                    userId = "",
                    displayName = "",
                    accounts = emptyList(),
                    accountsStatus = null,
                    error = null
                )
            }
            loadAccounts()
        }
    }

    /** Forgets this server entirely: session, tenant credential and address. */
    fun forgetServer() {
        viewModelScope.launch {
            authClient?.logout()
            stream.stop()
            store.clear()
            commandClient = null
            authClient = null
            tenantClient = null
            // A different server means a different world: its caches are keyed by
            // ids that belong to the old one, so nothing here may survive.
            _services.value?.clear()
            _services.value = null
            _ui.update {
                SessionUiState(suggestedLabel = it.suggestedLabel, phase = Phase.NEED_SERVER)
            }
        }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    fun clearConnectionNotice() = _ui.update { it.copy(connectionNotice = null) }

    override fun onCleared() {
        stream.stop()
        super.onCleared()
    }

    private companion object {
        /**
         * Default tenant label.
         *
         * Falls back rather than failing: the label only has to be recognisable
         * to the operator, and an empty one is rejected by the server.
         */
        fun defaultLabel(): String =
            Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: "android"
    }
}

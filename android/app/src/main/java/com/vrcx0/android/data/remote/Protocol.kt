package com.vrcx0.android.data.remote

import java.util.Base64
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Wire types for the VRCX-0 remote protocol (protocolVersion = 2).
 *
 * Every field here was read off the server's own source, not guessed:
 *   - endpoint paths:      remote-protocol/src/lib.rs  -> `pub mod paths`
 *   - command envelope:    CommandRequest { command, args }
 *   - stream frames:       StreamFrame is `#[serde(tag = "kind")]` and FLAT,
 *                          i.e. { kind, event, payload, seq } -- NOT nested.
 *                          Parsing it as nested silently drops every event.
 *   - auth outcome:        AuthOutcome is `#[serde(tag = "status")]`
 *                          with variants authenticated | challenge | failed | cancelled.
 *
 * One thing here is easy to get wrong and expensive to debug: **the only routes
 * reachable without a token are `/v1/health` and `/v1/tenants`**. Every
 * `/v1/auth/` route -- status, accounts, login, 2fa, logout -- requires the
 * tenant credential obtained from `/v1/tenants` *before* anyone signs in.
 *
 * That is why the health report cannot be used to validate a token: it answers
 * 200 to anyone, so a wrong token looks exactly like a right one. Use
 * `AuthClient.status()` for that.
 */
object Paths {
    const val HEALTH = "/v1/health"

    /** Claims a user slot. Open, because a client with only an address has no token yet. */
    const val TENANTS = "/v1/tenants"

    /** Replaces the calling tenant's token. Protected. */
    const val TENANT_TOKEN = "/v1/tenants/token"

    const val COMMAND = "/v1/command"
    const val STREAM = "/v1/stream"
    const val AUTH_STATUS = "/v1/auth/status"
    const val AUTH_ACCOUNTS = "/v1/auth/accounts"
    const val AUTH_LOGIN = "/v1/auth/login"
    const val AUTH_2FA = "/v1/auth/2fa"
    const val AUTH_LOGOUT = "/v1/auth/logout"
}

const val PROTOCOL_VERSION = 2

/** Lenient: the server adds fields as it migrates commands. */
val wireJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

// ---------------------------------------------------------------- health

/**
 * Matches `handle_health` in remote-server/src/api.rs, which is what actually
 * answers -- not the `HealthReport` struct in remote-protocol, which is unused
 * and describes a different shape entirely (it advertises `currentUserId`,
 * `endpoint` and `authenticated`; none of those are on the wire).
 *
 * Note this endpoint needs no token. A successful parse therefore proves the
 * server is reachable and says nothing about whether our credential is good.
 */
@Serializable
data class HealthReport(
    @SerialName("status") val status: String = "",
    @SerialName("appVersion") val appVersion: String = "",
    @SerialName("protocolVersion") val protocolVersion: Int = 0,
    @SerialName("runtime") val runtime: HealthRuntime? = null,
    @SerialName("tenants") val tenants: HealthTenants? = null,
    @SerialName("commands") val commands: HealthCommands? = null
) {
    val degraded: Boolean get() = status != "ok"

    /**
     * Whether this server has any tenant at all.
     *
     * Absent means a server predating tenants, which we treat as "occupied" so
     * we never claim against a shape we do not understand.
     */
    val hasTenants: Boolean get() = (tenants?.registered ?: 1) > 0
}

@Serializable
data class HealthRuntime(
    @SerialName("started") val started: Boolean = true,
    @SerialName("error") val error: String? = null
)

/**
 * How many user slots this server carries. Reading it before claiming is what
 * lets the UI say "you are the first user here" instead of demanding an invite
 * code that does not exist yet.
 */
@Serializable
data class HealthTenants(
    @SerialName("registered") val registered: Int = 0,
    @SerialName("live") val live: Int = 0,
    @SerialName("running") val running: Int = 0
)

@Serializable
data class HealthCommands(
    @SerialName("calls") val calls: Long = 0,
    @SerialName("ok") val ok: Long = 0,
    @SerialName("unimplemented") val unimplemented: Long = 0,
    @SerialName("failed") val failed: Long = 0,
    @SerialName("supported") val supported: List<String> = emptyList()
)

// ---------------------------------------------------------------- command

@Serializable
data class CommandRequest(
    val command: String,
    val args: JsonElement? = null
)

@Serializable
data class CommandError(
    val message: String = ""
)

// ---------------------------------------------------------------- tenant

/**
 * Body of `POST /v1/tenants`.
 *
 * `inviteCode` is omitted entirely when blank rather than sent as `""`: the
 * server distinguishes "no code supplied" from "a code was supplied and it was
 * empty", and the first is what it accepts for the very first tenant.
 */
@Serializable
data class TenantRegistrationRequest(
    val label: String,
    @SerialName("inviteCode") val inviteCode: String? = null
)

/** Answer to a successful claim. [token] is the only copy that will ever exist. */
@Serializable
data class TenantCredential(
    @SerialName("tenantId") val tenantId: String = "",
    val label: String = "",
    val token: String = ""
)

/** Body of a refused claim, e.g. `{"message": "...", "kind": "admissionClosed"}`. */
@Serializable
data class TenantFailure(
    val message: String = "",
    val kind: String = ""
)

/** The `kind` values the server can send, so callers can branch without string literals. */
object TenantFailureKind {
    /** A tenant already exists and the invite code was missing or wrong. */
    const val ADMISSION_CLOSED = "admissionClosed"
    const val DUPLICATE_LABEL = "duplicateLabel"
    const val INVALID_LABEL = "invalidLabel"
    const val OTHER = "other"
}

// ---------------------------------------------------------------- auth

@Serializable
data class AuthStatus(
    val authenticated: Boolean = false,
    val userId: String = "",
    val displayName: String = "",
    val endpoint: String = "",
    val loginAvailable: Boolean = true,
    val runtimeError: String? = null
)

@Serializable
data class AuthAccountEntry(
    val userId: String = "",
    val displayName: String? = null,
    val username: String? = null,
    val iconUrl: String? = null
)

@Serializable
data class AuthAccountsStatus(
    val authenticated: Boolean = false,
    val loginAvailable: Boolean = true,
    val currentUserId: String = "",
    val currentDisplayName: String? = null,
    val accounts: List<AuthAccountEntry> = emptyList()
)

@Serializable
data class LoginRequest(
    val username: String = "",
    val password: String = "",
    val saveCredentials: Boolean = false,
    val userId: String = ""
)

@Serializable
data class TwoFactorRequest(
    val attemptId: String,
    val method: String,
    val code: String
)

/**
 * `#[serde(tag = "status")]` -- a client needs exactly one parser for all four.
 *
 * The discriminator key is `"status"`, not kotlinx's default `"type"`, so it
 * must be declared here. Without it the decoder reports "class discriminator
 * was missing" and every sign-in -- including a 2FA challenge -- throws.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("status")
sealed interface AuthOutcome {
    /**
     * Signed in.
     *
     * [token] is deliberately nullable and is `null` on every current server.
     * It used to carry the server-wide bearer token, which is exactly the flaw
     * the tenant work removed: the token now comes from `POST /v1/tenants`
     * *before* sign-in, and a sign-in response that handed it out would put a
     * credential on the wire that the caller already has. Keep whatever tenant
     * token is already in storage -- do not overwrite it with this field.
     */
    @Serializable
    @SerialName("authenticated")
    data class Authenticated(
        val userId: String = "",
        val displayName: String = "",
        val endpoint: String = "",
        val token: String? = null
    ) : AuthOutcome

    @Serializable
    @SerialName("challenge")
    data class Challenge(
        val attemptId: String = "",
        val methods: List<String> = emptyList(),
        val error: String? = null
    ) : AuthOutcome

    @Serializable
    @SerialName("failed")
    data class Failed(
        val reason: String = "",
        val kind: String = ""
    ) : AuthOutcome

    @Serializable
    @SerialName("cancelled")
    data object Cancelled : AuthOutcome
}

// ---------------------------------------------------------------- stream

/**
 * `#[serde(tag = "kind")]`. Flat, not nested -- see the note at the top of this file.
 *
 * Discriminator key is `"kind"`; declaring it is what stops every event from
 * being silently dropped as "class discriminator was missing".
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface StreamFrame {
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocolVersion: Int = 0,
        val appVersion: String = ""
    ) : StreamFrame

    @Serializable
    @SerialName("event")
    data class Event(
        val event: String = "",
        val payload: JsonElement? = null,
        val seq: Long = 0
    ) : StreamFrame

    @Serializable
    @SerialName("lagged")
    data class Lagged(val skipped: Long = 0) : StreamFrame
}

// ---------------------------------------------------------------- feed

/**
 * Categories a feed row can belong to.
 *
 * Wire values are the variants' exact names (from the server's `FeedFilter`
 * enum, which only renames `Gps` to the literal `"GPS"`); `Status`/`Bio`/
 * `Avatar`/`Online`/`Offline` travel capitalised. The UI layer adds a few
 * client-only groupings (trust level, friend/unfriend, on-player-joining)
 * that are not part of this wire enum.
 */
@Serializable
enum class FeedFilter {
    @SerialName("GPS") Gps,
    Status,
    Bio,
    Avatar,
    Online,
    Offline
}

/** What a `feed_rows_query` page is sliced by. Wire values are lowercase. */
@Serializable
enum class FeedQueryMode {
    @SerialName("search") Search,
    @SerialName("lookup") Lookup,
    @SerialName("instance") Instance
}

/**
 * Continuation token returned by `feed_latest_query`.
 *
 * Passed straight back into `feed_rows_query` (`mode = "lookup"`) to fetch the
 * next older page; `null` means the persisted store has nothing older.
 */
@Serializable
data class FeedCursorInput(
    @SerialName("createdAt") val createdAt: String = "",
    val sourceRank: Long = 0,
    val rowId: Long = 0
)

/**
 * Input of `app__feed_latest_query` (a FLAT command: the whole object goes
 * under the `query` key -- see [ArgForms.FLAT_ARG_NAMES]).
 *
 * `userId` is the owner whose feed is being read; the server scopes rows to
 * that user. `maxRows` caps the first page (the desktop uses a table limit).
 *
 * `filters` is an inclusion list, and the two "empty" values differ: `null`
 * (omitted from the JSON) means "no filter" and returns everything, while an
 * empty array means "match nothing" and returns no rows. Verified against a
 * live server, so it is passed as `null` whenever no chip is selected.
 */
@Serializable
data class FeedLatestQueryInput(
    val userId: String,
    val filters: List<FeedFilter>? = null,
    val favoriteUserIds: List<String> = emptyList(),
    val scopedUserIds: List<String> = emptyList(),
    val excludedUserIds: List<String> = emptyList(),
    val favoritesOnly: Boolean = false,
    val maxRows: Long
)

/** Paged read used by "load older". Same shape as [FeedLatestQueryInput] plus a cursor. */
@Serializable
data class FeedRowsQueryInput(
    val userId: String,
    val mode: FeedQueryMode = FeedQueryMode.Lookup,
    val search: String = "",
    val filters: List<FeedFilter>? = null,
    val vipList: List<String> = emptyList(),
    val scopedUserIds: List<String> = emptyList(),
    val excludedUserIds: List<String> = emptyList(),
    val maxEntries: Long,
    val dateFrom: String = "",
    val dateTo: String = "",
    val cursor: FeedCursorInput? = null
)

/**
 * Input of `app__feed_search_query` (also FLAT, whole object under `query`).
 *
 * `search` is matched case-insensitively against the row's text fields, and
 * accepts `WRLD_`/`GRP_` prefixes to match a location, and `PRIVATE`/`PUBLIC`
 * to match the avatar ownership of the row's owner. The server folds
 * `FeedSearchQueryInput` and `FeedLatestQueryInput` into the same matcher, so
 * the fields line up one-to-one.
 */
@Serializable
data class FeedSearchQueryInput(
    val userId: String,
    val search: String = "",
    val filters: List<FeedFilter>? = null,
    val favoriteUserIds: List<String> = emptyList(),
    val scopedUserIds: List<String> = emptyList(),
    val excludedUserIds: List<String> = emptyList(),
    val favoritesOnly: Boolean = false,
    val dateFrom: String = "",
    val dateTo: String = "",
    val maxRows: Long
)

/**
 * One feed row. Every field is optional on the wire (`skip_serializing_if`
 * on the server), so a row may only ever carry a `type`/`displayName`/time.
 *
 * `created_at` is the one field the server forces to snake_case (an explicit
 * `#[serde(rename = "created_at")]` overrides the struct's camelCase default),
 * hence the annotation below; everything else follows camelCase.
 */
@Serializable
data class FeedRowOutput(
    val rowId: Long? = null,
    val sourceRank: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val userId: String? = null,
    val displayName: String? = null,
    @SerialName("type") val type: String? = null,
    val location: String? = null,
    val worldName: String? = null,
    val previousLocation: String? = null,
    val time: Long? = null,
    val groupName: String? = null,
    val status: String? = null,
    val statusDescription: String? = null,
    val previousStatus: String? = null,
    val previousStatusDescription: String? = null,
    val bio: String? = null,
    val previousBio: String? = null,
    val ownerId: String? = null,
    val avatarName: String? = null,
    val currentAvatarImageUrl: String? = null,
    val currentAvatarThumbnailImageUrl: String? = null,
    val currentAvatarTags: List<String>? = null,
    val previousOwnerId: String? = null,
    val previousAvatarName: String? = null,
    val previousCurrentAvatarImageUrl: String? = null,
    val previousCurrentAvatarThumbnailImageUrl: String? = null,
    val previousCurrentAvatarTags: List<String>? = null,
    val ownerUserId: String? = null
)

/** Output of `app__feed_latest_query`. */
@Serializable
data class FeedReadModelOutput(
    val rows: List<FeedRowOutput> = emptyList(),
    val maxSequence: Long = 0,
    val persistedCursor: FeedCursorInput? = null,
    val persistedHasMore: Boolean = false
)

// ---------------------------------------------------------------- friends

/**
 * A friend record, exactly as the server serialises a `FriendRecord`.
 *
 * Most keys are camelCase; the four timestamps are explicitly snake_case on
 * the wire (`date_joined`/`last_activity`/`last_login`/`last_mobile`). The
 * server's `#[serde(flatten)] extra` map is intentionally dropped -- a thin
 * client only needs the typed fields and `wireJson` ignores the rest.
 */
@Serializable
data class FriendRecord(
    val id: String = "",
    val displayName: String = "",
    val username: String = "",
    val state: String = "",
    val location: String = "",
    val travelingToLocation: String = "",
    val worldId: String = "",
    val platform: String = "",
    val lastPlatform: String = "",
    val status: String = "",
    val statusDescription: String = "",
    val bio: String = "",
    val iconUrl: String = "",
    val currentAvatarImageUrl: String = "",
    val currentAvatarThumbnailImageUrl: String = "",
    val currentAvatarAuthorId: String = "",
    val currentAvatarName: String = "",
    @SerialName("date_joined") val dateJoined: String? = null,
    @SerialName("last_activity") val lastActivity: String? = null,
    @SerialName("last_login") val lastLogin: String? = null,
    @SerialName("last_mobile") val lastMobile: String? = null
)

/**
 * Output of `app__social_friend_roster_baseline_get`.
 *
 * `snapshot` is a raw JSON blob on the wire (`RawJson` on the server): an
 * object with `friendsById` (friendId -> [FriendRecord]) and
 * `orderedFriendIds` (friendId array). It is kept as [JsonElement] here and
 * unwrapped by the repository, because kotlinx cannot model the server's
 * `#[serde(flatten)]` map and we only need those two keys.
 */
@Serializable
data class SocialFriendRosterBaselineOutput(
    val userId: String = "",
    val stale: Boolean = false,
    val count: Long = 0,
    val detail: String = "",
    val snapshot: JsonElement? = null,
    val friendLogChanged: Boolean = false
)

/**
 * A friend-roster snapshot: `friendsById` (friendId -> [FriendRecord]) plus the
 * server's own ordering.
 *
 * This is the shape carried both by [SocialFriendRosterBaselineOutput.snapshot]
 * and by `authenticatedRuntimePhase.friendBaseline.snapshot` inside
 * [CombinedSnapshotOutput]. The server does write `orderedFriendIds` there (the
 * combined snapshot had 242 ids), but the Android UI still derives its own
 * presence ordering, so the field is only carried, not relied on.
 */
@Serializable
data class FriendRosterSnapshot(
    @SerialName("friendsById") val friendsById: Map<String, FriendRecord> = emptyMap(),
    @SerialName("orderedFriendIds") val orderedFriendIds: List<String> = emptyList()
)

/**
 * Minimal view of `app__backend_runtime_combined_snapshot_get`, the real
 * hydration source for the signed-in user.
 *
 * Why this and not `app__social_friend_roster_baseline_get`: that command hands
 * out a baseline exactly once and afterwards answers
 * `{"snapshot":null,"detail":"Superseded friend roster baseline."}` (verified
 * against a live server). A thin client that has not stored that baseline gets
 * an empty roster and renders a blank friend list. The combined snapshot always
 * carries the full roster under `authenticatedRuntimePhase.friendBaseline`.
 *
 * The payload is large and mostly irrelevant here, so only the path down to the
 * roster is modelled; `wireJson` ignores the rest.
 */
@Serializable
data class CombinedSnapshotOutput(
    val authenticatedRuntimePhase: CombinedRuntimePhase? = null,
    val authenticatedSession: CombinedSession? = null
)

/**
 * The signed-in account's own snapshot, inside the combined snapshot.
 *
 * Worth reading even though the roster lives elsewhere: it is where the client
 * can find **its own avatar**, which is otherwise a separate command. Note
 * `currentAvatar` is an `avtr_...` id and the snapshot does NOT carry a name for
 * it, so the name has to be resolved (see `AvatarNameRepository`).
 */
@Serializable
data class CombinedSession(
    val session: CombinedSessionBody? = null
)

@Serializable
data class CombinedSessionBody(
    val currentUserSnapshot: CombinedCurrentUser? = null
)

/**
 * The signed-in account's own record.
 *
 * This is the richest single source about the user: the profile page can be
 * rendered almost entirely from here without one extra command. Only `bio`
 * (and the badge list) has to come from `app__vrchat_user_profile_get`,
 * because the session snapshot does not carry them.
 *
 * Note `currentAvatar` is an `avtr_...` id with **no name beside it** in this
 * payload, so the model name still has to be resolved.
 */
@Serializable
data class CombinedCurrentUser(
    val id: String = "",
    val displayName: String = "",
    val username: String = "",
    val currentAvatar: String = "",
    val currentAvatarImageUrl: String = "",
    val currentAvatarThumbnailImageUrl: String = "",
    val iconUrl: String = "",
    val bannerUrl: String = "",
    val status: String = "",
    val statusDescription: String = "",
    val pronouns: String = "",
    val state: String = "",
    val stateBucket: String = "",
    val tags: List<String> = emptyList(),
    val ageVerificationStatus: String = "",
    val ageVerified: Boolean = false,
    val allowAvatarCopying: Boolean = true,
    val isBoopingEnabled: Boolean = true,
    val hasSharedConnectionsOptOut: Boolean = false,
    val homeLocation: String = "",
    val location: String = "",
    /** Group ids the account belongs to -- the only group source on the wire. */
    val presence: CombinedPresence? = null,
    /** The account's own friends, in three buckets. */
    val friends: List<String> = emptyList(),
    val onlineFriends: List<String> = emptyList(),
    val offlineFriends: List<String> = emptyList(),
    val activeFriends: List<String> = emptyList(),
    @SerialName("date_joined") val dateJoined: String? = null,
    @SerialName("last_login") val lastLogin: String? = null,
    @SerialName("last_activity") val lastActivity: String? = null
)

@Serializable
data class CombinedPresence(
    val groups: List<String> = emptyList(),
    val platform: String = "",
    val status: String = "",
    val world: String = "",
    val instanceType: String = "",
    /** The raw instance segment (`12345~hidden(usr_...)~region(jp)`), or "offline". */
    val instance: String = ""
)

/**
 * `authenticatedRuntimePhase` -- the live runtime state of the signed-in
 * account.
 *
 * All three baselines sit under here. `favoritesBaseline` is the one that
 * matters for the favourites tab: see [FavoritesBaselineOutput] for why it is
 * read from here rather than from `app__favorite_list`.
 */
@Serializable
data class CombinedRuntimePhase(
    val userId: String = "",
    val friendBaseline: FriendBaselineOutput? = null,
    val favoritesBaseline: FavoritesBaselineOutput? = null,
    val favorites: FavoritesLoadState? = null
)

@Serializable
data class FriendBaselineOutput(
    val count: Long = 0,
    val snapshot: FriendRosterSnapshot? = null
)

/**
 * How the server's own favourites load went.
 *
 * `detail` reads like `"183 favorites loaded."`; when the fetch failed,
 * `lastError` carries the reason and `status` is not `"ready"`. Surfaced so the
 * favourites tab can tell "you have no favourites" apart from "the load
 * failed", which would otherwise both be an empty list.
 */
@Serializable
data class FavoritesLoadState(
    val status: String = "",
    val detail: String = "",
    val lastError: String? = null,
    val attempt: Long = 0
)

// ---------------------------------------------------------------- mutual graph

/**
 * Six-state fetch machine for the mutual-friends graph (see ANDROID_UI_SPEC 3.4).
 *
 * The server uses `#[serde(rename_all = "camelCase")]`, so the wire values are
 * lowercased first letters (`"idle"`/`"running"`/...). The Kotlin variants
 * MUST carry explicit `@SerialName` annotations, or kotlinx would send
 * `"Idle"`/`"Running"` and never match an incoming status frame.
 */
@Serializable
enum class MutualGraphFetchState {
    @SerialName("idle") Idle,
    @SerialName("running") Running,
    @SerialName("cancelling") Cancelling,
    @SerialName("completed") Completed,
    @SerialName("cancelled") Cancelled,
    @SerialName("error") Error
}

/** Progress of an in-flight or finished mutual-graph fetch. */
@Serializable
data class MutualGraphFetchStatus(
    val runId: Long = 0,
    val revision: Long = 0,
    val status: MutualGraphFetchState = MutualGraphFetchState.Idle,
    val ownerUserId: String = "",
    val totalFriends: Long = 0,
    val processedFriends: Long = 0,
    val currentFriendId: String = "",
    val fetchedFriends: Long = 0,
    val optedOutFriends: Long = 0,
    val failedFriends: Long = 0,
    val cancelRequested: Boolean = false,
    val startedAt: String = "",
    val updatedAt: String = "",
    val finishedAt: String? = null,
    val lastError: String? = null
)

@Serializable
data class MutualGraphLink(
    val friendId: String = "",
    val mutualId: String = ""
)

@Serializable
data class MutualGraphMeta(
    val friendId: String = "",
    val lastFetchedAt: String = "",
    val optedOut: Boolean = false,
    val totalCount: Int? = null
)

/** A committed mutual-graph snapshot, returned by `app__mutual_graph_snapshot_get`. */
@Serializable
data class MutualGraphSnapshotOutput(
    val friendIds: List<String> = emptyList(),
    val links: List<MutualGraphLink> = emptyList(),
    val meta: List<MutualGraphMeta> = emptyList()
)

/**
 * The single seam between the ViewModel and the command layer.
 *
 * Repositories take this instead of a [CommandClient] so they stay testable
 * without a network, and so the ViewModel owns client lifecycle (rebuilt on
 * every address/token change) without leaking it into the data layer.
 */
fun interface CommandRunner {
    suspend operator fun invoke(command: String, args: Map<String, Any?>): JsonElement
}

// ---------------------------------------------------------------- shared envelopes

/**
 * `{"data": ..., "status": 200}` — the envelope several server commands answer
 * with.
 *
 * The important detail is that `data` is a **string** on some commands and a
 * real object on others, and which is which is not obvious from the name:
 *
 *   - `app__vrchat_user_profile_get` -> `{"data":"{\"bio\":...}"}` (a string)
 *   - `app__vrchat_user_get`         -> `{"data":"{\"displayName\":...}"}`
 *   - `app__world_get`               -> `{"data":"{\"name\":...}"}`
 *   - `app__avatar_get`              -> flat object, NO envelope at all
 *
 * Use [unwrapDataEnvelope] rather than reading `data` directly; it handles the
 * string form and leaves an already-flat reply untouched.
 */
@Serializable
data class JsonDataEnvelope(
    val data: JsonElement? = null,
    val status: Long? = null
)

/**
 * Normalises a command reply to the payload inside `data`, if there is one.
 *
 * A stringified document is parsed; an object is returned as-is; a reply with
 * no `data` key (a flat command such as `app__avatar_get`) passes through.
 */
fun unwrapDataEnvelope(element: JsonElement): JsonElement {
    val obj = element as? JsonObject ?: return element
    val data = obj["data"] ?: return element
    if (data is JsonPrimitive && data.isString) {
        return runCatching { wireJson.parseToJsonElement(data.content) }.getOrDefault(data)
    }
    return data
}

// ---------------------------------------------------------------- images

/**
 * Output of `app__external_api_image_data_url_get`: a `data:image/png;base64,...`
 * string.
 *
 * This is the only way to read a VRChat image URL: those URLs answer 403 to
 * anyone without the account's session cookie, which lives on the server. A
 * client that points an image loader straight at `api.vrchat.cloud` gets a
 * broken image for every single avatar.
 */
@Serializable
data class ImageDataUrlOutput(
    val data: String = ""
)

/**
 * Strips the `data:<mime>;base64,` prefix and decodes the payload.
 *
 * `java.util.Base64` (not `android.util.Base64`) on purpose: this code is also
 * exercised by JVM unit tests, where the Android class does not exist. The
 * *MIME* decoder is used rather than the basic one because it ignores
 * characters outside the base64 alphabet, so a payload that arrives with line
 * breaks does not fail to decode.
 */
fun ImageDataUrlOutput.toBytes(): ByteArray? {
    val comma = data.indexOf(',')
    if (comma < 0) return null
    return runCatching {
        Base64.getMimeDecoder().decode(data.substring(comma + 1))
    }.getOrNull()
}

// ---------------------------------------------------------------- worlds

/**
 * One entry of `app__world_summaries_get`.
 *
 * The command answers a map keyed by world id (`{ "wrld_x": {...} }`), not an
 * object with a `worlds` array -- [parseWorldSummaries] is what turns it into
 * something addressable.
 */
@Serializable
data class WorldSummary(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val authorName: String = "",
    val imageUrl: String = "",
    val thumbnailImageUrl: String = ""
)

/** Decodes the `worldId -> [WorldSummary]` map, skipping any entry that fails. */
fun parseWorldSummaries(element: JsonElement): Map<String, WorldSummary> {
    val obj = element as? JsonObject ?: return emptyMap()
    return obj.entries.mapNotNull { (key, value) ->
        runCatching { key to wireJson.decodeFromJsonElement<WorldSummary>(value) }.getOrNull()
    }.toMap()
}

/**
 * A world's full record, from `app__world_get` with `full = true`.
 *
 * The distinction matters and is the reason this type exists separately from
 * [WorldSummary]:
 *
 *  - `app__world_summaries_get` (batch) and `app__world_get` **without** `full`
 *    both read the server's local cache, whose table has exactly twelve columns
 *    (`id`, `author_id`, `author_name`, `created_at`, `description`, `image_url`,
 *    `name`, `release_status`, `thumbnail_image_url`, `updated_at`, `version`
 *    plus `added_at`). Capacity, visit counts, tags, platforms and the date
 *    ranges are simply not in there.
 *  - `app__world_get` **with** `full = true` skips the cache entirely and hits
 *    the live VRChat API, which answers the complete world document.
 *
 * So "how detailed can this be" was never a UI limit -- the thin card was
 * showing everything the client had asked for. Asking properly is what unlocks
 * the rest.
 *
 * Every field is defaulted because VRChat omits keys rather than sending nulls,
 * and a missing `capacity` must not fail the whole decode.
 */
@Serializable
data class WorldDetail(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    @SerialName("authorId") val authorId: String = "",
    @SerialName("authorName") val authorName: String = "",
    val capacity: Int = 0,
    /** VRChat's suggested capacity, usually below [capacity]. */
    @SerialName("recommendedCapacity") val recommendedCapacity: Int = 0,
    @SerialName("imageUrl") val imageUrl: String = "",
    @SerialName("thumbnailImageUrl") val thumbnailImageUrl: String = "",
    /** ISO-8601. Present on every world, unlike [createdAt]/[updatedAt] pair. */
    @SerialName("publicationDate") val publicationDate: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("labsPublicationDate") val labsPublicationDate: String = "",
    @SerialName("releaseStatus") val releaseStatus: String = "",
    val version: Long = 0,
    val visits: Long = 0,
    val favorites: Long = 0,
    val popularity: Long = 0,
    val heat: Long = 0,
    val occupants: Int = 0,
    @SerialName("publicOccupants") val publicOccupants: Int = 0,
    @SerialName("privateOccupants") val privateOccupants: Int = 0,
    val tags: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    @SerialName("unityPackages") val unityPackages: List<UnityPackage> = emptyList(),
    @SerialName("isLabs") val isLabs: Boolean = false,
    val featured: Boolean = false,
    @SerialName("hasPersistData") val hasPersistData: Boolean = false,
    @SerialName("previewYoutubeId") val previewYoutubeId: String = "",
    val organization: String = ""
) {
    /** True once the live fetch has actually produced a world. */
    val isLoaded: Boolean get() = id.isNotBlank() || name.isNotBlank()

    /**
     * The best date to show as "published", by decreasing trustworthiness.
     *
     * `publicationDate` is the canonical one; `created_at` is the server cache's
     * fallback and was null for every row checked on this deployment, so it is
     * only used when it is genuinely there.
     */
    val publishedAt: String
        get() = listOf(publicationDate, createdAt, updatedAt)
            .firstOrNull { it.isNotBlank() } ?: ""
}

/**
 * One entry of a world's `unityPackages`: the actual uploaded build.
 *
 * This is what answers "is there a Quest version?" and "when was it last
 * updated?" -- a world can exist for PC only, and the platforms list alone does
 * not say which build is newer.
 */
@Serializable
data class UnityPackage(
    val id: String = "",
    val platform: String = "",
    @SerialName("unityVersion") val unityVersion: String = "",
    @SerialName("assetVersion") val assetVersion: Int = 0,
    val variant: String = "",
    @SerialName("performanceRating") val performanceRating: String = "",
    val scanStatus: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("assetUrl") val assetUrl: String = ""
) {
    /** "PC" / "Android" / "iOS" -- the same labels the rest of the app uses. */
    val platformLabel: String
        get() = when (platform.lowercase()) {
            "standalonewindows" -> "PC"
            "android" -> "Android"
            "ios" -> "iOS"
            else -> platform.ifBlank { "未知" }
        }
}

/**
 * A friend who has been to this world, from `app__world_friend_visits`.
 *
 * Note this is **historical**: it is assembled from the feed's GPS and
 * online/offline tables, so it answers "who has ever been here", not "who is
 * here now". The two are different questions and the sheet labels them
 * accordingly -- live presence comes from the roster, which the home tab already
 * groups by instance.
 */
@Serializable
data class WorldFriendVisitRow(
    @SerialName("user_id") val userId: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("visit_count") val visitCount: Long = 0,
    @SerialName("last_visited_at") val lastVisitedAt: String = ""
)

@Serializable
data class WorldFriendVisits(
    @SerialName("friend_count") val friendCount: Long = 0,
    @SerialName("last_visited_at") val lastVisitedAt: String = "",
    val friends: List<WorldFriendVisitRow> = emptyList()
)

/**
 * One past visit to a world, from `app__world_previous_instances_get`.
 *
 * [time] is milliseconds spent there, which is the only real play-time signal
 * this deployment has (the desktop logs feed it).
 */
@Serializable
data class PreviousInstanceRow(
    val id: Long = 0,
    val location: String = "",
    @SerialName("world_name") val worldName: String = "",
    @SerialName("group_name") val groupName: String = "",
    val time: Long = 0,
    @SerialName("created_at") val createdAt: String = ""
)

/**
 * The live occupant count for one instance, from `app__vrchat_instance_get`.
 *
 * Only meaningful for a *specific* instance (the id after the colon in a
 * location) -- VRChat counts per instance, not per world.
 */
@Serializable
data class InstanceInfo(
    @SerialName("userCount") val userCount: Int = 0,
    val capacity: Int = 0,
    val id: String = "",
    val name: String = "",
    @SerialName("worldId") val worldId: String = "",
    val type: String = "",
    val region: String = "",
    @SerialName("ownerId") val ownerId: String = "",
    val nUsers: Int = 0,
    val closedAt: String = ""
) {
    /** "3 / 16" -- omitted when the instance is empty or unknown. */
    val occupancyCaption: String?
        get() {
            val cap = if (capacity > 0) capacity else nUsers
            if (userCount <= 0 && cap <= 0) return null
            return if (cap > 0) "$userCount / $cap" else "$userCount"
        }

    /** "jp" / "us" / "eu", VRChat's short region codes. */
    val regionLabel: String
        get() = when (region.lowercase()) {
            "jp" -> "日本"
            "us" -> "美国"
            "eu" -> "欧洲"
            "" -> ""
            else -> region
        }
}

/** Decodes a `app__world_friend_visits` answer, tolerating the id-keyed map form. */
fun parseWorldFriendVisits(element: JsonElement): WorldFriendVisits =
    runCatching { wireJson.decodeFromJsonElement<WorldFriendVisits>(unwrapDataEnvelope(element)) }
        .getOrDefault(WorldFriendVisits())

/** Decodes a `app__world_previous_instances_get` answer into rows. */
fun parsePreviousInstances(element: JsonElement): List<PreviousInstanceRow> =
    runCatching {
        wireJson.decodeFromJsonElement<List<PreviousInstanceRow>>(unwrapDataEnvelope(element))
    }.getOrDefault(emptyList())

/** Decodes an `app__vrchat_instance_get` answer, or null when it did not resolve. */
fun parseInstanceInfo(element: JsonElement): InstanceInfo? =
    runCatching { wireJson.decodeFromJsonElement<InstanceInfo>(unwrapDataEnvelope(element)) }
        .getOrNull()
        ?.takeIf { it.userCount > 0 || it.capacity > 0 || it.id.isNotBlank() }

/**
 * The instance id inside a location -- everything after the first colon.
 *
 * `wrld_x:12345~region(jp)` -> `12345~region(jp)`. VRChat's instance endpoint
 * wants this suffix, not the world id, which is why a location cannot be passed
 * to it directly.
 */
fun instanceIdFromLocation(location: String): String {
    val trimmed = location.trim()
    if (!trimmed.startsWith("wrld_")) return ""
    return trimmed.substringAfter(':', "")
}

/**
 * Pulls the world id out of a VRChat `location`.
 *
 * A location looks like
 * `wrld_61e374f5-...:83734~group(grp_...~)~region(jp)`; the id is everything
 * before the first `:`.
 */
fun worldIdFromLocation(location: String): String {
    val trimmed = location.trim()
    if (!trimmed.startsWith("wrld_")) return ""
    return trimmed.substringBefore(':')
}

/**
 * The human-readable part of an instance, e.g. `Public`, `Friends+`, `Invite+`,
 * `Group`, `Group+` or `Group Public`.
 *
 * VRChat encodes access in the tags after the instance number
 * (`~groupAccessType(public)`, `~private(...)`, `~hidden(...)`, `~friends(...)`),
 * so the raw id carries the answer but nobody can read it.
 */
fun instanceAccessLabel(location: String): String {
    val rest = location.substringAfter(':', "")
    if (rest.isBlank()) return ""
    val groupAccess = Regex("groupAccessType\\(([a-z+]+)\\)").find(rest)?.groupValues?.get(1)
    if (groupAccess != null) {
        return when (groupAccess) {
            "public" -> "群组公开"
            "plus" -> "群组+"
            else -> "群组"
        }
    }
    return when {
        rest.contains("hidden(") -> "好友可见"
        rest.contains("private(") -> "私密"
        rest.contains("friends(") -> "好友"
        rest.contains("friends+") -> "好友+"
        rest.contains("invite+") -> "邀请+"
        rest.contains("invite(") -> "邀请"
        else -> "公开"
    }
}

// ---------------------------------------------------------------- avatars

/**
 * `app__avatar_get` answers a flat avatar object (`{"id","name",...}`) with no
 * `data` envelope -- unlike `app__world_get`, which does wrap.
 *
 * `name` is what the UI shows; `avtr_...` ids are meaningless to a reader.
 *
 * This is the only way to turn an `avtr_...` id into a name: neither the friend
 * records nor the favourites snapshot carry one (all 242 friend records have an
 * empty `currentAvatarName`). Verified fast (~3 ms per id), so resolving a whole
 * favourites page is affordable as long as the answers are cached.
 */
@Serializable
data class AvatarSummary(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val authorName: String = "",
    val authorId: String = "",
    val imageUrl: String = "",
    val thumbnailImageUrl: String = "",
    val releaseStatus: String = "",
    val version: Long = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

// ---------------------------------------------------------------- users

/** A profile badge (from `app__vrchat_user_profile_get`). */
@Serializable
data class ProfileBadge(
    val badgeId: String = "",
    val badgeName: String = "",
    val badgeDescription: String = "",
    val badgeImageUrl: String = "",
    val showcased: Boolean = false
)

/**
 * A user profile.
 *
 * Merged from two different commands, which is why so much of it is optional:
 * `app__vrchat_user_profile_get` carries `bio`/`badges`/`pronouns`/`iconUrl`,
 * while `app__vrchat_user_get` carries `date_joined`/`friendRequestStatus` and
 * the avatar fields. Anything a friend record already knows (state, platform,
 * location) does not need to come from here at all.
 */
@Serializable
data class UserProfile(
    val id: String = "",
    val displayName: String = "",
    val bio: String = "",
    val bioLinks: List<String> = emptyList(),
    val pronouns: String = "",
    val iconUrl: String = "",
    val bannerColor: String = "",
    val bannerType: String = "",
    val currentAvatarImageUrl: String = "",
    val currentAvatarThumbnailImageUrl: String = "",
    val currentAvatarName: String = "",
    val status: String = "",
    val statusDescription: String = "",
    val state: String = "",
    val platform: String = "",
    val lastPlatform: String = "",
    val location: String = "",
    val worldId: String = "",
    /**
     * Trust tags.
     *
     * Both endpoints send these, under different names: `profile_get` calls
     * them `trustTags`, `user_get` calls them `tags`. They are the same
     * `system_trust_*` list, so both keys are read.
     */
    val trustTags: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val badges: List<ProfileBadge> = emptyList(),
    val representedGroup: String? = null,
    val hasVrcPlus: Boolean? = null,
    val isFriend: Boolean? = null,
    val friendRequestStatus: String? = null,
    val developerType: String? = null,
    val username: String = "",
    /** Spoken-language tags, e.g. ["chi", "eng"]. */
    val languages: List<String> = emptyList(),
    /** Whether other users can clone this person's avatar. */
    @SerialName("allowAvatarCopying") val allowAvatarCopying: Boolean? = null,
    /** A custom uploaded profile image (distinct from the avatar thumbnail). */
    @SerialName("userIcon") val userIcon: String = "",
    // The two timestamps the server writes snake_case.
    @SerialName("date_joined") val dateJoined: String? = null,
    @SerialName("last_login") val lastLogin: String? = null,
    @SerialName("last_activity") val lastActivity: String? = null
) {
    /** Whichever trust-tag key this reply happened to use. */
    val effectiveTrustTags: List<String> get() = trustTags.ifEmpty { tags }
}

// ---------------------------------------------------------------- favorites

/**
 * The favourites baseline inside the combined snapshot.
 *
 * **Read favourites from here, not from `app__favorite_list`.**
 * `app__favorite_list` queries the server's *local* database, which on this
 * deployment has never been populated -- it answers `[]` for all three kinds
 * even though the account has 183 favourites. The combined snapshot instead
 * carries the live VRChat baseline (`/favorites` + `/favorite/groups`), which
 * is why the favourites tab has anything to show at all.
 */
@Serializable
data class FavoritesBaselineOutput(
    val count: Long = 0,
    val snapshot: FavoritesSnapshot? = null
)

@Serializable
data class FavoritesSnapshot(
    val currentUserId: String = "",
    val detail: String = "",
    val favoriteWorldIds: List<String> = emptyList(),
    val favoriteAvatarIds: List<String> = emptyList(),
    val favoriteFriendIds: List<String> = emptyList(),
    val favoriteWorldGroups: List<FavoriteGroup> = emptyList(),
    val favoriteAvatarGroups: List<FavoriteGroup> = emptyList(),
    val favoriteFriendGroups: List<FavoriteGroup> = emptyList(),
    /**
     * Group key -> the ids filed under it.
     *
     * Only worlds and friends get one of these from the server; avatars do not,
     * so the avatar grouping is derived from [remoteFavoritesById] instead.
     */
    val groupedFavoriteWorldIdsByGroupKey: Map<String, List<String>> = emptyMap(),
    val groupedFavoriteFriendIdsByGroupKey: Map<String, List<String>> = emptyMap(),
    /** favouriteId (`fvrt_...`) -> which entity it points at and where it is filed. */
    val remoteFavoritesById: Map<String, RemoteFavorite> = emptyMap(),
    val favoriteLimits: FavoriteLimits? = null,
    /**
     * VRCX's *own* local favourites, separate from VRChat's.
     *
     * These are the ones VRCX keeps in the server database rather than on the
     * VRChat account, so they survive without the account and can hold anything
     * the user pasted in. The three shapes are: the group names (`List<String>`,
     * not objects -- there is no key or capacity), the ids, and the
     * group-name -> ids map. Empty on this deployment, but modelled so the tab
     * can show them the moment they exist.
     */
    val localAvatarFavoriteGroups: List<String> = emptyList(),
    val localAvatarFavorites: Map<String, List<String>> = emptyMap(),
    val localAvatarFavoritesList: List<String> = emptyList(),
    val localFriendFavoriteGroups: List<String> = emptyList(),
    val localFriendFavorites: Map<String, List<String>> = emptyMap(),
    val localFriendFavoritesList: List<String> = emptyList()
)

/**
 * A favourite group as VRChat models it.
 *
 * [key] is the identity (`"world:worlds1"`), [displayName] is what to show.
 * [capacity] is the plan's per-group limit and [count] the current occupancy,
 * which together make a useful "80 / 100" caption.
 */
@Serializable
data class FavoriteGroup(
    val key: String = "",
    val name: String = "",
    val displayName: String = "",
    val type: String = "",
    val count: Long = 0,
    val capacity: Long = 0,
    val visibility: String = "",
    val assign: Boolean = false
)

/**
 * One line of [FavoritesSnapshot.remoteFavoritesById].
 *
 * The key of the map is the *favourite*'s id (`fvrt_...`), while
 * [favoriteId] is the **entity** id (`wrld_...`/`avtr_...`/`usr_...`) -- the
 * two are easy to confuse and only the latter can be looked up.
 */
@Serializable
data class RemoteFavorite(
    val id: String = "",
    val favoriteId: String = "",
    val type: String = "",
    @SerialName("\$groupKey") val groupKey: String = "",
    val tags: List<String> = emptyList()
)

@Serializable
data class FavoriteLimits(
    val maxFavoriteGroups: Map<String, Long> = emptyMap(),
    val maxFavoritesPerGroup: Map<String, Long> = emptyMap()
)

// ---------------------------------------------------------------- groups

/**
 * A VRChat group, as returned by `app__vrchat_group_get`.
 *
 * That command answers with the stringified-document envelope
 * (`{"data":"{\"name\":...}"}`), so this is decoded from [unwrapDataEnvelope].
 */
@Serializable
data class GroupSummary(
    val id: String = "",
    val name: String = "",
    val shortCode: String = "",
    val discriminator: String = "",
    val description: String = "",
    val iconUrl: String = "",
    val bannerUrl: String = "",
    val memberCount: Long = 0,
    val onlineMemberCount: Long = 0,
    val privacy: String = "",
    val isRepresenting: Boolean = false,
    val ownerId: String = "",
    @SerialName("createdAt") val createdAt: String? = null
)

/** `app__vrchat_group_posts_get` -> the same stringified envelope. */
@Serializable
data class GroupPostsOutput(
    val posts: List<GroupPost> = emptyList(),
    val total: Long = 0
)

@Serializable
data class GroupPost(
    val id: String = "",
    val groupId: String = "",
    val authorId: String = "",
    val title: String = "",
    val text: String = "",
    val imageUrl: String = "",
    val visibility: String = "",
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null
)

// ---------------------------------------------------------------- config

/**
 * One `{key, value}` pair from `app__config_list_values`.
 *
 * Both directions use this exact shape -- `app__config_set_values` takes a flat
 * `entries` array of the same objects. The keys are stored namespaced
 * (`config:vrcx_<lowercased name>`), but both commands accept and return either
 * form, so this client always passes the plain name it means.
 *
 * [value] is a **string**, and structured values are JSON-encoded inside it
 * (e.g. the credentials blob). Callers that know a key's shape parse it; the
 * rest are shown as text.
 */
@Serializable
data class ConfigEntry(
    val key: String = "",
    val value: String = ""
)


/**
 * A trust rank as VRChat officially names it in the client, from either shape
 * the server sends: a `system_trust_*` tag, a `TrustLevel` string as the friend
 * log writes it (`"Known User"`), or a `basic`/`known` shorthand. One mapping,
 * because three screens had drifted into three different translations.
 */
fun trustRankLabel(raw: String?): String {
    val v = raw.orEmpty().trim()
    if (v.isBlank()) return ""
    val lower = v.lowercase()
    return when {
        lower.contains("legendary") -> "传奇玩家"
        lower.contains("veteran") -> "资深玩家"
        lower.contains("trusted") -> "资深玩家" // legacy alias, kept for old logs
        lower.contains("known") -> "长期玩家"
        lower.contains("nuisance") -> "劣迹玩家"
        lower.contains("visitor") -> "游客"
        lower == "basic" || lower.contains("system_trust_basic") -> "萌新"
        lower == "user" || lower.contains("system_trust_user") -> "玩家"
        lower.contains("new_user") || lower == "new user" -> "萌新"
        else -> v
    }
}


/**
 * One row of the friend log the server accumulates by comparing consecutive
 * friend rosters: `Friend` (added), `Unfriend` (removed -- including being
 * removed by the other side), `FriendRequest`, `CancelFriendRequest`,
 * `DisplayName` (renamed) and `TrustLevel` (rank changed).
 *
 * The server's store starts empty and grows as it runs, so an empty answer is
 * "nothing recorded yet", not an error.
 */
@Serializable
data class FriendLogRow(
    @SerialName("row_id") val rowId: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("created_at") val createdAt: String = "",
    val type: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("previous_display_name") val previousDisplayName: String = "",
    @SerialName("trust_level") val trustLevel: String = "",
    @SerialName("previous_trust_level") val previousTrustLevel: String = "",
    @SerialName("friend_number") val friendNumber: kotlinx.serialization.json.JsonElement? = null
)

/** Which half of the feed screen is showing. */
enum class FeedSection { FEED, LOG }

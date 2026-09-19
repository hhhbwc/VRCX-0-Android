package com.vrcx0.android.data.repository

import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.FriendRecord
import com.vrcx0.android.data.remote.ProfileBadge
import com.vrcx0.android.data.remote.UserProfile
import com.vrcx0.android.data.remote.unwrapDataEnvelope
import kotlinx.serialization.json.JsonObject
import com.vrcx0.android.data.remote.wireJson
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Everything the detail sheets show about a person, assembled from three
 * sources that each know a different part of the answer.
 *
 * | field | source |
 * |---|---|
 * | bio, badges, pronouns, banner | `app__vrchat_user_profile_get` |
 * | display name, icon, join date, friend status | `app__vrchat_user_get` |
 * | state, platform, location, current world, avatar | the roster's [FriendRecord] |
 *
 * They are genuinely complementary, not redundant: the profile endpoint has no
 * `state`/`platform`/`location`, and `vrchat_user_get` has no `bio`. A friend
 * the client already holds a record for therefore needs only the profile call;
 * someone who is not a friend needs the user call as well to get a name.
 *
 * Both commands answer `{"data": "<stringified json>"}`, hence
 * [unwrapDataEnvelope] -- a different envelope shape from the image proxy's,
 * which really does carry a plain string.
 */
class ProfileRepository(private val runner: CommandRunner) {

    private val cache = HashMap<String, CachedCard>()

    /**
     * Loads a person's card.
     *
     * [friend] is optional and only a hint: when the caller already has the
     * roster record (a friend tapped in the list, or the author of a feed row
     * who happens to be a friend) its live presence fields are used instead of
     * leaving them blank.
     */
    suspend fun load(userId: String, friend: FriendRecord? = null, force: Boolean = false): UserCard {
        if (userId.isBlank()) return UserCard(userId = "", friend = friend)

        val now = System.currentTimeMillis()
        if (!force) {
            cache[userId]?.takeIf { now - it.at < TTL_MS }?.let { return it.card }
        }

        val profile = fetch<UserProfile>(PROFILE_COMMAND, userId)
        val user = fetch<UserProfile>(USER_COMMAND, userId)

        val card = UserCard(
            userId = userId,
            displayName = firstOf(
                profile?.displayName,
                user?.displayName,
                friend?.displayName,
                userId
            ),
            username = firstOf(user?.username, profile?.username),
            bio = firstOf(profile?.bio, user?.bio),
            pronouns = firstOf(profile?.pronouns, user?.pronouns),
            iconUrl = firstOf(
                profile?.iconUrl,
                user?.iconUrl,
                friend?.iconUrl
            ),
            bannerColor = firstOf(profile?.bannerColor, user?.bannerColor),
            badges = profile?.badges.orEmpty(),
            languages = profile?.languages.takeUnless { it.isNullOrEmpty() }
                ?: user?.languages.orEmpty(),
            allowAvatarCopying = profile?.allowAvatarCopying ?: user?.allowAvatarCopying,
            representedGroup = firstOf(profile?.representedGroup, user?.representedGroup),
            lastActivity = firstOf(friend?.lastActivity, user?.lastActivity, profile?.lastActivity),
            trustTags = profile?.effectiveTrustTags.orEmpty()
                .ifEmpty { user?.effectiveTrustTags.orEmpty() },
            status = firstOf(friend?.status, user?.status, profile?.status),
            statusDescription = firstOf(
                friend?.statusDescription,
                user?.statusDescription,
                profile?.statusDescription
            ),
            state = firstOf(friend?.state, user?.state, profile?.state),
            platform = firstOf(friend?.platform, user?.platform, profile?.platform),
            dateJoined = firstOf(profile?.dateJoined, user?.dateJoined, friend?.dateJoined),
            lastLogin = firstOf(friend?.lastLogin, user?.lastLogin, profile?.lastLogin),
            currentAvatarName = firstOf(
                profile?.currentAvatarName,
                user?.currentAvatarName,
                friend?.currentAvatarName
            ),
            currentAvatarThumbnailImageUrl = firstOf(
                friend?.currentAvatarThumbnailImageUrl,
                profile?.currentAvatarThumbnailImageUrl,
                user?.currentAvatarThumbnailImageUrl,
                friend?.currentAvatarImageUrl,
                profile?.currentAvatarImageUrl
            ),
            location = firstOf(friend?.location, user?.location),
            worldId = firstOf(
                friend?.worldId,
                user?.worldId,
                friend?.location?.let(::extractWorldId),
                user?.location?.let(::extractWorldId)
            ),
            isFriend = friend != null || user?.isFriend == true
        )

        cache[userId] = CachedCard(card, now)
        return card
    }

    /**
     * Sends a boop ("戳一戳") to [userId], optionally with an emoji.
     *
     * Verified on the wire: `app__vrchat_boop_send` (INPUT) answers
     * `{"data":"User booped!"}`. The server accepts either `emojiId` or
     * `inventoryItemId` and rejects both at once, so a blank [emojiId] means
     * the plain boop. Emoji ids for the built-in set are `default_<name>`
     * (e.g. `default_heart`), matching the desktop's emoji catalog.
     */
    suspend fun sendBoop(userId: String, emojiId: String = ""): Boolean {
        if (userId.isBlank()) return false
        val args = buildMap<String, Any?> {
            put("userId", userId)
            if (emojiId.isNotBlank()) put("emojiId", emojiId)
        }
        return runCatching {
            runner("app__vrchat_boop_send", args)
            true
        }.getOrDefault(false)
    }

    /**
     * Asks [userId] to invite me to their instance ("请求邀请").
     *
     * `app__vrchat_request_invite_send` (INPUT `{receiverUserId, params}`),
     * where `params` is `{}` -- `requestSlot` is optional on the wire and the
     * default slot is what the mobile UI sends too.
     */
    suspend fun requestInvite(receiverUserId: String): Boolean {
        if (receiverUserId.isBlank()) return false
        return runCatching {
            runner(
                "app__vrchat_request_invite_send",
                mapOf("receiverUserId" to receiverUserId, "params" to JsonObject(emptyMap()))
            )
            true
        }.getOrDefault(false)
    }

    /**
     * Invites [userId] into the instance I am in ("邀请加入").
     *
     * `app__notification_instance_invite_send` (INPUT) takes my current world
     * and instance; the caller reads those from this account's presence and
     * disables the action while offline, because inviting someone to
     * "offline" is meaningless.
     */
    suspend fun inviteToInstance(
        receiverUserId: String,
        worldId: String,
        instanceId: String,
        worldName: String
    ): Boolean {
        if (receiverUserId.isBlank() || worldId.isBlank() || instanceId.isBlank()) return false
        return runCatching {
            runner(
                "app__notification_instance_invite_send",
                mapOf(
                    "receiverUserId" to receiverUserId,
                    "worldId" to worldId,
                    "instanceId" to instanceId,
                    "worldName" to worldName
                )
            )
            true
        }.getOrDefault(false)
    }

    /**
     * Drops the cached card for [userId], so the next [load] goes to the wire.
     *
     * Needed because this cache has a 5-minute TTL and knows nothing about
     * writes: after the user edits their own presence, the cached card still
     * holds the *old* `status`/`statusDescription`, and opening the profile
     * sheet would render stale values over the freshly-saved ones -- the same
     * "I saved it and it shows the old text" symptom the presence overlay
     * exists to remove, just arriving through a different door.
     *
     * Invalidating is deliberately explicit rather than a blanket
     * `clear()`: only the editor knows whose card is now wrong.
     */
    fun invalidate(userId: String) {
        if (userId.isNotBlank()) cache.remove(userId)
    }

    private suspend inline fun <reified T> fetch(command: String, userId: String): T? =
        runCatching {
            val reply = runner(command, mapOf("userId" to userId))
            wireJson.decodeFromJsonElement<T>(unwrapDataEnvelope(reply))
        }.getOrNull()

    private fun extractWorldId(location: String): String =
        com.vrcx0.android.data.remote.worldIdFromLocation(location)

    fun clear() = cache.clear()

    private data class CachedCard(val card: UserCard, val at: Long)

    private companion object {
        const val PROFILE_COMMAND = "app__vrchat_user_profile_get"
        const val USER_COMMAND = "app__vrchat_user_get"
        const val TTL_MS = 5 * 60 * 1000L

        fun firstOf(vararg values: String?): String =
            values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }
}

/**
 * A person, as shown in a detail sheet.
 *
 * `isFriend` drives whether the sheet offers friend actions, and
 * [currentAvatarThumbnailImageUrl] is a *VRChat* URL -- it must go through
 * [ImageProxyRepository] to be readable.
 */
data class UserCard(
    val userId: String = "",
    val displayName: String = "",
    val username: String = "",
    val bio: String = "",
    val pronouns: String = "",
    val iconUrl: String = "",
    val bannerColor: String = "",
    val badges: List<ProfileBadge> = emptyList(),
    val trustTags: List<String> = emptyList(),
    val status: String = "",
    val statusDescription: String = "",
    val state: String = "",
    val platform: String = "",
    val dateJoined: String? = null,
    val lastLogin: String? = null,
    val currentAvatarName: String = "",
    val currentAvatarThumbnailImageUrl: String = "",
    val location: String = "",
    val worldId: String = "",
    val languages: List<String> = emptyList(),
    /** `null` = the server did not say (field absent), so render nothing. */
    val allowAvatarCopying: Boolean? = null,
    /** The group id this person showcases on their profile, if any. */
    val representedGroup: String? = null,
    val lastActivity: String? = null,
    val isFriend: Boolean = false,
    val friend: FriendRecord? = null
)

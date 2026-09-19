package com.vrcx0.android.data.remote

import com.vrcx0.android.data.repository.ImageProxyRepository
import java.util.Base64
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire contracts behind avatars, world names and profiles.
 *
 * Every payload here was captured from a live server, not invented. They matter
 * because the failure mode of each one is silent and identical: the UI shows a
 * blank circle, a raw `wrld_...` id, or nothing at all, with no exception
 * anywhere to point at the cause.
 */
class WireContractTest {

    // ------------------------------------------------------------ envelopes

    @Test
    fun a_stringified_data_document_is_unwrapped() {
        // Verbatim shape of `app__vrchat_user_profile_get`.
        val reply = wireJson.parseToJsonElement(
            """{"data":"{\"displayName\":\"馨宁雪OwO\",\"bio\":\"hi\"}","status":200}"""
        )
        val inner = unwrapDataEnvelope(reply)
        val profile = wireJson.decodeFromJsonElement<UserProfile>(inner)
        assertEquals("馨宁雪OwO", profile.displayName)
        assertEquals("hi", profile.bio)
    }

    @Test
    fun a_flat_reply_passes_through_untouched() {
        // `app__avatar_get` has no envelope at all; unwrapping must not eat it.
        val reply = wireJson.parseToJsonElement("""{"id":"avtr_1","name":"模型"}""")
        val avatar = wireJson.decodeFromJsonElement<AvatarSummary>(unwrapDataEnvelope(reply))
        assertEquals("模型", avatar.name)
    }

    @Test
    fun the_image_reply_carries_a_plain_string_not_a_nested_document() {
        // This is why the image path does NOT unwrap: `data` here is a data URL
        // string, and unwrapping would try (and fail) to parse it as JSON.
        val reply = wireJson.parseToJsonElement("""{"data":"data:image/png;base64,AAAA"}""")
        val output = wireJson.decodeFromJsonElement<ImageDataUrlOutput>(reply)
        assertTrue(output.data.startsWith("data:image/png;base64,"))
    }

    // ------------------------------------------------------------ worlds

    @Test
    fun world_summaries_decode_as_an_id_keyed_map() {
        val reply = wireJson.parseToJsonElement(
            """
            {"wrld_61e374f5-a05f-44a9-80ff-6b845923dcd3":{"id":"wrld_61e374f5-a05f-44a9-80ff-6b845923dcd3",
            "name":"［CN］ 中文新手教程 ［Tutorial］","authorName":"shine trick",
            "thumbnailImageUrl":"https://api.vrchat.cloud/api/1/image/file_x/30/256"}}
            """.trimIndent()
        )
        val summaries = parseWorldSummaries(reply)
        val world = summaries["wrld_61e374f5-a05f-44a9-80ff-6b845923dcd3"]
        assertEquals("［CN］ 中文新手教程 ［Tutorial］", world?.name)
        assertEquals("shine trick", world?.authorName)
    }

    @Test
    fun a_world_id_is_extracted_from_a_full_location() {
        val location =
            "wrld_61e374f5-a05f-44a9-80ff-6b845923dcd3:83734~group(grp_69408060)~region(jp)"
        assertEquals("wrld_61e374f5-a05f-44a9-80ff-6b845923dcd3", worldIdFromLocation(location))
    }

    @Test
    fun a_non_world_location_has_no_world_id() {
        assertEquals("", worldIdFromLocation("private"))
        assertEquals("", worldIdFromLocation("offline"))
        assertEquals("", worldIdFromLocation(""))
    }

    @Test
    fun instance_access_is_readable() {
        // The old UI printed `~groupAccessType(public)~region(jp)` verbatim.
        assertEquals(
            "群组公开",
            instanceAccessLabel("wrld_x:83734~group(grp_a)~groupAccessType(public)~region(jp)")
        )
        assertEquals("群组+", instanceAccessLabel("wrld_x:1~groupAccessType(plus)"))
        assertEquals("好友可见", instanceAccessLabel("wrld_x:98914~hidden(usr_a)~region(jp)"))
        assertEquals("私密", instanceAccessLabel("wrld_x:1~private(usr_a)"))
        assertEquals("好友", instanceAccessLabel("wrld_x:1~friends(usr_a)"))
        assertEquals("公开", instanceAccessLabel("wrld_x:1"))
    }

    // ------------------------------------------------------------ images

    @Test
    fun a_thumbnail_request_is_shrunk_to_the_requested_size() {
        // 256 costs 166 KB through the proxy; 128 costs 48 KB and is plenty for
        // a 40 dp list avatar.
        val url = "https://api.vrchat.cloud/api/1/image/file_abc/2/256"
        assertEquals(
            "https://api.vrchat.cloud/api/1/image/file_abc/2/128",
            ImageProxyRepository.withSize(url, 128)
        )
    }

    @Test
    fun a_smaller_source_is_not_upscaled() {
        val url = "https://api.vrchat.cloud/api/1/image/file_abc/2/64"
        assertEquals(url, ImageProxyRepository.withSize(url, 128))
    }

    @Test
    fun a_foreign_url_is_left_alone() {
        val url = "https://assets.vrchat.com/badges/8e/bdgai_abc.png"
        assertEquals(url, ImageProxyRepository.withSize(url, 128))
    }

    @Test
    fun a_data_url_decodes_to_bytes() {
        val raw = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
        val encoded = Base64.getEncoder().encodeToString(raw)
        val bytes = ImageDataUrlOutput("data:image/png;base64,$encoded").toBytes()
        assertEquals(raw.size, bytes?.size)
        assertTrue(ImageProxyRepository.looksLikeImage(bytes!!))
    }

    @Test
    fun a_reply_without_a_payload_does_not_decode() {
        assertNull(ImageDataUrlOutput("").toBytes())
        assertNull(ImageDataUrlOutput("not-a-data-url").toBytes())
    }

    @Test
    fun an_error_document_is_not_mistaken_for_an_image() {
        // The proxy answers 200 with an error body for a URL the account may
        // not read; caching that under the image's name would poison the cache.
        assertFalse(ImageProxyRepository.looksLikeImage("""{"message":"nope"}""".toByteArray()))
        assertFalse(ImageProxyRepository.looksLikeImage("<!DOCTYPE html>".toByteArray()))
        assertFalse(ImageProxyRepository.looksLikeImage(byteArrayOf(1, 2, 3)))
    }

    // ------------------------------------------------------------ users

    @Test
    fun the_own_snapshot_exposes_the_current_avatar_id() {
        val json = """
            {"authenticatedSession":{"session":{"currentUserSnapshot":{
            "id":"usr_me","displayName":"绝世好裤裆",
            "currentAvatar":"avtr_443faa4b-da5b-49ed-a44c-87705572f7a9",
            "currentAvatarThumbnailImageUrl":"https://api.vrchat.cloud/api/1/image/file_x/1/256"}}}}
        """.trimIndent()
        val me = wireJson.decodeFromString<CombinedSnapshotOutput>(json)
            .authenticatedSession?.session?.currentUserSnapshot
        assertEquals("绝世好裤裆", me?.displayName)
        // An id, with no name beside it -- which is why a name lookup exists.
        assertEquals("avtr_443faa4b-da5b-49ed-a44c-87705572f7a9", me?.currentAvatar)
    }

    @Test
    fun trust_tags_are_read_from_either_endpoint() {
        // `profile_get` says `trustTags`; `user_get` says `tags`. Same list.
        val fromProfile = wireJson.decodeFromJsonElement<UserProfile>(
            wireJson.parseToJsonElement("""{"trustTags":["system_trust_basic"]}""")
        )
        val fromUser = wireJson.decodeFromJsonElement<UserProfile>(
            wireJson.parseToJsonElement("""{"tags":["system_trust_basic","system_avatar_access"]}""")
        )
        assertEquals(listOf("system_trust_basic"), fromProfile.effectiveTrustTags)
        assertEquals(2, fromUser.effectiveTrustTags.size)
    }

    @Test
    fun a_profile_reads_the_snake_case_join_date() {
        val user = wireJson.decodeFromJsonElement<UserProfile>(
            wireJson.parseToJsonElement("""{"displayName":"A","date_joined":"2025-06-19","isFriend":true}""")
        )
        assertEquals("2025-06-19", user.dateJoined)
        assertEquals(true, user.isFriend)
    }

    @Test
    fun world_summaries_parse_a_real_reply_verbatim() {
        // Copied byte-for-byte from a live `app__world_summaries_get` reply, one
        // entry trimmed. The command answers a bare `{worldId: {...}}` map with
        // no envelope, and the entries carry fields the model does not name
        // (`created_at`, `authorId`, `version`, ...) -- so this pins both the
        // shape and the tolerance for extra keys. `looksLikeImage`-style silent
        // swallowing means a parser regression would otherwise surface only as
        // "另一个世界" in the UI.
        val json = """
            {"wrld_ebca0ab7-7ea8-46e2-aac6-fc5a67f94924":{"authorId":"usr_cd3ddb35","authorName":"shine trick","created_at":"2019-10-03T15:15:45.324Z","description":"中文新手教程","id":"wrld_ebca0ab7-7ea8-46e2-aac6-fc5a67f94924","imageUrl":"https://api.vrchat.cloud/api/1/file/file_be01773f-859e-4522-8940","name":"Idle Merchant 掛機商人（V0․3․1）","thumbnailImageUrl":"https://api.vrchat.cloud/api/1/image/file_c0b2d2fb/1/256","version":3}}
        """.trimIndent()
        val parsed = parseWorldSummaries(wireJson.parseToJsonElement(json))
        assertEquals(1, parsed.size)
        assertEquals("Idle Merchant 掛機商人（V0․3․1）", parsed.values.first().name)
    }

    @Test
    fun world_id_is_extracted_from_a_full_location() {
        assertEquals(
            "wrld_ebca0ab7-7ea8-46e2-aac6-fc5a67f94924",
            worldIdFromLocation("wrld_ebca0ab7-7ea8-46e2-aac6-fc5a67f94924:19925~hidden(usr_c4ac3830)~region(jp)")
        )
        // Not a world at all -- must not be mistaken for one.
        assertEquals("", worldIdFromLocation("private"))
        assertEquals("", worldIdFromLocation("offline"))
    }

    @Test
    fun a_live_world_get_decodes_through_the_data_envelope() {
        // `app__world_get` (the live fallback for the 89 favourite worlds the
        // cache never saw) wraps its answer in `{"data":"<json string>","status":...}`,
        // unlike the batch `world_summaries_get` which is a bare map. Captured
        // verbatim from a live fetch of an uncached world.
        val reply = wireJson.parseToJsonElement(
            """{"data":"{\"authorId\":\"usr_66099f6d\",\"authorName\":\"kioTwTo·\",\"created_at\":\"2026-09-15T17:09:20.732Z\",\"description\":\"kio的家\",\"id\":\"wrld_37888221-20f0-48b5-b6ee-1539fe33a002\",\"imageUrl\":\"https://api.vrchat.cloud/api/1/file/file_210a68d7\",\"name\":\"kio的家\",\"releaseStatus\":\"public\",\"thumbnailImageUrl\":\"https://api.vrchat.cloud/api/1/image/file_210a68d7\",\"updated_at\":\"2026-09-15T17:11:32.968Z\",\"version\":2}","status":200}"""
        )
        val world = wireJson.decodeFromJsonElement<WorldSummary>(unwrapDataEnvelope(reply))
        assertEquals("wrld_37888221-20f0-48b5-b6ee-1539fe33a002", world.id)
        assertEquals("kio的家", world.name)
        assertEquals("kioTwTo·", world.authorName)
    }
}

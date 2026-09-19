package com.vrcx0.android.data

import com.vrcx0.android.data.remote.CommandRunner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the local overlay that makes a presence edit visible.
 *
 * The combined snapshot is not a reliable read-back path for a presence write:
 * the server declares `status` / `statusDescription` local-authority fields
 * (`CURRENT_USER_REFRESH_LOCAL_AUTHORITY_FIELDS` in `application-realtime`) and
 * removes them from the REST refresh patch, so re-reading keeps returning the
 * old text even after a successful save. [SnapshotStore.applyLocalPresence] is
 * the fix, and these cases pin the two ways it could silently regress: sending
 * a field the user never touched, and dropping a deliberate blank.
 */
class PresenceEditTest {

    private val snapshotJson = """
        {
          "backendRuntime": {},
          "authenticatedRuntimePhase": {},
          "authenticatedSession": {
            "session": {
              "currentUserSnapshot": {
                "id": "usr_me",
                "displayName": "Tester",
                "status": "join me",
                "statusDescription": "old text",
                "pronouns": "they/them"
              }
            }
          }
        }
    """.trimIndent()

    private fun store(): SnapshotStore {
        val runner = CommandRunner { _, _ ->
            Json.parseToJsonElement(snapshotJson)
        }
        val store = SnapshotStore(runner)
        runBlocking { store.refresh("usr_me", force = true) }
        return store
    }

    @Test
    fun refresh_loads_the_baseline_presence() {
        val me = store().me.value
        assertEquals("join me", me?.status)
        assertEquals("old text", me?.statusDescription)
    }

    @Test
    fun local_overlay_replaces_the_status_text() {
        val store = store()

        store.applyLocalPresence(
            status = "busy",
            statusDescription = "new text",
            pronouns = "she/her"
        )

        val me = store.me.value
        assertEquals("busy", me?.status)
        assertEquals("new text", me?.statusDescription)
        assertEquals("she/her", me?.pronouns)
    }

    @Test
    fun untouched_fields_are_left_alone() {
        val store = store()

        // Only the status text was edited; the dialog never sent a status, so
        // the chip selection must not be invented locally either.
        store.applyLocalPresence(statusDescription = "only this")

        val me = store.me.value
        assertEquals("join me", me?.status)
        assertEquals("only this", me?.statusDescription)
        assertEquals("they/them", me?.pronouns)
    }

    @Test
    fun an_explicit_blank_clears_the_status_text() {
        val store = store()

        // Clearing the text is a real edit; a blank must overwrite rather than
        // be skipped the way a null (field not sent) is.
        store.applyLocalPresence(statusDescription = "")

        assertEquals("", store.me.value?.statusDescription)
    }

    @Test
    fun overlay_is_a_no_op_before_the_first_snapshot() {
        val runner = CommandRunner { _, _ -> JsonObject(emptyMap()) }
        val store = SnapshotStore(runner)

        // No snapshot yet -- there is nothing to patch, and this must not
        // fabricate a record that later reads would mistake for real data.
        store.applyLocalPresence(statusDescription = "ignored")

        assertEquals(null, store.me.value)
    }

    @Test
    fun has_self_snapshot_reflects_whether_an_overlay_can_land() {
        val runner = CommandRunner { _, _ -> Json.parseToJsonElement(snapshotJson) }
        val services = SessionServices(runner, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), kotlin.io.path.createTempDirectory("vrcx-test").toFile(), kotlin.io.path.createTempDirectory("vrcx-files").toFile())

        assertFalse(services.hasSelfSnapshot)

        runBlocking { services.snapshot.refresh("usr_me", force = true) }

        assertTrue(services.hasSelfSnapshot)
    }

    @Test
    fun services_apply_presence_edit_reaches_the_snapshot() {
        val runner = CommandRunner { _, _ -> Json.parseToJsonElement(snapshotJson) }
        val services = SessionServices(runner, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), kotlin.io.path.createTempDirectory("vrcx-test").toFile(), kotlin.io.path.createTempDirectory("vrcx-files").toFile())
        runBlocking { services.snapshot.refresh("usr_me", force = true) }

        services.applyPresenceEdit(status = "ask me", statusDescription = "hi")

        assertEquals("ask me", services.snapshot.me.value?.status)
        assertEquals("hi", services.snapshot.me.value?.statusDescription)
        assertEquals("they/them", services.snapshot.me.value?.pronouns)
    }

    /**
     * [ProfileRepository] caches a card for five minutes and knows nothing about
     * writes, so after a presence edit it would still hand the profile sheet the
     * *previous* status -- the same "saved but shows the old text" symptom,
     * arriving through the cache rather than the refresh patch. A presence write
     * must therefore invalidate the editor's own card.
     */
    @Test
    fun presence_edit_drops_the_cached_self_card() {
        var loads = 0
        val runner = CommandRunner { command, _ ->
            loads++
            // Either profile command is fine here; the card only needs to build.
            Json.parseToJsonElement("""{"data":"{\"displayName\":\"Tester\"}"}""")
        }
        val services = SessionServices(
            runner,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            kotlin.io.path.createTempDirectory("vrcx-test").toFile(),
            kotlin.io.path.createTempDirectory("vrcx-files").toFile()
        )

        runBlocking { services.profiles.load("usr_me") }
        val afterFirst = loads

        // Cached: a second load must not touch the wire.
        runBlocking { services.profiles.load("usr_me") }
        assertEquals(afterFirst, loads)

        // The write invalidates it, so the next load goes back to the wire.
        services.applyPresenceEdit(status = "busy", selfUserId = "usr_me")
        runBlocking { services.profiles.load("usr_me") }

        assertTrue(loads > afterFirst)
    }

    /**
     * Omitting the id still applies the overlay. The overlay is the part that
     * must never depend on a caller remembering an extra argument; dropping the
     * cache is the opportunistic half.
     */
    @Test
    fun presence_edit_without_an_id_still_overlays() {
        val runner = CommandRunner { _, _ -> Json.parseToJsonElement(snapshotJson) }
        val services = SessionServices(
            runner,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            kotlin.io.path.createTempDirectory("vrcx-test").toFile(),
            kotlin.io.path.createTempDirectory("vrcx-files").toFile()
        )
        runBlocking { services.snapshot.refresh("usr_me", force = true) }

        services.applyPresenceEdit(status = "busy")

        assertEquals("busy", services.snapshot.me.value?.status)
    }
}

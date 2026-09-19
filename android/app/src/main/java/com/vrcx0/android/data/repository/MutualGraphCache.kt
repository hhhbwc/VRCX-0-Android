package com.vrcx0.android.data.repository

import android.util.Log
import com.vrcx0.android.data.remote.MutualGraphFetchStatus
import com.vrcx0.android.data.remote.MutualGraphSnapshotOutput
import com.vrcx0.android.data.remote.wireJson
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A locally-cached mutual-graph result: the snapshot plus the state of the
 * fetch that produced it.
 *
 * ## Why this exists
 *
 * The graph is the one screen in the app whose data is *not* re-derivable from
 * the server on demand. A snapshot is the product of a multi-minute traversal
 * that walks every friend's friend list, and the server only runs it when asked
 * to. Before this file, opening the screen therefore showed "还没拉取过关系图"
 * with a button, and the user's own words were the specification: *"点开后请不
 * 要必须拉取才能看信息，以前又不是没拉取过，临时看以前的又不是不行"*.
 *
 * So the device keeps the last answer. The screen renders it immediately, marks
 * it with when it was taken, and the explicit 更新 button stays available for
 * anyone who wants a fresh traversal.
 *
 * ## Why `filesDir` and not `cacheDir`
 *
 * `cacheDir` is the obvious choice for a cache, and it is the wrong one here:
 * Android evicts it under storage pressure, and it can be cleared by the system
 * at any time. A snapshot is *minutes of server work* that cannot be
 * re-fetched cheaply, so losing it is not "one slow load", it is "run the
 * traversal again". It lives in `filesDir` alongside the other account state
 * and is dropped explicitly on sign-out.
 *
 * ## Why one file per user
 *
 * The graph is scoped to the signed-in account (`ownerUserId`). The file name
 * carries the id so two accounts on the same device cannot read each other's
 * graph. The id is a `usr_...` string and is not safe as a raw path component,
 * so it is sanitised down to its alphanumerics -- collision-free in practice
 * because VRChat ids are hex/uuid-shaped.
 */
class MutualGraphCache(sessionDir: File) {

    private val dir = File(sessionDir, "mutual-graph").apply { mkdirs() }

    /** What was read back from disk (or `null` when nothing is stored). */
    @Serializable
    data class Cached(
        val snapshot: MutualGraphSnapshotOutput,
        val status: MutualGraphFetchStatus? = null,
        /** Epoch milliseconds when this file was written, for an age label. */
        val savedAt: Long = 0L
    )

    /**
     * The stored graph for [userId], or `null` if there is none or it cannot be
     * read.
     *
     * A corrupt or partially-written file answers `null` and is deleted, rather
     * than throwing: this runs on screen open, and a bad file must degrade to
     * "no local copy" rather than to a crash or a permanently broken screen.
     * `runCatching` here is not hiding a bug -- the file is genuinely shared
     * with a process that may be killed mid-write.
     */
    fun read(userId: String): Cached? {
        val file = fileFor(userId) ?: return null
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            wireJson.decodeFromJsonElement<Cached>(wireJson.parseToJsonElement(file.readText()))
        }.onFailure {
            Log.w(TAG, "dropping unreadable graph cache ${file.name}: ${it.message}")
            file.delete()
        }.getOrNull()
            // An empty snapshot is not worth showing: it renders an empty
            // canvas and would suppress the "never fetched" state that tells
            // the user to press the button.
            ?.takeIf { it.snapshot.links.isNotEmpty() || it.snapshot.friendIds.isNotEmpty() }
    }

    /**
     * Writes [snapshot] for [userId]. Best-effort: a failed write loses nothing
     * but the convenience of the next cold start, and throwing here would take
     * down a screen whose actual data is already on display.
     *
     * Written to a `.part` sibling first and renamed, so a process death
     * mid-write leaves the previous good file in place instead of a truncated
     * one.
     */
    fun write(userId: String, snapshot: MutualGraphSnapshotOutput, status: MutualGraphFetchStatus?) {
        val file = fileFor(userId) ?: return
        // No point persisting an empty result over a good one: a cancelled or
        // failed traversal must not erase the graph the user can currently see.
        if (snapshot.links.isEmpty() && snapshot.friendIds.isEmpty()) return

        val payload = wireJson.encodeToString(
            Cached.serializer(),
            Cached(snapshot = snapshot, status = status, savedAt = System.currentTimeMillis())
        )
        runCatching {
            val tmp = File(dir, file.name + ".part")
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "could not write graph cache: ${it.message}") }
    }

    /** Drops the stored graph for [userId]. */
    fun clear(userId: String) {
        fileFor(userId)?.delete()
    }

    /** Drops every stored graph -- used when the account changes. */
    fun clearAll() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun fileFor(userId: String): File? {
        val safe = userId.trim().lowercase().filter { it.isLetterOrDigit() || it == '-' }
        if (safe.isEmpty()) return null
        return File(dir, safe + ".json")
    }

    private companion object {
        const val TAG = "VrcxGraphCache"
    }
}

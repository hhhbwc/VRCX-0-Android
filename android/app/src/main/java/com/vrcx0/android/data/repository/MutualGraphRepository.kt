package com.vrcx0.android.data.repository

import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.MutualGraphFetchStatus
import com.vrcx0.android.data.remote.MutualGraphSnapshotOutput
import com.vrcx0.android.data.remote.wireJson

/**
 * Drives the mutual-friends graph fetch and reads its snapshot.
 *
 * Fetching is a two-phase job:
 *   1. [startFetch] (INPUT) kicks off an async traversal on the server. Progress
 *      arrives as stream events `mutualGraphFetchStatus`; the screen listens to
 *      `SessionViewModel.streamFrames` and decodes each into
 *      [com.vrcx0.android.data.remote.MutualGraphFetchStatus].
 *   2. [snapshot] (FLAT, key `userId`) returns the committed result once the job
 *      finishes, or a partial result mid-flight.
 *
 * [cancelFetch] requests a stop; the server transitions to the `cancelling`
 * state and then `cancelled`.
 *
 * ## The local copy
 *
 * Every successful [snapshot] is written to [cache] and [cached] reads it back,
 * so opening the screen shows the previous result immediately instead of an
 * empty state with a button. See [MutualGraphCache] for why that data is too
 * expensive to re-derive on demand.
 *
 * The cache is written through the repository rather than by the screen so
 * there is exactly one writer and no screen can forget to persist. Failures to
 * write are not surfaced: the in-memory snapshot the UI is already rendering is
 * unaffected, and only the next cold start notices.
 */
class MutualGraphRepository(
    private val runner: CommandRunner,
    private val cache: MutualGraphCache? = null
) {

    suspend fun startFetch(ownerUserId: String, friendIds: List<String> = emptyList()) {
        runner(
            "app__mutual_graph_fetch_start",
            mapOf("ownerUserId" to ownerUserId, "friendIds" to friendIds)
        )
    }

    suspend fun cancelFetch(ownerUserId: String) {
        runner("app__mutual_graph_fetch_cancel", mapOf("ownerUserId" to ownerUserId))
    }

    /**
     * Fetches the current snapshot and persists it.
     *
     * [status] is the fetch state known at the call site, stored beside the
     * snapshot so a restored screen can report whether the traversal that
     * produced it finished or was interrupted.
     */
    suspend fun snapshot(
        userId: String,
        status: MutualGraphFetchStatus? = null
    ): MutualGraphSnapshotOutput {
        val element = runner("app__mutual_graph_snapshot_get", mapOf("userId" to userId))
        val parsed = wireJson.decodeFromString<MutualGraphSnapshotOutput>(element.toString())
        cache?.write(userId, parsed, status)
        return parsed
    }

    /**
     * The last snapshot stored on this device, or `null` if there is none.
     *
     * Deliberately never throws and never touches the network: it is called
     * while the screen is being built.
     */
    fun cached(userId: String): MutualGraphCache.Cached? = cache?.read(userId)

    /** Forgets the stored snapshot for [userId]. */
    fun forget(userId: String) {
        cache?.clear(userId)
    }
}

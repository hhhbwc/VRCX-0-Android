package com.vrcx0.android.data.repository

import android.util.Log
import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.ImageDataUrlOutput
import com.vrcx0.android.data.remote.toBytes
import com.vrcx0.android.data.remote.wireJson
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Loads VRChat images through the server, and caches them on disk.
 *
 * **Why a proxy is required, not optional.** `api.vrchat.cloud` image URLs are
 * authenticated: requested from the phone they answer `403 Forbidden`, because
 * the session cookie that authorises them lives on the server, not on this
 * device. Pointing Coil straight at the URL therefore produces a broken image
 * for *every* avatar -- which is exactly what the app did before this class
 * existed. `app__external_api_image_data_url_get` is the server's own fetch
 * path and returns the bytes as a `data:` URL.
 *
 * **Why a file cache.** The proxy round trip costs roughly 1.2 s per image
 * (a VRChat API hop from the router, measured). With no cache that is paid
 * again on every cold start, for every avatar on screen, so the list looks
 * empty for seconds each launch. Caching the decoded bytes in `cacheDir` means
 * each image is fetched once, ever, and afterwards Coil can read it from a
 * local file -- fast, offline, and it also keeps the router's uplink quiet.
 *
 * **Why the size is rewritten.** The record's `iconUrl` points at
 * `.../256`, which is a 166 KB payload (82 KB of base64). A 40 dp list avatar
 * needs nothing like that; asking for `.../128` cuts it to 48 KB, and `.../64`
 * to 15 KB. Lists use the smaller size and detail views the larger one.
 */
class ImageProxyRepository(
    private val runner: CommandRunner,
    private val scope: CoroutineScope,
    cacheDir: File
) {

    private val dir = File(cacheDir, "vrcx-images").apply { mkdirs() }

    /** Guards [jobs] only -- never held across a network call. */
    private val gate = Mutex()

    /**
     * In-flight fetches, keyed by the rewritten URL.
     *
     * A list scrolls past the same avatar more than once and Compose will ask
     * for it concurrently; without this the same image would be downloaded
     * several times over.
     */
    private val jobs = HashMap<String, Deferred<File?>>()

    /**
     * URLs that have already failed in this session.
     *
     * Without this, a scroll is pathological: an avatar that cannot be fetched
     * (a deleted file, an image the account may not read) is asked for again
     * every time the row enters composition, so flicking up and down a 242-row
     * list fires hundreds of doomed proxy round trips -- each costing a VRChat
     * API hop. Remembering the failure turns "retry forever" into "give up
     * once", and the UI shows the initial-letter fallback either way.
     */
    private val failed = HashSet<String>()

    /**
     * Resolves [rawUrl] to a local file, fetching it if necessary.
     *
     * Returns `null` for a blank URL or a failed fetch; callers treat that as
     * "no image" and show a placeholder rather than retrying in a loop.
     *
     * The whole body runs on [Dispatchers.IO], not just the network call. The
     * cache hit path looks cheap but is not: it rewrites the URL with a regex,
     * instantiates a `SHA-1` (a JCA provider lookup), hashes the URL and then
     * `stat`s the file -- all of which used to happen on the caller's
     * dispatcher, which for `RemoteAvatar` is the main thread, once per avatar
     * per scroll. Measured on device, that alone showed up as slow UI-thread
     * frames.
     *
     * @param size VRChat thumbnail width to request (64/128/256/512/1024).
     */
    suspend fun resolve(rawUrl: String, size: Int = LIST_SIZE): File? = withContext(Dispatchers.IO) {
        val url = withSize(rawUrl, size)
        if (url.isBlank()) return@withContext null

        val target = File(dir, cacheKey(url))
        if (target.isFile && target.length() > 0L) return@withContext target

        gate.withLock { if (url in failed) return@withContext null }

        val job = gate.withLock {
            jobs[url] ?: scope.async {
                val result = try {
                    fetch(url, target)
                } finally {
                    gate.withLock { jobs.remove(url) }
                }
                if (result == null) gate.withLock { failed.add(url) }
                result
            }.also { jobs[url] = it }
        }
        runCatching { job.await() }.getOrNull()
    }

    /** Deletes the whole on-disk cache. */
    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        failed.clear()
    }

    private suspend fun fetch(url: String, target: File): File? = withContext(Dispatchers.IO) {
        val reply = runCatching {
            runner("app__external_api_image_data_url_get", mapOf("url" to url))
        }.onFailure {
            // Every path below returns `null`, and a silent `null` is how a
            // regression here hides: the UI just shows initial letters and
            // nothing says why. One line per distinct failure makes the cause
            // readable from `adb logcat`.
            Log.w(TAG, "proxy fetch failed for $url: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull() ?: return@withContext null

        // Decoded directly, with no envelope unwrapping: this command's `data`
        // is a plain string (`"data:image/png;base64,..."`), NOT a nested JSON
        // document. The two shapes want different helpers.
        val dataUrl = runCatching {
            wireJson.decodeFromJsonElement<ImageDataUrlOutput>(reply).data
        }.onFailure {
            Log.w(TAG, "proxy reply was not {data:...}: ${reply.toString().take(160)}")
        }.getOrNull().orEmpty()

        val bytes = ImageDataUrlOutput(dataUrl).toBytes()
        if (bytes == null) {
            Log.w(TAG, "proxy reply had no decodable data: url=$url prefix=${dataUrl.take(40)}")
            return@withContext null
        }
        if (!looksLikeImage(bytes)) {
            Log.w(TAG, "proxy reply was not an image (${bytes.size}B): url=$url")
            return@withContext null
        }

        // Write to a sibling then rename: a half-written file under the real
        // name would be served forever from the cache fast path above.
        val tmp = File(dir, target.name + ".part")
        return@withContext runCatching {
            tmp.writeBytes(bytes)
            if (tmp.renameTo(target)) {
                target
            } else {
                tmp.delete()
                null
            }
        }.onFailure { Log.w(TAG, "could not write ${target.name}: ${it.message}") }.getOrNull()
    }

    private fun cacheKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {

        const val TAG = "VrcxImages"

        /** For list rows (avatars ~40 dp). */
        const val LIST_SIZE = 128

        /** For detail sheets and the top bar (avatars ~64-96 dp). */
        const val DETAIL_SIZE = 256

        /**
         * Rewrites the trailing thumbnail-width segment of a VRChat image URL.
         *
         * `https://api.vrchat.cloud/api/1/image/<fileId>/<version>/256`
         *   -> `.../128`
         *
         * Anything that is not a recognised VRChat `image/` URL is returned
         * untouched, so this cannot corrupt a URL it does not understand.
         */
        fun withSize(url: String, size: Int): String {
            // Matched on the API *path*, not the host. An earlier version
            // tested `contains("api.vrchat.com")` -- but the real host is
            // `api.vrchat.cloud`, so the guard never passed and every thumbnail
            // was fetched at full size. The path is the stable part.
            if (!url.contains("/api/1/image/")) return url
            val match = SIZE_TAIL.find(url) ?: return url
            val digits = match.groupValues[1]
            val query = match.groupValues[2]
            val digitsStart = match.range.first + 1
            // Only shrink: asking for a width larger than the source is a
            // wasted fetch, and VRChat serves the largest available anyway.
            if (digits.toIntOrNull()?.let { it <= size } == true) return url
            return url.substring(0, digitsStart) + size + query
        }

        private val SIZE_TAIL = Regex("/(\\d{2,4})(\\?.*)?$")

        /**
         * Magic-number check.
         *
         * The proxy answers `200` with an error document for a URL the account
         * may not read, and caching that under the image's name would poison
         * the cache permanently.
         */
        fun looksLikeImage(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            fun at(i: Int) = bytes[i].toInt() and 0xFF
            return when {
                at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> true // PNG
                at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> true                     // JPEG
                at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> true                     // GIF
                at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                    at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> true // WEBP
                else -> false
            }
        }
    }
}

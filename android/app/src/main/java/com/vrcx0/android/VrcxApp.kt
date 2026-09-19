package com.vrcx0.android

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy

/**
 * Configures Coil once for the whole app.
 *
 * Without this, Coil runs on its default memory cache, which is a fraction of
 * the available heap -- measured on this phone that showed up as ~16 MB of
 * native heap just for bitmaps, with no upper bound that reflects what this app
 * actually shows (a few dozen list-sized avatars at a time).
 *
 * So the cache is capped explicitly:
 *  - **Memory** -- 25% of the *process* heap limit rather than the device's,
 *    and never more than 32 MB. Avatar lists scroll a lot but only ever show a
 *    screenful at once.
 *  - **Disk** -- 64 MB. Images are already cached on disk by
 *    [com.vrcx0.android.data.repository.ImageProxyRepository] (one file per
 *    image, fetched once ever), so this is only a decode cache in front of it.
 *  - **Bitmaps are reused** (`bitmapPooling` via Coil's defaults) and, on
 *    Android 8+, kept in a hardware-accelerated config where possible.
 */
class VrcxApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        val maxMemoryBytes = Runtime.getRuntime().maxMemory()
        // 25% of the process heap, hard-capped so a large-heap device does not
        // silently grant Coil an oversized cache.
        val memoryBudget = (maxMemoryBytes / 4).coerceAtMost(MEMORY_CACHE_CAP_BYTES)
        Log.i(TAG, "heap=${maxMemoryBytes / 1024}KB coilMemoryCache=${memoryBudget / 1024}KB")

        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(memoryBudget.toInt())
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            // The bitmaps come from files that never change (a VRChat file id
            // is immutable), so re-validating them over the network is pure
            // waste -- and the app has no network path to them anyway.
            .networkCachePolicy(CachePolicy.DISABLED)
            .respectCacheHeaders(false)
            .crossfade(false)
            .build()
    }

    private companion object {
        const val TAG = "VrcxApp"
        const val MEMORY_CACHE_CAP_BYTES = 32L * 1024 * 1024
        const val DISK_CACHE_BYTES = 64L * 1024 * 1024
    }
}

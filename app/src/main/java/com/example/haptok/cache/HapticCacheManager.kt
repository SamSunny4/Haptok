package com.example.haptok.cache

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.haptok.models.HapticTimeline
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

/**
 * High-level cache manager for haptic timelines.
 *
 * Uses a content hash (first 1 MB + file size) so the same video always
 * maps to the same cached haptic track regardless of URI changes.
 */
class HapticCacheManager(private val context: Context) {

    companion object {
        private const val TAG = "HapticCacheManager"
        private const val MAX_CACHE_ENTRIES = 50
        private const val HASH_READ_BYTES = 1024 * 1024 // 1 MB
    }

    private val dao = HapticCacheDatabase.getInstance(context).hapticCacheDao()

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val timelineAdapter = moshi.adapter(HapticTimeline::class.java)

    /**
     * Check if a haptic timeline is cached for the given video URI.
     */
    suspend fun getCachedTimeline(videoUri: String): HapticTimeline? = withContext(Dispatchers.IO) {
        try {
            val hash = computeContentHash(Uri.parse(videoUri))
            val cached = if (hash != null) dao.getByVideoHash(hash) else dao.getByVideoUri(videoUri)
            cached?.let { timelineAdapter.fromJson(it.hapticTimelineJson) }
        } catch (e: Exception) {
            Log.w(TAG, "Cache lookup failed: ${e.message}")
            null
        }
    }

    /**
     * Save a haptic timeline to the cache for the given video.
     */
    suspend fun saveTimeline(
        videoUri: String,
        videoTitle: String,
        videoDurationSeconds: Double,
        timeline: HapticTimeline,
    ) = withContext(Dispatchers.IO) {
        try {
            evictIfNeeded()
            val hash = computeContentHash(Uri.parse(videoUri)) ?: videoUri.hashCode().toString()
            val json = timelineAdapter.toJson(timeline)
            dao.insert(
                CachedHapticTrack(
                    videoContentHash = hash,
                    videoUri = videoUri,
                    videoTitle = videoTitle,
                    hapticTimelineJson = json,
                    videoDurationSeconds = videoDurationSeconds,
                )
            )
            Log.d(TAG, "Cached haptic timeline for '$videoTitle'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache timeline: ${e.message}")
        }
    }

    /** Return total number of cached entries. */
    suspend fun getCacheCount(): Int = withContext(Dispatchers.IO) {
        dao.getTotalCount()
    }

    /** Clear all cached haptic tracks. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearAll()
        Log.d(TAG, "Cache cleared")
    }

    /** Evict oldest entries when cache exceeds [MAX_CACHE_ENTRIES]. */
    private suspend fun evictIfNeeded() {
        val count = dao.getTotalCount()
        if (count >= MAX_CACHE_ENTRIES) {
            val excess = count - MAX_CACHE_ENTRIES + 1
            dao.deleteOldest(excess)
            Log.d(TAG, "Evicted $excess old cache entries")
        }
    }

    /**
     * Compute a content hash from the first [HASH_READ_BYTES] of the video
     * file to identify duplicate content even if URIs differ.
     */
    private fun computeContentHash(uri: Uri): String? {
        return try {
            val resolver = context.contentResolver
            val inputStream: InputStream = resolver.openInputStream(uri) ?: return null
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var totalRead = 0
            inputStream.use { stream ->
                while (totalRead < HASH_READ_BYTES) {
                    val remaining = HASH_READ_BYTES - totalRead
                    val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                    totalRead += read
                }
            }
            // Include total bytes read in hash for uniqueness
            digest.update(totalRead.toString().toByteArray())
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not compute content hash: ${e.message}")
            null
        }
    }
}

package com.example.haptok.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.haptok.cache.HapticCacheManager
import com.example.haptok.haptics.HapticCapabilityManager
import com.example.haptok.models.HapticTimeline
import com.example.haptok.models.JobStatus
import com.example.haptok.network.NetworkModule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

/**
 * Sealed class representing the stages of video processing.
 */
sealed class ProcessingState {
    data object Idle : ProcessingState()
    data class Uploading(val progress: Float) : ProcessingState()
    data class Processing(val stage: String, val progress: Float) : ProcessingState()
    data class Completed(val timeline: HapticTimeline) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
    data class CacheHit(val timeline: HapticTimeline) : ProcessingState()
}

/**
 * Central repository orchestrating the full video-to-haptics pipeline:
 *
 * 1. Check local cache for existing haptic track
 * 2. Upload video to backend server
 * 3. Poll job status until completion
 * 4. Download haptic timeline
 * 5. Cache result locally
 */
class HaptokRepository(private val context: Context) {

    companion object {
        private const val TAG = "HaptokRepository"
        private const val POLL_INTERVAL_MS = 1500L
        private const val MAX_POLL_ATTEMPTS = 300 // ~7.5 min max
    }

    private val cacheManager = HapticCacheManager(context)
    private val api get() = NetworkModule.getInstance(context).apiService

    /**
     * Process a video and emit [ProcessingState] updates as a Flow.
     */
    fun processVideo(videoUri: Uri): Flow<ProcessingState> = flow {
        val uriString = videoUri.toString()

        // 1. Check cache
        emit(ProcessingState.Idle)
        val cached = cacheManager.getCachedTimeline(uriString)
        if (cached != null) {
            Log.d(TAG, "Cache hit for $uriString")
            emit(ProcessingState.CacheHit(cached))
            return@flow
        }

        // 2. Upload video
        emit(ProcessingState.Uploading(0f))
        try {
            val videoBytes = readVideoBytes(videoUri)
            val fileName = getFileName(videoUri) ?: "video.mp4"
            val mediaType = context.contentResolver.getType(videoUri) ?: "video/mp4"

            val videoPart = MultipartBody.Part.createFormData(
                "file",
                fileName,
                videoBytes.toRequestBody(mediaType.toMediaTypeOrNull())
            )

            val deviceProfile = HapticCapabilityManager.getProfile(context)
            val profileJson = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
                .adapter(com.example.haptok.models.DeviceProfile::class.java)
                .toJson(deviceProfile)

            val profilePart = profileJson.toRequestBody("text/plain".toMediaTypeOrNull())

            emit(ProcessingState.Uploading(0.5f))
            val uploadResponse = api.uploadVideo(videoPart, profilePart)

            if (!uploadResponse.isSuccessful) {
                emit(ProcessingState.Error("Upload failed: ${uploadResponse.code()} ${uploadResponse.message()}"))
                return@flow
            }

            val jobId = uploadResponse.body()?.jobId
                ?: throw IllegalStateException("No job_id in response")

            emit(ProcessingState.Uploading(1f))
            Log.d(TAG, "Upload complete, job_id=$jobId")

            // 3. Poll for completion
            var attempts = 0
            while (attempts < MAX_POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                attempts++

                val statusResponse = api.getJobStatus(jobId)
                if (!statusResponse.isSuccessful) {
                    Log.w(TAG, "Status poll failed: ${statusResponse.code()}")
                    continue
                }

                val jobStatus: JobStatus = statusResponse.body() ?: continue
                val stage = jobStatus.stage ?: jobStatus.status
                val progress = jobStatus.progress ?: 0f

                when (jobStatus.status) {
                    "completed", "complete" -> {
                        // 4. Download haptic timeline
                        emit(ProcessingState.Processing("Downloading haptics...", 0.95f))
                        val hapticsResponse = api.getHapticTrack(jobId)
                        if (hapticsResponse.isSuccessful && hapticsResponse.body() != null) {
                            val timeline = hapticsResponse.body()!!

                            // 5. Cache result
                            val title = getFileName(videoUri) ?: "video"
                            cacheManager.saveTimeline(
                                videoUri = uriString,
                                videoTitle = title,
                                videoDurationSeconds = timeline.durationSeconds,
                                timeline = timeline,
                            )

                            emit(ProcessingState.Completed(timeline))
                        } else {
                            emit(ProcessingState.Error("Failed to download haptic track"))
                        }
                        return@flow
                    }
                    "failed" -> {
                        emit(ProcessingState.Error(jobStatus.message ?: "Processing failed"))
                        return@flow
                    }
                    else -> {
                        emit(ProcessingState.Processing(stage, progress))
                    }
                }
            }

            emit(ProcessingState.Error("Processing timed out"))

        } catch (e: Exception) {
            Log.e(TAG, "Processing error", e)
            emit(ProcessingState.Error(e.message ?: "Unknown error"))
        }
    }

    /** Read all bytes from a content URI. */
    private fun readVideoBytes(uri: Uri): ByteArray {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open video URI")
        val buffer = ByteArrayOutputStream()
        inputStream.use { it.copyTo(buffer, bufferSize = 8192) }
        return buffer.toByteArray()
    }

    /** Get the display file name from a content URI. */
    private fun getFileName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) it.getString(idx) else null
            } else null
        }
    }

    /** Get cache count for settings display. */
    suspend fun getCacheCount(): Int = cacheManager.getCacheCount()

    /** Clear all cached haptic tracks. */
    suspend fun clearCache() = cacheManager.clearAll()
}

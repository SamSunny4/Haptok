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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.source
import java.io.InputStream

sealed class ProcessingState {
    data object Idle : ProcessingState()
    data class Uploading(val progress: Float) : ProcessingState()
    data class Processing(val stage: String, val progress: Float) : ProcessingState()
    data class Completed(val timeline: HapticTimeline) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
    data class CacheHit(val timeline: HapticTimeline) : ProcessingState()
}

class HaptokRepository(private val context: Context) {

    companion object {
        private const val TAG = "HaptokRepository"
        private const val POLL_INTERVAL_MS = 1500L
        private const val MAX_POLL_ATTEMPTS = 300 // ~7.5 min max
        private const val CHUNK_SIZE = 8192L
    }

    private val cacheManager = HapticCacheManager(context)
    private val api get() = NetworkModule.getInstance(context).apiService

    fun processVideo(videoUri: Uri, forceNative: Boolean = false): Flow<ProcessingState> = channelFlow {
        val uriString = videoUri.toString()

        if (forceNative) {
            send(ProcessingState.Error("Force native mode — using real-time audio haptics"))
            return@channelFlow
        }

        // 1. Check cache first
        val cached = cacheManager.getCachedTimeline(uriString)
        if (cached != null) {
            Log.d(TAG, "Cache hit for $uriString")
            send(ProcessingState.CacheHit(cached))
            return@channelFlow
        }

        try {
            val contentResolver = context.contentResolver
            val fileSize = getFileSize(videoUri)
            val fileName = getFileName(videoUri) ?: "video.mp4"
            val mediaType = contentResolver.getType(videoUri) ?: "video/mp4"

            Log.d(TAG, "Starting upload: $fileName ($fileSize bytes)")

            // Streaming RequestBody
            val progressHolder = floatArrayOf(0f)
            val videoRequestBody = object : RequestBody() {
                override fun contentType() = mediaType.toMediaTypeOrNull()
                override fun contentLength() = fileSize
                override fun writeTo(sink: BufferedSink) {
                    val inputStream = contentResolver.openInputStream(videoUri)
                        ?: throw IllegalStateException("Cannot open video URI")
                    inputStream.source().use { source ->
                        val buf = Buffer()
                        var totalWritten = 0L
                        var bytesRead: Long
                        while (source.read(buf, CHUNK_SIZE).also { bytesRead = it } != -1L) {
                            sink.write(buf, bytesRead)
                            totalWritten += bytesRead
                            val prog = if (fileSize > 0) totalWritten.toFloat() / fileSize else 0f
                            progressHolder[0] = prog.coerceIn(0f, 1f)
                        }
                    }
                }
            }

            val videoPart = MultipartBody.Part.createFormData("file", fileName, videoRequestBody)

            val deviceProfile = HapticCapabilityManager.getProfile(context)
            val profileJson = NetworkModule.getInstance(context).moshi
                .adapter(com.example.haptok.models.DeviceProfile::class.java)
                .toJson(deviceProfile)
            val profilePart = profileJson.toRequestBody("text/plain".toMediaTypeOrNull())

            send(ProcessingState.Uploading(0f))

            var isUploading = true
            val progressJob = launch {
                while (isUploading) {
                    send(ProcessingState.Uploading(progressHolder[0]))
                    delay(200)
                }
            }

            try {
                val uploadResponse = withContext(Dispatchers.IO) {
                    api.uploadVideo(videoPart, profilePart)
                }
                
                isUploading = false
                progressJob.cancel()

                if (!uploadResponse.isSuccessful) {
                    val errorBody = uploadResponse.errorBody()?.string() ?: "Unknown error"
                    Log.e(TAG, "Upload failed ${uploadResponse.code()}: $errorBody")
                    send(ProcessingState.Error("Upload failed: HTTP ${uploadResponse.code()}"))
                    return@channelFlow
                }

                val body = uploadResponse.body()
                if (body == null || body.jobId.isBlank()) {
                    send(ProcessingState.Error("Server returned empty job ID"))
                    return@channelFlow
                }
                val jobId = body.jobId
                send(ProcessingState.Uploading(1f))
                Log.d(TAG, "Upload complete, job_id=$jobId")

                // 3. Poll for completion
                var attempts = 0
                var consecutiveFailures = 0
                while (attempts < MAX_POLL_ATTEMPTS) {
                    delay(POLL_INTERVAL_MS)
                    attempts++

                    val statusResponse = withContext(Dispatchers.IO) {
                        try { api.getJobStatus(jobId) } catch (e: Exception) { 
                            Log.e(TAG, "Status poll exception", e)
                            null 
                        }
                    }

                    if (statusResponse == null || !statusResponse.isSuccessful) {
                        Log.w(TAG, "Status poll failed (attempt $attempts)")
                        consecutiveFailures++
                        if (consecutiveFailures > 5) {
                            send(ProcessingState.Error("Server disconnected or invalid JSON"))
                            return@channelFlow
                        }
                        continue
                    }
                    consecutiveFailures = 0

                    val jobStatus: JobStatus = statusResponse.body() ?: continue

                    Log.d(TAG, "Job $jobId: status=${jobStatus.status} progress=${jobStatus.progress}")

                    when (jobStatus.status.lowercase()) {
                        "complete", "completed" -> {
                            // 4. Download haptic timeline
                            send(ProcessingState.Processing("Finalizing haptics...", 0.95f))
                            val hapticsResponse = withContext(Dispatchers.IO) {
                                try { api.getHapticTrack(jobId) } catch (e: Exception) { null }
                            }

                            if (hapticsResponse != null && hapticsResponse.isSuccessful && hapticsResponse.body() != null) {
                                val timeline = hapticsResponse.body()!!
                                // 5. Cache result
                                cacheManager.saveTimeline(
                                    videoUri = uriString,
                                    videoTitle = fileName,
                                    videoDurationSeconds = timeline.durationSeconds,
                                    timeline = timeline,
                                )
                                send(ProcessingState.Completed(timeline))
                            } else {
                                send(ProcessingState.Error("Failed to download haptic track"))
                            }
                            return@channelFlow
                        }
                        "failed" -> {
                            send(ProcessingState.Error(jobStatus.error ?: jobStatus.message.ifBlank { "Processing failed" }))
                            return@channelFlow
                        }
                        else -> {
                            val stageLabel = when (jobStatus.status.lowercase()) {
                                "queued" -> "Queued..."
                                "extracting" -> "Extracting audio/video..."
                                "analyzing_audio" -> "Analyzing audio..."
                                "analyzing_video" -> "Analyzing video..."
                                "fusing" -> "Fusing signals..."
                                "generating" -> "Generating haptics..."
                                else -> jobStatus.message.ifBlank { jobStatus.status }
                            }
                            send(ProcessingState.Processing(stageLabel, jobStatus.progress))
                        }
                    }
                }

                send(ProcessingState.Error("Processing timed out after ${MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000}s"))

            } catch (e: Exception) {
                Log.e(TAG, "Processing error inside processVideo", e)
                send(ProcessingState.Error(e.message ?: "Unknown error"))
            } finally {
                isUploading = false
                progressJob.cancel()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Outer processing error", e)
            send(ProcessingState.Error(e.message ?: "Unknown error"))
        }
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) it.getLong(idx) else -1L
                } else -1L
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) it.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}

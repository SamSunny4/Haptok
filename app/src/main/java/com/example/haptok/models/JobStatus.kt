package com.example.haptok.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Returned by the backend immediately after a video upload is accepted.
 */
@JsonClass(generateAdapter = true)
data class UploadResponse(
    @Json(name = "job_id") val jobId: String,
    val status: String,
    val message: String?
)

/**
 * Represents the current state of a haptic-generation job on the backend.
 *
 * The [status] field progresses through: pending → processing → completed | failed.
 * While processing, [stage] gives finer-grained feedback (e.g. "analyzing_audio").
 * Once completed, [hapticTimeline] contains the full result.
 */
@JsonClass(generateAdapter = true)
data class JobStatus(
    @Json(name = "job_id") val jobId: String,
    /** pending, processing, completed, failed */
    val status: String,
    /** uploading, extracting, analyzing_audio, analyzing_video, fusing, generating, complete */
    val stage: String?,
    /** 0.0 – 1.0 */
    val progress: Float?,
    val message: String?,
    @Json(name = "haptic_timeline") val hapticTimeline: HapticTimeline?
)

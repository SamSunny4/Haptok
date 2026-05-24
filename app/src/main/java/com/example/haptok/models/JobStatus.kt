package com.example.haptok.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Returned by the backend immediately after a video upload is accepted.
 * Backend serialises with by_alias=True → camelCase keys.
 */
@JsonClass(generateAdapter = true)
data class UploadResponse(
    // Backend Pydantic field: job_id, alias: jobId  → by_alias=True sends "jobId"
    @Json(name = "jobId") val jobId: String,
    val status: String,
    val message: String? = null,
)

/**
 * Represents the current state of a haptic-generation job on the backend.
 * Backend serialises with by_alias=True → camelCase keys.
 */
@JsonClass(generateAdapter = true)
data class JobStatus(
    // Backend sends "jobId" (by_alias=True on Pydantic field job_id with alias "jobId")
    @Json(name = "jobId") val jobId: String,
    val status: String,
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null,
    // Backend alias "resultReady"
    @Json(name = "resultReady") val resultReady: Boolean = false,
)

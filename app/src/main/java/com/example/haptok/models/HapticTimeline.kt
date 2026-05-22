package com.example.haptok.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Root object for a haptic timeline produced by the backend.
 *
 * Contains all tracks, events, and curves that describe how haptic
 * feedback should be rendered over the duration of a video.
 */
@JsonClass(generateAdapter = true)
data class HapticTimeline(
    val version: String,
    @Json(name = "source_video") val sourceVideo: String,
    @Json(name = "duration_seconds") val durationSeconds: Double,
    @Json(name = "sample_rate_hz") val sampleRateHz: Int,
    val tracks: List<HapticTrack>,
    val metadata: ProcessingMetadata?
)

/**
 * A single haptic track, typically representing one category of haptic
 * feedback (e.g. transient impacts, continuous textures).
 */
@JsonClass(generateAdapter = true)
data class HapticTrack(
    val name: String,
    /** Category: TRANSIENT, CONTINUOUS, or TEXTURE. */
    val category: String,
    val events: List<HapticEvent>,
    val curves: List<HapticCurve>
)

/**
 * An individual haptic event — either a short transient or a sustained
 * continuous vibration at a specific point in time.
 */
@JsonClass(generateAdapter = true)
data class HapticEvent(
    /** "transient" or "continuous". */
    val type: String,
    @Json(name = "time_seconds") val timeSeconds: Double,
    @Json(name = "duration_seconds") val durationSeconds: Double?,
    /** 0.0–1.0 normalized intensity. */
    val intensity: Float,
    /** 0.0–1.0 normalized sharpness. */
    val sharpness: Float,
    @Json(name = "frequency_hz") val frequencyHz: Int?,
    /** Semantic tags like "explosion", "footstep", "rain". */
    val tags: List<String>,
    /** Spatial hint: LEFT, RIGHT, or CENTER. */
    @Json(name = "spatial_hint") val spatialHint: String?,
    /** Target vibrator actuator ID for multi-actuator devices. */
    @Json(name = "actuator_id") val actuatorId: Int?
)

/**
 * A continuous parameter curve that modulates intensity, sharpness,
 * or frequency over a time range via control points.
 */
@JsonClass(generateAdapter = true)
data class HapticCurve(
    /** Parameter name: INTENSITY, SHARPNESS, or FREQUENCY. */
    val parameter: String,
    @Json(name = "time_start") val timeStart: Double,
    @Json(name = "time_end") val timeEnd: Double,
    @Json(name = "control_points") val controlPoints: List<ControlPoint>
)

/** A single control point on a parameter curve. */
@JsonClass(generateAdapter = true)
data class ControlPoint(
    val time: Double,
    val value: Float
)

/** Metadata about the backend processing that produced this timeline. */
@JsonClass(generateAdapter = true)
data class ProcessingMetadata(
    @Json(name = "models_used") val modelsUsed: List<String>,
    @Json(name = "generation_timestamp") val generationTimestamp: String,
    @Json(name = "confidence_scores") val confidenceScores: Map<String, Float>?
)

package com.example.haptok.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Describes the haptic hardware capabilities of the current device.
 *
 * Sent to the backend alongside the video upload so the server can
 * tailor the generated [HapticTimeline] to the hardware that will
 * actually play it back.
 */
@JsonClass(generateAdapter = true)
data class DeviceProfile(
    @Json(name = "device_model") val deviceModel: String,
    @Json(name = "vibrator_count") val vibratorCount: Int,
    @Json(name = "vibrator_ids") val vibratorIds: List<Int>,
    @Json(name = "has_amplitude_control") val hasAmplitudeControl: Boolean,
    @Json(name = "supported_primitives") val supportedPrimitives: List<String>,
    @Json(name = "has_haptic_generator") val hasHapticGenerator: Boolean,
    @Json(name = "android_api_level") val androidApiLevel: Int
)

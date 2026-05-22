package com.example.haptok.haptics

import android.content.Context
import android.media.audiofx.HapticGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import com.example.haptok.models.DeviceProfile

/**
 * Thread-safe singleton that detects the haptic hardware capabilities
 * of the current device and exposes them as a [DeviceProfile].
 *
 * Usage:
 * ```
 * HapticCapabilityManager.init(context)
 * val profile = HapticCapabilityManager.deviceProfile
 * ```
 */
object HapticCapabilityManager {

    private const val TAG = "HapticCapability"

    /** All primitive IDs we care about, paired with human-readable names. */
    private val PRIMITIVE_MAP = mapOf(
        VibrationEffect.Composition.PRIMITIVE_CLICK to "CLICK",
        VibrationEffect.Composition.PRIMITIVE_TICK to "TICK",
        VibrationEffect.Composition.PRIMITIVE_THUD to "THUD",
        VibrationEffect.Composition.PRIMITIVE_SPIN to "SPIN",
        VibrationEffect.Composition.PRIMITIVE_SLOW_RISE to "SLOW_RISE",
        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE to "QUICK_RISE",
        VibrationEffect.Composition.PRIMITIVE_QUICK_FALL to "QUICK_FALL",
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK to "LOW_TICK",
    )

    @Volatile
    private var _deviceProfile: DeviceProfile? = null

    /**
     * The detected device profile. Throws [IllegalStateException] if
     * [init] has not been called yet.
     */
    val deviceProfile: DeviceProfile
        get() = _deviceProfile
            ?: throw IllegalStateException("HapticCapabilityManager.init() not called")

    /** Whether the manager has been initialised. */
    val isInitialized: Boolean get() = _deviceProfile != null

    /**
     * Probes the hardware and caches the result.
     * Safe to call multiple times — subsequent calls are no-ops.
     *
     * @param context Application context.
     */
    @Synchronized
    fun init(context: Context) {
        if (_deviceProfile != null) return

        val vibratorManager =
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager

        val vibratorIds = vibratorManager.vibratorIds.toList()
        Log.d(TAG, "Vibrator IDs: $vibratorIds")

        // Use the default vibrator for capability checks.
        val defaultVibrator = vibratorManager.defaultVibrator

        val hasAmplitude = defaultVibrator.hasAmplitudeControl()
        Log.d(TAG, "Amplitude control: $hasAmplitude")

        // Check which primitives are supported.
        val supported = mutableListOf<String>()
        for ((primitiveId, name) in PRIMITIVE_MAP) {
            if (defaultVibrator.areAllPrimitivesSupported(primitiveId)) {
                supported.add(name)
            }
        }
        Log.d(TAG, "Supported primitives: $supported")

        // HapticGenerator availability.
        val hasHapticGen = try {
            HapticGenerator.isAvailable()
        } catch (e: Exception) {
            Log.w(TAG, "HapticGenerator check failed", e)
            false
        }
        Log.d(TAG, "HapticGenerator available: $hasHapticGen")

        _deviceProfile = DeviceProfile(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            vibratorCount = vibratorIds.size,
            vibratorIds = vibratorIds,
            hasAmplitudeControl = hasAmplitude,
            supportedPrimitives = supported,
            hasHapticGenerator = hasHapticGen,
            androidApiLevel = Build.VERSION.SDK_INT,
        )

        Log.i(TAG, "Device profile: $_deviceProfile")
    }

    /**
     * Returns the primitive integer constant for a given name,
     * or `null` if the primitive is not supported on this device.
     */
    fun primitiveIdByName(name: String): Int? {
        val id = PRIMITIVE_MAP.entries.firstOrNull { it.value == name }?.key ?: return null
        return if (deviceProfile.supportedPrimitives.contains(name)) id else null
    }

    /** Whether a specific primitive is supported by the default vibrator. */
    fun isPrimitiveSupported(primitiveId: Int): Boolean {
        val name = PRIMITIVE_MAP[primitiveId] ?: return false
        return deviceProfile.supportedPrimitives.contains(name)
    }

    /**
     * Convenience method: initialise if needed and return the profile.
     */
    fun getProfile(context: Context): DeviceProfile {
        if (!isInitialized) init(context)
        return deviceProfile
    }
}

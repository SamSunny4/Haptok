package com.example.haptok.haptics

import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import com.example.haptok.models.HapticEvent
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Maps abstract [HapticEvent] instances from the haptic timeline JSON
 * to concrete [VibrationEffect] or [CombinedVibration] objects that
 * the Android vibrator hardware can execute.
 *
 * Every translation path checks [HapticCapabilityManager] for primitive
 * support and falls back to waveform-based vibrations when hardware
 * primitives are unavailable.
 *
 * @param vibratorManager System [VibratorManager] for multi-actuator support.
 * @param intensityMultiplier Global intensity scale applied on top of per-event intensity.
 */
class HapticEventTranslator(
    private val vibratorManager: VibratorManager,
    private var intensityMultiplier: Float = 1.0f,
) {

    companion object {
        private const val TAG = "HapticTranslator"

        // Clamp helpers
        private fun clampAmplitude(raw: Float): Int =
            max(1, min(255, (raw * 255).roundToInt()))

        private fun clampScale(value: Float): Float =
            max(0f, min(1f, value))
    }

    /** Update the global intensity multiplier (0.0–1.0). */
    fun setIntensityMultiplier(multiplier: Float) {
        intensityMultiplier = clampScale(multiplier)
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Translate a single [HapticEvent] into a [VibrationEffect].
     * Tag-based dispatch is tried first; generic fallback otherwise.
     */
    fun translate(event: HapticEvent): VibrationEffect {
        val scaledIntensity = clampScale(event.intensity * intensityMultiplier)
        val tag = event.tags.firstOrNull()?.lowercase()

        return when (tag) {
            "explosion" -> buildExplosion(scaledIntensity)
            "gunshot" -> buildGunshot(scaledIntensity)
            "engine_rumble" -> buildEngineRumble(scaledIntensity, event.durationSeconds)
            "footstep" -> buildFootstep(scaledIntensity)
            "rain" -> buildRain(scaledIntensity, event.durationSeconds)
            "impact" -> buildImpact(scaledIntensity)
            "acceleration" -> buildAcceleration(scaledIntensity, event.durationSeconds)
            "tension" -> buildTension(scaledIntensity, event.durationSeconds)
            "camera_shake" -> buildCameraShake(scaledIntensity, event.durationSeconds)
            else -> buildGeneric(event.type, scaledIntensity, event.sharpness, event.durationSeconds)
        }
    }

    /**
     * Translate and wrap into a [CombinedVibration] for multi-actuator
     * playback, respecting the event's [HapticEvent.spatialHint] and
     * [HapticEvent.actuatorId].
     */
    fun translateCombined(event: HapticEvent): CombinedVibration {
        val effect = translate(event)
        val ids = vibratorManager.vibratorIds

        // If device only has one actuator, use global vibration.
        if (ids.size <= 1) {
            return CombinedVibration.createParallel(effect)
        }

        // Specific actuator requested.
        if (event.actuatorId != null && ids.contains(event.actuatorId)) {
            return CombinedVibration.startParallel()
                .addVibrator(event.actuatorId, effect)
                .combine()
        }

        // Spatial hint based routing (first two actuators assumed L/R).
        return when (event.spatialHint?.uppercase()) {
            "LEFT" -> CombinedVibration.startParallel()
                .addVibrator(ids[0], effect)
                .combine()
            "RIGHT" -> {
                val targetId = if (ids.size > 1) ids[1] else ids[0]
                CombinedVibration.startParallel()
                    .addVibrator(targetId, effect)
                    .combine()
            }
            else -> CombinedVibration.createParallel(effect)
        }
    }

    // ── Tag-Specific Builders ───────────────────────────────────────────

    /** Explosion: THUD at full scale + continuous rumble decay over 500 ms. */
    private fun buildExplosion(intensity: Float): VibrationEffect {
        if (HapticCapabilityManager.isPrimitiveSupported(
                VibrationEffect.Composition.PRIMITIVE_THUD
            )
        ) {
            return VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, intensity)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, intensity * 0.4f, 30)
                .compose()
        }
        // Waveform fallback: sharp spike then exponential decay.
        return buildDecayWaveform(intensity, durationMs = 500)
    }

    /** Gunshot: CLICK + QUICK_FALL. */
    private fun buildGunshot(intensity: Float): VibrationEffect {
        val hasClick = HapticCapabilityManager.isPrimitiveSupported(
            VibrationEffect.Composition.PRIMITIVE_CLICK
        )
        val hasFall = HapticCapabilityManager.isPrimitiveSupported(
            VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
        )
        if (hasClick && hasFall) {
            return VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, intensity)
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
                    intensity * 0.7f,
                    20
                )
                .compose()
        }
        return buildDecayWaveform(intensity, durationMs = 120)
    }

    /** Engine rumble: continuous amplitude-modulated waveform. */
    private fun buildEngineRumble(intensity: Float, durationSec: Double?): VibrationEffect {
        val durationMs = ((durationSec ?: 0.5) * 1000).toLong().coerceIn(50, 5000)
        val steps = (durationMs / 20).toInt().coerceIn(2, 250)
        val timings = LongArray(steps) { 20L }
        val amplitudes = IntArray(steps) { i ->
            // Subtle oscillation around the base intensity.
            val oscillation = 0.15f * kotlin.math.sin(i.toFloat() * 0.8f).toFloat()
            clampAmplitude(intensity * (0.85f + oscillation))
        }
        return VibrationEffect.createWaveform(timings, amplitudes, -1)
    }

    /** Footstep: single THUD scaled to 30–60 % of intensity. */
    private fun buildFootstep(intensity: Float): VibrationEffect {
        val scaled = clampScale(intensity * 0.5f).coerceIn(0.3f, 0.6f)
        if (HapticCapabilityManager.isPrimitiveSupported(
                VibrationEffect.Composition.PRIMITIVE_THUD
            )
        ) {
            return VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scaled)
                .compose()
        }
        return VibrationEffect.createOneShot(60, clampAmplitude(scaled))
    }

    /** Rain: rapid LOW_TICK sequence with random 10–30 ms gaps. */
    private fun buildRain(intensity: Float, durationSec: Double?): VibrationEffect {
        val durationMs = ((durationSec ?: 0.3) * 1000).toLong().coerceIn(50, 3000)
        if (HapticCapabilityManager.isPrimitiveSupported(
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            )
        ) {
            val comp = VibrationEffect.startComposition()
            var elapsed = 0L
            while (elapsed < durationMs) {
                val delay = if (elapsed == 0L) 0 else Random.nextInt(10, 31)
                comp.addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    clampScale(intensity * Random.nextFloat().coerceIn(0.3f, 1.0f)),
                    delay
                )
                elapsed += delay + 15 // approximate primitive duration
            }
            return comp.compose()
        }
        // Waveform fallback: short on/off bursts.
        val burstCount = (durationMs / 25).toInt().coerceIn(2, 120)
        val timings = LongArray(burstCount * 2) { if (it % 2 == 0) 5L else Random.nextLong(10, 30) }
        val amplitudes = IntArray(burstCount * 2) { if (it % 2 == 0) clampAmplitude(intensity * 0.4f) else 0 }
        return VibrationEffect.createWaveform(timings, amplitudes, -1)
    }

    /** Impact: CLICK + THUD. */
    private fun buildImpact(intensity: Float): VibrationEffect {
        val hasClick = HapticCapabilityManager.isPrimitiveSupported(
            VibrationEffect.Composition.PRIMITIVE_CLICK
        )
        val hasThud = HapticCapabilityManager.isPrimitiveSupported(
            VibrationEffect.Composition.PRIMITIVE_THUD
        )
        if (hasClick && hasThud) {
            return VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, intensity)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, intensity * 0.8f, 15)
                .compose()
        }
        return buildDecayWaveform(intensity, durationMs = 150)
    }

    /** Acceleration: SLOW_RISE + sustained waveform. */
    private fun buildAcceleration(intensity: Float, durationSec: Double?): VibrationEffect {
        val durationMs = ((durationSec ?: 0.4) * 1000).toLong().coerceIn(50, 3000)
        if (HapticCapabilityManager.isPrimitiveSupported(
                VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
            )
        ) {
            return VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, intensity)
                .compose()
        }
        // Ramp waveform fallback.
        val steps = (durationMs / 20).toInt().coerceIn(2, 150)
        val timings = LongArray(steps) { 20L }
        val amplitudes = IntArray(steps) { i ->
            clampAmplitude(intensity * (i.toFloat() / steps))
        }
        return VibrationEffect.createWaveform(timings, amplitudes, -1)
    }

    /** Tension: low-amplitude continuous waveform. */
    private fun buildTension(intensity: Float, durationSec: Double?): VibrationEffect {
        val durationMs = ((durationSec ?: 1.0) * 1000).toLong().coerceIn(100, 5000)
        val amp = clampAmplitude(intensity * 0.25f)
        return VibrationEffect.createOneShot(durationMs, amp)
    }

    /** Camera shake: fast alternating on/off. */
    private fun buildCameraShake(intensity: Float, durationSec: Double?): VibrationEffect {
        val durationMs = ((durationSec ?: 0.3) * 1000).toLong().coerceIn(50, 2000)
        val cycles = (durationMs / 30).toInt().coerceIn(2, 60)
        val timings = LongArray(cycles * 2) { if (it % 2 == 0) 15L else 15L }
        val amplitudes = IntArray(cycles * 2) { i ->
            if (i % 2 == 0) clampAmplitude(intensity) else clampAmplitude(intensity * 0.2f)
        }
        return VibrationEffect.createWaveform(timings, amplitudes, -1)
    }

    // ── Generic Builders ────────────────────────────────────────────────

    /**
     * Builds an effect for events that don't match any known tag.
     * Transient events pick a primitive based on sharpness;
     * continuous events produce an amplitude waveform.
     */
    private fun buildGeneric(
        type: String,
        intensity: Float,
        sharpness: Float,
        durationSec: Double?,
    ): VibrationEffect {
        return if (type == "transient") {
            buildGenericTransient(intensity, sharpness)
        } else {
            buildGenericContinuous(intensity, durationSec)
        }
    }

    private fun buildGenericTransient(intensity: Float, sharpness: Float): VibrationEffect {
        // High sharpness → CLICK, low sharpness → THUD, medium → TICK.
        val primitiveId = when {
            sharpness > 0.7f -> VibrationEffect.Composition.PRIMITIVE_CLICK
            sharpness < 0.3f -> VibrationEffect.Composition.PRIMITIVE_THUD
            else -> VibrationEffect.Composition.PRIMITIVE_TICK
        }
        if (HapticCapabilityManager.isPrimitiveSupported(primitiveId)) {
            return VibrationEffect.startComposition()
                .addPrimitive(primitiveId, intensity)
                .compose()
        }
        // Waveform fallback.
        val durationMs = if (sharpness > 0.5f) 30L else 80L
        return VibrationEffect.createOneShot(durationMs, clampAmplitude(intensity))
    }

    private fun buildGenericContinuous(intensity: Float, durationSec: Double?): VibrationEffect {
        val durationMs = ((durationSec ?: 0.3) * 1000).toLong().coerceIn(20, 5000)
        return VibrationEffect.createOneShot(durationMs, clampAmplitude(intensity))
    }

    // ── Utility ─────────────────────────────────────────────────────────

    /**
     * Creates a waveform that decays exponentially from [peakIntensity]
     * over [durationMs].
     */
    private fun buildDecayWaveform(peakIntensity: Float, durationMs: Long): VibrationEffect {
        val stepMs = 20L
        val steps = (durationMs / stepMs).toInt().coerceIn(2, 250)
        val timings = LongArray(steps) { stepMs }
        val amplitudes = IntArray(steps) { i ->
            val progress = i.toFloat() / steps
            val decay = kotlin.math.exp(-3.0 * progress).toFloat()
            clampAmplitude(peakIntensity * decay)
        }
        return VibrationEffect.createWaveform(timings, amplitudes, -1)
    }
}

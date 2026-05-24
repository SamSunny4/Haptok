package com.example.haptok.fallback

import android.content.Context
import android.media.audiofx.Visualizer
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Real-time audio → haptics engine.
 *
 * Designed to produce *cinematic* haptic feedback for movie trailers and
 * action content.  The key insight is that raw FFT energy alone produces
 * random buzzing — instead we track **energy envelopes** over time and
 * only fire haptics at **meaningful moments**:
 *
 *  1. **Bass hits** — sudden spikes in sub-bass / bass energy (explosions,
 *     impacts, bass drops).  Mapped to short, strong vibrations.
 *  2. **Sustained rumble** — prolonged low-frequency energy (engines,
 *     drones, tension music).  Mapped to gentle continuous vibration.
 *  3. **Transients** — spectral flux (sudden broad-spectrum change like
 *     gunshots, scene cuts, cymbal crashes).  Mapped to sharp taps.
 *
 * All haptic firing has cooldown timers to prevent motor spam and let
 * each event be felt distinctly.
 */
class AudioHapticFallback(private val context: Context) {

    companion object {
        private const val TAG = "AudioFallback"

        // ── Cooldowns (ms) — how long to wait before the same type fires again
        private const val BASS_HIT_COOLDOWN_MS   = 100L
        private const val RUMBLE_COOLDOWN_MS      = 200L
        private const val TRANSIENT_COOLDOWN_MS   = 80L
    }

    var isRunning = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var visualizer: Visualizer? = null

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator =
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    // ── Tuning knobs ────────────────────────────────────────────────────
    @Volatile private var intensityMultiplier = 0.75f
    fun setIntensity(v: Float) { intensityMultiplier = v.coerceIn(0f, 1f) }

    // ── Envelope state (exponential moving averages) ─────────────────────
    private var subEma     = 0f   // sub-bass 20–80 Hz
    private var bassEma    = 0f   // bass 80–300 Hz
    private var totalEma   = 0f   // total energy (all bins)
    private var prevMag: FloatArray? = null

    // ── Cooldown timestamps ──────────────────────────────────────────────
    private var lastBassHitMs    = 0L
    private var lastRumbleMs     = 0L
    private var lastTransientMs  = 0L

    // ── Public API ───────────────────────────────────────────────────────

    fun start(audioSessionId: Int) {
        if (isRunning) return
        if (audioSessionId == 0) {
            Log.w(TAG, "audioSessionId=0, cannot start")
            return
        }
        try {
            val sizes = Visualizer.getCaptureSizeRange()
            val captureSize = 1024.coerceIn(sizes[0], sizes[1])

            visualizer = Visualizer(audioSessionId).apply {
                this.captureSize = captureSize
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, r: Int) = Unit
                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, r: Int) {
                            if (fft != null && isRunning) {
                                scope.launch { processFft(fft) }
                            }
                        }
                    },
                    // ~10 Hz — fast enough for music, slow enough to not spam
                    Visualizer.getMaxCaptureRate() / 2,
                    false, true,
                )
                enabled = true
            }
            isRunning = true
            Log.i(TAG, "Started audioSession=$audioSessionId captureSize=$captureSize")
        } catch (e: Exception) {
            Log.e(TAG, "Visualizer start failed: ${e.message}", e)
        }
    }

    fun stop() {
        isRunning = false
        try { visualizer?.enabled = false; visualizer?.release() } catch (_: Exception) {}
        visualizer = null
        resetState()
        Log.d(TAG, "Stopped")
    }

    fun release() { stop(); scope.cancel() }

    // ── FFT processing ───────────────────────────────────────────────────

    private fun processFft(fft: ByteArray) {
        val n = fft.size / 2
        if (n < 8) return
        val now = System.currentTimeMillis()

        // Magnitudes (skip DC)
        val mag = FloatArray(n)
        for (i in 1 until n) {
            val re = fft[2 * i].toFloat()
            val im = fft[2 * i + 1].toFloat()
            mag[i] = kotlin.math.sqrt(re * re + im * im)
        }

        // Bin frequency width: Visualizer internally uses 44100 Hz
        val binHz = 22050f / n

        // ── Band energies ─────────────────────────────────────────────
        val subEnergy  = bandSum(mag, binHz, 20f,  80f)
        val bassEnergy = bandSum(mag, binHz, 80f,  300f)
        val midEnergy  = bandSum(mag, binHz, 300f, 2000f)
        val highEnergy = bandSum(mag, binHz, 2000f, 16000f)
        val totalEnergy = subEnergy + bassEnergy + midEnergy + highEnergy

        // ── Spectral flux (onset detection) ───────────────────────────
        val prev = prevMag
        var flux = 0f
        if (prev != null && prev.size == mag.size) {
            for (i in 1 until n) {
                val d = mag[i] - prev[i]
                if (d > 0f) flux += d
            }
        }
        prevMag = mag.copyOf()

        // ── Update EMAs (alpha=0.25 = faster response to changes) ──────
        val alpha = 0.25f
        subEma   = alpha * subEnergy   + (1f - alpha) * subEma
        bassEma  = alpha * bassEnergy  + (1f - alpha) * bassEma
        totalEma = alpha * totalEnergy + (1f - alpha) * totalEma

        val mult = intensityMultiplier
        if (mult < 0.01f) return

        // ═══════════════════════════════════════════════════════════════
        //  DECISION LOGIC — priority: transient > bass hit > rumble
        // ═══════════════════════════════════════════════════════════════

        // 1) TRANSIENT — spectral flux spike = impact / beat / scene cut
        val fluxThreshold = maxOf(120f, totalEma * 0.8f)
        if (flux > fluxThreshold && (now - lastTransientMs) > TRANSIENT_COOLDOWN_MS) {
            lastTransientMs = now
            val ratio = (flux / fluxThreshold).coerceIn(1f, 4f)
            val amp = (mult * 0.35f * ratio).coerceIn(0.2f, 1.0f)
            fireOneShot(30, amp)
            return
        }

        // 2) BASS HIT — sub+bass sudden spike above its average
        val lowEnergy = subEnergy + bassEnergy
        val lowEma = subEma + bassEma
        val bassThreshold = maxOf(80f, lowEma * 1.8f)
        if (lowEnergy > bassThreshold && (now - lastBassHitMs) > BASS_HIT_COOLDOWN_MS) {
            lastBassHitMs = now
            val ratio = (lowEnergy / bassThreshold).coerceIn(1f, 4f)
            val amp = (mult * 0.45f * ratio).coerceIn(0.25f, 1.0f)
            fireOneShot(55, amp)
            return
        }

        // 3) SUSTAINED RUMBLE — prolonged sub-bass (engines, drones)
        if (subEnergy > maxOf(50f, subEma * 1.3f) &&
            (now - lastRumbleMs) > RUMBLE_COOLDOWN_MS
        ) {
            lastRumbleMs = now
            val amp = (mult * (subEnergy / maxOf(1f, subEma)) * 0.15f).coerceIn(0.08f, 0.45f)
            fireWaveform(90, amp)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun bandSum(mag: FloatArray, binHz: Float, lo: Float, hi: Float): Float {
        val iLo = maxOf(1, (lo / binHz).toInt())
        val iHi = minOf(mag.size - 1, (hi / binHz).toInt())
        var s = 0f
        for (i in iLo..iHi) s += mag[i]
        return s
    }

    private fun fireOneShot(ms: Long, amplitude: Float) {
        val amp = (amplitude * 255).toInt().coerceIn(1, 255)
        vibrator.vibrate(
            if (vibrator.hasAmplitudeControl())
                VibrationEffect.createOneShot(ms, amp)
            else
                VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    private fun fireWaveform(ms: Long, amplitude: Float) {
        if (!vibrator.hasAmplitudeControl()) {
            // Skip continuous rumble on devices without amplitude control, 
            // otherwise it just buzzes constantly at max strength.
            return
        }
        val amp = (amplitude * 255).toInt().coerceIn(1, 255)
        vibrator.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, ms),
                intArrayOf(0, amp),
                -1
            )
        )
    }

    private fun resetState() {
        subEma = 0f; bassEma = 0f; totalEma = 0f
        prevMag = null
        lastBassHitMs = 0L; lastRumbleMs = 0L; lastTransientMs = 0L
    }
}

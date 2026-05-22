package com.example.haptok.fallback

import android.media.audiofx.Visualizer
import android.util.Log
import com.example.haptok.haptics.HapticEngine
import com.example.haptok.models.HapticEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Real-time audio-to-haptics fallback when the cloud backend is unavailable.
 *
 * Attaches an Android [Visualizer] to the audio session produced by
 * ExoPlayer, captures FFT frames, extracts per-band energies, detects
 * transients via spectral flux, and maps the result to haptic events
 * that are sent to the [HapticEngine].
 *
 * **Requires `RECORD_AUDIO` permission** — the caller must obtain it
 * before calling [start].
 *
 * @param hapticEngine  Engine to fire detected haptic events through.
 * @param lookAheadMs   How early to fire events to compensate for motor latency.
 */
class AudioHapticFallback(
    private val hapticEngine: HapticEngine,
    private val lookAheadMs: Long = 20L,
) {

    companion object {
        private const val TAG = "AudioHapticFallback"

        // Frequency band boundaries (bin indices depend on capture size & sample rate).
        // Using standard 44.1 kHz assumptions.
        private const val SAMPLE_RATE = 44100
    }

    // ── State ───────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var visualizer: Visualizer? = null
    private var isRunning = false

    private val subBassThreshold = AdaptiveThreshold(multiplier = 2.0f)
    private val bassThreshold = AdaptiveThreshold(multiplier = 2.2f)
    private val midThreshold = AdaptiveThreshold(multiplier = 2.5f)
    private val highThreshold = AdaptiveThreshold(multiplier = 3.0f)
    private val fluxThreshold = AdaptiveThreshold(multiplier = 2.0f, attackRate = 0.4f)

    /** Previous FFT magnitudes for spectral flux computation. */
    private var previousMagnitudes: FloatArray? = null

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Attach to the given audio session and start analysing.
     *
     * @param audioSessionId  ExoPlayer's audio session ID
     *   (`exoPlayer.audioSessionId`).
     */
    fun start(audioSessionId: Int) {
        if (isRunning) {
            Log.w(TAG, "Already running")
            return
        }

        try {
            val viz = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1] // max capture
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int,
                        ) {
                            // We only use FFT.
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int,
                        ) {
                            if (fft != null) {
                                scope.launch { processFft(fft, samplingRate) }
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    false, // waveform
                    true,  // fft
                )
                enabled = true
            }
            visualizer = viz
            isRunning = true
            Log.i(TAG, "Started (captureSize=${viz.captureSize})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Visualizer", e)
        }
    }

    /** Stop analysis and release the [Visualizer]. */
    fun stop() {
        isRunning = false
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Visualizer", e)
        }
        visualizer = null
        previousMagnitudes = null
        subBassThreshold.reset()
        bassThreshold.reset()
        midThreshold.reset()
        highThreshold.reset()
        fluxThreshold.reset()
        Log.d(TAG, "Stopped")
    }

    /** Release all resources including the coroutine scope. */
    fun release() {
        stop()
        scope.cancel()
    }

    // ── FFT Processing ──────────────────────────────────────────────────

    /**
     * Process a single FFT frame captured by the [Visualizer].
     *
     * The byte array layout from Android Visualizer FFT:
     * `[Re(0), Im(0), Re(1), Im(1), … Re(n/2), Im(n/2)]`
     * where the first pair is DC (Im is always 0) and the last pair is Nyquist.
     */
    private fun processFft(fft: ByteArray, samplingRate: Int) {
        val n = fft.size / 2 // number of frequency bins
        if (n < 4) return

        val actualSampleRate = samplingRate / 1000 // Visualizer returns in mHz
        val binWidth = actualSampleRate.toFloat() / (2 * n)

        // Compute magnitudes.
        val magnitudes = FloatArray(n)
        for (i in 0 until n) {
            val re = fft[2 * i].toFloat()
            val im = fft[2 * i + 1].toFloat()
            magnitudes[i] = sqrt(re * re + im * im)
        }

        // ── Band energies ───────────────────────────────────────────
        val subBassEnergy = bandEnergy(magnitudes, binWidth, 20f, 80f)
        val bassEnergy = bandEnergy(magnitudes, binWidth, 80f, 250f)
        val midEnergy = bandEnergy(magnitudes, binWidth, 250f, 2000f)
        val highEnergy = bandEnergy(magnitudes, binWidth, 2000f, 20000f)

        // ── Spectral flux (transient detection) ─────────────────────
        val flux = computeSpectralFlux(magnitudes)
        val isTransient = fluxThreshold.isAboveThreshold("flux", flux)

        // ── Map to haptic events ────────────────────────────────────

        // Transient detection → impact haptic.
        if (isTransient && flux > 50f) {
            val intensity = (flux / 500f).coerceIn(0.3f, 1.0f)
            val sharpness = if (highEnergy > midEnergy) 0.8f else 0.4f
            hapticEngine.fireEvent(
                HapticEvent(
                    type = "transient",
                    timeSeconds = 0.0, // immediate
                    durationSeconds = null,
                    intensity = intensity,
                    sharpness = sharpness,
                    frequencyHz = null,
                    tags = listOf("impact"),
                    spatialHint = null,
                    actuatorId = null,
                )
            )
        }

        // Sub-bass above threshold → continuous low rumble.
        if (subBassThreshold.isAboveThreshold("sub_bass", subBassEnergy)) {
            val intensity = (subBassEnergy / 300f).coerceIn(0.2f, 0.8f)
            hapticEngine.fireEvent(
                HapticEvent(
                    type = "continuous",
                    timeSeconds = 0.0,
                    durationSeconds = 0.04, // 40 ms segment
                    intensity = intensity,
                    sharpness = 0.1f,
                    frequencyHz = 50,
                    tags = listOf("engine_rumble"),
                    spatialHint = null,
                    actuatorId = null,
                )
            )
        }

        // Mid energy → subtle texture.
        if (midThreshold.isAboveThreshold("mid", midEnergy)) {
            val intensity = (midEnergy / 400f).coerceIn(0.1f, 0.5f)
            hapticEngine.fireEvent(
                HapticEvent(
                    type = "continuous",
                    timeSeconds = 0.0,
                    durationSeconds = 0.03,
                    intensity = intensity,
                    sharpness = 0.5f,
                    frequencyHz = null,
                    tags = listOf("tension"),
                    spatialHint = null,
                    actuatorId = null,
                )
            )
        }

        previousMagnitudes = magnitudes
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Sum of magnitudes in the frequency range [loHz, hiHz). */
    private fun bandEnergy(
        magnitudes: FloatArray,
        binWidth: Float,
        loHz: Float,
        hiHz: Float,
    ): Float {
        val loIdx = (loHz / binWidth).toInt().coerceIn(0, magnitudes.size - 1)
        val hiIdx = (hiHz / binWidth).toInt().coerceIn(loIdx, magnitudes.size - 1)
        var sum = 0f
        for (i in loIdx..hiIdx) sum += magnitudes[i]
        return sum
    }

    /**
     * Half-wave-rectified spectral flux: the sum of positive magnitude
     * differences between the current and previous frame.
     */
    private fun computeSpectralFlux(currentMag: FloatArray): Float {
        val prev = previousMagnitudes ?: return 0f
        val len = minOf(currentMag.size, prev.size)
        var flux = 0f
        for (i in 0 until len) {
            val diff = currentMag[i] - prev[i]
            if (diff > 0) flux += diff
        }
        return flux
    }
}

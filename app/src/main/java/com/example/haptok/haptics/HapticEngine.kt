package com.example.haptok.haptics

import android.content.Context
import android.os.CombinedVibration
import android.os.VibratorManager
import android.util.Log
import com.example.haptok.models.HapticEvent
import com.example.haptok.models.HapticTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Core haptic playback engine.
 *
 * Accepts a [HapticTimeline], converts its events via [HapticEventTranslator],
 * and drives the device vibrator(s) in sync with an external clock
 * (typically an ExoPlayer position).
 *
 * Thread-safe — all mutable state is guarded by a [Mutex].
 */
class HapticEngine(context: Context) {

    companion object {
        private const val TAG = "HapticEngine"
    }

    /** Playback state exposed to observers. */
    enum class State { IDLE, PLAYING, PAUSED, STOPPED }

    // ── System services ─────────────────────────────────────────────────

    private val vibratorManager: VibratorManager =
        context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager

    private val translator = HapticEventTranslator(vibratorManager)

    // ── State ───────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private var timeline: HapticTimeline? = null
    private var allEvents: List<HapticEvent> = emptyList()
    private var playbackJob: Job? = null
    private var currentPositionMs: Long = 0L

    private val _state = MutableStateFlow(State.IDLE)
    /** Observable playback state. */
    val state: StateFlow<State> = _state.asStateFlow()

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Load a [HapticTimeline] for playback.
     * Sorts all events chronologically across all tracks.
     */
    suspend fun load(hapticTimeline: HapticTimeline) = mutex.withLock {
        stop_internal()
        timeline = hapticTimeline
        allEvents = hapticTimeline.tracks
            .flatMap { it.events }
            .sortedBy { it.timeSeconds }
        _state.value = State.IDLE
        Log.d(TAG, "Loaded timeline with ${allEvents.size} events")
    }

    /**
     * Begin (or resume) playback from [startPositionMs].
     * Events are fired on a coroutine that sleeps between events.
     */
    fun play(startPositionMs: Long = currentPositionMs) {
        scope.launch {
            mutex.withLock {
                cancelPlayback()
                currentPositionMs = startPositionMs
                _state.value = State.PLAYING
            }
            startPlaybackLoop(startPositionMs)
        }
    }

    /** Pause playback and cancel all pending vibrations. */
    fun pause() {
        scope.launch {
            mutex.withLock {
                if (_state.value == State.PLAYING) {
                    cancelPlayback()
                    vibratorManager.cancel()
                    _state.value = State.PAUSED
                    Log.d(TAG, "Paused at ${currentPositionMs}ms")
                }
            }
        }
    }

    /** Seek to [positionMs]. If playing, re-arms the event loop. */
    fun seekTo(positionMs: Long) {
        scope.launch {
            mutex.withLock {
                cancelPlayback()
                vibratorManager.cancel()
                currentPositionMs = positionMs
                Log.d(TAG, "Seeked to ${positionMs}ms")
            }
            if (_state.value == State.PLAYING) {
                startPlaybackLoop(positionMs)
            }
        }
    }

    /** Fully stop playback and reset position. */
    fun stop() {
        scope.launch { mutex.withLock { stop_internal() } }
    }

    /**
     * Set the global intensity multiplier (0.0–1.0).
     * Takes effect from the next event fired.
     */
    fun setIntensity(intensity: Float) {
        translator.setIntensityMultiplier(intensity)
    }

    /**
     * Fire a single [HapticEvent] immediately (used by [HapticTimelinePlayer]
     * and fallback engine).
     */
    fun fireEvent(event: HapticEvent) {
        try {
            val combined = translator.translateCombined(event)
            vibratorManager.vibrate(combined)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fire event: ${e.message}")
        }
    }

    /** Release all resources. Call when the engine is no longer needed. */
    fun release() {
        scope.launch {
            mutex.withLock { stop_internal() }
            scope.cancel()
        }
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun stop_internal() {
        cancelPlayback()
        vibratorManager.cancel()
        currentPositionMs = 0L
        _state.value = State.STOPPED
    }

    private fun cancelPlayback() {
        playbackJob?.cancel()
        playbackJob = null
    }

    /**
     * Self-contained playback coroutine.
     * Walks through sorted events, sleeping between them.
     */
    private fun startPlaybackLoop(fromMs: Long) {
        playbackJob = scope.launch {
            val startTime = System.currentTimeMillis() - fromMs
            val startSec = fromMs / 1000.0

            // Find the first event at or after startSec.
            var idx = allEvents.binarySearchInsertionPoint(startSec)

            while (isActive && idx < allEvents.size) {
                val event = allEvents[idx]
                val eventMs = (event.timeSeconds * 1000).toLong()
                val now = System.currentTimeMillis() - startTime

                val waitMs = eventMs - now
                if (waitMs > 0) delay(waitMs)

                if (!isActive) break

                mutex.withLock { currentPositionMs = eventMs }

                try {
                    val combined = translator.translateCombined(event)
                    vibratorManager.vibrate(combined)
                } catch (e: Exception) {
                    Log.w(TAG, "Vibration error at ${event.timeSeconds}s: ${e.message}")
                }

                idx++
            }

            // Reached end of timeline.
            if (isActive) {
                mutex.withLock {
                    _state.value = State.STOPPED
                    Log.d(TAG, "Playback complete")
                }
            }
        }
    }

    /**
     * Binary-search helper: returns the index of the first event whose
     * [HapticEvent.timeSeconds] >= [targetSec].
     */
    private fun List<HapticEvent>.binarySearchInsertionPoint(targetSec: Double): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (this[mid].timeSeconds < targetSec) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

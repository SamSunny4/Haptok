package com.example.haptok.haptics

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.haptok.models.HapticEvent
import com.example.haptok.models.HapticTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Synchronises haptic playback with a Media3 [ExoPlayer] instance.
 *
 * Instead of the standalone [HapticEngine] playback loop, this class
 * ties event firing directly to the player's real-time position so
 * that seeks, pauses, and speed changes are handled correctly.
 *
 * Events are scheduled with a configurable look-ahead window (default
 * 200 ms) and a latency compensation offset (default −30 ms) so that
 * the vibrator fires slightly before the visual frame to account for
 * motor spin-up time.
 *
 * @param exoPlayer  The player whose position drives scheduling.
 * @param hapticEngine  Engine used to actually fire vibrations.
 * @param lookAheadMs  How far ahead to schedule events (ms).
 * @param latencyCompensationMs  Negative = fire early to compensate for motor latency.
 */
class HapticTimelinePlayer(
    private val exoPlayer: ExoPlayer,
    private val hapticEngine: HapticEngine,
    private val lookAheadMs: Long = 200L,
    private val latencyCompensationMs: Long = -30L,
) : Player.Listener {

    companion object {
        private const val TAG = "TimelinePlayer"
        private const val SCHEDULER_TICK_MS = 16L // ~60 Hz tick
    }

    // ── State ───────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private var timeline: HapticTimeline? = null
    private var sortedEvents: List<HapticEvent> = emptyList()

    /** Index of the next event that has NOT yet been fired. */
    private var nextEventIndex = 0

    private var schedulerJob: Job? = null
    private var isAttached = false

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Load a timeline and begin synchronising with the player.
     * If the player is already playing, scheduling starts immediately.
     */
    fun start(hapticTimeline: HapticTimeline) {
        scope.launch {
            mutex.withLock {
                timeline = hapticTimeline
                sortedEvents = hapticTimeline.tracks
                    .flatMap { it.events }
                    .sortedBy { it.timeSeconds }
                nextEventIndex = 0
            }

            if (!isAttached) {
                exoPlayer.addListener(this@HapticTimelinePlayer)
                isAttached = true
            }

            if (exoPlayer.isPlaying) {
                startScheduler()
            }

            Log.d(TAG, "Started with ${sortedEvents.size} events")
        }
    }

    /** Stop haptic synchronisation and detach from the player. */
    fun stop() {
        scope.launch {
            mutex.withLock {
                stopScheduler()
                if (isAttached) {
                    exoPlayer.removeListener(this@HapticTimelinePlayer)
                    isAttached = false
                }
                timeline = null
                sortedEvents = emptyList()
                nextEventIndex = 0
            }
            Log.d(TAG, "Stopped")
        }
    }

    /** Release all resources. */
    fun release() {
        stop()
        scope.cancel()
    }

    // ── Player.Listener ─────────────────────────────────────────────────

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        scope.launch {
            if (isPlaying) {
                resyncIndex()
                startScheduler()
            } else {
                stopScheduler()
            }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        scope.launch {
            Log.d(TAG, "Position discontinuity → ${newPosition.positionMs}ms (reason=$reason)")
            mutex.withLock {
                stopScheduler()
                resyncIndexLocked()
            }
            if (exoPlayer.isPlaying) startScheduler()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
            scope.launch { stopScheduler() }
        }
    }

    // ── Scheduler ───────────────────────────────────────────────────────

    private fun startScheduler() {
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch {
            Log.d(TAG, "Scheduler started")
            while (isActive) {
                tick()
                delay(SCHEDULER_TICK_MS)
            }
        }
    }

    private fun stopScheduler() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    /**
     * One scheduler tick: fires all events whose timestamp falls within
     * `[playerPos, playerPos + lookAheadMs)`, adjusted by latency
     * compensation.
     */
    private suspend fun tick() {
        val playerPosMs = exoPlayer.currentPosition
        val windowEndSec = (playerPosMs + lookAheadMs) / 1000.0

        mutex.withLock {
            while (nextEventIndex < sortedEvents.size) {
                val event = sortedEvents[nextEventIndex]
                val eventTimeSec = event.timeSeconds + (latencyCompensationMs / 1000.0)

                if (eventTimeSec > windowEndSec) break

                // Event is in the window — compute how long to delay before firing.
                val eventMs = (eventTimeSec * 1000).toLong()
                val fireDelay = eventMs - playerPosMs
                if (fireDelay > 0) {
                    // Launch a precise-fire coroutine so we don't block the tick loop.
                    val eventToFire = event
                    scope.launch {
                        delay(fireDelay)
                        hapticEngine.fireEvent(eventToFire)
                    }
                } else {
                    // Already past — fire immediately.
                    hapticEngine.fireEvent(event)
                }
                nextEventIndex++
            }
        }
    }

    // ── Index management ────────────────────────────────────────────────

    private suspend fun resyncIndex() = mutex.withLock { resyncIndexLocked() }

    /**
     * Binary-search the sorted event list to find the first event at or
     * after the player's current position. Must hold [mutex].
     */
    private fun resyncIndexLocked() {
        val positionSec = exoPlayer.currentPosition / 1000.0
        nextEventIndex = sortedEvents.binarySearchInsertionPoint(positionSec)
        Log.d(TAG, "Re-synced index to $nextEventIndex (pos=${positionSec}s)")
    }

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

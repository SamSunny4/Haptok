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
import kotlinx.coroutines.withContext

/**
 * Synchronises haptic playback with a Media3 [ExoPlayer] instance.
 *
 * Runs a tight 16ms tick loop that reads the real-time player position and
 * fires any haptic events that fall within the current lookahead window.
 * Handles seeks (position discontinuity) and pause/resume.
 */
class HapticTimelinePlayer(
    private val exoPlayer: ExoPlayer,
    private val hapticEngine: HapticEngine,
    private val lookAheadMs: Long = 200L,
    private val latencyCompensationMs: Long = -30L,
) {
    companion object {
        private const val TAG = "TimelinePlayer"
        private const val TICK_MS = 16L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    private var sortedEvents: List<HapticEvent> = emptyList()
    /** Index of the next event not yet dispatched. Reset on seek. */
    @Volatile private var nextEventIndex = 0

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                resyncAndStart()
            } else {
                stopTick()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // Seek — recalculate which event comes next
            resyncAndStart()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                stopTick()
            }
        }
    }

    /** Load a timeline and attach to the player. Must be called from main thread. */
    fun start(hapticTimeline: HapticTimeline) {
        sortedEvents = hapticTimeline.tracks
            .flatMap { it.events }
            .sortedBy { it.timeSeconds }
        nextEventIndex = 0
        // Always attach from main thread so ExoPlayer doesn't crash
        exoPlayer.addListener(listener)
        if (exoPlayer.isPlaying) {
            resyncAndStart()
        }
        Log.d(TAG, "Started with ${sortedEvents.size} events")
    }

    fun release() {
        stopTick()
        // Remove listener safely from main thread
        scope.launch(Dispatchers.Main) {
            runCatching { exoPlayer.removeListener(listener) }
        }
        scope.cancel()
    }

    // ── Internal ──────────────────────────────────────────────────────

    private fun resyncAndStart() {
        stopTick()
        val posMs = exoPlayer.currentPosition
        nextEventIndex = binarySearchFrom(posMs / 1000.0)
        Log.d(TAG, "Resynced to event idx=$nextEventIndex at pos=${posMs}ms")
        startTick()
    }

    private fun startTick() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    private fun stopTick() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun tick() {
        if (!exoPlayer.isPlaying) return
        val playerPosMs = exoPlayer.currentPosition
        val windowEndSec = (playerPosMs + lookAheadMs) / 1000.0

        val events = sortedEvents
        while (nextEventIndex < events.size) {
            val event = events[nextEventIndex]
            val eventTimeSec = event.timeSeconds + (latencyCompensationMs / 1000.0)
            if (eventTimeSec > windowEndSec) break

            val eventMs = (eventTimeSec * 1000).toLong()
            val fireDelay = eventMs - playerPosMs
            val eventToFire = event
            if (fireDelay > 0) {
                scope.launch {
                    delay(fireDelay)
                    hapticEngine.fireEvent(eventToFire)
                }
            } else {
                hapticEngine.fireEvent(eventToFire)
            }
            nextEventIndex++
        }
    }

    private fun binarySearchFrom(targetSec: Double): Int {
        val list = sortedEvents
        var lo = 0; var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (list[mid].timeSeconds < targetSec) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

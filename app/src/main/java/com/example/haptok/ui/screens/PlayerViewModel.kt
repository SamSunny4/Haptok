package com.example.haptok.ui.screens

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.haptok.fallback.AudioHapticFallback
import com.example.haptok.haptics.HapticEngine
import com.example.haptok.haptics.HapticTimelinePlayer
import com.example.haptok.models.HapticTimeline
import com.example.haptok.repository.HaptokRepository
import com.example.haptok.repository.ProcessingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PlayerViewModel"
private const val PREFS_NAME = "haptok_settings"
private const val KEY_FORCE_NATIVE = "force_native_haptics"
private const val KEY_HAPTIC_INTENSITY = "haptic_intensity"
private const val KEY_FALLBACK_ENABLED = "fallback_enabled"

data class PlayerUiState(
    val processingState: ProcessingState = ProcessingState.Idle,
    val hapticIntensity: Float = 0.75f,
    val hapticsEnabled: Boolean = true,
    val hapticMode: String = "SERVER",
    val isFallbackMode: Boolean = false,
    val isPlaying: Boolean = false,
    val isVideoReady: Boolean = false,
    val currentPositionMs: Long = 0L,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val repository = HaptokRepository(context)

    private val _uiState = MutableStateFlow(
        PlayerUiState(
            hapticIntensity = prefs.getFloat(KEY_HAPTIC_INTENSITY, 0.75f),
        )
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _exoPlayer = MutableStateFlow<ExoPlayer?>(null)
    val exoPlayer: StateFlow<ExoPlayer?> = _exoPlayer.asStateFlow()

    private var hapticEngine: HapticEngine? = null
    private var timelinePlayer: HapticTimelinePlayer? = null
    private var audioFallback: AudioHapticFallback? = null
    private var currentVideoUri: String? = null
    private var positionPollingJob: Job? = null
    /** Guards against readyToPlay before first frame rendered */
    private var pendingReadyToPlay = false

    // ── Init ─────────────────────────────────────────────────────────────

    fun initPlayer(videoUri: String, hapticMode: String) {
        if (_exoPlayer.value != null) return
        currentVideoUri = videoUri
        _uiState.value = _uiState.value.copy(hapticMode = hapticMode)

        hapticEngine = HapticEngine(context).also {
            it.setIntensity(_uiState.value.hapticIntensity)
        }

        val player = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(videoUri)))
            prepare()
            playWhenReady = false

            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                    if (isPlaying) startPositionPolling() else stopPositionPolling()
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    Log.d(TAG, "onAudioSessionIdChanged: $audioSessionId")
                    val fb = audioFallback ?: return
                    if (!fb.isRunning && audioSessionId != 0) {
                        fb.start(audioSessionId)
                    }
                }

                override fun onRenderedFirstFrame() {
                    Log.d(TAG, "onRenderedFirstFrame")
                    _uiState.value = _uiState.value.copy(isVideoReady = true)
                    if (pendingReadyToPlay) {
                        pendingReadyToPlay = false
                    }
                }
            })
        }
        _exoPlayer.value = player

        when (hapticMode) {
            "FALLBACK" -> {
                // Force native: no server, no overlay. Wait for onRenderedFirstFrame.
                viewModelScope.launch(Dispatchers.Main) {
                    activateFallback()
                    startPlayback()
                }
            }
            "SERVER", "CACHED" -> {
                // We'll pass forceNative = false to repository. The repository handles CACHED vs SERVER internally,
                // or we can just fetch from cache first if CACHED mode.
                // For now, let repository handle it.
                processVideoFromServer(videoUri, forceCached = hapticMode == "CACHED")
            }
        }
    }

    /** Explicit cleanup — call from DisposableEffect or onCleared */
    fun releasePlayer() {
        Log.d(TAG, "releasePlayer")
        stopPositionPolling()
        releaseHaptics()
        hapticEngine?.release()
        hapticEngine = null
        _exoPlayer.value?.stop()
        _exoPlayer.value?.release()
        _exoPlayer.value = null
        currentVideoUri = null
        pendingReadyToPlay = false

        // Reset UI state so re-entry is clean
        _uiState.value = _uiState.value.copy(
            isPlaying = false,
            isVideoReady = false,
            currentPositionMs = 0L,
            processingState = ProcessingState.Idle,
        )
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
    }

    // ── Controls ──────────────────────────────────────────────────────────

    fun togglePlayPause() {
        val p = _exoPlayer.value ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekTo(positionMs: Long) {
        _exoPlayer.value?.seekTo(positionMs)
        _uiState.value = _uiState.value.copy(currentPositionMs = positionMs)
    }

    fun setHapticIntensity(intensity: Float) {
        prefs.edit().putFloat(KEY_HAPTIC_INTENSITY, intensity).apply()
        _uiState.value = _uiState.value.copy(hapticIntensity = intensity)
        hapticEngine?.setIntensity(intensity)
        audioFallback?.setIntensity(intensity)
    }

    fun toggleHaptics(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(hapticsEnabled = enabled)
        val scale = if (enabled) _uiState.value.hapticIntensity else 0f
        hapticEngine?.setIntensity(scale)
        audioFallback?.setIntensity(scale)
    }

    fun setForceNative(enabled: Boolean) {
        // Obsolete with HapticModeDialog, but keeping for compatibility if button exists
        val mode = if (enabled) "FALLBACK" else "SERVER"
        _uiState.value = _uiState.value.copy(hapticMode = mode)
        currentVideoUri?.let { uri ->
            releaseHaptics()
            if (enabled) {
                viewModelScope.launch(Dispatchers.Main) {
                    activateFallback()
                    _uiState.value = _uiState.value.copy(
                        processingState = ProcessingState.Idle,
                        isVideoReady = true,
                        isFallbackMode = true
                    )
                }
            } else {
                _uiState.value =
                    _uiState.value.copy(isFallbackMode = false, isVideoReady = false)
                processVideoFromServer(uri, false)
            }
        }
    }

    // ── Server processing ─────────────────────────────────────────────────

    private fun processVideoFromServer(videoUri: String, forceCached: Boolean) {
        viewModelScope.launch {
            repository.processVideo(Uri.parse(videoUri), forceNative = false)
                .collect { state ->
                    _uiState.value = _uiState.value.copy(processingState = state)
                    when (state) {
                        is ProcessingState.Completed -> withContext(Dispatchers.Main) {
                            attachHapticTimeline(state.timeline)
                            startPlayback()
                        }
                        is ProcessingState.CacheHit -> withContext(Dispatchers.Main) {
                            attachHapticTimeline(state.timeline)
                            startPlayback()
                        }
                        is ProcessingState.Error -> withContext(Dispatchers.Main) {
                            Log.w(TAG, "Server error: ${state.message} → audio fallback")
                            activateFallback()
                            startPlayback()
                        }
                        else -> Unit
                    }
                }
        }
    }

    /** Tell ExoPlayer to start; the first visible frame triggers isVideoReady */
    private fun startPlayback() {
        pendingReadyToPlay = true
        _exoPlayer.value?.playWhenReady = true
    }

    // ── Haptic timeline (server mode) ─────────────────────────────────────

    private fun attachHapticTimeline(timeline: HapticTimeline) {
        val engine = hapticEngine ?: return
        val player = _exoPlayer.value ?: return
        releaseHaptics()
        Log.d(TAG, "Attaching timeline: ${timeline.tracks.sumOf { it.events.size }} events")
        timelinePlayer = HapticTimelinePlayer(
            exoPlayer = player,
            hapticEngine = engine,
            latencyCompensationMs = prefs.getInt("latency_offset_ms", -30).toLong(),
        ).also { it.start(timeline) }
        _uiState.value = _uiState.value.copy(isFallbackMode = false)
    }

    // ── Audio fallback ─────────────────────────────────────────────────────

    private fun activateFallback() {
        val player = _exoPlayer.value ?: return
        releaseHaptics()

        val fb = AudioHapticFallback(context = context)
        fb.setIntensity(_uiState.value.hapticIntensity)
        audioFallback = fb

        val sessionId = player.audioSessionId
        Log.d(TAG, "activateFallback — audioSessionId=$sessionId")
        if (sessionId != 0) fb.start(sessionId)

        _uiState.value = _uiState.value.copy(isFallbackMode = true)
    }

    private fun releaseHaptics() {
        timelinePlayer?.release()
        timelinePlayer = null
        audioFallback?.release()
        audioFallback = null
    }

    // ── Position polling ──────────────────────────────────────────────────

    private fun startPositionPolling() {
        if (positionPollingJob?.isActive == true) return
        positionPollingJob = viewModelScope.launch {
            while (isActive) {
                _uiState.value = _uiState.value.copy(
                    currentPositionMs = _exoPlayer.value?.currentPosition ?: 0L
                )
                delay(200)
            }
        }
    }

    private fun stopPositionPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = null
    }
}

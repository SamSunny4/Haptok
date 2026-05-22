package com.example.haptok.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.haptok.haptics.HapticEngine
import com.example.haptok.haptics.HapticTimelinePlayer
import com.example.haptok.models.HapticTimeline
import com.example.haptok.repository.HaptokRepository
import com.example.haptok.repository.ProcessingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the player screen.
 */
data class PlayerUiState(
    val processingState: ProcessingState = ProcessingState.Idle,
    val hapticIntensity: Float = 0.75f,
    val hapticsEnabled: Boolean = true,
    val isFallbackMode: Boolean = false,
    val isPlaying: Boolean = false,
)

/**
 * ViewModel for [PlayerScreen]. Manages video playback, haptic processing,
 * and haptic synchronization lifecycle.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PlayerViewModel"
    }

    private val context = application.applicationContext
    private val repository = HaptokRepository(context)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** ExoPlayer instance — created lazily and released on [onCleared]. */
    var exoPlayer: ExoPlayer? = null
        private set

    private var hapticEngine: HapticEngine? = null
    private var timelinePlayer: HapticTimelinePlayer? = null
    private var currentTimeline: HapticTimeline? = null

    /**
     * Initialise the player and start processing the video.
     */
    fun initPlayer(videoUri: String) {
        if (exoPlayer != null) return // already initialised

        val player = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(videoUri)))
            prepare()
            playWhenReady = true
            // Track playing state for UI updates.
            // HapticTimelinePlayer registers its own Player.Listener for sync.
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                }
            })
        }
        exoPlayer = player

        hapticEngine = HapticEngine(context)

        // Start backend processing
        processVideo(videoUri)
    }

    /**
     * Process the video through the backend or cache.
     */
    private fun processVideo(videoUri: String) {
        viewModelScope.launch {
            repository.processVideo(Uri.parse(videoUri)).collect { state ->
                _uiState.value = _uiState.value.copy(processingState = state)

                when (state) {
                    is ProcessingState.Completed -> attachHaptics(state.timeline)
                    is ProcessingState.CacheHit -> attachHaptics(state.timeline)
                    is ProcessingState.Error -> {
                        // Enable fallback mode on error
                        _uiState.value = _uiState.value.copy(isFallbackMode = true)
                    }
                    else -> { /* status updates handled by UI */ }
                }
            }
        }
    }

    /**
     * Attach a haptic timeline to the player for synchronised playback.
     */
    private suspend fun attachHaptics(timeline: HapticTimeline) {
        currentTimeline = timeline
        val engine = hapticEngine ?: return
        val player = exoPlayer ?: return

        engine.load(timeline)
        engine.setIntensity(_uiState.value.hapticIntensity)

        timelinePlayer?.release()
        timelinePlayer = HapticTimelinePlayer(player, engine).also {
            it.start(timeline)
        }
    }

    fun setHapticIntensity(intensity: Float) {
        _uiState.value = _uiState.value.copy(hapticIntensity = intensity)
        hapticEngine?.setIntensity(intensity)
    }

    fun toggleHaptics(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(hapticsEnabled = enabled)
        if (enabled) {
            hapticEngine?.setIntensity(_uiState.value.hapticIntensity)
        } else {
            hapticEngine?.setIntensity(0f)
        }
    }

    override fun onCleared() {
        super.onCleared()
        timelinePlayer?.release()
        hapticEngine?.release()
        exoPlayer?.release()
        exoPlayer = null
    }
}

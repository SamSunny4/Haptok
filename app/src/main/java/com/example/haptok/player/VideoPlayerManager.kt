package com.example.haptok.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wrapper around Media3 [ExoPlayer] that manages lifecycle, exposes
 * playback state as [StateFlow], and provides convenient access to the
 * audio session ID needed by the [com.example.haptok.fallback.AudioHapticFallback].
 */
class VideoPlayerManager(private val context: Context) {

    companion object {
        private const val TAG = "VideoPlayerManager"
    }

    // ── Playback state model ────────────────────────────────────────────

    /** Simplified playback state exposed to the UI / haptic layers. */
    data class PlaybackState(
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val isEnded: Boolean = false,
        val durationMs: Long = 0L,
        val currentPositionMs: Long = 0L,
        val errorMessage: String? = null,
    )

    // ── Fields ──────────────────────────────────────────────────────────

    private var _exoPlayer: ExoPlayer? = null

    /**
     * The underlying [ExoPlayer] instance. Throws if [init] has not been
     * called. Prefer using the wrapper methods; direct access is provided
     * for [com.example.haptok.haptics.HapticTimelinePlayer].
     */
    val exoPlayer: ExoPlayer
        get() = _exoPlayer
            ?: throw IllegalStateException("VideoPlayerManager not initialised")

    private val _playbackState = MutableStateFlow(PlaybackState())

    /** Observable playback state. */
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateState()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}", error)
            _playbackState.value = _playbackState.value.copy(
                errorMessage = error.localizedMessage ?: "Playback error",
            )
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /** Create and configure the player. Safe to call multiple times. */
    fun init() {
        if (_exoPlayer != null) return
        val player = ExoPlayer.Builder(context).build().apply {
            addListener(playerListener)
            playWhenReady = false
        }
        _exoPlayer = player
        Log.d(TAG, "ExoPlayer created")
    }

    /** Set a media item from a content/file URI string. */
    fun setMediaUri(uri: String) {
        exoPlayer.apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
        Log.d(TAG, "Media set: $uri")
    }

    /** Set a [MediaItem] directly (e.g. from a content resolver). */
    fun setMediaItem(item: MediaItem) {
        exoPlayer.apply {
            setMediaItem(item)
            prepare()
        }
    }

    fun play() {
        exoPlayer.playWhenReady = true
    }

    fun pause() {
        exoPlayer.playWhenReady = false
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) pause() else play()
    }

    /** Returns the audio session ID for the Visualizer / HapticGenerator. */
    val audioSessionId: Int
        get() = exoPlayer.audioSessionId

    // ── Lifecycle callbacks (call from Activity/Fragment) ────────────────

    /** Pause playback when the app goes to background. */
    fun onPause() {
        _exoPlayer?.playWhenReady = false
    }

    /** Resume playback when the app returns to foreground (optional). */
    fun onResume() {
        // Don't auto-resume — let the user press play.
    }

    /** Release all resources. Must be called from onDestroy. */
    fun release() {
        _exoPlayer?.run {
            removeListener(playerListener)
            stop()
            release()
        }
        _exoPlayer = null
        _playbackState.value = PlaybackState()
        Log.d(TAG, "Released")
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun updateState() {
        val p = _exoPlayer ?: return
        _playbackState.value = PlaybackState(
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            isEnded = p.playbackState == Player.STATE_ENDED,
            durationMs = p.duration.coerceAtLeast(0),
            currentPositionMs = p.currentPosition,
        )
    }
}

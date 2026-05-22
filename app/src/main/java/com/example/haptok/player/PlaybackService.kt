package com.example.haptok.player

import android.content.Intent
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Minimal [MediaSessionService] that enables foreground playback with
 * a system media notification.
 *
 * Declared in AndroidManifest with `foregroundServiceType="mediaPlayback"`.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "PlaybackService"
    }

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player: Player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()

        Log.d(TAG, "Service created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }
}

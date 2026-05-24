package com.example.haptok.ui.screens

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.example.haptok.repository.ProcessingState
import com.example.haptok.theme.DarkBackground
import com.example.haptok.theme.GlassWhite
import com.example.haptok.theme.HaptokBlue
import com.example.haptok.theme.HaptokCyan
import com.example.haptok.theme.HaptokViolet
import com.example.haptok.ui.components.IntensitySlider
import com.example.haptok.ui.components.ProcessingIndicator

@Composable
fun PlayerScreen(
    videoUri: String,
    hapticMode: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val exoPlayer by viewModel.exoPlayer.collectAsStateWithLifecycle()

    LaunchedEffect(videoUri, hapticMode) {
        viewModel.initPlayer(videoUri, hapticMode)
    }

    // Release player when leaving this screen
    DisposableEffect(Unit) {
        onDispose { viewModel.releasePlayer() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .testTag("player_screen")
    ) {

        // ── Video — controller disabled; we draw our own controls ──
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    // Disable the built-in controller so we own play/pause/seek
                    useController = false
                    keepScreenOn = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { playerView ->
                if (playerView.player != exoPlayer) {
                    playerView.player = exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Only show loading overlay during server processing, never in fallback mode
        AnimatedVisibility(
            visible = !uiState.isVideoReady && !uiState.isFallbackMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DarkBackground.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (val state = uiState.processingState) {
                        is ProcessingState.Uploading -> ProcessingIndicator(
                            stage = if (state.progress < 0.99f)
                                "Uploading video… ${(state.progress * 100).toInt()}%"
                            else "Upload complete — processing...",
                            progress = state.progress,
                        )
                        is ProcessingState.Processing -> ProcessingIndicator(
                            stage = state.stage,
                            progress = state.progress,
                        )
                        is ProcessingState.Idle -> ProcessingIndicator(
                            stage = "Preparing...",
                            progress = 0f,
                        )
                        else -> {}
                    }
                }
            }
        }

        // ── Top bar: back + badges + toggles ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .background(GlassWhite, CircleShape)
                    .testTag("player_back_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }

            Spacer(Modifier.weight(1f))

            if (uiState.isFallbackMode) {
                Text(
                    "AUDIO FALLBACK",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFB74D),
                    modifier = Modifier
                        .background(Color(0x33FFB74D), MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Spacer(Modifier.width(8.dp))
            }

            IconButton(
                onClick = { viewModel.setForceNative(!uiState.isFallbackMode) },
                modifier = Modifier
                    .background(
                        if (uiState.isFallbackMode) Color(0xFF4CAF50).copy(alpha = 0.4f) else GlassWhite,
                        CircleShape,
                    )
                    .testTag("force_native_button")
            ) {
                Icon(
                    Icons.Filled.PhoneAndroid,
                    contentDescription = "Force native haptics",
                    tint = if (uiState.isFallbackMode) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.6f),
                )
            }

            Spacer(Modifier.width(4.dp))

            IconButton(
                onClick = { viewModel.toggleHaptics(!uiState.hapticsEnabled) },
                modifier = Modifier
                    .background(
                        if (uiState.hapticsEnabled) HaptokViolet.copy(alpha = 0.5f) else GlassWhite,
                        CircleShape,
                    )
                    .testTag("haptic_toggle_button")
            ) {
                Icon(
                    Icons.Filled.Vibration,
                    contentDescription = "Toggle Haptics",
                    tint = if (uiState.hapticsEnabled) HaptokCyan else Color.White.copy(alpha = 0.5f),
                )
            }
        }

        // ── Bottom controls (visible once video is ready) ──
        AnimatedVisibility(
            visible = uiState.isVideoReady,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, DarkBackground.copy(alpha = 0.95f))
                        )
                    )
                    .systemBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {

                // Seek bar + time
                val duration = exoPlayer?.duration?.takeIf { it > 0 } ?: 1L
                val position = uiState.currentPositionMs.coerceIn(0, duration)
                val progress = position.toFloat() / duration.toFloat()

                val totalSec = duration / 1000
                val posSec = position / 1000
                val timeText = "%d:%02d / %d:%02d".format(
                    posSec / 60, posSec % 60,
                    totalSec / 60, totalSec % 60
                )

                Text(
                    timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.End)
                )

                Slider(
                    value = progress,
                    onValueChange = { frac ->
                        val seekMs = (frac * duration).toLong()
                        viewModel.seekTo(seekMs)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = HaptokCyan,
                        activeTrackColor = HaptokCyan,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("seek_slider"),
                )

                // Play/Pause + intensity row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier
                            .background(GlassWhite, CircleShape)
                            .testTag("play_pause_button")
                    ) {
                        Icon(
                            if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                            tint = Color.White,
                        )
                    }

                    if (uiState.hapticsEnabled) {
                        IntensitySlider(
                            intensity = uiState.hapticIntensity,
                            onIntensityChange = viewModel::setHapticIntensity,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // ── Error banner ──
        AnimatedVisibility(
            visible = uiState.processingState is ProcessingState.Error && uiState.isVideoReady,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .systemBarsPadding()
                .padding(top = 60.dp, start = 16.dp, end = 16.dp),
        ) {
            val errorMsg = (uiState.processingState as? ProcessingState.Error)?.message ?: ""
            Text(
                text = "⚠ $errorMsg",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFB74D),
                modifier = Modifier
                    .background(Color(0x33FFB74D), MaterialTheme.shapes.medium)
                    .padding(12.dp)
                    .fillMaxWidth()
            )
        }
    }
}

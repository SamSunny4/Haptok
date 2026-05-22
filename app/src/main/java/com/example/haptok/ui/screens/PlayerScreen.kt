package com.example.haptok.ui.screens

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.example.haptok.repository.ProcessingState
import com.example.haptok.theme.DarkBackground
import com.example.haptok.theme.GlassWhite
import com.example.haptok.theme.HaptokCyan
import com.example.haptok.theme.HaptokViolet
import com.example.haptok.ui.components.IntensitySlider
import com.example.haptok.ui.components.ProcessingIndicator

/**
 * Full-screen video player with haptic feedback overlay.
 */
@Composable
fun PlayerScreen(
    videoUri: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showControls by remember { mutableStateOf(true) }

    // Initialise player on first composition
    LaunchedEffect(videoUri) {
        viewModel.initPlayer(videoUri)
    }

    // Keep screen on during playback
    DisposableEffect(Unit) {
        onDispose {
            // Player cleanup is handled by ViewModel.onCleared
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .testTag("player_screen")
    ) {
        // ── Video Player ──
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowFastForwardButton(false)
                    setShowRewindButton(false)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    keepScreenOn = true
                }
            },
            update = { playerView ->
                playerView.player = viewModel.exoPlayer
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Top overlay: back button + haptic toggle ──
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
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }

            Spacer(Modifier.weight(1f))

            // Fallback mode badge
            if (uiState.isFallbackMode) {
                Text(
                    "FALLBACK",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFB74D),
                    modifier = Modifier
                        .background(Color(0x33FFB74D), MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Spacer(Modifier.width(8.dp))
            }

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

        // ── Processing overlay ──
        val showProcessing = uiState.processingState is ProcessingState.Uploading ||
                uiState.processingState is ProcessingState.Processing
        AnimatedVisibility(
            visible = showProcessing,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
        ) {
            when (val state = uiState.processingState) {
                is ProcessingState.Uploading -> ProcessingIndicator(
                    stage = "Uploading video...",
                    progress = state.progress,
                )
                is ProcessingState.Processing -> ProcessingIndicator(
                    stage = state.stage,
                    progress = state.progress,
                )
                else -> {}
            }
        }

        // ── Bottom: intensity slider ──
        AnimatedVisibility(
            visible = uiState.hapticsEnabled && !showProcessing,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, DarkBackground.copy(alpha = 0.8f))
                        )
                    )
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                IntensitySlider(
                    intensity = uiState.hapticIntensity,
                    onIntensityChange = viewModel::setHapticIntensity,
                )
            }
        }

        // ── Error banner ──
        AnimatedVisibility(
            visible = uiState.processingState is ProcessingState.Error,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .systemBarsPadding()
                .padding(top = 60.dp, start = 16.dp, end = 16.dp),
        ) {
            val errorMsg = (uiState.processingState as? ProcessingState.Error)?.message ?: ""
            Text(
                text = "⚠ $errorMsg\nUsing fallback audio haptics",
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

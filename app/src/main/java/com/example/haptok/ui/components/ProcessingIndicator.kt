package com.example.haptok.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.haptok.theme.HaptokBlue
import com.example.haptok.theme.HaptokCyan
import com.example.haptok.theme.HaptokViolet
import com.example.haptok.theme.StatusComplete
import com.example.haptok.theme.TextPrimary
import com.example.haptok.theme.TextSecondary

@Composable
fun ProcessingIndicator(
    stage: String,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "processing_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    val stageIcon = getStageIcon(stage)
    val isComplete = stage.contains("Complete", ignoreCase = true)

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("processing_indicator"),
        cornerRadius = 20.dp,
        showAccentEdge = true,
        accentColor = if (isComplete) StatusComplete else HaptokViolet,
        contentPadding = 16.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = stageIcon,
                    contentDescription = stage,
                    tint = if (isComplete) StatusComplete else HaptokCyan,
                    modifier = Modifier
                        .size(24.dp)
                        .alpha(if (isComplete) 1f else pulseAlpha),
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stage,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
            }

            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = if (isComplete) StatusComplete else HaptokBlue,
                trackColor = HaptokBlue.copy(alpha = 0.15f),
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

private fun getStageIcon(stage: String): ImageVector {
    return when {
        stage.contains("Upload", ignoreCase = true) -> Icons.Default.CloudUpload
        stage.contains("Extract", ignoreCase = true) -> Icons.Default.AudioFile
        stage.contains("audio", ignoreCase = true) -> Icons.Default.MusicNote
        stage.contains("video", ignoreCase = true) -> Icons.Default.Videocam
        stage.contains("Generat", ignoreCase = true) -> Icons.Default.Vibration
        stage.contains("Complete", ignoreCase = true) -> Icons.Default.Check
        else -> Icons.Default.Memory
    }
}

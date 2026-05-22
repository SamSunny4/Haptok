package com.example.haptok.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.haptok.theme.HapticAmbient
import com.example.haptok.theme.HapticImpact
import com.example.haptok.theme.HapticMotion
import com.example.haptok.theme.HapticTexture

data class HapticChannelState(
    val impact: Float = 0f,   // 0.0 - 1.0
    val ambient: Float = 0f,
    val texture: Float = 0f,
    val motion: Float = 0f,
)

@Composable
fun HapticVisualizer(
    channelState: HapticChannelState,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "haptic_pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    val animatedImpact by animateFloatAsState(
        targetValue = channelState.impact,
        animationSpec = tween(100),
        label = "impact",
    )
    val animatedAmbient by animateFloatAsState(
        targetValue = channelState.ambient,
        animationSpec = tween(200),
        label = "ambient",
    )
    val animatedTexture by animateFloatAsState(
        targetValue = channelState.texture,
        animationSpec = tween(150),
        label = "texture",
    )
    val animatedMotion by animateFloatAsState(
        targetValue = channelState.motion,
        animationSpec = tween(180),
        label = "motion",
    )

    val channels = listOf(
        HapticImpact to animatedImpact,
        HapticAmbient to animatedAmbient,
        HapticTexture to animatedTexture,
        HapticMotion to animatedMotion,
    )

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("haptic_visualizer"),
        cornerRadius = 12.dp,
        contentPadding = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            channels.forEach { (color, value) ->
                val barValue = value * pulse
                HapticBar(
                    color = color,
                    value = barValue,
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp),
                )
            }
        }
    }
}

@Composable
private fun HapticBar(
    color: Color,
    value: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val barWidth = size.width
        val maxBarHeight = size.height
        val barHeight = maxBarHeight * value.coerceIn(0f, 1f)

        if (barHeight > 0f) {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        color,
                        color.copy(alpha = 0.4f),
                    ),
                    startY = size.height - barHeight,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }

        // Always draw a subtle base
        drawRoundRect(
            color = color.copy(alpha = 0.15f),
            topLeft = Offset(0f, size.height - 4.dp.toPx()),
            size = Size(barWidth, 4.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
    }
}

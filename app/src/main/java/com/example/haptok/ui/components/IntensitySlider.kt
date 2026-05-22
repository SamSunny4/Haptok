package com.example.haptok.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.haptok.theme.HaptokBlue
import com.example.haptok.theme.HaptokViolet
import com.example.haptok.theme.TextPrimary
import com.example.haptok.theme.TextSecondary

@Composable
fun IntensitySlider(
    intensity: Float,
    onIntensityChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("intensity_slider_card"),
        cornerRadius = 20.dp,
        showAccentEdge = true,
        accentColor = HaptokBlue,
        contentPadding = 12.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Vibration,
                contentDescription = "Haptic intensity",
                tint = HaptokBlue,
                modifier = Modifier.size(24.dp),
            )

            Slider(
                value = intensity,
                onValueChange = { newValue ->
                    onIntensityChange(newValue)
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = HaptokViolet,
                    activeTrackColor = HaptokBlue,
                    inactiveTrackColor = HaptokBlue.copy(alpha = 0.2f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("intensity_slider"),
            )

            Text(
                text = "${(intensity * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
        }
    }
}


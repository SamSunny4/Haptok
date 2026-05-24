package com.example.haptok.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.haptok.theme.DarkSurface
import com.example.haptok.theme.DarkSurfaceVariant
import com.example.haptok.theme.GlassBorder
import com.example.haptok.theme.GlassWhite
import com.example.haptok.theme.HaptokBlue
import com.example.haptok.theme.HaptokCyan
import com.example.haptok.theme.HaptokViolet
import com.example.haptok.theme.StatusComplete
import com.example.haptok.theme.TextPrimary
import com.example.haptok.theme.TextSecondary
import com.example.haptok.theme.TextTertiary

enum class HapticMode {
    CACHED,
    SERVER,
    FALLBACK
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HapticModeDialog(
    hasCachedWaveform: Boolean,
    onModeSelected: (HapticMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
        ) {
            // Title
            Text(
                text = "Select Haptic Mode",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = "Choose how haptic feedback is generated",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(bottom = 20.dp),
            )

            // Option: Saved Waveform
            if (hasCachedWaveform) {
                ModeOptionCard(
                    icon = Icons.Default.Save,
                    title = "Use Saved Waveform",
                    description = "Load previously generated haptic data — instant playback",
                    accentColor = StatusComplete,
                    gradientColors = listOf(StatusComplete.copy(alpha = 0.15f), StatusComplete.copy(alpha = 0.05f)),
                    onClick = { onModeSelected(HapticMode.CACHED) },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Option: Server
            ModeOptionCard(
                icon = Icons.Default.Cloud,
                title = "Generate from Server",
                description = "AI-powered analysis — best quality haptics, requires server",
                accentColor = HaptokViolet,
                gradientColors = listOf(HaptokViolet.copy(alpha = 0.15f), HaptokBlue.copy(alpha = 0.05f)),
                onClick = { onModeSelected(HapticMode.SERVER) },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Option: Fallback
            ModeOptionCard(
                icon = Icons.Default.GraphicEq,
                title = "Audio Fallback (Local)",
                description = "Real-time audio analysis — works offline, no delay",
                accentColor = HaptokCyan,
                gradientColors = listOf(HaptokCyan.copy(alpha = 0.15f), HaptokCyan.copy(alpha = 0.05f)),
                onClick = { onModeSelected(HapticMode.FALLBACK) },
            )
        }
    }
}

@Composable
private fun ModeOptionCard(
    icon: ImageVector,
    title: String,
    description: String,
    accentColor: androidx.compose.ui.graphics.Color,
    gradientColors: List<androidx.compose.ui.graphics.Color>,
    onClick: () -> Unit,
) {
    var isPressed by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (isPressed) accentColor else GlassBorder,
        label = "border",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(gradientColors))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable {
                isPressed = true
                onClick()
            }
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Icon circle
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(alpha = 0.15f)),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
    }
}
